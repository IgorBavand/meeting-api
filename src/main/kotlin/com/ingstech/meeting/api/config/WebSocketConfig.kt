package com.ingstech.meeting.api.config

import com.ingstech.meeting.api.service.RealtimeTranscriptionHandler
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.*

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS()
    }
}

@Configuration
@EnableWebSocket
class RawWebSocketConfig(
    private val realtimeTranscriptionHandler: RealtimeTranscriptionHandler
) : WebSocketConfigurer {
    
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(realtimeTranscriptionHandler, "/ws/transcription")
            .setAllowedOriginPatterns("*")
    }
}