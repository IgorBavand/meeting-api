package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.StreamingTranscriptionService
import com.ingstech.meeting.api.service.GeminiSummaryService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/transcription")
class StreamingTranscriptionController(
    private val streamingService: StreamingTranscriptionService,
    private val geminiService: GeminiSummaryService
) {
    private val logger = LoggerFactory.getLogger(StreamingTranscriptionController::class.java)

    /**
     * Receive and process a single audio chunk
     */
    @PostMapping("/chunk", consumes = ["multipart/form-data"])
    fun processChunk(
        @RequestParam("audio") audioFile: MultipartFile,
        @RequestParam("roomSid") roomSid: String,
        @RequestParam("chunkIndex") chunkIndex: Int,
        @RequestParam("hasOverlap", defaultValue = "false") hasOverlap: Boolean
    ): ResponseEntity<Map<String, Any>> {
        
        logger.info("Received chunk $chunkIndex for room $roomSid (${audioFile.size} bytes)")

        val result = streamingService.processChunk(
            roomSid = roomSid,
            chunkIndex = chunkIndex,
            audioData = audioFile.bytes,
            hasOverlap = hasOverlap
        )

        return if (result != null) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "chunkIndex" to result.chunkIndex,
                "transcription" to result.transcription,
                "totalChunks" to streamingService.getChunkCount(roomSid)
            ))
        } else {
            ResponseEntity.ok(mapOf(
                "success" to false,
                "chunkIndex" to chunkIndex,
                "transcription" to "",
                "error" to "Failed to process chunk"
            ))
        }
    }

    /**
     * Finalize transcription and get full text
     */
    @PostMapping("/finalize")
    fun finalizeTranscription(
        @RequestBody request: FinalizeRequest
    ): ResponseEntity<Map<String, Any>> {
        
        logger.info("Finalizing transcription for room ${request.roomSid}")

        val fullTranscription = streamingService.finalizeTranscription(request.roomSid)
        
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "roomSid" to request.roomSid,
            "fullTranscription" to fullTranscription,
            "wordCount" to fullTranscription.split("\\s+".toRegex()).size
        ))
    }

    /**
     * Get current partial transcription
     */
    @GetMapping("/partial/{roomSid}")
    fun getPartialTranscription(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        val transcription = streamingService.getPartialTranscription(roomSid)
        val chunkCount = streamingService.getChunkCount(roomSid)
        
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "transcription" to transcription,
            "chunkCount" to chunkCount,
            "isComplete" to false
        ))
    }

    /**
     * Finalize and generate summary
     */
    @PostMapping("/finalize-with-summary")
    fun finalizeWithSummary(
        @RequestBody request: FinalizeRequest
    ): ResponseEntity<Map<String, Any>> {
        
        logger.info("Finalizing transcription with summary for room ${request.roomSid}")

        val fullTranscription = streamingService.finalizeTranscription(request.roomSid)
        
        // Generate summary with Gemini
        val summary = geminiService.generateSummary(
            roomSid = request.roomSid,
            roomName = request.roomName,
            transcription = fullTranscription
        )

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "roomSid" to request.roomSid,
            "fullTranscription" to fullTranscription,
            "summary" to summary
        ))
    }

    /**
     * Clear room streaming data
     */
    @DeleteMapping("/{roomSid}")
    fun clearRoom(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        streamingService.clearRoom(roomSid)
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "message" to "Room $roomSid cleared"
        ))
    }
}

data class FinalizeRequest(
    val roomSid: String,
    val roomName: String? = null
)
