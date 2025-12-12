package com.ingstech.meeting.api.service

import com.twilio.Twilio
import com.twilio.jwt.accesstoken.AccessToken
import com.twilio.jwt.accesstoken.VideoGrant
import com.twilio.rest.video.v1.Room
import com.twilio.rest.video.v1.room.RoomRecording
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

@Service
class TwilioService(
    @Value("\${twilio.account.sid}") private val accountSid: String,
    @Value("\${twilio.api.key.sid}") private val apiKeySid: String,
    @Value("\${twilio.api.key.secret}") private val apiKeySecret: String
) {
    private val logger = LoggerFactory.getLogger(TwilioService::class.java)

    @PostConstruct
    fun init() {
        Twilio.init(apiKeySid, apiKeySecret, accountSid)
    }

    fun generateTwilioToken(guest: String): String {
        val grant: VideoGrant = VideoGrant()

        val token: AccessToken = AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
            .identity(guest)
            .grant(grant)
            .build()

        logger.info("Generated token for user: $guest")
        return token.toJwt()
    }

    fun generateTokenForRoom(guest: String, roomName: String): String {
        val grant = VideoGrant().setRoom(roomName)

        val token: AccessToken = AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
            .identity(guest)
            .grant(grant)
            .build()

        logger.info("Generated token for user: $guest in room: $roomName")
        return token.toJwt()
    }

    /**
     * Create a room with recording enabled
     */
    fun createRoomWithRecording(roomName: String): Room {
        logger.info("Creating room with recording: $roomName")
        
        return try {
            // Try to fetch existing room
            val existingRoom = Room.fetcher(roomName).fetch()
            logger.info("Room already exists: ${existingRoom.sid}")
            existingRoom
        } catch (e: Exception) {
            // Create new room with recording
            val room = Room.creator()
                .setUniqueName(roomName)
                .setType(Room.RoomType.GROUP)
                .setRecordParticipantsOnConnect(true)
                .setStatusCallback("${getWebhookBaseUrl()}/webhooks/twilio/room-ended")
                .setStatusCallbackMethod(com.twilio.http.HttpMethod.POST)
                .create()
            
            logger.info("Created new room with recording: ${room.sid}")
            room
        }
    }

    fun getRoomByName(roomName: String): Room? {
        return try {
            Room.fetcher(roomName).fetch()
        } catch (e: Exception) {
            logger.warn("Room not found: $roomName")
            null
        }
    }

    fun getRoomBySid(roomSid: String): Room? {
        return try {
            Room.fetcher(roomSid).fetch()
        } catch (e: Exception) {
            logger.warn("Room not found by SID: $roomSid")
            null
        }
    }

    private fun getWebhookBaseUrl(): String {
        // This should be configured via properties in production
        return System.getenv("WEBHOOK_BASE_URL") ?: "https://a200e536236e.ngrok-free.app"
    }
}
