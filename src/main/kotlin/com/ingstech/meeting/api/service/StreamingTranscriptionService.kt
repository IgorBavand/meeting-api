package com.ingstech.meeting.api.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ChunkTranscription(
    val chunkIndex: Int,
    val transcription: String,
    val timestamp: Long
)

data class RoomStreamingState(
    val roomSid: String,
    val chunks: MutableList<ChunkTranscription> = mutableListOf(),
    var isFinalized: Boolean = false,
    var fullTranscription: String? = null,
    var lastContext: String = "",  // Context for next chunk
    val processingLock: ReentrantLock = ReentrantLock()  // Lock for sequential processing
)

@Service
class StreamingTranscriptionService(
    private val audioConverterService: AudioConverterService,
    private val whisperService: WhisperTranscriptionService
) {
    private val logger = LoggerFactory.getLogger(StreamingTranscriptionService::class.java)
    private val roomStates = ConcurrentHashMap<String, RoomStreamingState>()
    private val streamingBasePath = "/tmp/streaming"

    init {
        File(streamingBasePath).mkdirs()
    }

    fun processChunk(
        roomSid: String,
        chunkIndex: Int,
        audioData: ByteArray,
        hasOverlap: Boolean
    ): ChunkTranscription? {
        logger.info("Processing chunk $chunkIndex for room $roomSid (${audioData.size} bytes, overlap: $hasOverlap)")

        // Initialize room state if needed
        val roomState = roomStates.getOrPut(roomSid) { RoomStreamingState(roomSid) }

        // Process sequentially with lock to maintain context
        return roomState.processingLock.withLock {
            processChunkInternal(roomState, chunkIndex, audioData, hasOverlap)
        }
    }

    private fun processChunkInternal(
        roomState: RoomStreamingState,
        chunkIndex: Int,
        audioData: ByteArray,
        hasOverlap: Boolean
    ): ChunkTranscription? {
        // Save chunk to temp file
        val roomDir = File("$streamingBasePath/${roomState.roomSid}")
        roomDir.mkdirs()
        
        val chunkFile = File(roomDir, "chunk_$chunkIndex.webm")
        chunkFile.writeBytes(audioData)

        return try {
            // Convert to PCM16 WAV
            val convertedPath = audioConverterService.convertToPcm16(chunkFile.toPath())
            
            if (convertedPath == null) {
                logger.warn("Failed to convert chunk $chunkIndex")
                chunkFile.delete()
                return null
            }

            // Get context from previous chunks for better continuity
            val contextPrompt = buildContextPrompt(roomState)
            
            // Transcribe with Whisper using context
            val transcription = whisperService.transcribeWithContext(convertedPath, contextPrompt)
            
            // Cleanup temp files
            chunkFile.delete()
            convertedPath.toFile().delete()

            if (transcription.isNullOrBlank()) {
                logger.warn("Empty transcription for chunk $chunkIndex")
                return null
            }

            // Clean and process the transcription
            val cleanedTranscription = cleanTranscription(transcription, roomState, hasOverlap)

            val chunkResult = ChunkTranscription(
                chunkIndex = chunkIndex,
                transcription = cleanedTranscription,
                timestamp = System.currentTimeMillis()
            )

            // Update state
            roomState.chunks.add(chunkResult)
            
            // Update context for next chunk (last 50 words)
            roomState.lastContext = getLastWords(cleanedTranscription, 50)
            
            logger.info("Chunk $chunkIndex transcribed: ${cleanedTranscription.take(100)}...")
            
            chunkResult
        } catch (e: Exception) {
            logger.error("Error processing chunk $chunkIndex for room ${roomState.roomSid}", e)
            chunkFile.delete()
            null
        }
    }

    private fun buildContextPrompt(roomState: RoomStreamingState): String {
        if (roomState.chunks.isEmpty()) {
            return ""
        }
        
        // Get last 3 chunks' transcriptions for context
        val recentTranscriptions = roomState.chunks
            .sortedBy { it.chunkIndex }
            .takeLast(3)
            .joinToString(" ") { it.transcription }
        
        // Return last ~100 words as context
        return getLastWords(recentTranscriptions, 100)
    }

    private fun getLastWords(text: String, count: Int): String {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return words.takeLast(count).joinToString(" ")
    }

    private fun cleanTranscription(
        transcription: String,
        roomState: RoomStreamingState,
        hasOverlap: Boolean
    ): String {
        var cleaned = transcription.trim()
        
        // Remove common artifacts
        cleaned = cleaned
            .replace(Regex("\\[.*?\\]"), "") // Remove [MUSIC], [NOISE], etc
            .replace(Regex("\\(.*?\\)"), "") // Remove (inaudible), etc
            .replace(Regex("\\s+"), " ")
            .trim()

        // Remove overlap with previous chunk if applicable
        if (hasOverlap && roomState.chunks.isNotEmpty()) {
            cleaned = removeOverlapText(cleaned, roomState.chunks.lastOrNull()?.transcription)
        }

        return cleaned
    }

    private fun removeOverlapText(newText: String, previousText: String?): String {
        if (previousText.isNullOrBlank()) return newText
        
        // Get last ~40 words from previous chunk
        val prevWords = previousText.split("\\s+".toRegex()).takeLast(40)
        if (prevWords.isEmpty()) return newText

        val newWords = newText.split("\\s+".toRegex())
        
        // Find overlap by looking for matching sequence
        for (overlapSize in minOf(prevWords.size, 25) downTo 3) {
            val prevEnd = prevWords.takeLast(overlapSize).joinToString(" ").lowercase()
            val newStart = newWords.take(overlapSize).joinToString(" ").lowercase()
            
            // Check for similarity (allowing some variation)
            if (similarity(prevEnd, newStart) > 0.75) {
                return newWords.drop(overlapSize).joinToString(" ")
            }
        }
        
        return newText
    }

    private fun similarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        if (longer.isEmpty()) return 1.0
        
        val editDistance = levenshteinDistance(longer, shorter)
        return (longer.length - editDistance) / longer.length.toDouble()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }

    fun finalizeTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid]
        
        if (roomState == null) {
            logger.warn("No streaming state found for room $roomSid")
            return ""
        }

        return roomState.processingLock.withLock {
            if (roomState.isFinalized && roomState.fullTranscription != null) {
                return@withLock roomState.fullTranscription!!
            }

            // Combine all chunks maintaining order
            val fullTranscription = roomState.chunks
                .sortedBy { it.chunkIndex }
                .joinToString(" ") { it.transcription }
                .replace("\\s+".toRegex(), " ")
                .trim()

            roomState.fullTranscription = fullTranscription
            roomState.isFinalized = true

            // Cleanup
            cleanupRoom(roomSid)

            logger.info("Finalized transcription for room $roomSid: ${fullTranscription.length} chars")
            fullTranscription
        }
    }

    fun getPartialTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid] ?: return ""
        return roomState.processingLock.withLock {
            roomState.chunks
                .sortedBy { it.chunkIndex }
                .joinToString(" ") { it.transcription }
        }
    }

    fun getChunkCount(roomSid: String): Int {
        return roomStates[roomSid]?.chunks?.size ?: 0
    }

    private fun cleanupRoom(roomSid: String) {
        try {
            val roomDir = File("$streamingBasePath/$roomSid")
            if (roomDir.exists()) {
                roomDir.deleteRecursively()
            }
        } catch (e: Exception) {
            logger.warn("Failed to cleanup streaming files for room $roomSid", e)
        }
    }

    fun clearRoom(roomSid: String) {
        roomStates.remove(roomSid)
        cleanupRoom(roomSid)
    }
}
