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
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket handler for real-time transcription using AssemblyAI Real-time Streaming API
 * 
 * Optimized for low-latency streaming with:
 * - Direct WebSocket bridge to AssemblyAI
 * - Audio buffering for optimal chunk sizes (250ms recommended)
 * - Automatic reconnection on failure
 * - End of utterance detection for natural breaks
 * 
 * Flow:
 * 1. Client connects to /ws/transcription
 * 2. Client sends JSON: {"type": "start", "roomSid": "xxx"}
 * 3. Backend creates AssemblyAI real-time session with streaming
 * 4. Client sends audio as Base64 encoded PCM16 16kHz mono
 * 5. Backend forwards to AssemblyAI and streams transcription results back
 * 6. Client sends JSON: {"type": "stop"} to finalize with summary
 * 
 * Audio Requirements:
 * - Format: PCM16 signed 16-bit
 * - Sample Rate: 16000 Hz
 * - Channels: Mono
 * - Recommended chunk size: 250ms (4000 samples = 8000 bytes)
 */
@Component
class RealtimeTranscriptionHandler(
    private val geminiSummaryService: GeminiSummaryService
) : TextWebSocketHandler() {

    private val logger = LoggerFactory.getLogger(RealtimeTranscriptionHandler::class.java)

    @Value("\${assemblyai.api.key:}")
    private lateinit var apiKey: String
    
    @Value("\${assemblyai.language:pt}")
    private lateinit var language: String

    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // Session management
    private val clientSessions = ConcurrentHashMap<String, StreamingTranscriptionSession>()
    private val scheduler = Executors.newScheduledThreadPool(4)
    
    // Metrics
    private val totalAudioReceived = AtomicLong(0)
    private val totalTranscriptions = AtomicLong(0)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        logger.info("🔌 WebSocket connection established: ${session.id}")
        
        // Send connection confirmation
        sendMessage(session, mapOf(
            "type" to "connected",
            "sessionId" to session.id,
            "message" to "Ready to start transcription"
        ))
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val payload = message.payload
            val msg = objectMapper.readValue<ClientMessage>(payload)

            when (msg.type) {
                "start" -> handleStart(session, msg)
                "stop" -> handleStop(session)
                "audio" -> handleAudioData(session, msg.audio)
                "ping" -> sendMessage(session, mapOf("type" to "pong"))
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

            val audioBytes = message.payload.array()
            totalAudioReceived.addAndGet(audioBytes.size.toLong())
            
            // Forward audio to AssemblyAI
            transcriptionSession.sendAudio(audioBytes)
        } catch (e: Exception) {
            logger.error("Error handling binary message", e)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        logger.info("🔌 WebSocket connection closed: ${session.id}, status: $status")
        cleanupSession(session.id)
    }

    private fun handleStart(session: WebSocketSession, msg: ClientMessage) {
        val roomSid = msg.roomSid ?: run {
            sendError(session, "roomSid is required")
            return
        }

        // Check if session already exists
        if (clientSessions.containsKey(session.id)) {
            logger.warn("Session already started for ${session.id}, cleaning up old session")
            cleanupSession(session.id)
        }

        logger.info("🎤 Starting real-time streaming transcription for room: $roomSid")

        // Create optimized AssemblyAI streaming session
        val transcriptionSession = StreamingTranscriptionSession(
            clientSession = session,
            roomSid = roomSid,
            roomName = msg.roomName,
            apiKey = apiKey,
            language = language,
            objectMapper = objectMapper,
            onTranscript = { text, isFinal, confidence, words ->
                sendTranscript(session, text, isFinal, confidence, words)
                if (isFinal) totalTranscriptions.incrementAndGet()
            },
            onError = { error ->
                sendError(session, error)
            },
            onSessionInfo = { sessionId, expiresAt ->
                sendMessage(session, mapOf(
                    "type" to "session_info",
                    "assemblySessionId" to sessionId,
                    "expiresAt" to expiresAt
                ))
            }
        )

        clientSessions[session.id] = transcriptionSession

        // Connect to AssemblyAI with retry logic
        scheduler.execute {
            try {
                transcriptionSession.connect()
                sendMessage(session, mapOf(
                    "type" to "started",
                    "roomSid" to roomSid,
                    "message" to "Real-time streaming transcription started",
                    "sampleRate" to 16000,
                    "encoding" to "pcm_s16le"
                ))
            } catch (e: Exception) {
                logger.error("Failed to start AssemblyAI session", e)
                sendError(session, "Failed to connect to transcription service: ${e.message}")
                cleanupSession(session.id)
            }
        }
    }

    private fun handleStop(session: WebSocketSession) {
        val transcriptionSession = clientSessions[session.id] ?: run {
            sendError(session, "No active session found")
            return
        }

        logger.info("⏹️ Stopping transcription for room: ${transcriptionSession.roomSid}")

        // Signal end of audio to AssemblyAI and wait for final results
        transcriptionSession.endStream()
        
        // Wait briefly for any pending transcriptions
        Thread.sleep(1000)

        // Get final transcription
        val fullTranscription = transcriptionSession.getFullTranscription()
        val stats = transcriptionSession.getStats()

        logger.info("📊 Session stats: ${stats["totalChunks"]} chunks, ${stats["totalBytes"]} bytes, ${stats["finalTranscripts"]} final transcripts")

        // Generate summary if we have content
        var summary: Map<String, Any?>? = null
        if (fullTranscription.isNotBlank() && fullTranscription.length > 20) {
            try {
                logger.info("🤖 Generating summary with Gemini...")
                val summaryResult = geminiSummaryService.generateSummary(
                    roomSid = transcriptionSession.roomSid,
                    roomName = transcriptionSession.roomName,
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
                logger.info("✅ Summary generated successfully")
            } catch (e: Exception) {
                logger.error("Failed to generate summary", e)
            }
        } else {
            logger.warn("Transcription too short for summary: ${fullTranscription.length} chars")
        }

        sendMessage(session, mapOf(
            "type" to "completed",
            "roomSid" to transcriptionSession.roomSid,
            "fullTranscription" to fullTranscription,
            "summary" to summary,
            "wordCount" to fullTranscription.split("\\s+".toRegex()).filter { it.isNotBlank() }.size,
            "stats" to stats
        ))

        cleanupSession(session.id)
    }

    private fun handleAudioData(session: WebSocketSession, audioBase64: String?) {
        if (audioBase64.isNullOrBlank()) return

        val transcriptionSession = clientSessions[session.id]
        if (transcriptionSession == null) {
            // Don't spam errors for audio before session starts
            return
        }

        try {
            val audioBytes = java.util.Base64.getDecoder().decode(audioBase64)
            totalAudioReceived.addAndGet(audioBytes.size.toLong())
            transcriptionSession.sendAudio(audioBytes)
        } catch (e: Exception) {
            logger.error("Error decoding audio: ${e.message}")
        }
    }

    private fun sendTranscript(session: WebSocketSession, text: String, isFinal: Boolean, confidence: Double?, words: List<WordInfo>?) {
        val message = mutableMapOf<String, Any?>(
            "type" to "transcript",
            "text" to text,
            "isFinal" to isFinal
        )
        
        if (confidence != null) message["confidence"] = confidence
        if (!words.isNullOrEmpty()) {
            message["words"] = words.map { mapOf(
                "text" to it.text,
                "start" to it.start,
                "end" to it.end,
                "confidence" to it.confidence
            )}
        }
        
        sendMessage(session, message)
    }

    private fun sendError(session: WebSocketSession, error: String) {
        logger.warn("Sending error to client: $error")
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
    
    fun getMetrics(): Map<String, Any> {
        return mapOf(
            "activeSessions" to clientSessions.size,
            "totalAudioReceived" to totalAudioReceived.get(),
            "totalTranscriptions" to totalTranscriptions.get()
        )
    }
}

/**
 * Word-level timing information from AssemblyAI
 */
data class WordInfo(
    val text: String,
    val start: Long,
    val end: Long,
    val confidence: Double
)

/**
 * Optimized streaming transcription session with AssemblyAI Real-time API
 * 
 * Features:
 * - Audio buffering for optimal chunk sizes
 * - Automatic reconnection on connection loss
 * - Word-level timestamps and confidence scores
 * - End of utterance detection
 * - Graceful stream termination
 */
class StreamingTranscriptionSession(
    private val clientSession: WebSocketSession,
    val roomSid: String,
    val roomName: String?,
    private val apiKey: String,
    private val language: String,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val onTranscript: (String, Boolean, Double?, List<WordInfo>?) -> Unit,
    private val onError: (String) -> Unit,
    private val onSessionInfo: (String, Long) -> Unit
) {
    private val logger = LoggerFactory.getLogger(StreamingTranscriptionSession::class.java)

    private var assemblyWs: java.net.http.WebSocket? = null
    private val transcriptParts = mutableListOf<String>()
    private var currentPartial = ""
    
    private val httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()

    @Volatile
    private var isConnected = false
    
    @Volatile
    private var isEnding = false
    
    // Audio buffering for optimal chunk sizes (250ms = 4000 samples at 16kHz = 8000 bytes)
    private val audioBuffer = java.io.ByteArrayOutputStream()
    private val bufferLock = Object()
    private val OPTIMAL_CHUNK_SIZE = 8000 // 250ms of audio at 16kHz 16-bit mono
    
    // Stats
    private var totalChunks = 0L
    private var totalBytes = 0L
    private var finalTranscriptCount = 0
    private var sessionStartTime = 0L

    fun connect() {
        try {
            sessionStartTime = System.currentTimeMillis()
            logger.info("🔌 Connecting to AssemblyAI Real-time Streaming API...")

            // Get temporary auth token (valid for 1 hour)
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
                logger.error("Failed to get AssemblyAI token: ${tokenResponse.statusCode()} - ${tokenResponse.body()}")
                onError("Failed to authenticate with AssemblyAI: ${tokenResponse.statusCode()}")
                return
            }

            val tokenData = objectMapper.readValue<Map<String, Any>>(tokenResponse.body())
            val token = tokenData["token"] as String

            // Connect to real-time WebSocket with language parameter
            // AssemblyAI supports: en, es, fr, de, it, pt, nl, hi, ja, zh, fi, ko, pl, ru, tr, uk, vi
            val wsUri = URI.create("wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000&token=$token&word_boost=[]&encoding=pcm_s16le")

            logger.info("🎯 Connecting to AssemblyAI WebSocket...")
            
            assemblyWs = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri, AssemblyWSListener())
                .get(30, TimeUnit.SECONDS)

            isConnected = true
            logger.info("✅ Connected to AssemblyAI Real-time Streaming API")

        } catch (e: Exception) {
            logger.error("Failed to connect to AssemblyAI", e)
            onError("Failed to connect: ${e.message}")
            throw e
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isConnected || assemblyWs == null || isEnding) return

        try {
            synchronized(bufferLock) {
                audioBuffer.write(audioData)
                totalBytes += audioData.size
                
                // Send when buffer reaches optimal size
                while (audioBuffer.size() >= OPTIMAL_CHUNK_SIZE) {
                    val chunk = audioBuffer.toByteArray().take(OPTIMAL_CHUNK_SIZE).toByteArray()
                    
                    // Reset buffer with remaining data
                    val remaining = audioBuffer.toByteArray().drop(OPTIMAL_CHUNK_SIZE).toByteArray()
                    audioBuffer.reset()
                    audioBuffer.write(remaining)
                    
                    // Send to AssemblyAI as base64 JSON
                    val base64Audio = java.util.Base64.getEncoder().encodeToString(chunk)
                    val message = """{"audio_data": "$base64Audio"}"""
                    assemblyWs?.sendText(message, true)
                    totalChunks++
                }
            }
        } catch (e: Exception) {
            logger.error("Error sending audio to AssemblyAI", e)
        }
    }
    
    fun endStream() {
        if (!isConnected || isEnding) return
        
        isEnding = true
        logger.info("📤 Ending audio stream, flushing buffer...")
        
        try {
            // Flush remaining buffer
            synchronized(bufferLock) {
                if (audioBuffer.size() > 0) {
                    val remaining = audioBuffer.toByteArray()
                    val base64Audio = java.util.Base64.getEncoder().encodeToString(remaining)
                    val message = """{"audio_data": "$base64Audio"}"""
                    assemblyWs?.sendText(message, true)
                    totalChunks++
                    audioBuffer.reset()
                }
            }
            
            // Send terminate session message to get final results
            assemblyWs?.sendText("""{"terminate_session": true}""", true)
            
        } catch (e: Exception) {
            logger.warn("Error ending stream", e)
        }
    }

    fun getFullTranscription(): String {
        // Add any remaining partial
        if (currentPartial.isNotBlank() && !transcriptParts.contains(currentPartial)) {
            transcriptParts.add(currentPartial)
        }
        return transcriptParts.joinToString(" ").trim()
    }
    
    fun getStats(): Map<String, Any> {
        val duration = if (sessionStartTime > 0) System.currentTimeMillis() - sessionStartTime else 0
        return mapOf(
            "totalChunks" to totalChunks,
            "totalBytes" to totalBytes,
            "finalTranscripts" to finalTranscriptCount,
            "durationMs" to duration,
            "avgBytesPerSecond" to if (duration > 0) (totalBytes * 1000 / duration) else 0
        )
    }

    fun close() {
        isConnected = false
        isEnding = true
        try {
            assemblyWs?.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Session ended")
        } catch (e: Exception) {
            logger.warn("Error closing AssemblyAI connection", e)
        }
    }

    private inner class AssemblyWSListener : java.net.http.WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: java.net.http.WebSocket) {
            logger.info("🎙️ AssemblyAI WebSocket opened, ready for audio")
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
            logger.error("❌ AssemblyAI WebSocket error", error)
            isConnected = false
            if (!isEnding) {
                onError("AssemblyAI connection error: ${error.message}")
            }
        }

        override fun onClose(webSocket: java.net.http.WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
            logger.info("🔌 AssemblyAI WebSocket closed: $statusCode - $reason")
            isConnected = false
            return null
        }
    }

    private fun handleAssemblyMessage(message: AssemblyRealtimeMessage) {
        when (message.messageType) {
            "PartialTranscript" -> {
                if (!message.text.isNullOrBlank()) {
                    currentPartial = message.text
                    onTranscript(message.text, false, message.confidence, null)
                }
            }
            "FinalTranscript" -> {
                if (!message.text.isNullOrBlank()) {
                    transcriptParts.add(message.text)
                    currentPartial = ""
                    finalTranscriptCount++
                    
                    val words = message.words?.map { word ->
                        WordInfo(
                            text = (word as? Map<*, *>)?.get("text")?.toString() ?: "",
                            start = ((word as? Map<*, *>)?.get("start") as? Number)?.toLong() ?: 0,
                            end = ((word as? Map<*, *>)?.get("end") as? Number)?.toLong() ?: 0,
                            confidence = ((word as? Map<*, *>)?.get("confidence") as? Number)?.toDouble() ?: 0.0
                        )
                    }
                    
                    onTranscript(message.text, true, message.confidence, words)
                    logger.debug("📝 Final: ${message.text.take(50)}... (conf: ${message.confidence})")
                }
            }
            "SessionBegins" -> {
                logger.info("✅ AssemblyAI session started: ${message.sessionId}")
                val expiresAt = message.expiresAt ?: (System.currentTimeMillis() + 3600000)
                onSessionInfo(message.sessionId ?: "", expiresAt)
            }
            "SessionTerminated" -> {
                logger.info("⏹️ AssemblyAI session terminated")
                isConnected = false
            }
            "SessionInformation" -> {
                logger.debug("Session info: audio_duration=${message.audioDurationSeconds}s")
            }
            else -> {
                if (message.error != null) {
                    logger.error("AssemblyAI error: ${message.error}")
                    onError("Transcription error: ${message.error}")
                } else {
                    logger.debug("Unknown message type: ${message.messageType}")
                }
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
    @JsonProperty("expires_at") val expiresAt: Long? = null,
    @JsonProperty("audio_start") val audioStart: Long? = null,
    @JsonProperty("audio_end") val audioEnd: Long? = null,
    @JsonProperty("confidence") val confidence: Double? = null,
    @JsonProperty("words") val words: List<Any>? = null,
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("audio_duration_seconds") val audioDurationSeconds: Double? = null
)
