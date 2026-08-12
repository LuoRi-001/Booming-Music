package com.mardous.booming.util

import com.mardous.booming.data.model.Song

/**
 * Tracks the listening time of the current song session.
 *
 * A session settles exactly once: as soon as the accumulated listening
 * time reaches [minListenMs] (snapshotted into the session when the song
 * starts, so threshold changes only affect subsequently started songs),
 * [trySettleNow] atomically claims the settlement. Settling bumps the
 * play count, inserts a playback event and scrobbles. Afterwards the
 * session keeps accruing listening time, which is written to the database
 * incrementally via [topUp] — periodically by the settle monitor and
 * immediately on pause/transition/finalize — so the stats stay accurate
 * even if the process dies without a clean stop.
 */
class SongPlayCountHelper(private var minListenMs: Long) {

    private val stopWatch = StopWatch()
    var song = Song.emptySong
        private set

    /** Whether this session is finished and must no longer settle or top up. */
    var isFinalized = false
        private set

    /** Accumulated listening time at the moment the settlement was claimed. */
    var settledDurationMs = 0L
        private set

    /** Timestamp (ms) recorded with the settled playback event. */
    var settledEventTimeMs = 0L
        private set

    private var playCountBumped = false
    private var lastWrittenMs = 0L
    private var sessionMinListenMs = minListenMs

    val actualListeningTime: Long
        get() = stopWatch.elapsedTime

    fun updateMinListenMs(ms: Long) {
        synchronized(this) {
            minListenMs = ms
        }
    }

    /**
     * Atomically claims the play-count settlement for the current session
     * when the minimum listening time has been reached. Returns the
     * settled play, or null when not eligible or already settled.
     */
    fun trySettleNow(): SettledPlay? {
        synchronized(this) {
            if (playCountBumped || stopWatch.elapsedTime < sessionMinListenMs) return null
            playCountBumped = true
            settledDurationMs = stopWatch.elapsedTime
            settledEventTimeMs = System.currentTimeMillis()
            lastWrittenMs = settledDurationMs
            return SettledPlay(song, settledDurationMs, settledEventTimeMs)
        }
    }

    /**
     * Top up the listening time accrued since the last write. Returns
     * null when there is nothing to top up (not settled, finalized, or
     * the accrued delta is below [TOP_UP_MIN_MS] unless [force]).
     */
    fun topUp(force: Boolean = false): TopUpResult? {
        synchronized(this) {
            if (!playCountBumped || isFinalized) return null
            val elapsed = stopWatch.elapsedTime
            val deltaMs = elapsed - lastWrittenMs
            if (!force && deltaMs < TOP_UP_MIN_MS) return null
            lastWrittenMs = elapsed
            return TopUpResult(song, settledEventTimeMs, deltaMs, elapsed)
        }
    }

    /**
     * Ends the session. Settles now if it never settled but reached the
     * threshold. Returns what to record, or null when there is nothing to
     * record (never settled and threshold not reached).
     */
    fun notifyFinalized(): FinalizeResult? {
        synchronized(this) {
            isFinalized = true
            val alreadySettled = playCountBumped
            if (!alreadySettled && stopWatch.elapsedTime >= sessionMinListenMs) {
                playCountBumped = true
                settledDurationMs = stopWatch.elapsedTime
                settledEventTimeMs = System.currentTimeMillis()
                lastWrittenMs = settledDurationMs
            }
            if (!playCountBumped) return null
            return FinalizeResult(
                song = song,
                wasSettled = alreadySettled,
                durationMs = settledDurationMs,
                timePlayed = settledEventTimeMs,
                topUpMs = stopWatch.elapsedTime - lastWrittenMs
            )
        }
    }

    fun notifySongChanged(song: Song, isPlaying: Boolean) {
        synchronized(this) {
            stopWatch.reset()
            if (isPlaying) {
                stopWatch.start()
            }
            this.song = song
            this.sessionMinListenMs = minListenMs
            this.playCountBumped = false
            this.settledDurationMs = 0L
            this.settledEventTimeMs = 0L
            this.lastWrittenMs = 0L
            this.isFinalized = false
        }
    }

    fun notifyPlayStateChanged(isPlaying: Boolean) {
        synchronized(this) {
            if (isPlaying) {
                stopWatch.start()
            } else {
                stopWatch.pause()
            }
        }
    }

    companion object {
        private const val TOP_UP_MIN_MS = 5000L
        val TAG: String = SongPlayCountHelper::class.java.simpleName
    }
}

data class SettledPlay(
    val song: Song,
    val durationMs: Long,
    val timePlayed: Long
)

data class TopUpResult(
    val song: Song,
    val timePlayed: Long,
    val deltaMs: Long,
    val totalMs: Long
)

data class FinalizeResult(
    val song: Song,
    /** Whether the settlement already happened before this call. */
    val wasSettled: Boolean,
    /** Settled listening time; this is what the playback event records. */
    val durationMs: Long,
    /** Timestamp recorded with the settled playback event. */
    val timePlayed: Long,
    /** Extra listening time since the last write, to top up when [wasSettled]. */
    val topUpMs: Long
)
