package com.ingstech.meeting.api.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Service
class AudioConverterService(
    @Value("\${ffmpeg.path:ffmpeg}") private val ffmpegPath: String
) {
    private val logger = LoggerFactory.getLogger(AudioConverterService::class.java)

    fun convertToPcm16(inputPath: Path): Path? {
        val inputFile = inputPath.toFile()
        val outputFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}_converted.wav")

        return try {
            // Audio processing pipeline:
            // 1. highpass filter at 80Hz to remove low frequency noise/rumble
            // 2. lowpass filter at 8000Hz to remove high frequency noise
            // 3. afftdn for noise reduction (moderate settings)
            // 4. compand for dynamic range compression (makes speech clearer)
            // 5. volume normalization
            val audioFilters = listOf(
                "highpass=f=80",
                "lowpass=f=8000",
                "afftdn=nf=-20",
                "compand=attacks=0.3:decays=0.8:points=-80/-80|-45/-45|-27/-25|0/-7|20/-7",
                "loudnorm=I=-16:TP=-1.5:LRA=11"
            ).joinToString(",")
            
            val command = listOf(
                ffmpegPath,
                "-i", inputFile.absolutePath,
                "-af", audioFilters,
                "-ar", "16000",           // 16kHz sample rate (optimal for Whisper)
                "-ac", "1",               // Mono channel
                "-sample_fmt", "s16",     // 16-bit signed integer
                "-f", "wav",              // WAV format
                "-y",                     // Overwrite output
                outputFile.absolutePath
            )

            logger.info("Converting audio with noise reduction: ${inputFile.name}")
            
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(120, TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                logger.error("FFmpeg conversion timed out for ${inputFile.name}")
                return null
            }

            if (process.exitValue() == 0 && outputFile.exists()) {
                logger.info("Successfully converted ${inputFile.name}")
                outputFile.toPath()
            } else {
                val output = process.inputStream.bufferedReader().readText()
                logger.error("FFmpeg conversion failed for ${inputFile.name}: $output")
                null
            }
        } catch (e: Exception) {
            logger.error("Error converting audio file: ${inputFile.name}", e)
            null
        }
    }

    fun convertAllToPcm16(inputPaths: List<Path>): List<Path> {
        return inputPaths.mapNotNull { convertToPcm16(it) }
    }

    fun mergeAudioFiles(inputPaths: List<Path>, outputPath: Path): Path? {
        if (inputPaths.isEmpty()) return null
        if (inputPaths.size == 1) return inputPaths.first()

        return try {
            val listFile = File(outputPath.parent.toFile(), "filelist.txt")
            listFile.writeText(inputPaths.joinToString("\n") { "file '${it.toAbsolutePath()}'" })

            val command = listOf(
                ffmpegPath,
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.absolutePath,
                "-ar", "16000",
                "-ac", "1",
                "-sample_fmt", "s16",
                "-f", "wav",
                "-y",
                outputPath.toAbsolutePath().toString()
            )

            logger.info("Merging ${inputPaths.size} audio files")

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(300, TimeUnit.SECONDS)

            listFile.delete()

            if (!completed) {
                process.destroyForcibly()
                logger.error("FFmpeg merge timed out")
                return null
            }

            if (process.exitValue() == 0 && outputPath.toFile().exists()) {
                logger.info("Successfully merged audio files to ${outputPath.fileName}")
                outputPath
            } else {
                val output = process.inputStream.bufferedReader().readText()
                logger.error("FFmpeg merge failed: $output")
                null
            }
        } catch (e: Exception) {
            logger.error("Error merging audio files", e)
            null
        }
    }
}
