package com.ingstech.meeting.api.service

import com.twilio.Twilio
import com.twilio.rest.video.v1.room.RoomRecording
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import jakarta.annotation.PostConstruct

@Service
class RecordingService(
    @Value("\${twilio.account.sid}") private val accountSid: String,
    @Value("\${twilio.api.key.sid}") private val apiKeySid: String,
    @Value("\${twilio.api.key.secret}") private val apiKeySecret: String
) {
    private val logger = LoggerFactory.getLogger(RecordingService::class.java)
    private val httpClient = HttpClient.newBuilder().build()
    private val recordingsBasePath = "/tmp/recordings"

    @PostConstruct
    fun init() {
        Twilio.init(apiKeySid, apiKeySecret, accountSid)
        File(recordingsBasePath).mkdirs()
    }

    fun listRecordings(roomSid: String): List<RoomRecording> {
        logger.info("Listing recordings for room: $roomSid")
        return RoomRecording.reader(roomSid).read().toList()
    }

    fun downloadRecordingsAsync(roomSid: String): CompletableFuture<List<Path>> {
        return CompletableFuture.supplyAsync {
            downloadRecordings(roomSid)
        }
    }

    fun downloadRecordings(roomSid: String): List<Path> {
        val recordings = listRecordings(roomSid)
        logger.info("Found ${recordings.size} recordings for room $roomSid")

        val roomDir = File("$recordingsBasePath/$roomSid")
        roomDir.mkdirs()

        val downloadedFiles = mutableListOf<Path>()
        val futures = recordings.map { recording ->
            CompletableFuture.supplyAsync {
                downloadSingleRecording(recording, roomDir)
            }
        }

        CompletableFuture.allOf(*futures.toTypedArray()).join()
        
        futures.forEach { future ->
            future.get()?.let { downloadedFiles.add(it) }
        }

        logger.info("Downloaded ${downloadedFiles.size} files for room $roomSid")
        return downloadedFiles
    }

    private fun downloadSingleRecording(recording: RoomRecording, roomDir: File): Path? {
        return try {
            val mediaUri = "https://video.twilio.com/v1/Rooms/${recording.roomSid}/Recordings/${recording.sid}/Media"
            
            val credentials = Base64.getEncoder().encodeToString("$apiKeySid:$apiKeySecret".toByteArray())
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(mediaUri))
                .header("Authorization", "Basic $credentials")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            
            if (response.statusCode() == 200) {
                val extension = getExtensionFromCodec(recording.codec?.toString() ?: "webm")
                val outputFile = File(roomDir, "${recording.sid}.$extension")
                
                FileOutputStream(outputFile).use { fos ->
                    fos.write(response.body())
                }
                
                logger.info("Downloaded recording ${recording.sid} to ${outputFile.absolutePath}")
                outputFile.toPath()
            } else if (response.statusCode() == 302) {
                val redirectUrl = response.headers().firstValue("Location").orElse(null)
                if (redirectUrl != null) {
                    downloadFromUrl(redirectUrl, recording, roomDir)
                } else {
                    logger.error("Redirect without Location header for ${recording.sid}")
                    null
                }
            } else {
                logger.error("Failed to download recording ${recording.sid}: HTTP ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error downloading recording ${recording.sid}", e)
            null
        }
    }

    private fun downloadFromUrl(url: String, recording: RoomRecording, roomDir: File): Path? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        
        return if (response.statusCode() == 200) {
            val extension = getExtensionFromCodec(recording.codec?.toString() ?: "webm")
            val outputFile = File(roomDir, "${recording.sid}.$extension")
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(response.body())
            }
            
            logger.info("Downloaded recording ${recording.sid} to ${outputFile.absolutePath}")
            outputFile.toPath()
        } else {
            logger.error("Failed to download from redirect URL for ${recording.sid}: HTTP ${response.statusCode()}")
            null
        }
    }

    private fun getExtensionFromCodec(codec: String): String {
        return when (codec.lowercase()) {
            "vp8", "vp9" -> "webm"
            "h264" -> "mp4"
            "opus" -> "mka"
            "pcmu", "pcma" -> "wav"
            else -> "webm"
        }
    }

    fun cleanupRecordings(roomSid: String) {
        try {
            val roomDir = File("$recordingsBasePath/$roomSid")
            if (roomDir.exists()) {
                roomDir.deleteRecursively()
                logger.info("Cleaned up recordings for room $roomSid")
            }
        } catch (e: Exception) {
            logger.warn("Failed to cleanup recordings for room $roomSid", e)
        }
    }
}
