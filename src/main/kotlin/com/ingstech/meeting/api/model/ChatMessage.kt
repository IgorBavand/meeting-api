package com.ingstech.meeting.api.model

data class ChatMessage(
    val user: String,
    val text: String,
    val time: String,
    val room: String
)