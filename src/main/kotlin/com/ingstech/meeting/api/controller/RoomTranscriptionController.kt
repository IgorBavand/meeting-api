package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.AssemblyAITranscriptionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/rooms")
class RoomTranscriptionController(
    private val assemblyAIService: AssemblyAITranscriptionService
) {

    /**
     * Get transcription for a specific room
     */
    @GetMapping("/{roomSid}/transcription")
    fun getTranscription(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        val transcription = assemblyAIService.getTranscription(roomSid)
        val status = assemblyAIService.getStatus(roomSid)
        
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "transcription" to transcription,
            "status" to if (status["isFinalized"] == true) "COMPLETED" else "PROCESSING",
            "processedChunks" to (status["processedChunks"] ?: 0)
        ))
    }

    /**
     * Get summary for a specific room
     */
    @GetMapping("/{roomSid}/summary")
    fun getSummary(@PathVariable roomSid: String): ResponseEntity<Map<String, Any?>> {
        val summary = assemblyAIService.getSummary(roomSid)
        
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "summary" to summary,
            "status" to if (summary != null) "COMPLETED" else "PENDING"
        ))
    }

    /**
     * Get processing status for a room
     */
    @GetMapping("/{roomSid}/status")
    fun getProcessingStatus(@PathVariable roomSid: String): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(assemblyAIService.getStatus(roomSid))
    }

    /**
     * Get both transcription and summary in one call
     */
    @GetMapping("/{roomSid}/full")
    fun getFullResult(@PathVariable roomSid: String): ResponseEntity<Map<String, Any?>> {
        val status = assemblyAIService.getStatus(roomSid)
        val transcription = assemblyAIService.getTranscription(roomSid)
        val summary = assemblyAIService.getSummary(roomSid)
        
        val overallStatus = when {
            status["exists"] == false -> "NOT_FOUND"
            status["activeProcessing"] as? Int ?: 0 > 0 -> "PROCESSING"
            transcription.isBlank() -> "PENDING"
            summary == null -> "TRANSCRIPTION_COMPLETE"
            else -> "COMPLETE"
        }
        
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "transcription" to mapOf(
                "text" to transcription,
                "status" to if (transcription.isNotBlank()) "COMPLETED" else "PENDING"
            ),
            "summary" to summary,
            "status" to overallStatus,
            "processedChunks" to (status["processedChunks"] ?: 0),
            "activeProcessing" to (status["activeProcessing"] ?: 0)
        ))
    }
}
