package com.ingstech.meeting.api.domain

import java.time.Instant

data class RoomSummaryResult(
    val roomSid: String,
    val roomName: String?,
    val summary: String,
    val generalSummary: String?,
    val topicsDiscussed: List<String>,
    val decisionsMade: List<String>,
    val nextSteps: List<String>,
    val participantsMentioned: List<String>,
    val issuesRaised: List<String>,
    val overallSentiment: String?,
    val processedAt: Instant,
    val status: SummaryStatus
)

enum class SummaryStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
