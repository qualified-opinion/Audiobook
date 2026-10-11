package com.janreins.audiobook.player

import kotlin.math.abs

data class EotTarget(val bookId: String, val trackIndex: Int,
                     val updatedAtElapsedRealtimeMs: Long, val updatedAtWallTimeMs: Long)

interface EndOfTrackStore {
    fun endOfTrackTarget(): EotTarget?
    fun saveEndOfTrackTarget(target: EotTarget)
    fun clearEndOfTrackTarget()
}

object EndOfTrackState {
    const val MAX_AGE_MS = 30 * 60_000L
    enum class Decision { ENABLE, CLEAR, WAIT }

    fun decide(target: EotTarget?, currentBookId: String?, currentIndex: Int,
               elapsedRealtimeMs: Long, wallTimeMs: Long): Decision {
        if (target == null) return Decision.CLEAR
        val age = elapsedRealtimeMs - target.updatedAtElapsedRealtimeMs
        val savedBootTime = target.updatedAtWallTimeMs - target.updatedAtElapsedRealtimeMs
        val currentBootTime = wallTimeMs - elapsedRealtimeMs
        if (age < 0 || age > MAX_AGE_MS || abs(currentBootTime - savedBootTime) > 5_000L) return Decision.CLEAR
        if (currentBookId == null) return Decision.WAIT
        return if (target.bookId == currentBookId && target.trackIndex == currentIndex) Decision.ENABLE
        else Decision.CLEAR
    }
}
