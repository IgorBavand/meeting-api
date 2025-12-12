package com.ingstech.meeting.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Service
class WhisperTranscriptionService(
    @Value("\${whisper.mode:local}") private val whisperMode: String,
    @Value("\${whisper.docker.container:whisper}") private val dockerContainer: String,
    @Value("\${whisper.docker.model.path:/models/ggml-base.bin}") private val dockerModelPath: String,
    @Value("\${whisper.http.url:http://localhost:9000}") private val httpUrl: String,
    @Value("\${whisper.path:/opt/homebrew/bin/whisper-cli}") private val whisperPath: String,
    @Value("\${whisper.model:small}") private val whisperModel: String,
    @Value("\${whisper.language:pt}") private val whisperLanguage: String,
    @Value("\${whisper.threads:4}") private val threads: Int,
    @Value("\${whisper.models.path:/tmp/whisper-models}") private val modelsPath: String,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(WhisperTranscriptionService::class.java)
    private val httpClient = HttpClient.newBuilder().build()
    
    // Cache the model file to avoid repeated lookups
    @Volatile
    private var cachedModelFile: File? = null

    fun transcribe(audioPath: Path): String? {
        return transcribeWithContext(audioPath, "")
    }

    fun transcribeWithContext(audioPath: Path, contextPrompt: String): String? {
        logger.info("Transcribing audio file: ${audioPath.fileName} using mode: $whisperMode")
        
        return when (whisperMode.lowercase()) {
            "docker" -> transcribeViaDocker(audioPath, contextPrompt)
            "http", "api" -> transcribeViaHttp(audioPath)
            else -> transcribeViaLocal(audioPath, contextPrompt)
        }
    }

    /**
     * Transcribe via local whisper-cli with optimized parameters for accuracy
     */
    private fun transcribeViaLocal(audioPath: Path, contextPrompt: String): String? {
        val audioFile = audioPath.toFile()
        
        if (!audioFile.exists()) {
            logger.error("Audio file does not exist: ${audioFile.absolutePath}")
            return null
        }

        // Try to find model with fallback (use cache)
        val modelFile = getCachedModelFile()
        if (modelFile == null) {
            logger.error("No Whisper model found in $modelsPath")
            return null
        }

        return try {
            // BALANCED: Good quality with acceptable speed
            val command = mutableListOf(
                whisperPath,
                "-m", modelFile.absolutePath,
                "-f", audioFile.absolutePath,
                "-t", threads.toString(),
                "-l", whisperLanguage,
                "-np",                // no prints (cleaner output)
                "-nt",                // no timestamps
                // BALANCED parameters - quality + speed
                "-bs", "3",           // beam-size 3 (balance)
                "-bo", "3",           // best-of 3 (balance)
                "-mc", "64",          // context 64 tokens
                "-sns",               // suppress non-speech tokens
                "--flash-attn"        // enable flash attention for speed
            )

            // Add context prompt if available (helps maintain conversation continuity)
            if (contextPrompt.isNotBlank()) {
                command.addAll(listOf("--prompt", contextPrompt.take(224)))  // Max 224 chars
            }

            logger.info("Running Whisper command: ${command.joinToString(" ")}")

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            
            // Read output in real-time
            reader.forEachLine { line ->
                output.appendLine(line)
            }

            val completed = process.waitFor(120, TimeUnit.SECONDS)  // 2 min timeout (reduced from 10)

            if (!completed) {
                process.destroyForcibly()
                logger.error("Whisper transcription timed out")
                return null
            }

            if (process.exitValue() != 0) {
                logger.error("Whisper failed with exit code ${process.exitValue()}: $output")
                return null
            }

            // Parse output
            val transcription = extractTranscriptionFromOutput(output.toString())
            
            if (transcription.isNullOrBlank()) {
                logger.warn("Empty transcription from Whisper")
                return null
            }

            logger.info("Transcription completed, length: ${transcription.length} chars")
            transcription
            
        } catch (e: Exception) {
            logger.error("Error during Whisper transcription", e)
            null
        }
    }

    /**
     * Transcribe via Docker container execution
     */
    private fun transcribeViaDocker(audioPath: Path, contextPrompt: String): String? {
        val audioFile = audioPath.toFile()

        return try {
            val containerAudioPath = "/audio/${audioFile.name}"

            val command = mutableListOf(
                "docker", "exec", dockerContainer,
                "whisper-cli",
                "-m", dockerModelPath,
                "-f", containerAudioPath,
                "-t", threads.toString(),
                "-l", whisperLanguage,
                "-np",
                "-nt",
                "-bs", "8",
                "-bo", "8",
                "-mc", "128",
                "-sns",
                "-nf"
            )

            if (contextPrompt.isNotBlank()) {
                command.addAll(listOf("--prompt", contextPrompt.take(224)))
            }

            logger.info("Running Whisper via Docker: ${command.joinToString(" ")}")

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(600, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                logger.error("Docker Whisper timed out")
                return null
            }

            if (process.exitValue() != 0) {
                logger.error("Docker Whisper failed: $output")
                return null
            }

            extractTranscriptionFromOutput(output)
        } catch (e: Exception) {
            logger.error("Error during Docker Whisper transcription", e)
            null
        }
    }

    /**
     * Transcribe via HTTP API
     */
    private fun transcribeViaHttp(audioPath: Path): String? {
        return try {
            val audioBytes = Files.readAllBytes(audioPath)
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"

            val bodyBuilder = StringBuilder()
            bodyBuilder.append("--$boundary\r\n")
            bodyBuilder.append("Content-Disposition: form-data; name=\"audio_file\"; filename=\"${audioPath.fileName}\"\r\n")
            bodyBuilder.append("Content-Type: audio/wav\r\n\r\n")

            val bodyPrefix = bodyBuilder.toString().toByteArray()
            val bodySuffix = "\r\n--$boundary--\r\n".toByteArray()
            val fullBody = bodyPrefix + audioBytes + bodySuffix

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$httpUrl/asr?output=txt&language=$whisperLanguage"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(fullBody))
                .build()

            logger.info("Calling Whisper HTTP API at $httpUrl")

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val transcription = response.body().trim()
                logger.info("HTTP transcription completed, length: ${transcription.length} chars")
                transcription
            } else {
                logger.error("Whisper HTTP API error: ${response.statusCode()} - ${response.body()}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error calling Whisper HTTP API", e)
            null
        }
    }

    private fun extractTranscriptionFromOutput(output: String): String? {
        // Filter out whisper log lines and extract only transcription
        val logPrefixes = listOf(
            "whisper_", "main:", "ggml_", "system_info:", "warning:", 
            "main: processing", "main: WARNING", "=", "size", "output_"
        )
        
        val lines = output.lines()
            .map { line ->
                // Remove timestamp prefixes if present [00:00:00.000 --> 00:00:05.000]
                line.replace(Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\s*"), "")
                    .trim()
            }
            .filter { line -> 
                line.isNotBlank() && 
                !logPrefixes.any { prefix -> line.lowercase().startsWith(prefix.lowercase()) } &&
                !line.contains("MB") &&
                !line.contains("ms per run") &&
                !line.contains("total time") &&
                !line.contains("encode time") &&
                !line.contains("decode time") &&
                !line.contains("sample time") &&
                !line.matches(Regex("^\\s*$"))
            }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (lines.isNotEmpty()) {
            logger.info("Extracted transcription: ${lines.take(100)}...")
            lines
        } else {
            logger.warn("Could not extract transcription from output")
            null
        }
    }

    fun transcribeMultiple(audioPaths: List<Path>): String {
        return audioPaths.mapNotNull { transcribe(it) }
            .joinToString("\n\n")
    }

    /**
     * Get cached model file or find it
     */
    private fun getCachedModelFile(): File? {
        cachedModelFile?.let { 
            if (it.exists()) return it 
        }
        
        val found = findModelFile()
        if (found != null) {
            cachedModelFile = found
            logger.info("Cached model file: ${found.name}")
        }
        return found
    }

    /**
     * Find the best available model file with fallback
     */
    private fun findModelFile(): File? {
        // Priority order: small first for speed, then others
        val modelPriority = listOf(whisperModel, "small", "medium", "base")
        
        for (model in modelPriority) {
            val file = File("$modelsPath/ggml-$model.bin")
            if (file.exists()) {
                if (model != whisperModel) {
                    logger.warn("Configured model '$whisperModel' not found, using fallback: $model")
                }
                return file
            }
        }
        
        // Try to find any .bin file
        val modelsDir = File(modelsPath)
        if (modelsDir.exists() && modelsDir.isDirectory) {
            val anyModel = modelsDir.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
            if (anyModel != null) {
                logger.warn("Using first available model: ${anyModel.name}")
                return anyModel
            }
        }
        
        return null
    }
}
