package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.RoomProcessingService
import com.ingstech.meeting.api.util.TwilioSignatureValidator
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/webhooks/twilio")
class RoomWebhookController(
    private val roomProcessingService: RoomProcessingService,
    private val signatureValidator: TwilioSignatureValidator
) {
    private val logger = LoggerFactory.getLogger(RoomWebhookController::class.java)

    /**
     * Webhook endpoint for Twilio room events.
     * Responds immediately with 200 OK and processes asynchronously.
     */
    @PostMapping("/room-ended")
    fun handleRoomEnded(
        @RequestHeader("X-Twilio-Signature", required = false) signature: String?,
        @RequestParam allParams: Map<String, String>,
        @RequestBody(required = false) body: String?
    ): ResponseEntity<String> {
        
        val roomSid = allParams["RoomSid"] ?: allParams["roomSid"]
        val roomName = allParams["RoomName"] ?: allParams["roomName"]
        val statusCallbackEvent = allParams["StatusCallbackEvent"] ?: allParams["statusCallbackEvent"]
        
        logger.info("Received Twilio webhook - Event: $statusCallbackEvent, RoomSid: $roomSid, RoomName: $roomName")
        
        // Validate signature (optional in development)
        if (signature != null && !signatureValidator.validate(signature, allParams)) {
            logger.warn("Invalid Twilio signature for room $roomSid")
            // Continue processing anyway for POC - in production, return 403
        }
        
        if (roomSid.isNullOrBlank()) {
            logger.warn("Missing RoomSid in webhook request")
            return ResponseEntity.badRequest().body("Missing RoomSid")
        }
        
        // Check if this is a room-ended event
        if (statusCallbackEvent == "room-ended" || statusCallbackEvent == "room-completed") {
            logger.info("Room ended event detected, starting async processing for room: $roomSid")
            
            // Fire and forget - respond immediately
            roomProcessingService.processRoomAsync(roomSid, roomName)
        } else {
            logger.debug("Ignoring non-room-ended event: $statusCallbackEvent")
        }
        
        // Always return 200 OK immediately (low latency requirement)
        return ResponseEntity.ok("OK")
    }

    /**
     * Alternative endpoint accepting JSON body
     */
    @PostMapping("/room-event", consumes = ["application/json"])
    fun handleRoomEvent(
        @RequestHeader("X-Twilio-Signature", required = false) signature: String?,
        @RequestBody payload: Map<String, Any?>
    ): ResponseEntity<String> {
        
        val roomSid = payload["RoomSid"]?.toString() ?: payload["roomSid"]?.toString()
        val roomName = payload["RoomName"]?.toString() ?: payload["roomName"]?.toString()
        val statusCallbackEvent = payload["StatusCallbackEvent"]?.toString() ?: payload["statusCallbackEvent"]?.toString()
        
        logger.info("Received Twilio JSON webhook - Event: $statusCallbackEvent, RoomSid: $roomSid")
        
        if (roomSid.isNullOrBlank()) {
            return ResponseEntity.badRequest().body("Missing RoomSid")
        }
        
        if (statusCallbackEvent == "room-ended" || statusCallbackEvent == "room-completed") {
            roomProcessingService.processRoomAsync(roomSid, roomName)
        }
        
        return ResponseEntity.ok("OK")
    }

    /**
     * Manual trigger for testing purposes
     */
    @PostMapping("/process-room/{roomSid}")
    fun manualProcessRoom(
        @PathVariable roomSid: String,
        @RequestParam(required = false) roomName: String?
    ): ResponseEntity<String> {
        logger.info("Manual processing triggered for room: $roomSid")
        roomProcessingService.processRoomAsync(roomSid, roomName)
        return ResponseEntity.accepted().body("Processing started for room: $roomSid")
    }
}
