package com.ingstech.meeting.api.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

data class PendingChunk(
    val chunkIndex: Int,
    val audioData: ByteArray,
    val hasOverlap: Boolean,
    val receivedAt: Long = System.currentTimeMillis()
)

data class ProcessedChunk(
    val chunkIndex: Int,
    val transcription: String,
    val processedAt: Long = System.currentTimeMillis()
)

data class OptimizedRoomState(
    val roomSid: String,
    val processedChunks: ConcurrentHashMap<Int, ProcessedChunk> = ConcurrentHashMap(),
    val pendingChunks: ConcurrentLinkedQueue<PendingChunk> = ConcurrentLinkedQueue(),
    val lastProcessedIndex: AtomicInteger = AtomicInteger(-1),
    @Volatile var isFinalized: Boolean = false,
    @Volatile var fullTranscription: String? = null,
    @Volatile var isProcessing: Boolean = false
)

@Service
class OptimizedStreamingTranscriptionService(
    private val audioConverterService: AudioConverterService,
    private val whisperService: WhisperTranscriptionService
) {
    private val logger = LoggerFactory.getLogger(OptimizedStreamingTranscriptionService::class.java)
    private val roomStates = ConcurrentHashMap<String, OptimizedRoomState>()
    private val streamingBasePath = "/tmp/streaming"
    
    // Thread pool for parallel chunk processing
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    )

    init {
        File(streamingBasePath).mkdirs()
    }

    /**
     * Queue a chunk for processing - returns immediately
     */
    fun queueChunk(
        roomSid: String,
        chunkIndex: Int,
        audioData: ByteArray,
        hasOverlap: Boolean
    ): Boolean {
        logger.info("Queueing chunk $chunkIndex for room $roomSid (${audioData.size} bytes)")
        
        val roomState = roomStates.getOrPut(roomSid) { OptimizedRoomState(roomSid) }
        
        if (roomState.isFinalized) {
            logger.warn("Room $roomSid is already finalized, ignoring chunk $chunkIndex")
            return false
        }

        // Add to pending queue
        roomState.pendingChunks.add(PendingChunk(chunkIndex, audioData, hasOverlap))
        
        // Trigger async processing
        processNextChunks(roomState)
        
        return true
    }

    /**
     * Process chunks from queue in parallel but maintain order in results
     */
    private fun processNextChunks(roomState: OptimizedRoomState) {
        if (roomState.isProcessing) return
        
        synchronized(roomState) {
            if (roomState.isProcessing) return
            roomState.isProcessing = true
        }

        executor.submit {
            try {
                while (roomState.pendingChunks.isNotEmpty() && !roomState.isFinalized) {
                    val chunk = roomState.pendingChunks.poll() ?: break
                    
                    try {
                        val result = processChunkFast(roomState, chunk)
                        if (result != null) {
                            roomState.processedChunks[chunk.chunkIndex] = result
                            
                            // Update last processed index
                            var current = roomState.lastProcessedIndex.get()
                            while (chunk.chunkIndex > current) {
                                if (roomState.lastProcessedIndex.compareAndSet(current, chunk.chunkIndex)) {
                                    break
                                }
                                current = roomState.lastProcessedIndex.get()
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing chunk ${chunk.chunkIndex}", e)
                    }
                }
            } finally {
                roomState.isProcessing = false
            }
        }
    }

    /**
     * Fast chunk processing - optimized for speed
     */
    private fun processChunkFast(
        roomState: OptimizedRoomState,
        chunk: PendingChunk
    ): ProcessedChunk? {
        val startTime = System.currentTimeMillis()
        
        val roomDir = File("$streamingBasePath/${roomState.roomSid}")
        roomDir.mkdirs()
        
        val chunkFile = File(roomDir, "chunk_${chunk.chunkIndex}.webm")
        
        return try {
            chunkFile.writeBytes(chunk.audioData)
            
            // Convert to WAV
            val convertedPath = audioConverterService.convertToPcm16(chunkFile.toPath())
            if (convertedPath == null) {
                logger.warn("Failed to convert chunk ${chunk.chunkIndex}")
                return null
            }

            // Get minimal context (only last chunk for speed)
            val context = getMinimalContext(roomState, chunk.chunkIndex)
            
            // Transcribe with fast settings
            val transcription = whisperService.transcribeWithContext(convertedPath, context)
            
            // Cleanup
            chunkFile.delete()
            convertedPath.toFile().delete()

            if (transcription.isNullOrBlank()) {
                return null
            }

            // Clean transcription
            val cleaned = cleanTranscriptionFast(transcription)
            
            val elapsed = System.currentTimeMillis() - startTime
            logger.info("Chunk ${chunk.chunkIndex} processed in ${elapsed}ms: ${cleaned.take(50)}...")

            ProcessedChunk(chunk.chunkIndex, cleaned)
            
        } catch (e: Exception) {
            logger.error("Error in fast processing chunk ${chunk.chunkIndex}", e)
            chunkFile.delete()
            null
        }
    }

    /**
     * Get minimal context for speed - only last processed chunk
     */
    private fun getMinimalContext(roomState: OptimizedRoomState, currentIndex: Int): String {
        if (currentIndex == 0) return ""
        
        // Get previous chunk if available
        val prevChunk = roomState.processedChunks[currentIndex - 1]
        if (prevChunk != null) {
            // Return last 30 words
            val words = prevChunk.transcription.split("\\s+".toRegex())
            return words.takeLast(30).joinToString(" ")
        }
        
        return ""
    }

    /**
     * Fast transcription cleaning - minimal processing
     */
    private fun cleanTranscriptionFast(text: String): String {
        return text
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Get current transcription (partial or complete)
     */
    fun getTranscription(roomSid: String): String {
        val roomState = roomStates[roomSid] ?: return ""
        
        if (roomState.fullTranscription != null) {
            return roomState.fullTranscription!!
        }
        
        return buildOrderedTranscription(roomState)
    }

    /**
     * Build transcription maintaining chunk order
     */
    private fun buildOrderedTranscription(roomState: OptimizedRoomState): String {
        val chunks = roomState.processedChunks.values
            .sortedBy { it.chunkIndex }
        
        if (chunks.isEmpty()) return ""
        
        val result = StringBuilder()
        var lastText = ""
        
        for (chunk in chunks) {
            val text = chunk.transcription
            
            // Remove overlap with previous chunk
            val cleanedText = if (lastText.isNotBlank()) {
                removeOverlapFast(text, lastText)
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

    /**
     * Fast overlap removal - simpler algorithm
     */
    private fun removeOverlapFast(newText: String, prevText: String): String {
        val prevWords = prevText.split("\\s+".toRegex()).takeLast(15)
        val newWords = newText.split("\\s+".toRegex())
        
        if (prevWords.isEmpty() || newWords.isEmpty()) return newText
        
        // Simple: check if first few words of new match last few of prev
        for (matchSize in minOf(10, prevWords.size, newWords.size) downTo 2) {
            val prevEnd = prevWords.takeLast(matchSize).joinToString(" ").lowercase()
            val newStart = newWords.take(matchSize).joinToString(" ").lowercase()
            
            if (prevEnd == newStart) {
                return newWords.drop(matchSize).joinToString(" ")
            }
        }
        
        return newText
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

        // Wait for pending chunks to complete (max 30 seconds)
        val deadline = System.currentTimeMillis() + 30_000
        while (roomState.pendingChunks.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }

        roomState.isFinalized = true
        
        val transcription = buildOrderedTranscription(roomState)
        roomState.fullTranscription = transcription
        
        logger.info("Finalized room $roomSid: ${transcription.length} chars from ${roomState.processedChunks.size} chunks")
        
        // Cleanup
        cleanupRoom(roomSid)
        
        return transcription
    }

    /**
     * Get processing status
     */
    fun getStatus(roomSid: String): Map<String, Any> {
        val roomState = roomStates[roomSid] ?: return mapOf(
            "exists" to false,
            "processedChunks" to 0,
            "pendingChunks" to 0
        )
        
        return mapOf(
            "exists" to true,
            "processedChunks" to roomState.processedChunks.size,
            "pendingChunks" to roomState.pendingChunks.size,
            "isFinalized" to roomState.isFinalized,
            "isProcessing" to roomState.isProcessing,
            "lastProcessedIndex" to roomState.lastProcessedIndex.get()
        )
    }

    fun getChunkCount(roomSid: String): Int {
        return roomStates[roomSid]?.processedChunks?.size ?: 0
    }

    private fun cleanupRoom(roomSid: String) {
        try {
            val roomDir = File("$streamingBasePath/$roomSid")
            if (roomDir.exists()) {
                roomDir.deleteRecursively()
            }
        } catch (e: Exception) {
            logger.warn("Failed to cleanup room $roomSid", e)
        }
    }

    fun clearRoom(roomSid: String) {
        roomStates.remove(roomSid)
        cleanupRoom(roomSid)
    }
}
