package com.alexisgordr.icdetector.models

enum class ForensicCaseState { CAPTURING, POST_CAPTURE, READY, INTERRUPTED }

data class ForensicCase(
    val id: Long,
    val caseCode: String,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String?,
    val state: ForensicCaseState,
    val cellIdentity: String,
    val highestPhase: Int,
    val confirmed: Boolean,
    val sampleCount: Int
)

data class ForensicSample(
    val id: Long,
    val caseId: Long,
    val wallTimeMs: Long,
    val elapsedTimeMs: Long,
    val event: String,
    val payloadJson: String
)
