package com.ingstech.meeting.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

data class AssemblyAIRoomState(
    val roomSid: String,
    val chunks: ConcurrentHashMap<Int, String> = ConcurrentHashMap(),
    val lastChunkIndex: AtomicInteger = AtomicInteger(-1),
    @Volatile var isFinalized: Boolean = false,
    @Volatile var fullTranscription: String? = null,
    @Volatile var summary: Map<String, Any?>? = null
)

data class AssemblyAIUploadResponse(
    val upload_url: String
)

data class AssemblyAITranscriptResponse(
    val id: String,
    val status: String,
    val text: String? = null,
    val error: String? = null
)

@Service
class AssemblyAITranscriptionService(
    private val audioConverterService: AudioConverterService
) {
    private val logger = LoggerFactory.getLogger(AssemblyAITranscriptionService::class.java)
    
    @Value("\${assemblyai.api.key:}")
    private lateinit var apiKey: String
    
    @Value("\${assemblyai.language:pt}")
    private lateinit var language: String
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()
    
    private val objectMapper = ObjectMapper().apply {
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    
    private val roomStates = ConcurrentHashMap<String, AssemblyAIRoomState>()
    private val streamingBasePath = "/tmp/streaming"
    
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    )
    
    private val activeProcessing = ConcurrentHashMap<String, AtomicInteger>()

    init {
        File(streamingBasePath).mkdirs()
    }

    /**
     * Queue a chunk for transcription
     */
    fun queueChunk(
        roomSid: String,
        chunkIndex: Int,
        audioData: ByteArray,
        hasOverlap: Boolean
    ): Boolean {
        logger.info("Queueing chunk $chunkIndex for room $roomSid (${audioData.size} bytes)")
        
        val roomState = roomStates.getOrPut(roomSid) { AssemblyAIRoomState(roomSid) }
        val counter = activeProcessing.getOrPut(roomSid) { AtomicInteger(0) }
        
        if (roomState.isFinalized) {
            logger.warn("Room $roomSid is already finalized, ignoring chunk $chunkIndex")
            return false
        }

        counter.incrementAndGet()
        
        executor.submit {
            try {
                processChunk(roomState, chunkIndex, audioData)
            } finally {
                counter.decrementAndGet()
            }
        }
        
        return true
    }

    private fun processChunk(roomState: AssemblyAIRoomState, chunkIndex: Int, audioData: ByteArray) {
        val startTime = System.currentTimeMillis()
        val roomDir = File("$streamingBasePath/${roomState.roomSid}")
        roomDir.mkdirs()
        
        val chunkFile = File(roomDir, "chunk_$chunkIndex.webm")
        chunkFile.writeBytes(audioData)
        
        try {
            // Convert to WAV
            val convertedPath = audioConverterService.convertToPcm16(chunkFile.toPath())
            
            if (convertedPath == null) {
                logger.warn("Failed to convert chunk $chunkIndex")
                return
            }
            
            // Transcribe with AssemblyAI
            val transcription = transcribeFile(convertedPath)
            
            // Cleanup
            chunkFile.delete()
            convertedPath.toFile().delete()
            
            if (!transcription.isNullOrBlank() && transcription.length > 3) {
                roomState.chunks[chunkIndex] = transcription
                
                // Update last index
                var current = roomState.lastChunkIndex.get()
                while (chunkIndex > current) {
                    if (roomState.lastChunkIndex.compareAndSet(current, chunkIndex)) break
                    current = roomState.lastChunkIndex.get()
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                logger.info("Chunk $chunkIndex transcribed in ${elapsed}ms: ${transcription.take(50)}...")
            } else {
                logger.warn("Empty or invalid transcription for chunk $chunkIndex")
            }
            
        } catch (e: Exception) {
            logger.error("Error processing chunk $chunkIndex", e)
            chunkFile.delete()
        }
    }

    /**
     * Transcribe audio file using AssemblyAI
     */
    fun transcribeFile(audioPath: Path): String? {
        try {
            // Step 1: Upload file
            val uploadUrl = uploadFile(audioPath)
            if (uploadUrl == null) {
                logger.error("Failed to upload file to AssemblyAI")
                return null
            }
            
            // Step 2: Create transcription
            val transcriptId = createTranscription(uploadUrl)
            if (transcriptId == null) {
                logger.error("Failed to create transcription")
                return null
            }
            
            // Step 3: Poll for result
            return pollTranscription(transcriptId)
            
        } catch (e: Exception) {
            logger.error("AssemblyAI transcription failed", e)
            return null
        }
    }

    private fun uploadFile(audioPath: Path): String? {
        val file = audioPath.toFile()
        if (!file.exists()) {
            logger.error("File does not exist: $audioPath")
            return null
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.assemblyai.com/v2/upload"))
            .header("Authorization", apiKey)
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofFile(audioPath))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            logger.error("Upload failed: ${response.statusCode()} - ${response.body()}")
            return null
        }
        
        val uploadResponse = objectMapper.readValue<AssemblyAIUploadResponse>(response.body())
        return uploadResponse.upload_url
    }

    private fun createTranscription(audioUrl: String): String? {
        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "audio_url" to audioUrl,
                "language_code" to language,
                "punctuate" to true,
                "format_text" to true,
                "speech_model" to "best"
            )
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.assemblyai.com/v2/transcript"))
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            logger.error("Create transcription failed: ${response.statusCode()} - ${response.body()}")
            return null
        }
        
        val transcriptResponse = objectMapper.readValue<AssemblyAITranscriptResponse>(response.body())
        return transcriptResponse.id
    }

    private fun pollTranscription(transcriptId: String): String? {
        val maxAttempts = 120 // 60 seconds max
        val pollInterval = 500L
        
        repeat(maxAttempts) { attempt ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.assemblyai.com/v2/transcript/$transcriptId"))
                .header("Authorization", apiKey)
                .GET()
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                Thread.sleep(pollInterval)
                return@repeat
            }
            
            val transcriptResponse = objectMapper.readValue<AssemblyAITranscriptResponse>(response.body())
            
            when (transcriptResponse.status) {
                "completed" -> return transcriptResponse.text
                "error" -> {
                    logger.error("Transcription error: ${transcriptResponse.error}")
                    return null
                }
                else -> Thread.sleep(pollInterval)
            }
        }
        
        logger.warn("Transcription timed out for $transcriptId")
        return null
    }

    /**
     * Finalize and get complete transcription
     */
    fun finalizeTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid]
        
        if (roomState == null) {
            logger.warn("No state found for room $roomSid")
            return ""
        }

        // Return cached if already finalized
        if (roomState.fullTranscription != null) {
            return roomState.fullTranscription!!
        }

        roomState.isFinalized = true

        // Wait for active processing (max 60 seconds)
        val counter = activeProcessing[roomSid]
        val deadline = System.currentTimeMillis() + 60_000
        
        while (System.currentTimeMillis() < deadline) {
            val activeCount = counter?.get() ?: 0
            if (activeCount == 0) break
            logger.info("Waiting for $activeCount active transcriptions...")
            Thread.sleep(500)
        }
        
        val transcription = buildOrderedTranscription(roomState)
        roomState.fullTranscription = transcription
        
        logger.info("Finalized room $roomSid: ${transcription.length} chars from ${roomState.chunks.size} chunks")
        
        return transcription
    }

    private fun buildOrderedTranscription(roomState: AssemblyAIRoomState): String {
        val chunks = roomState.chunks.entries
            .sortedBy { it.key }
            .map { it.value }
        
        if (chunks.isEmpty()) return ""
        
        val result = StringBuilder()
        var lastText = ""
        
        for (text in chunks) {
            val cleanedText = if (lastText.isNotBlank()) {
                removeOverlap(text, lastText)
            } else {
                text
            }
            
            if (cleanedText.isNotBlank()) {
                if (result.isNotEmpty()) result.append(" ")
                result.append(cleanedText)
            }
            
            lastText = text
        }
        
        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun removeOverlap(newText: String, prevText: String): String {
        val prevWords = prevText.split("\\s+".toRegex()).takeLast(15)
        val newWords = newText.split("\\s+".toRegex())
        
        if (prevWords.isEmpty() || newWords.isEmpty()) return newText
        
        for (matchSize in minOf(10, prevWords.size, newWords.size) downTo 3) {
            val prevEnd = prevWords.takeLast(matchSize).joinToString(" ").lowercase()
            val newStart = newWords.take(matchSize).joinToString(" ").lowercase()
            
            if (prevEnd == newStart) {
                return newWords.drop(matchSize).joinToString(" ")
            }
        }
        
        return newText
    }

    fun getTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid] ?: return ""
        
        if (roomState.fullTranscription != null) {
            return roomState.fullTranscription!!
        }
        
        return buildOrderedTranscription(roomState)
    }

    fun setSummary(roomSid: String, summary: Map<String, Any?>) {
        roomStates[roomSid]?.summary = summary
    }

    fun getSummary(roomSid: String): Map<String, Any?>? {
        return roomStates[roomSid]?.summary
    }

    fun getStatus(roomSid: String): Map<String, Any> {
        val roomState = roomStates[roomSid] ?: return mapOf(
            "exists" to false,
            "processedChunks" to 0,
            "activeProcessing" to 0,
            "isFinalized" to false
        )
        
        val activeCount = activeProcessing[roomSid]?.get() ?: 0
        
        return mapOf(
            "exists" to true,
            "processedChunks" to roomState.chunks.size,
            "activeProcessing" to activeCount,
            "isFinalized" to roomState.isFinalized,
            "lastChunkIndex" to roomState.lastChunkIndex.get(),
            "hasTranscription" to (roomState.fullTranscription != null),
            "hasSummary" to (roomState.summary != null)
        )
    }

    fun getChunkCount(roomSid: String): Int {
        return roomStates[roomSid]?.chunks?.size ?: 0
    }

    fun clearRoom(roomSid: String) {
        roomStates.remove(roomSid)
        activeProcessing.remove(roomSid)
        
        try {
            val roomDir = File("$streamingBasePath/$roomSid")
            if (roomDir.exists()) {
                roomDir.deleteRecursively()
            }
        } catch (e: Exception) {
            logger.warn("Failed to cleanup room $roomSid", e)
        }
    }
}
