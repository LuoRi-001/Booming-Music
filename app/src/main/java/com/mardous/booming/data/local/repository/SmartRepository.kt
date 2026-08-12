/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.data.local.repository

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore.Audio.AudioColumns
import com.mardous.booming.data.local.MediaQueryDispatcher
import com.mardous.booming.data.local.room.HistoryDao
import com.mardous.booming.data.local.room.HistoryEntity
import com.mardous.booming.data.local.room.PlayCountDao
import com.mardous.booming.data.local.room.PlayCountEntity
import com.mardous.booming.data.local.room.PlaybackEventDao
import com.mardous.booming.data.local.room.PlaybackEventEntity
import com.mardous.booming.data.local.room.PlaybackTimeEntry
import com.mardous.booming.data.mapper.toHistoryEntity
import com.mardous.booming.data.mapper.toSong
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.ContentType
import com.mardous.booming.data.model.Song
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

interface SmartRepository {
    suspend fun topAlbums(): List<Album>
    suspend fun topAlbumArtists(): List<Artist>
    suspend fun recentSongs(): List<Song>
    suspend fun recentSongs(query: String, contentType: ContentType): List<Song>
    suspend fun recentAlbums(): List<Album>
    suspend fun recentAlbumArtists(): List<Artist>
    suspend fun notRecentlyPlayedSongs(): List<Song>
    suspend fun playCountSongs(): List<Song>
    fun playCountSongsFlow(): Flow<List<Song>>
    suspend fun findSongsInPlayCount(songs: List<Song>): List<PlayCountEntity>
    suspend fun findSongInPlayCount(songId: Long): PlayCountEntity?
    suspend fun deleteSongInPlayCount(songId: Long)
    suspend fun deleteSongsInPlayCount(songIds: List<Long>)
    suspend fun insetOrIncrementPlayCount(song: Song, timePlayed: Long, actualDurationMs: Long = 0L)
    suspend fun insetOrIncrementSkipCount(song: Song)
    suspend fun addPlayDuration(song: Song, timePlayed: Long, actualDurationMs: Long)
    suspend fun updatePlaybackEventDuration(songId: Long, timePlayed: Long, durationMs: Long)
    suspend fun clearPlayCount()
    suspend fun historySongs(): List<Song>
    fun historySongsFlow(): Flow<List<Song>>
    suspend fun upsertSongInHistory(currentSong: Song)
    suspend fun deleteSongInHistory(songId: Long)
    suspend fun deleteSongsInHistory(songIds: List<Long>)
    suspend fun clearSongHistory()
    suspend fun totalDurationSince(cutoff: Long): Long
    fun totalDurationSinceFlow(cutoff: Long = 0): Flow<Long>
    suspend fun getPlaybackDataSince(cutoff: Long): List<PlaybackTimeEntry>
    fun rankingSince(cutoff: Long): Flow<List<PlayCountEntity>>
    suspend fun insertPlaybackEvent(song: Song, timePlayed: Long, durationMs: Long)
}

