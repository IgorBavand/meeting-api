package com.ingstech.meeting.api.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.*
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebSocket handler for real-time transcription using AssemblyAI Real-time API
 * 
 * Flow:
 * 1. Client connects to /ws/transcription
 * 2. Client sends JSON: {"type": "start", "roomSid": "xxx"}
 * 3. Backend creates AssemblyAI real-time session
 * 4. Client sends audio as binary messages (Base64 encoded PCM16 16kHz)
 * 5. Backend forwards to AssemblyAI and sends transcription results back
 * 6. Client sends JSON: {"type": "stop"} to finalize
 */
@Component
class RealtimeTranscriptionHandler(
    private val geminiSummaryService: GeminiSummaryService
) : TextWebSocketHandler() {

    private val logger = LoggerFactory.getLogger(RealtimeTranscriptionHandler::class.java)

    @Value("\${assemblyai.api.key:}")
    private lateinit var apiKey: String

    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // Session management
    private val clientSessions = ConcurrentHashMap<String, TranscriptionSession>()
    private val scheduler = Executors.newScheduledThreadPool(2)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        logger.info("WebSocket connection established: ${session.id}")
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val payload = message.payload
            val msg = objectMapper.readValue<ClientMessage>(payload)

            when (msg.type) {
                "start" -> handleStart(session, msg)
                "stop" -> handleStop(session)
                "audio" -> handleAudioData(session, msg.audio)
                else -> sendError(session, "Unknown message type: ${msg.type}")
            }
        } catch (e: Exception) {
            logger.error("Error handling message", e)
            sendError(session, "Error: ${e.message}")
        }
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        try {
            val transcriptionSession = clientSessions[session.id]
            if (transcriptionSession == null) {
                sendError(session, "Session not started. Send 'start' message first.")
                return
            }

            // Forward audio to AssemblyAI
            transcriptionSession.sendAudio(message.payload.array())
        } catch (e: Exception) {
            logger.error("Error handling binary message", e)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        logger.info("WebSocket connection closed: ${session.id}, status: $status")
        cleanupSession(session.id)
    }

    private fun handleStart(session: WebSocketSession, msg: ClientMessage) {
        val roomSid = msg.roomSid ?: run {
            sendError(session, "roomSid is required")
            return
        }

        logger.info("Starting real-time transcription for room: $roomSid")

        // Create AssemblyAI real-time session
        val transcriptionSession = TranscriptionSession(
            clientSession = session,
            roomSid = roomSid,
            apiKey = apiKey,
            objectMapper = objectMapper,
            onTranscript = { text, isFinal ->
                sendTranscript(session, text, isFinal)
            },
            onError = { error ->
                sendError(session, error)
            }
        )

        clientSessions[session.id] = transcriptionSession

        // Connect to AssemblyAI
        transcriptionSession.connect()

        sendMessage(session, mapOf(
            "type" to "started",
            "roomSid" to roomSid,
            "message" to "Real-time transcription started"
        ))
    }

    private fun handleStop(session: WebSocketSession) {
        val transcriptionSession = clientSessions[session.id] ?: return

        logger.info("Stopping transcription for room: ${transcriptionSession.roomSid}")

        // Get final transcription
        val fullTranscription = transcriptionSession.getFullTranscription()

        // Generate summary if we have content
        var summary: Map<String, Any?>? = null
        if (fullTranscription.isNotBlank() && fullTranscription.length > 20) {
            try {
                val summaryResult = geminiSummaryService.generateSummary(
                    roomSid = transcriptionSession.roomSid,
                    roomName = null,
                    transcription = fullTranscription
                )
                summary = mapOf(
                    "generalSummary" to summaryResult.generalSummary,
                    "topicsDiscussed" to summaryResult.topicsDiscussed,
                    "decisionsMade" to summaryResult.decisionsMade,
                    "nextSteps" to summaryResult.nextSteps,
                    "participantsMentioned" to summaryResult.participantsMentioned,
                    "issuesRaised" to summaryResult.issuesRaised,
                    "overallSentiment" to summaryResult.overallSentiment
                )
            } catch (e: Exception) {
                logger.error("Failed to generate summary", e)
            }
        }

        sendMessage(session, mapOf(
            "type" to "completed",
            "roomSid" to transcriptionSession.roomSid,
            "fullTranscription" to fullTranscription,
            "summary" to summary,
            "wordCount" to fullTranscription.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        ))

        cleanupSession(session.id)
    }

    private fun handleAudioData(session: WebSocketSession, audioBase64: String?) {
        if (audioBase64 == null) return

        val transcriptionSession = clientSessions[session.id]
        if (transcriptionSession == null) {
            sendError(session, "Session not started")
            return
        }

        try {
            val audioBytes = java.util.Base64.getDecoder().decode(audioBase64)
            transcriptionSession.sendAudio(audioBytes)
        } catch (e: Exception) {
            logger.error("Error decoding audio", e)
        }
    }

    private fun sendTranscript(session: WebSocketSession, text: String, isFinal: Boolean) {
        sendMessage(session, mapOf(
            "type" to "transcript",
            "text" to text,
            "isFinal" to isFinal
        ))
    }

    private fun sendError(session: WebSocketSession, error: String) {
        sendMessage(session, mapOf(
            "type" to "error",
            "error" to error
        ))
    }

    private fun sendMessage(session: WebSocketSession, data: Map<String, Any?>) {
        try {
            if (session.isOpen) {
                val json = objectMapper.writeValueAsString(data)
                session.sendMessage(TextMessage(json))
            }
        } catch (e: Exception) {
            logger.error("Error sending message", e)
        }
    }

    private fun cleanupSession(sessionId: String) {
        clientSessions.remove(sessionId)?.close()
    }
}

