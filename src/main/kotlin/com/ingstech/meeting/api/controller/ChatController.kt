package com.ingstech.meeting.api.controller

import com.ingstech.meeting.api.model.ChatMessage
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Controller
class ChatController(
    private val messagingTemplate: SimpMessagingTemplate
) {

    @MessageMapping("/chat.sendMessage")
    fun sendMessage(@Payload chatMessage: ChatMessage) {
        val messageWithTime = chatMessage.copy(
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        )
        messagingTemplate.convertAndSend("/topic/chat/${chatMessage.room}", messageWithTime)
        println("Message sent to room ${chatMessage.room}: ${chatMessage.user} - ${chatMessage.text}")
    }

    @MessageMapping("/chat.joinRoom")
    fun joinRoom(@Payload chatMessage: ChatMessage) {
        println("User ${chatMessage.user} joined room ${chatMessage.room}")
        val joinMessage = chatMessage.copy(
            text = "${chatMessage.user} entrou na sala",
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        )
        messagingTemplate.convertAndSend("/topic/chat/${chatMessage.room}", joinMessage)
    }

    @MessageMapping("/chat.leaveRoom")
    fun leaveRoom(@Payload chatMessage: ChatMessage) {
        println("User ${chatMessage.user} left room ${chatMessage.room}")
        val leaveMessage = chatMessage.copy(
            text = "${chatMessage.user} saiu da sala",
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        )
        messagingTemplate.convertAndSend("/topic/chat/${chatMessage.room}", leaveMessage)
    }
}