class RealSmartRepository(
    private val context: Context,
    private val songRepository: RealSongRepository,
    private val albumRepository: RealAlbumRepository,
    private val artistRepository: RealArtistRepository,
    private val historyDao: HistoryDao,
    private val playCountDao: PlayCountDao,
    private val playbackEventDao: PlaybackEventDao,
) : SmartRepository {

    override suspend fun topAlbums(): List<Album> =
        albumRepository.splitIntoAlbums(playCountSongs(), sorted = false)

    override suspend fun topAlbumArtists(): List<Artist> =
        artistRepository.splitIntoAlbumArtists(topAlbums())

    // "Recent" here means recently *played* (most recent play time first),
    // not recently added to the library. HistoryEntity is upserted on every
    // track change with the current timestamp, so it already holds the
    // last-played order.
    override suspend fun recentSongs(): List<Song> =
        historyDao.historySongs().map { it.toSong() }

    override suspend fun recentSongs(query: String, contentType: ContentType): List<Song> =
        songRepository.songs(makeLastAddedCursor(query, contentType))

    override suspend fun recentAlbums(): List<Album> =
        albumRepository.splitIntoAlbums(recentSongs(), sorted = false)

    override suspend fun recentAlbumArtists(): List<Artist> =
        artistRepository.splitIntoAlbumArtists(recentAlbums())

    override suspend fun notRecentlyPlayedSongs(): List<Song> {
        return buildList {
            addAll(songRepository.songs())

            val playedSongIds = historyDao.playedSongIds()
            removeAll { it.id in playedSongIds }

            val oldSongIds = historyDao.notPlayedSongIds(
                cutoff = Preferences.getHistoryCutoff(context).interval
            )
            val oldSongs = songRepository.songs(
                songRepository.makeSongCursor(
                    selection = "${AudioColumns._ID} IN (${oldSongIds.joinToString(",") { "?" }})",
                    selectionValues = oldSongIds.map { it.toString() }.toTypedArray()
                )
            )
            addAll(oldSongs)
        }
    }

    override suspend fun playCountSongs(): List<Song> {
        playCountDao.cleanInvalidEntries()
        return playCountDao.playCountSongs().fromPlayCountToSongs()
    }

    override fun playCountSongsFlow(): Flow<List<Song>> =
        playCountDao.playCountSongsFlow()
            // Defer reactive refreshes briefly so a play-count write
            // landing during a page transition does not redraw the screen
            // mid-animation.
            .debounce(300)
            .map { playCountEntities ->
                playCountEntities.fromPlayCountToSongs()
            }

    override suspend fun findSongsInPlayCount(songs: List<Song>): List<PlayCountEntity> {
        if (songs.isEmpty()) return emptyList()
        return buildList {
            songs.map { it.id }.chunked(MAX_ITEMS_PER_CHUNK).forEach { chunkIds ->
                addAll(playCountDao.findSongsExistInPlayCount(chunkIds))
            }
        }
    }

    override suspend fun findSongInPlayCount(songId: Long): PlayCountEntity? =
        playCountDao.findSongExistInPlayCount(songId)

    override suspend fun deleteSongInPlayCount(songId: Long) =
        playCountDao.deleteSongInPlayCount(songId)

    override suspend fun deleteSongsInPlayCount(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        songIds.chunked(MAX_ITEMS_PER_CHUNK).forEach { chunkIds ->
            playCountDao.deleteSongsInPlayCount(chunkIds)
        }
    }

    override suspend fun insetOrIncrementPlayCount(song: Song, timePlayed: Long, actualDurationMs: Long) =
        playCountDao.insertOrIncrementPlayCount(song, timePlayed, actualDurationMs)

    override suspend fun insetOrIncrementSkipCount(song: Song) =
        playCountDao.insertOrIncrementSkipCount(song)

    override suspend fun addPlayDuration(song: Song, timePlayed: Long, actualDurationMs: Long) =
        playCountDao.addPlayDuration(song, timePlayed, actualDurationMs)

    override suspend fun updatePlaybackEventDuration(songId: Long, timePlayed: Long, durationMs: Long) =
        playbackEventDao.updateDuration(songId, timePlayed, durationMs)

    override suspend fun clearPlayCount() {
        playCountDao.clearPlayCount()
    }

    override suspend fun historySongs(): List<Song> =
        historyDao.historySongs(Preferences.getHistoryCutoff(context).interval).
            fromHistoryToSongs()

    override fun historySongsFlow(): Flow<List<Song>> =
        historyDao.historySongsFlow(Preferences.getHistoryCutoff(context).interval)
            .map { historyEntities -> historyEntities.fromHistoryToSongs() }

    override suspend fun upsertSongInHistory(currentSong: Song) =
        historyDao.upsertSongInHistory(currentSong.toHistoryEntity(System.currentTimeMillis()))

    override suspend fun deleteSongInHistory(songId: Long) {
        historyDao.deleteSongInHistory(songId)
    }

    override suspend fun deleteSongsInHistory(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        songIds.chunked(MAX_ITEMS_PER_CHUNK).forEach { chunkIds ->
            historyDao.deleteSongsInHistory(chunkIds)
        }
    }

    override suspend fun clearSongHistory() {
        historyDao.clearHistory()
    }

    override suspend fun totalDurationSince(cutoff: Long): Long =
        historyDao.totalDurationSince(cutoff)

    // Both stats sources are backed by the event table so durations and
    // timeline bars reflect plays within the selected range, not lifetime
    // counters.
    override fun totalDurationSinceFlow(cutoff: Long): Flow<Long> =
        playbackEventDao.totalDurationSince(cutoff).debounce(300)

    override suspend fun getPlaybackDataSince(cutoff: Long): List<PlaybackTimeEntry> =
        playbackEventDao.playbackEventsSince(cutoff)

    /**
     * Ranking of songs by plays within [cutoff]..now, backed by play events.
     * [PlayCountEntity] rows are reused as song snapshots, but the play
     * count and duration values are replaced with the exact in-range
     * aggregates.
     */
    override fun rankingSince(cutoff: Long): Flow<List<PlayCountEntity>> =
        playbackEventDao.rankingSince(cutoff)
            .debounce(300)
            .map { rows ->
            if (rows.isEmpty()) {
                emptyList()
            } else {
                val entities = playCountDao.findSongsExistInPlayCount(rows.map { it.id })
                rows.mapNotNull { row ->
                    entities.find { it.id == row.id }?.copy(
                        playCount = row.plays,
                        totalPlayDurationMs = row.durationMs,
                        timePlayed = row.lastPlayed
                    )
                }
            }
        }

    override suspend fun insertPlaybackEvent(song: Song, timePlayed: Long, durationMs: Long) {
        if (song.id < 0 || song.data.isEmpty()) return
        playbackEventDao.insert(
            PlaybackEventEntity(songId = song.id, timePlayed = timePlayed, durationMs = durationMs)
        )
    }

    private fun makeLastAddedCursor(query: String?, contentType: ContentType): Cursor? {
        val cutoff = Preferences.getLastAddedCutoff(context).interval
        val queryDispatcher = MediaQueryDispatcher()
            .setProjection(RealSongRepository.getBaseProjection())
            .setSelection("${AudioColumns.DATE_ADDED}>?")
            .setSelectionArguments(arrayOf(cutoff.toString()))
            .setSortOrder("${AudioColumns.DATE_ADDED} DESC")
        if (!query.isNullOrEmpty()) {
            when (contentType) {
                ContentType.RecentAlbums -> queryDispatcher.addSelection("${AudioColumns.ALBUM} LIKE ?")
                ContentType.RecentArtists -> queryDispatcher.addSelection("${AudioColumns.ALBUM_ARTIST} LIKE ?")
                ContentType.RecentSongs -> queryDispatcher.addSelection("${AudioColumns.TITLE} LIKE ?")
                else -> error("Content type is not valid: $contentType")
            }
            queryDispatcher.addArguments("%$query%")
        }
        return songRepository.makeSongCursor(queryDispatcher)
    }

    private suspend fun List<PlayCountEntity>.fromPlayCountToSongs(): List<Song> = withContext(IO) {
        // Purge any leftover invalid entries (id < 0, empty data, or file deleted).
        // cleanInvalidEntries() is also called by playCountSongs() before loading the
        // list; this call handles the playCountSongsFlow() reactive path.
        playCountDao.cleanInvalidEntries()

        val (deletedTracks, validTracks) = partition {
            it.id == -1L || it.data.isEmpty() || !File(it.data).exists()
        }
        if (deletedTracks.isNotEmpty()) {
            deletedTracks.map { it.id }
                .chunked(MAX_ITEMS_PER_CHUNK)
                .forEach { chunkIds ->
                    deleteSongsInPlayCount(chunkIds)
                }
        }

        validTracks.map { it.toSong() }
    }

    private suspend fun List<HistoryEntity>.fromHistoryToSongs(): List<Song> = withContext(IO) {
        val (deletedTracks, validTracks) = partition { it.id == -1L || !File(it.data).exists() }
        if (deletedTracks.isNotEmpty()) {
            deletedTracks.map { it.id }
                .chunked(MAX_ITEMS_PER_CHUNK)
                .forEach { chunkIds ->
                    deleteSongsInHistory(chunkIds)
                }
        }

        validTracks.map { it.toSong() }
    }
}
