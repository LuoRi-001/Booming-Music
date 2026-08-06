package com.mardous.booming.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One row per completed play (insert, never overwritten). This is the
 * event-level record that lets listening stats (ranking, play count,
 * duration, timeline) be filtered by a time range, unlike
 * [PlayCountEntity] which only keeps cumulative counters.
 */
@Entity(primaryKeys = ["song_id", "time_played"])
data class PlaybackEventEntity(
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "time_played")
    val timePlayed: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long
)
