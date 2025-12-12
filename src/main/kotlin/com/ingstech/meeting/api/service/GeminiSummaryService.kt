package com.ingstech.meeting.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ingstech.meeting.api.domain.RoomSummaryResult
import com.ingstech.meeting.api.domain.SummaryStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

@Service
class GeminiSummaryService(
    @Value("\${gemini.api.key:}") private val apiKey: String,
    @Value("\${gemini.model:gemini-2.0-flash}") private val model: String,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(GeminiSummaryService::class.java)
    private val httpClient = HttpClient.newBuilder().build()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    fun generateSummary(roomSid: String, roomName: String?, transcription: String): RoomSummaryResult {
        logger.info("Generating summary for room $roomSid, transcription length: ${transcription.length}, apiKey configured: ${apiKey.isNotBlank()}")
        
        if (apiKey.isBlank()) {
            logger.warn("Gemini API key not configured, returning raw transcription as summary")
            return createFallbackResult(roomSid, roomName, transcription)
        }

        if (transcription.isBlank()) {
            logger.warn("Empty transcription provided, returning empty summary")
            return createFallbackResult(roomSid, roomName, "Nenhuma transcrição disponível")
        }

        return try {
            val prompt = buildPrompt(transcription)
            val response = callGeminiApi(prompt)
            
            if (response != null) {
                parseSummaryResponse(roomSid, roomName, response)
            } else {
                createFallbackResult(roomSid, roomName, transcription)
            }
        } catch (e: Exception) {
            logger.error("Error generating summary with Gemini", e)
            createFailedResult(roomSid, roomName, e.message)
        }
    }

    private fun buildPrompt(transcription: String): String {
        return """
            Gere um resumo estruturado desta conversa de sala Twilio.
            Responda APENAS em formato JSON válido, sem markdown ou texto adicional.
            
            Inclua os seguintes campos:
            {
                "generalSummary": "Resumo geral da reunião em 2-3 frases",
                "topicsDiscussed": ["lista de tópicos discutidos"],
                "decisionsMade": ["lista de decisões tomadas"],
                "nextSteps": ["lista de próximos passos"],
                "participantsMentioned": ["nomes mencionados na conversa"],
                "issuesRaised": ["problemas ou dúvidas levantadas"],
                "overallSentiment": "positivo/neutro/negativo"
            }
            
            Se algum campo não tiver informação, retorne array vazio [] ou null.
            
            Transcrição completa:
            $transcription
        """.trimIndent()
    }

    private fun callGeminiApi(prompt: String): String? {
        val url = "$baseUrl/$model:generateContent?key=$apiKey"
        
        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.3,
                "maxOutputTokens" to 2048
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build()

        logger.info("Calling Gemini API for summary generation")
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        return if (response.statusCode() == 200) {
            val responseJson = objectMapper.readTree(response.body())
            val text = responseJson
                .path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text")
                .asText()
            
            logger.info("Gemini API response received, length: ${text.length}")
            text
        } else {
            logger.error("Gemini API error: ${response.statusCode()} - ${response.body()}")
            null
        }
    }

    private fun parseSummaryResponse(roomSid: String, roomName: String?, response: String): RoomSummaryResult {
        return try {
            // Clean response - remove markdown code blocks if present
            val cleanedResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            val json = objectMapper.readTree(cleanedResponse)
            
            RoomSummaryResult(
                roomSid = roomSid,
                roomName = roomName,
                summary = cleanedResponse,
                generalSummary = json.path("generalSummary").asText(null),
                topicsDiscussed = json.path("topicsDiscussed").map { it.asText() },
                decisionsMade = json.path("decisionsMade").map { it.asText() },
                nextSteps = json.path("nextSteps").map { it.asText() },
                participantsMentioned = json.path("participantsMentioned").map { it.asText() },
                issuesRaised = json.path("issuesRaised").map { it.asText() },
                overallSentiment = json.path("overallSentiment").asText(null),
                processedAt = Instant.now(),
                status = SummaryStatus.COMPLETED
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse structured summary, using raw response", e)
            RoomSummaryResult(
                roomSid = roomSid,
                roomName = roomName,
                summary = response,
                generalSummary = response,
                topicsDiscussed = emptyList(),
                decisionsMade = emptyList(),
                nextSteps = emptyList(),
                participantsMentioned = emptyList(),
                issuesRaised = emptyList(),
                overallSentiment = null,
                processedAt = Instant.now(),
                status = SummaryStatus.COMPLETED
            )
        }
    }

    private fun createFallbackResult(roomSid: String, roomName: String?, transcription: String): RoomSummaryResult {
        return RoomSummaryResult(
            roomSid = roomSid,
            roomName = roomName,
            summary = transcription,
            generalSummary = "Transcrição sem sumarização (API key não configurada)",
            topicsDiscussed = emptyList(),
            decisionsMade = emptyList(),
            nextSteps = emptyList(),
            participantsMentioned = emptyList(),
            issuesRaised = emptyList(),
            overallSentiment = null,
            processedAt = Instant.now(),
            status = SummaryStatus.COMPLETED
        )
    }

    private fun createFailedResult(roomSid: String, roomName: String?, error: String?): RoomSummaryResult {
        return RoomSummaryResult(
            roomSid = roomSid,
            roomName = roomName,
            summary = "Erro ao gerar resumo: $error",
            generalSummary = null,
            topicsDiscussed = emptyList(),
            decisionsMade = emptyList(),
            nextSteps = emptyList(),
            participantsMentioned = emptyList(),
            issuesRaised = emptyList(),
            overallSentiment = null,
            processedAt = Instant.now(),
            status = SummaryStatus.FAILED
        )
    }
}
