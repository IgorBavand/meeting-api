package com.ingstech.meeting.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsConfig {

    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()
        config.allowCredentials = false
        config.addAllowedOriginPattern("http://localhost:*")
        config.addAllowedOriginPattern("https://localhost:*")
        config.addAllowedOriginPattern("http://172.20.10.*:*")
        config.addAllowedOriginPattern("https://172.20.10.*:*")
        config.addAllowedOriginPattern("http://192.168.*.*:*")
        config.addAllowedOriginPattern("https://192.168.*.*:*")
        config.addAllowedOriginPattern("https://lvh.me:*")
        config.addAllowedOriginPattern("https://vite.lvh.me:*")
        config.addAllowedOrigin("https://meeting-portal.vercel.app")
        config.addAllowedHeader("*")
        config.addAllowedMethod("GET")
        config.addAllowedMethod("POST")
        config.addAllowedMethod("PUT")
        config.addAllowedMethod("DELETE")
        config.addAllowedMethod("OPTIONS")

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        source.registerCorsConfiguration("/ws/**", config)
        source.registerCorsConfiguration("/webhooks/**", config)

        return CorsFilter(source)
    }
}
