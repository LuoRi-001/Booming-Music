package com.mardous.booming.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackEventDao {
    companion object {
        private const val RANKING_LIMIT = 100
    }

    @Insert
    suspend fun insert(event: PlaybackEventEntity)

    /**
     * Extends the duration of the playback event created at settlement
     * time, so topping up listening time never adds a second row (which
     * would inflate the plays count in rankings).
     */
    @Query("UPDATE PlaybackEventEntity SET duration_ms = :durationMs WHERE song_id = :songId AND time_played = :timePlayed")
    suspend fun updateDuration(songId: Long, timePlayed: Long, durationMs: Long)

    /**
     * Per-song aggregates within a time range. [RankingRow.plays] and
     * [RankingRow.durationMs] are the exact in-range values (unlike
     * [PlayCountEntity.playCount] which is a lifetime counter).
     */
    @Query("""
        SELECT song_id AS id, COUNT(*) AS plays, SUM(duration_ms) AS durationMs,
               MAX(time_played) AS lastPlayed
        FROM PlaybackEventEntity
        WHERE (:cutoff = 0 OR time_played > :cutoff)
        GROUP BY song_id
        ORDER BY plays DESC, durationMs DESC
        LIMIT :limit
    """)
    fun rankingSince(cutoff: Long, limit: Int = RANKING_LIMIT): Flow<List<RankingRow>>

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM PlaybackEventEntity WHERE (:cutoff = 0 OR time_played > :cutoff)")
    fun totalDurationSince(cutoff: Long = 0): Flow<Long>

    @Query("SELECT time_played, duration_ms AS total_play_duration_ms FROM PlaybackEventEntity WHERE time_played > :cutoff ORDER BY time_played ASC")
    suspend fun playbackEventsSince(cutoff: Long): List<PlaybackTimeEntry>
}

data class RankingRow(
    val id: Long,
    val plays: Int,
    @ColumnInfo(name = "durationMs")
    val durationMs: Long,
    @ColumnInfo(name = "lastPlayed")
    val lastPlayed: Long
)
