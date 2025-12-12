package com.ingstech.meeting.api.service

import com.ingstech.meeting.api.domain.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class RoomProcessingService(
    private val recordingService: RecordingService,
    private val audioConverterService: AudioConverterService,
    private val whisperService: WhisperTranscriptionService,
    private val geminiService: GeminiSummaryService
) {
    private val logger = LoggerFactory.getLogger(RoomProcessingService::class.java)
    
    // In-memory storage for POC (replace with database in production)
    private val processingStates = ConcurrentHashMap<String, RoomProcessingState>()

    @Async
    fun processRoomAsync(roomSid: String, roomName: String?) {
        logger.info("Starting async processing for room: $roomSid")
        
        // Initialize state
        val state = RoomProcessingState(
            roomSid = roomSid,
            roomName = roomName,
            transcriptionResult = RoomTranscriptionResult(
                roomSid = roomSid,
                roomName = roomName,
                transcription = "",
                duration = 0,
                processedAt = Instant.now(),
                status = TranscriptionStatus.PROCESSING
            )
        )
        processingStates[roomSid] = state
        
        try {
            // Step 1: Download recordings
            logger.info("Step 1: Downloading recordings for room $roomSid")
            val recordingFiles = recordingService.downloadRecordings(roomSid)
            
            if (recordingFiles.isEmpty()) {
                logger.warn("No recordings found for room $roomSid")
                updateTranscriptionStatus(roomSid, TranscriptionStatus.FAILED, "No recordings found")
                return
            }
            
            // Step 2: Convert to PCM16 16kHz
            logger.info("Step 2: Converting ${recordingFiles.size} files to PCM16")
            val convertedFiles = audioConverterService.convertAllToPcm16(recordingFiles)
            
            if (convertedFiles.isEmpty()) {
                logger.warn("Failed to convert any audio files for room $roomSid")
                updateTranscriptionStatus(roomSid, TranscriptionStatus.FAILED, "Audio conversion failed")
                return
            }
            
            // Step 3: Merge if multiple files
            val audioToTranscribe: Path = if (convertedFiles.size > 1) {
                logger.info("Step 3: Merging ${convertedFiles.size} audio files")
                val mergedPath = Path.of("/tmp/recordings/$roomSid/merged.wav")
                audioConverterService.mergeAudioFiles(convertedFiles, mergedPath) ?: convertedFiles.first()
            } else {
                convertedFiles.first()
            }
            
            // Step 4: Transcribe with Whisper
            logger.info("Step 4: Transcribing audio with Whisper")
            val startTime = System.currentTimeMillis()
            val transcription = whisperService.transcribe(audioToTranscribe)
            val duration = System.currentTimeMillis() - startTime
            
            if (transcription.isNullOrBlank()) {
                logger.warn("Transcription failed or empty for room $roomSid")
                updateTranscriptionStatus(roomSid, TranscriptionStatus.FAILED, "Transcription failed")
                return
            }
            
            // Update transcription result
            state.transcriptionResult = RoomTranscriptionResult(
                roomSid = roomSid,
                roomName = roomName,
                transcription = transcription,
                duration = duration,
                processedAt = Instant.now(),
                status = TranscriptionStatus.COMPLETED
            )
            
            logger.info("Transcription completed for room $roomSid in ${duration}ms")
            
            // Step 5: Generate summary with Gemini
            logger.info("Step 5: Generating summary with Gemini")
            state.summaryResult = RoomSummaryResult(
                roomSid = roomSid,
                roomName = roomName,
                summary = "",
                generalSummary = null,
                topicsDiscussed = emptyList(),
                decisionsMade = emptyList(),
                nextSteps = emptyList(),
                participantsMentioned = emptyList(),
                issuesRaised = emptyList(),
                overallSentiment = null,
                processedAt = Instant.now(),
                status = SummaryStatus.PROCESSING
            )
            
            val summaryResult = geminiService.generateSummary(roomSid, roomName, transcription)
            state.summaryResult = summaryResult
            
            logger.info("Summary generation completed for room $roomSid")
            
            // Step 6: Cleanup temporary files
            logger.info("Step 6: Cleaning up temporary files")
            recordingService.cleanupRecordings(roomSid)
            
            logger.info("Processing completed successfully for room $roomSid")
            
        } catch (e: Exception) {
            logger.error("Error processing room $roomSid", e)
            updateTranscriptionStatus(roomSid, TranscriptionStatus.FAILED, e.message ?: "Unknown error")
        }
    }

    private fun updateTranscriptionStatus(roomSid: String, status: TranscriptionStatus, message: String) {
        val state = processingStates[roomSid]
        if (state != null) {
            state.transcriptionResult = RoomTranscriptionResult(
                roomSid = roomSid,
                roomName = state.roomName,
                transcription = message,
                duration = 0,
                processedAt = Instant.now(),
                status = status
            )
        }
    }

    fun getTranscription(roomSid: String): RoomTranscriptionResult? {
        return processingStates[roomSid]?.transcriptionResult
    }

    fun getSummary(roomSid: String): RoomSummaryResult? {
        return processingStates[roomSid]?.summaryResult
    }

    fun getProcessingState(roomSid: String): RoomProcessingState? {
        return processingStates[roomSid]
    }

    fun listAllProcessedRooms(): List<String> {
        return processingStates.keys().toList()
    }
}
