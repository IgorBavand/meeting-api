package com.ingstech.meeting.api.service

import com.twilio.jwt.accesstoken.AccessToken
import com.twilio.jwt.accesstoken.VideoGrant
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TwilioService(
    @Value("\${twilio.account.sid}") private val accountSid: String,
    @Value("\${twilio.api.key.sid}") private val apiKeySid: String,
    @Value("\${twilio.api.key.secret}") private val apiKeySecret: String
) {

    fun generateTwilioToken(guest: String): String {
        val grant: VideoGrant = VideoGrant()

        val token: AccessToken = AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
            .identity(guest)
            .grant(grant)
            .build()

        println(token.toJwt())

        return token.toJwt()
    }
}