/**
 * Manages a single real-time transcription session with AssemblyAI
 */
class TranscriptionSession(
    private val clientSession: WebSocketSession,
    val roomSid: String,
    private val apiKey: String,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val logger = LoggerFactory.getLogger(TranscriptionSession::class.java)

    private var assemblyWs: java.net.http.WebSocket? = null
    private val transcriptParts = mutableListOf<String>()
    private var currentPartial = ""
    private val httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()

    @Volatile
    private var isConnected = false

    fun connect() {
        try {
            logger.info("Connecting to AssemblyAI Real-time API...")

            // Get temporary auth token
            val tokenRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("https://api.assemblyai.com/v2/realtime/token"))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    """{"expires_in": 3600}"""
                ))
                .build()

            val tokenResponse = httpClient.send(tokenRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            
            if (tokenResponse.statusCode() != 200) {
                logger.error("Failed to get token: ${tokenResponse.statusCode()} - ${tokenResponse.body()}")
                onError("Failed to authenticate with AssemblyAI")
                return
            }

            val tokenData = objectMapper.readValue<Map<String, Any>>(tokenResponse.body())
            val token = tokenData["token"] as String

            // Connect to real-time WebSocket
            val wsUri = URI.create("wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000&token=$token")

            assemblyWs = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri, AssemblyWSListener())
                .get(30, TimeUnit.SECONDS)

            isConnected = true
            logger.info("Connected to AssemblyAI Real-time API")

        } catch (e: Exception) {
            logger.error("Failed to connect to AssemblyAI", e)
            onError("Failed to connect: ${e.message}")
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isConnected || assemblyWs == null) return

        try {
            // AssemblyAI expects base64 encoded audio in JSON
            val base64Audio = java.util.Base64.getEncoder().encodeToString(audioData)
            val message = """{"audio_data": "$base64Audio"}"""
            assemblyWs?.sendText(message, true)
        } catch (e: Exception) {
            logger.error("Error sending audio", e)
        }
    }

    fun getFullTranscription(): String {
        // Add any remaining partial
        if (currentPartial.isNotBlank()) {
            transcriptParts.add(currentPartial)
        }
        return transcriptParts.joinToString(" ").trim()
    }

    fun close() {
        isConnected = false
        try {
            // Send terminate message
            assemblyWs?.sendText("""{"terminate_session": true}""", true)
            assemblyWs?.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Session ended")
        } catch (e: Exception) {
            logger.warn("Error closing AssemblyAI connection", e)
        }
    }

    private inner class AssemblyWSListener : java.net.http.WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: java.net.http.WebSocket) {
            logger.info("AssemblyAI WebSocket opened")
            webSocket.request(1)
        }

        override fun onText(webSocket: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
            buffer.append(data)

            if (last) {
                try {
                    val message = objectMapper.readValue<AssemblyRealtimeMessage>(buffer.toString())
                    handleAssemblyMessage(message)
                } catch (e: Exception) {
                    logger.error("Error parsing AssemblyAI message: ${buffer.toString().take(200)}", e)
                }
                buffer.clear()
            }

            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: java.net.http.WebSocket, error: Throwable) {
            logger.error("AssemblyAI WebSocket error", error)
            isConnected = false
            onError("AssemblyAI connection error: ${error.message}")
        }

        override fun onClose(webSocket: java.net.http.WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
            logger.info("AssemblyAI WebSocket closed: $statusCode - $reason")
            isConnected = false
            return null
        }
    }

    private fun handleAssemblyMessage(message: AssemblyRealtimeMessage) {
        when (message.messageType) {
            "PartialTranscript" -> {
                if (!message.text.isNullOrBlank()) {
                    currentPartial = message.text
                    onTranscript(message.text, false)
                }
            }
            "FinalTranscript" -> {
                if (!message.text.isNullOrBlank()) {
                    transcriptParts.add(message.text)
                    currentPartial = ""
                    onTranscript(message.text, true)
                    logger.info("Final transcript: ${message.text.take(50)}...")
                }
            }
            "SessionBegins" -> {
                logger.info("AssemblyAI session started: ${message.sessionId}")
            }
            "SessionTerminated" -> {
                logger.info("AssemblyAI session terminated")
                isConnected = false
            }
            else -> {
                logger.debug("Unknown message type: ${message.messageType}")
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClientMessage(
    val type: String,
    val roomSid: String? = null,
    val roomName: String? = null,
    val audio: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AssemblyRealtimeMessage(
    @JsonProperty("message_type") val messageType: String? = null,
    @JsonProperty("text") val text: String? = null,
    @JsonProperty("session_id") val sessionId: String? = null,
    @JsonProperty("audio_start") val audioStart: Long? = null,
    @JsonProperty("audio_end") val audioEnd: Long? = null,
    @JsonProperty("confidence") val confidence: Double? = null,
    @JsonProperty("words") val words: List<Any>? = null,
    @JsonProperty("error") val error: String? = null
)
