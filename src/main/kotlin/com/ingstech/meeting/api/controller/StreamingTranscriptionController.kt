package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.AssemblyAITranscriptionService
import com.ingstech.meeting.api.service.GeminiSummaryService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/transcription")
class StreamingTranscriptionController(
    private val assemblyAIService: AssemblyAITranscriptionService,
    private val geminiService: GeminiSummaryService
) {
    private val logger = LoggerFactory.getLogger(StreamingTranscriptionController::class.java)

    /**
     * Receive and queue a single audio chunk for processing
     * Returns immediately without waiting for transcription
     */
    @PostMapping("/chunk", consumes = ["multipart/form-data"])
    fun processChunk(
        @RequestParam("audio") audioFile: MultipartFile,
        @RequestParam("roomSid") roomSid: String,
        @RequestParam("chunkIndex") chunkIndex: Int,
        @RequestParam("hasOverlap", defaultValue = "false") hasOverlap: Boolean
    ): ResponseEntity<Map<String, Any>> {
        
        logger.info("Received chunk $chunkIndex for room $roomSid (${audioFile.size} bytes)")

        // Queue chunk for async processing - returns immediately
        val queued = assemblyAIService.queueChunk(
            roomSid = roomSid,
            chunkIndex = chunkIndex,
            audioData = audioFile.bytes,
            hasOverlap = hasOverlap
        )

        return ResponseEntity.ok(mapOf(
            "success" to queued,
            "chunkIndex" to chunkIndex,
            "queued" to queued,
            "status" to assemblyAIService.getStatus(roomSid)
        ))
    }

    /**
     * Finalize transcription and get full text
     */
    @PostMapping("/finalize")
    fun finalizeTranscription(
        @RequestBody request: FinalizeRequest
    ): ResponseEntity<Map<String, Any>> {
        
        logger.info("Finalizing transcription for room ${request.roomSid}")

        val fullTranscription = assemblyAIService.finalizeTranscription(request.roomSid)
        
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "roomSid" to request.roomSid,
            "fullTranscription" to fullTranscription,
            "wordCount" to fullTranscription.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        ))
    }

    /**
     * Get current transcription (partial or complete)
     */
    @GetMapping("/partial/{roomSid}")
    fun getPartialTranscription(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        val transcription = assemblyAIService.getTranscription(roomSid)
        val status = assemblyAIService.getStatus(roomSid)
        
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "transcription" to transcription,
            "status" to status
        ))
    }

    /**
     * Get processing status
     */
    @GetMapping("/status/{roomSid}")
    fun getStatus(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(assemblyAIService.getStatus(roomSid))
    }

    /**
     * Finalize and generate summary
     */
    @PostMapping("/finalize-with-summary")
    fun finalizeWithSummary(
        @RequestBody request: FinalizeRequest
    ): ResponseEntity<Map<String, Any?>> {
        
        logger.info("Finalizing transcription with summary for room ${request.roomSid}")

        val fullTranscription = assemblyAIService.finalizeTranscription(request.roomSid)
        
        // Generate summary with Gemini
        val summaryResult = geminiService.generateSummary(
            roomSid = request.roomSid,
            roomName = request.roomName,
            transcription = fullTranscription
        )
        
        // Convert to map and save
        val summaryMap = mapOf(
            "generalSummary" to summaryResult.generalSummary,
            "topicsDiscussed" to summaryResult.topicsDiscussed,
            "decisionsMade" to summaryResult.decisionsMade,
            "nextSteps" to summaryResult.nextSteps,
            "participantsMentioned" to summaryResult.participantsMentioned,
            "issuesRaised" to summaryResult.issuesRaised,
            "overallSentiment" to summaryResult.overallSentiment,
            "status" to summaryResult.status.name
        )
        
        assemblyAIService.setSummary(request.roomSid, summaryMap)

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "roomSid" to request.roomSid,
            "fullTranscription" to fullTranscription,
            "summary" to summaryMap,
            "wordCount" to fullTranscription.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        ))
    }

    /**
     * Clear room streaming data
     */
    @DeleteMapping("/{roomSid}")
    fun clearRoom(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        assemblyAIService.clearRoom(roomSid)
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "message" to "Room $roomSid cleared"
        ))
    }
    
    /**
     * Get all active streaming sessions info
     */
    @GetMapping("/sessions")
    fun getActiveSessions(): ResponseEntity<Map<String, Any>> {
        val chunkBased = assemblyAIService.getStatus("__all__")
        return ResponseEntity.ok(mapOf(
            "mode" to "streaming",
            "description" to "Use /ws/transcription WebSocket for real-time streaming",
            "chunkBasedStatus" to chunkBased
        ))
    }
}

data class FinalizeRequest(
    val roomSid: String,
    val roomName: String? = null
)
