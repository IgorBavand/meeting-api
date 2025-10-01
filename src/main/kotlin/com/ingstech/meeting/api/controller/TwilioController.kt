package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.service.TwilioService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/twilio")
class TwilioController(
    private val twilioService: TwilioService
) {

    @GetMapping("/token/{guest}")
    fun getToken(@PathVariable guest: String): String {
        return twilioService.generateTwilioToken(guest);
    }
}