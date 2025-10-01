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
        config.addAllowedOrigin("http://localhost:4200")
        config.addAllowedOrigin("https://localhost:4200")
        config.addAllowedOrigin("https://localhost.localdomain:4200")
        config.addAllowedOrigin("https://lvh.me:4200")
        config.addAllowedOrigin("https://vite.lvh.me:4200")
        config.addAllowedOrigin("https://192.168.0.3:4200")
        config.addAllowedOrigin("https://172.17.0.1:4200")
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

        return CorsFilter(source)
    }
}
