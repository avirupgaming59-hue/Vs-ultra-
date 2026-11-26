package com.example.service

import kotlin.math.min
import kotlin.math.pow

enum class WatchdogHealthStatus {
    IDLE,
    MONITORING,
    WAITING_BACKOFF,
    REBOOTING,
    CIRCUIT_OPEN,
}

sealed interface RebootDecision {
    data class ScheduleReboot(
        val attemptNumber: Int,
        val maxAttempts: Int,
        val delayMs: Long,
    ) : RebootDecision

    data class CircuitOpen(
        val consecutiveFailures: Int,
        val reason: String,
    ) : RebootDecision
}

data class WatchdogSnapshot(
    val consecutiveFailures: Int = 0,
    val lastHealthyStartMs: Long = 0L,
    val lastFailureMs: Long = 0L,
    val totalReboots: Int = 0,
)

/**
 * Pure exponential-backoff and crash-loop circuit-breaker state machine for
 * [CodeServerWatchdogService]. Prevents tight restart loops if the code-server
 * repeatedly crashes immediately after launch.
 */
class RebootBackoffTracker(
    private val initialBackoffMs: Long = 2_000L,
    private val backoffMultiplier: Double = 2.0,
    private val maxBackoffMs: Long = 60_000L,
    val maxConsecutiveFailures: Int = 5,
    private val healthyResetWindowMs: Long = 60_000L,
) {
    private var consecutiveFailures: Int = 0
    private var lastHealthyStartMs: Long = 0L
    private var lastFailureMs: Long = 0L
    private var totalReboots: Int = 0

    @Synchronized
    fun restore(snapshot: WatchdogSnapshot) {
        consecutiveFailures = snapshot.consecutiveFailures.coerceAtLeast(0)
        lastHealthyStartMs = snapshot.lastHealthyStartMs.coerceAtLeast(0L)
        lastFailureMs = snapshot.lastFailureMs.coerceAtLeast(0L)
        totalReboots = snapshot.totalReboots.coerceAtLeast(0)
    }

    @Synchronized
    fun snapshot(): WatchdogSnapshot = WatchdogSnapshot(
        consecutiveFailures = consecutiveFailures,
        lastHealthyStartMs = lastHealthyStartMs,
        lastFailureMs = lastFailureMs,
        totalReboots = totalReboots,
    )

    @Synchronized
    fun recordHealthyHeartbeat(nowMs: Long) {
        if (lastHealthyStartMs == 0L) {
            lastHealthyStartMs = nowMs
        } else if (consecutiveFailures > 0 && nowMs - lastHealthyStartMs >= healthyResetWindowMs) {
            consecutiveFailures = 0
        }
    }

    @Synchronized
    fun onProcessDied(nowMs: Long): RebootDecision {
        // If it stayed healthy longer than healthyResetWindowMs before dying, treat as a fresh incident
        if (lastHealthyStartMs > 0L && nowMs - lastHealthyStartMs >= healthyResetWindowMs) {
            consecutiveFailures = 0
        }
        lastHealthyStartMs = 0L
        lastFailureMs = nowMs
        consecutiveFailures += 1

        if (consecutiveFailures > maxConsecutiveFailures) {
            return RebootDecision.CircuitOpen(
                consecutiveFailures = consecutiveFailures,
                reason = "Code-server died $consecutiveFailures times consecutively; auto-reboot paused to avoid crash loop.",
            )
        }

        val exponent = (consecutiveFailures - 1).coerceAtLeast(0).toDouble()
        val rawDelay = initialBackoffMs.toDouble() * backoffMultiplier.pow(exponent)
        val delayMs = min(rawDelay, maxBackoffMs.toDouble()).toLong().coerceAtLeast(initialBackoffMs)
        totalReboots += 1

        return RebootDecision.ScheduleReboot(
            attemptNumber = consecutiveFailures,
            maxAttempts = maxConsecutiveFailures,
            delayMs = delayMs,
        )
    }

    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        consecutiveFailures = 0
        lastHealthyStartMs = nowMs
        lastFailureMs = 0L
    }
}
