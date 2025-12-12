package com.ingstech.meeting.api.util

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class TwilioSignatureValidator(
    @Value("\${twilio.auth.token:}") private val authToken: String,
    @Value("\${twilio.webhook.url:}") private val webhookUrl: String,
    @Value("\${twilio.signature.validation.enabled:false}") private val validationEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(TwilioSignatureValidator::class.java)

    fun validate(signature: String, params: Map<String, String>): Boolean {
        if (!validationEnabled) {
            logger.debug("Signature validation disabled")
            return true
        }

        if (authToken.isBlank() || webhookUrl.isBlank()) {
            logger.warn("Auth token or webhook URL not configured, skipping validation")
            return true
        }

        return try {
            val expectedSignature = computeSignature(params)
            val isValid = signature == expectedSignature
            
            if (!isValid) {
                logger.warn("Signature mismatch. Expected: $expectedSignature, Got: $signature")
            }
            
            isValid
        } catch (e: Exception) {
            logger.error("Error validating Twilio signature", e)
            false
        }
    }

    private fun computeSignature(params: Map<String, String>): String {
        // Sort parameters alphabetically and concatenate
        val sortedParams = params.toSortedMap()
        val data = StringBuilder(webhookUrl)
        
        sortedParams.forEach { (key, value) ->
            data.append(key).append(value)
        }

        // Compute HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(authToken.toByteArray(), "HmacSHA1")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data.toString().toByteArray())
        
        // Base64 encode
        return Base64.getEncoder().encodeToString(rawHmac)
    }
}
