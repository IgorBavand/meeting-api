package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.util.TwilioSignatureValidator
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Webhook controller for Twilio room events.
 * Note: With streaming transcription via AssemblyAI, we no longer need to process
 * recordings from Twilio webhooks. This controller just logs events.
 */
@RestController
@RequestMapping("/webhooks/twilio")
class RoomWebhookController(
    private val signatureValidator: TwilioSignatureValidator
) {
    private val logger = LoggerFactory.getLogger(RoomWebhookController::class.java)

    /**
     * Webhook endpoint for Twilio room events.
     * Responds immediately with 200 OK.
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
        }
        
        // With streaming transcription, processing happens during the call
        // This webhook is informational only
        if (statusCallbackEvent == "room-ended" || statusCallbackEvent == "room-completed") {
            logger.info("Room ended: $roomSid - Transcription already processed via streaming")
        }
        
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
        val statusCallbackEvent = payload["StatusCallbackEvent"]?.toString() ?: payload["statusCallbackEvent"]?.toString()
        
        logger.info("Received Twilio JSON webhook - Event: $statusCallbackEvent, RoomSid: $roomSid")
        
        return ResponseEntity.ok("OK")
    }
}
