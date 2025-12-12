package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.domain.RoomProcessingState
import com.ingstech.meeting.api.domain.RoomSummaryResult
import com.ingstech.meeting.api.domain.RoomTranscriptionResult
import com.ingstech.meeting.api.service.RoomProcessingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/rooms")
class RoomTranscriptionController(
    private val roomProcessingService: RoomProcessingService
) {

    /**
     * Get transcription for a specific room
     */
    @GetMapping("/{roomSid}/transcription")
    fun getTranscription(@PathVariable roomSid: String): ResponseEntity<RoomTranscriptionResult> {
        val result = roomProcessingService.getTranscription(roomSid)
        return if (result != null) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get summary for a specific room
     */
    @GetMapping("/{roomSid}/summary")
    fun getSummary(@PathVariable roomSid: String): ResponseEntity<RoomSummaryResult> {
        val result = roomProcessingService.getSummary(roomSid)
        return if (result != null) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get full processing state for a room
     */
    @GetMapping("/{roomSid}/status")
    fun getProcessingStatus(@PathVariable roomSid: String): ResponseEntity<RoomProcessingState> {
        val state = roomProcessingService.getProcessingState(roomSid)
        return if (state != null) {
            ResponseEntity.ok(state)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * List all processed rooms
     */
    @GetMapping
    fun listProcessedRooms(): ResponseEntity<List<String>> {
        return ResponseEntity.ok(roomProcessingService.listAllProcessedRooms())
    }

    /**
     * Get both transcription and summary in one call
     * Returns PENDING status if room is not yet processed
     */
    @GetMapping("/{roomSid}/full")
    fun getFullResult(@PathVariable roomSid: String): ResponseEntity<Map<String, Any?>> {
        val transcription = roomProcessingService.getTranscription(roomSid)
        val summary = roomProcessingService.getSummary(roomSid)
        
        // Always return 200 with status info, even if not yet processed
        return ResponseEntity.ok(mapOf(
            "roomSid" to roomSid,
            "transcription" to transcription,
            "summary" to summary,
            "status" to when {
                transcription == null -> "PENDING"
                transcription.status.name == "PROCESSING" -> "PROCESSING"
                summary == null -> "TRANSCRIPTION_COMPLETE"
                summary.status.name == "PROCESSING" -> "SUMMARIZING"
                else -> "COMPLETE"
            }
        ))
    }
}
