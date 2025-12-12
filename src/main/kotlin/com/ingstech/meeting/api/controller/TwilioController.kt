package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.TwilioService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/twilio")
class TwilioController(
    private val twilioService: TwilioService
) {

    @GetMapping("/token/{guest}", produces = ["text/plain"])
    fun getToken(@PathVariable guest: String): String {
        return twilioService.generateTwilioToken(guest)
    }

    @GetMapping("/token/{guest}/room/{roomName}", produces = ["text/plain"])
    fun getTokenForRoom(
        @PathVariable guest: String,
        @PathVariable roomName: String
    ): String {
        // Create room with recording if it doesn't exist
        twilioService.createRoomWithRecording(roomName)
        return twilioService.generateTokenForRoom(guest, roomName)
    }

    @PostMapping("/room/{roomName}")
    fun createRoom(@PathVariable roomName: String): Map<String, String> {
        val room = twilioService.createRoomWithRecording(roomName)
        return mapOf(
            "sid" to room.sid,
            "name" to room.uniqueName,
            "status" to room.status.toString()
        )
    }
}