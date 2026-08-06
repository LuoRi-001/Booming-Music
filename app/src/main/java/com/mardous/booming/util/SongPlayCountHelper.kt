package com.mardous.booming.util

import com.mardous.booming.data.model.Song

class SongPlayCountHelper {

    private val stopWatch = StopWatch()
    var song = Song.emptySong
        private set

    val actualListeningTime: Long
        get() = stopWatch.elapsedTime

    fun shouldBumpPlayCount(): Boolean {
        return stopWatch.elapsedTime >= MIN_LISTEN_MS
    }

    fun notifySongChanged(song: Song, isPlaying: Boolean) {
        synchronized(this) {
            stopWatch.reset()
            if (isPlaying) {
                stopWatch.start()
            }
            this.song = song
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
        private const val MIN_LISTEN_MS = 5000L
        val TAG: String = SongPlayCountHelper::class.java.simpleName
    }
}