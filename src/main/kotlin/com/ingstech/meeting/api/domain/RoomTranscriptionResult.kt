package com.ingstech.meeting.api.domain

import java.time.Instant

data class RoomTranscriptionResult(
    val roomSid: String,
    val roomName: String?,
    val transcription: String,
    val duration: Long,
    val processedAt: Instant,
    val status: TranscriptionStatus
)

enum class TranscriptionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
