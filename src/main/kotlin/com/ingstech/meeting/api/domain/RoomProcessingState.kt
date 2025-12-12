package com.ingstech.meeting.api.domain

data class RoomProcessingState(
    val roomSid: String,
    val roomName: String?,
    var transcriptionResult: RoomTranscriptionResult? = null,
    var summaryResult: RoomSummaryResult? = null
)
