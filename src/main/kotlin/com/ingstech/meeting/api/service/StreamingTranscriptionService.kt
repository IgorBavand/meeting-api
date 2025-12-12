package com.ingstech.meeting.api.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class ChunkTranscription(
    val chunkIndex: Int,
    val transcription: String,
    val timestamp: Long
)

data class RoomStreamingState(
    val roomSid: String,
    val chunks: MutableList<ChunkTranscription> = mutableListOf(),
    var isFinalized: Boolean = false,
    var fullTranscription: String? = null
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

        // Save chunk to temp file
        val roomDir = File("$streamingBasePath/$roomSid")
        roomDir.mkdirs()
        
        val chunkFile = File(roomDir, "chunk_$chunkIndex.webm")
        chunkFile.writeBytes(audioData)

        return try {
            // Convert to PCM16 WAV
            val convertedPath = audioConverterService.convertToPcm16(chunkFile.toPath())
            
            if (convertedPath == null) {
                logger.warn("Failed to convert chunk $chunkIndex")
                return null
            }

            // Transcribe with Whisper
            val transcription = whisperService.transcribe(convertedPath)
            
            // Cleanup temp files
            chunkFile.delete()
            convertedPath.toFile().delete()

            if (transcription.isNullOrBlank()) {
                logger.warn("Empty transcription for chunk $chunkIndex")
                return null
            }

            // Remove overlap text if this is not the first chunk
            val cleanedTranscription = if (hasOverlap && roomState.chunks.isNotEmpty()) {
                removeOverlapText(transcription, roomState.chunks.lastOrNull()?.transcription)
            } else {
                transcription
            }

            val chunkResult = ChunkTranscription(
                chunkIndex = chunkIndex,
                transcription = cleanedTranscription,
                timestamp = System.currentTimeMillis()
            )

            roomState.chunks.add(chunkResult)
            logger.info("Chunk $chunkIndex transcribed: ${cleanedTranscription.take(50)}...")
            
            chunkResult
        } catch (e: Exception) {
            logger.error("Error processing chunk $chunkIndex for room $roomSid", e)
            chunkFile.delete()
            null
        }
    }

    private fun removeOverlapText(newText: String, previousText: String?): String {
        if (previousText.isNullOrBlank()) return newText
        
        // Get last ~30 words from previous chunk
        val prevWords = previousText.split("\\s+".toRegex()).takeLast(30)
        if (prevWords.isEmpty()) return newText

        val newWords = newText.split("\\s+".toRegex())
        
        // Find overlap by looking for matching sequence
        for (overlapSize in minOf(prevWords.size, 20) downTo 3) {
            val prevEnd = prevWords.takeLast(overlapSize).joinToString(" ").lowercase()
            val newStart = newWords.take(overlapSize).joinToString(" ").lowercase()
            
            // Check for similarity (allowing some variation due to transcription differences)
            if (similarity(prevEnd, newStart) > 0.7) {
                // Remove overlapping words from new text
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

        if (roomState.isFinalized && roomState.fullTranscription != null) {
            return roomState.fullTranscription!!
        }

        // Combine all chunks
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
        return fullTranscription
    }

    fun getPartialTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid] ?: return ""
        return roomState.chunks
            .sortedBy { it.chunkIndex }
            .joinToString(" ") { it.transcription }
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
