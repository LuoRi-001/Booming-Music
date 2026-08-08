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

package com.mardous.booming.ui.screen.library

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.coil.CustomPlaylistImageManager
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.filesystem.FileSystemItem
import com.mardous.booming.core.model.filesystem.FileSystemQuery
import com.mardous.booming.data.SongProvider
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.local.room.InclExclDao
import com.mardous.booming.data.local.room.InclExclEntity
import com.mardous.booming.data.local.room.PlaybackTimeEntry
import com.mardous.booming.data.local.room.PlayCountEntity
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.local.room.SongEntity
import com.mardous.booming.data.mapper.toSongEntity
import com.mardous.booming.data.mapper.toSongsEntity
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.ContentType
import com.mardous.booming.data.model.Folder
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.model.Playlist
import com.mardous.booming.data.model.ReleaseYear
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.network.LoginParams
import com.mardous.booming.data.model.network.ScrobblingService
import com.mardous.booming.extensions.files.getCanonicalPathSafe
import com.mardous.booming.extensions.media.indexOfSong
import com.mardous.booming.ui.dialogs.playlists.AddToPlaylistUiState
import com.mardous.booming.ui.screen.library.home.SuggestedResult
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.StorageUtil
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

class LibraryViewModel(
    private val repository: Repository,
    private val inclExclDao: InclExclDao,
    private val customPlaylistImageManager: CustomPlaylistImageManager
) : ViewModel() {

    init {
        viewModelScope.launch(IO) {
            initializeBlacklist()
            deleteMissingContent()
        }
    }

    private val suggestions = MutableLiveData(SuggestedResult.Idle)
    private val songs = MutableLiveData<List<Song>>()
    private val albums = MutableLiveData<List<Album>>()
    private val artists = MutableLiveData<List<Artist>>()
    private val playlists = MutableLiveData<List<PlaylistWithSongs>>()
    private val genres = MutableLiveData<List<Genre>>()
    private val years = MutableLiveData<List<ReleaseYear>>()
    private val fileSystem = MutableLiveData<FileSystemQuery>()
    private val fabMargin = MutableLiveData(LibraryMargin(0))
    private val miniPlayerMargin = MutableLiveData(LibraryMargin(0))
    private val songHistory = MutableLiveData<List<Song>>()

    fun getSuggestions(): LiveData<SuggestedResult> = suggestions
    fun getSongs(): LiveData<List<Song>> = songs
    fun getAlbums(): LiveData<List<Album>> = albums
    fun getArtists(): LiveData<List<Artist>> = artists
    fun getPlaylists(): LiveData<List<PlaylistWithSongs>> = playlists
    fun getGenres(): LiveData<List<Genre>> = genres
    fun getYears(): LiveData<List<ReleaseYear>> = years
    fun getFileSystem(): LiveData<FileSystemQuery> = fileSystem
    fun getFabMargin(): LiveData<LibraryMargin> = fabMargin
    fun getMiniPlayerMargin(): LiveData<LibraryMargin> = miniPlayerMargin

    private fun createValueAnimator(oldValue: Int, newValue: Int, setter: (Int) -> Unit): Animator {
        return ValueAnimator.ofInt(oldValue, newValue).apply {
            addUpdateListener { setter(it.animatedValue as Int) }
            doOnEnd { setter(newValue) }
            start()
        }
    }

    fun setLibraryMargins(fabBottomMargin: LibraryMargin, bottomSheetMargin: LibraryMargin) {
        val fabAnimator = createValueAnimator(
            oldValue = fabMargin.value!!.margin,
            newValue = fabBottomMargin.margin
        ) {
            fabMargin.postValue(fabBottomMargin.copy(margin = it))
        }
        val miniPlayerAnimator = createValueAnimator(
            oldValue = miniPlayerMargin.value!!.margin,
            newValue = bottomSheetMargin.margin
        ) {
            miniPlayerMargin.postValue(bottomSheetMargin.copy(margin = it))
        }
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fabAnimator, miniPlayerAnimator)
        animatorSet.start()
    }

    suspend fun albumById(id: Long) = repository.albumById(id)
    fun artistById(id: Long) = repository.artistById(id)
    suspend fun devicePlaylistById(id: Long) = repository.devicePlaylist(id)
    fun genreBySong(song: Song): LiveData<Genre> = liveData(IO) {
        emit(repository.genreBySong(song))
    }

    fun allSongs() = liveData(IO) {
        emit(repository.allSongs())
    }

    fun forceReload(reloadType: ReloadType) = viewModelScope.launch(IO) {
        when (reloadType) {
            ReloadType.Songs -> fetchSongs()
            ReloadType.Albums -> fetchAlbums()
            ReloadType.Artists -> fetchArtists()
            ReloadType.Playlists -> fetchPlaylists()
            ReloadType.Genres -> fetchGenres()
            ReloadType.Folders -> fetchFolders()
            ReloadType.Years -> fetchYears()
            ReloadType.Suggestions -> fetchSuggestions()
        }
    }

    private suspend fun fetchSuggestions() {
        val currentValue = suggestions.value?.copy(state = SuggestedResult.State.Loading)
            ?: SuggestedResult(SuggestedResult.State.Loading)
        suggestions.postValue(currentValue)

        val data = repository.homeSuggestions()
        suggestions.postValue(SuggestedResult(SuggestedResult.State.Ready, data))
    }

    private suspend fun fetchSongs() {
        songs.postValue(repository.allSongs())
    }

    private suspend fun fetchAlbums() {
        albums.postValue(repository.allAlbums())
    }

    private suspend fun fetchArtists() {
        if (Preferences.onlyAlbumArtists) {
            artists.postValue(repository.allAlbumArtists())
        } else {
            artists.postValue(repository.allArtists())
        }
    }

    private suspend fun fetchPlaylists() {
        playlists.postValue(repository.playlistsWithSongs(true))
    }

    private suspend fun fetchGenres() {
        genres.postValue(repository.allGenres())
    }

    private suspend fun fetchYears() {
        years.postValue(repository.allYears())
    }

    private fun fetchFolders() {
        navigateToPath()
    }

    private suspend fun filesToSongs(
        files: List<FileSystemItem>,
        includeFolders: Boolean,
        deepListing: Boolean
    ): List<Song> {
        return buildList {
            if (includeFolders) {
                val songs = files.filterIsInstance<Folder>().flatMap {
                    if (deepListing) {
                        repository.songsByFolder(it.filePath, true)
                    } else {
                        it.songs
                    }
                }
                addAll(songs)
            }
            addAll(files.filterIsInstance<Song>())
        }
    }

    fun navigateToPath(
        navigateToPath: String? = null,
        hierarchyView: Boolean = Preferences.hierarchyFolderView
    ) = viewModelScope.launch(IO) {
        if (hierarchyView) {
            val path = if (navigateToPath.isNullOrEmpty()) {
                fileSystem.value?.path ?: Preferences.startDirectory.getCanonicalPathSafe()
            } else {
                navigateToPath
            }
            fileSystem.postValue(repository.filesInPath(path))
        } else {
            fileSystem.postValue(repository.allFolders())
        }
    }

    fun scanPaths(context: Context, paths: Array<String>): LiveData<Int> = liveData(IO) {
        val scanResult = runCatching {
            suspendCancellableCoroutine { continuation ->
                var progress = 0
                val total = paths.size

                MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                    progress++
                    if (progress == total && continuation.isActive) {
                        continuation.resume(total)
                    }
                }
            }
        }
        emit(scanResult.getOrElse { 0 })
    }

    fun scanAllPaths(context: Context): LiveData<Int> {
        // We attempt to retrieve all storage roots using our StorageManager-based utility.
        // If that fails for some reason, we fall back to Environment.getExternalStorageDirectory()
        // to scan at least the device's primary storage root.
        val storageRoots = StorageUtil.refreshStorageVolumes()
                .map { it.filePath }
                .plus(Environment.getExternalStorageDirectory().path)
                .distinct()
                .toTypedArray()

        return scanPaths(context, storageRoots)
    }

    fun blacklistPath(file: File) = viewModelScope.launch(IO) {
        inclExclDao.insertPath(InclExclEntity(file.getCanonicalPathSafe(), InclExclDao.BLACKLIST))
        forceReload(ReloadType.Folders)
    }

    fun listSongsFromFiles(
        song: Song,
        files: List<FileSystemItem>?
    ) = liveData(IO) {
        if (!files.isNullOrEmpty()) {
            val currentFolder = fileSystem.value
            val songs = if (currentFolder != null) {
                filesToSongs(files, includeFolders = false, deepListing = false)
            } else {
                emptyList()
            }
            val startPos = songs.indexOfSong(song.id).coerceAtLeast(0)
            emit(songs to startPos)
        }
    }

    fun songs(providers: List<Any>): LiveData<List<Song>> = liveData(IO) {
        val songs = providers.filterIsInstance<SongProvider>()
            .flatMap { it.songs }
        emit(songs)
    }

    fun songs(
        files: List<FileSystemItem>,
        includeFolders: Boolean,
        deepListing: Boolean
    ): LiveData<List<Song>> = liveData(IO) {
        val songs = filesToSongs(files, includeFolders, deepListing)
        emit(songs)
    }

    fun artists(type: ContentType): LiveData<List<Artist>> = liveData(IO) {
        when (type) {
            ContentType.TopArtists -> emit(repository.topArtists())
            ContentType.RecentArtists -> emit(repository.recentArtists())
            else -> emit(arrayListOf())
        }
    }

    fun albums(type: ContentType): LiveData<List<Album>> = liveData(IO) {
        when (type) {
            ContentType.TopAlbums -> emit(repository.topAlbums())
            ContentType.RecentAlbums -> emit(repository.recentAlbums())
            else -> emit(arrayListOf())
        }
    }

    fun clearHistory() {
        viewModelScope.launch(IO) {
            repository.clearSongHistory()
        }
        songHistory.value = emptyList()
    }


    fun lastAddedSongs(): LiveData<List<Song>> = liveData(IO) {
        emit(repository.recentSongs())
    }

    fun favoriteSongsFlow() = repository.favoriteSongsFlow()

    fun playCountSongsFlow() = repository.playCountSongsFlow()

    // Cached stats queries: switching the time range emits the last known
    // data for the range immediately, so the screen doesn't flash an empty
    // state (or collapse the layout) while the fresh query runs.
    private val rankingCache = mutableMapOf<StatsTimeRange, List<PlayCountEntity>>()
    private val timelineCache = mutableMapOf<StatsTimeRange, List<TimelineBar>>()
    private val durationCache = mutableMapOf<StatsTimeRange, Long>()

    // Scroll position of the listening stats screen, kept in this activity-
    // scoped ViewModel so it survives fragment recreation when leaving and
    // re-entering the screen.
    var statsScrollIndex: Int = 0
        private set
    var statsScrollOffset: Int = 0
        private set

    fun saveStatsScrollPosition(index: Int, offset: Int) {
        statsScrollIndex = index
        statsScrollOffset = offset
    }

    // Last measured height (px) of the listening stats card on the home
    // screen. Used as a placeholder height when the card is recreated, so it
    // occupies its full height from the first frame — the home screen's
    // scroll restore then never sees a partially rendered card.
    var statsCardHeightPx: Int = 0

    fun listeningStatsRanking(range: StatsTimeRange): LiveData<List<PlayCountEntity>> {
        val cached = rankingCache[range]
        return if (cached != null) {
            // Synchronously emit the cached value on the main thread so a
            // fresh composition renders the full card (incl. the top-3 block)
            // on its first frame. A liveData(IO) cold start goes through the
            // IO dispatcher queue and can take hundreds of ms, which delays
            // the card's final height and breaks the home screen's scroll
            // restore. The async refresh below keeps the value live (e.g. on
            // the stats screen while listening).
            liveData {
                emit(cached)
                repository.rankingSince(cutoffForRange(range)).collect { fresh ->
                    rankingCache[range] = fresh
                    emit(fresh)
                }
            }
        } else {
            liveData(IO) {
                repository.rankingSince(cutoffForRange(range)).collect { fresh ->
                    rankingCache[range] = fresh
                    emit(fresh)
                }
            }
        }
    }

    fun getTimelineBars(range: StatsTimeRange): LiveData<List<TimelineBar>> {
        val cached = timelineCache[range]
        return if (cached != null) {
            liveData {
                emit(cached)
                val bars = withContext(IO) {
                    val now = LocalDate.now()
                    val zoneId = ZoneId.systemDefault()
                    val entries = repository.getPlaybackDataSince(cutoffForRange(range))
                    computeTimelineBars(entries, range, now, zoneId)
                }
                timelineCache[range] = bars
                emit(bars)
            }
        } else {
            liveData(IO) {
                val now = LocalDate.now()
                val zoneId = ZoneId.systemDefault()
                val entries = repository.getPlaybackDataSince(cutoffForRange(range))
                val bars = computeTimelineBars(entries, range, now, zoneId)
                timelineCache[range] = bars
                emit(bars)
            }
        }
    }

    fun totalDurationFor(range: StatsTimeRange): LiveData<Long> {
        val cached = durationCache[range]
        return if (cached != null) {
            liveData {
                emit(cached)
                repository.totalDurationSinceFlow(cutoffForRange(range)).collect { fresh ->
                    durationCache[range] = fresh
                    emit(fresh)
                }
            }
        } else {
            liveData(IO) {
                repository.totalDurationSinceFlow(cutoffForRange(range)).collect { fresh ->
                    durationCache[range] = fresh
                    emit(fresh)
                }
            }
        }
    }

    fun totalDurationForToday(): LiveData<Long> = totalDurationFor(StatsTimeRange.TODAY)

    fun totalDurationForThisWeek(): LiveData<Long> = totalDurationFor(StatsTimeRange.WEEK)

    fun totalDurationForThisMonth(): LiveData<Long> = totalDurationFor(StatsTimeRange.MONTH)

    fun totalDurationForThisYear(): LiveData<Long> = totalDurationFor(StatsTimeRange.YEAR)

    fun totalDurationAllTime(): LiveData<Long> = totalDurationFor(StatsTimeRange.ALL)

    private fun cutoffForRange(range: StatsTimeRange): Long {
        val now = LocalDate.now()
        val zoneId = ZoneId.systemDefault()
        return when (range) {
            StatsTimeRange.TODAY -> now.atStartOfDay(zoneId).toInstant().toEpochMilli()
            StatsTimeRange.WEEK -> now.with(DayOfWeek.MONDAY).atStartOfDay(zoneId).toInstant().toEpochMilli()
            StatsTimeRange.MONTH -> now.withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            StatsTimeRange.YEAR -> now.withDayOfYear(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            StatsTimeRange.ALL -> 0L
        }
    }

    fun historySongsFlow() = repository.historySongsFlow()

    fun notRecentlyPlayedSongs(): LiveData<List<Song>> = liveData(IO) {
        emit(repository.notRecentlyPlayedSongs())
    }

    private val _addToPlaylistUiState = MutableStateFlow<AddToPlaylistUiState?>(null)
    val addToPlaylistUiState = _addToPlaylistUiState.asStateFlow()

    fun prepareToAddToPlaylist(searchQuery: String? = null) = viewModelScope.launch(IO) {
        _addToPlaylistUiState.update { it ?: AddToPlaylistUiState.Loading }

        val playlists = if (searchQuery.isNullOrBlank()) {
            repository.playlistsWithSongs()
        } else {
            repository.searchPlaylists(searchQuery)
        }

        _addToPlaylistUiState.value = if (playlists.isEmpty()) {
            AddToPlaylistUiState.Empty(searchQuery)
        } else {
            AddToPlaylistUiState.Ready(playlists)
        }
    }

    fun addToPlaylists(
        playlistsIds: List<Long>,
        songs: List<Song>
    ) = viewModelScope.launch(IO) {
        val state = addToPlaylistUiState.value ?: return@launch
        if (state is AddToPlaylistUiState.Ready && state.playlists.isNotEmpty()) {
            _addToPlaylistUiState.value = state.copy(isLoading = true)

            var success = true
            val playlists = state.playlists.filter { playlistsIds.contains(it.playlistEntity.playListId) }
            for (playlist in playlists) {
                val checkedSongs = songs.filterNot {
                    repository.checkSongExistInPlaylist(playlist.playlistEntity, it)
                }
                val result = runCatching {
                    insertSongs(
                        songs = checkedSongs.map {
                            it.toSongEntity(playListId = playlist.playlistEntity.playListId)
                        }
                    )
                }
                success = success && result.isSuccess
            }

            _addToPlaylistUiState.value = AddToPlaylistUiState.Completed(success)
            forceReload(ReloadType.Playlists)
        }
    }

    fun finishAddingToPlaylists() {
        _addToPlaylistUiState.value = null
    }

    fun updatePlaylist(
        playlist: PlaylistEntity,
        newName: String,
        newImageUri: String?,
        newDescription: String?
    ) = viewModelScope.launch(IO) {
        var imageUri = playlist.customCoverUri
        if (newImageUri != imageUri) {
            if (!imageUri.isNullOrEmpty()) {
                customPlaylistImageManager.deleteImage(imageUri.toUri())
            }
            imageUri = customPlaylistImageManager.createPlaylistImage(newImageUri)?.toString()
        }
        repository.updatePlaylist(
            playlist.copy(
                playlistName = newName,
                customCoverUri = imageUri,
                description = newDescription
            )
        )
    }

    fun deleteSongsInPlaylist(songs: List<SongEntity>) = viewModelScope.launch(IO) {
        repository.deleteSongsInPlaylist(songs)
        forceReload(ReloadType.Playlists)
    }

    fun deletePlaylists(playlists: List<PlaylistEntity>) = viewModelScope.launch(IO) {
        for (playlist in playlists) {
            playlist.customCoverUri?.let {
                customPlaylistImageManager.deleteImage(it.toUri())
            }
        }
        repository.deletePlaylists(playlists)
        forceReload(ReloadType.Playlists)
    }

    fun createCustomPlaylist(
        playlistName: String,
        customCoverUri: String? = null,
        description: String? = null,
        songs: List<Song> = emptyList()
    ): LiveData<AddToPlaylistResult> = liveData(IO) {
        emit(AddToPlaylistResult(playlistName, isWorking = true))

        val playlists = checkPlaylistExists(playlistName)
        if (playlists.isEmpty()) {
            val playlistImageUri = customPlaylistImageManager.createPlaylistImage(customCoverUri)
            val playlistEntity = PlaylistEntity(
                playlistName = playlistName,
                customCoverUri = playlistImageUri?.toString(),
                description = description
            )
            val playlistId: Long = createPlaylist(playlistEntity)
            if (songs.isNotEmpty()) {
                insertSongs(songs.map { it.toSongEntity(playlistId) })
            }
            val playlistCreated = (playlistId != -1L)
            val isFavoritePlaylist = repository.checkFavoritePlaylist()?.playListId == playlistId
            emit(
                AddToPlaylistResult(
                    playlistName,
                    playlistCreated = playlistCreated,
                    isFavoritePlaylist = isFavoritePlaylist,
                    insertedSongs = songs.size
                )
            )
        } else {
            // Playlist already exists
            emit(AddToPlaylistResult(playlistName, playlistCreated = false))
        }
        forceReload(ReloadType.Playlists)
    }

    fun favoritePlaylist(): LiveData<PlaylistEntity> = liveData(IO) {
        emit(repository.favoritePlaylist())
    }

    fun isSongFavorite(song: Song): LiveData<Boolean> = liveData(IO) {
        emit(repository.isSongFavorite(song.id))
    }

    suspend fun insertSongs(songs: List<SongEntity>) = repository.insertSongsInPlaylist(songs)

    private suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity> =
        repository.checkPlaylistExists(playlistName)

    private suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long =
        repository.createPlaylist(playlistEntity)

    private suspend fun deleteMissingContent() {
        repository.deleteMissingContent()
    }

    fun getDevicePlaylists(): LiveData<List<ImportablePlaylistResult>> = liveData(IO) {
        val devicePlaylists = repository.devicePlaylists()
        val importablePlaylists = devicePlaylists.map {
            ImportablePlaylistResult(it.name, it.getSongs())
        }.filter {
            it.songs.isNotEmpty()
        }
        emit(importablePlaylists)
    }

    fun importPlaylist(context: Context, playlist: ImportablePlaylistResult): LiveData<ImportResult> = liveData(IO) {
        var count = 1
        var playlistName = playlist.playlistName
        while (repository.checkPlaylistExists(playlistName).isNotEmpty() && count <= 100) {
            playlistName = "${playlist.playlistName} $count"
            count++
        }
        if (repository.checkPlaylistExists(playlistName).isEmpty()) {
            val id = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))
            if (id != -1L) {
                repository.insertSongsInPlaylist(playlist.songs.toSongsEntity(id))
                emit(ImportResult.success(context, playlist))
                forceReload(ReloadType.Playlists)
            } else {
                emit(ImportResult.error(context, playlist))
            }
        } else {
            emit(ImportResult.error(context, playlist))
        }
    }

    fun deleteSongs(songs: List<Song>) = viewModelScope.launch(IO) {
        repository.deleteSongs(songs)
    }

    private suspend fun initializeBlacklist() {
        if (!Preferences.initializedBlacklist) {
            repository.initializeBlacklist()
            Preferences.initializedBlacklist = true
        }
    }

    fun getLoginState(service: ScrobblingService) = repository.getLoginState(service)

    fun logInToService(service: ScrobblingService, params: LoginParams) = viewModelScope.launch(IO) {
        repository.loginToService(service, params)
    }

    fun logoutFromService(service: ScrobblingService) = viewModelScope.launch(IO) {
        repository.logoutFromService(service)
    }

    @Suppress("DEPRECATION")
    fun handleIntent(intent: Intent): LiveData<HandleIntentResult> = liveData(IO) {
        val result = HandleIntentResult(handled = true)
        val uri = intent.data
        if (uri == null || uri.scheme == "glance-action") {
            emit(result.copy(handled = false))
        } else try {
            if (uri.toString().isNotEmpty()) {
                val songs = repository.songsByUri(uri)
                emit(result.copy(songs = songs, failed = songs.isEmpty()))
            } else {
                when (intent.type) {
                    MediaStore.Audio.Playlists.CONTENT_TYPE -> {
                        val id = parseIdFromIntent(intent, "playlistId", "playlist")
                        if (id >= 0) {
                            val position = intent.getIntExtra("position", 0)
                            val playlist = devicePlaylistById(id)
                            if (playlist != Playlist.EmptyPlaylist) {
                                emit(result.copy(songs = playlist.getSongs(), position = position))
                            } else {
                                emit(result)
                            }
                        }
                    }

                    MediaStore.Audio.Albums.CONTENT_TYPE -> {
                        val id = parseIdFromIntent(intent, "albumId", "album")
                        if (id >= 0) {
                            val position = intent.getIntExtra("position", 0)
                            emit(result.copy(songs = albumById(id).songs, position = position))
                        }
                    }

                    MediaStore.Audio.Artists.CONTENT_TYPE -> {
                        val id = parseIdFromIntent(intent, "artistId", "artist")
                        if (id >= 0) {
                            val position = intent.getIntExtra("position", 0)
                            emit(result.copy(songs = artistById(id).songs, position = position))
                        }
                    }

                    else -> emit(result.copy(handled = false))
                }
            }
        } catch (e: Exception) {
            Log.e("LibraryViewModel", "handleIntent() failed; intent=$intent", e)
        }
    }

    private fun parseIdFromIntent(intent: Intent, longKey: String, stringKey: String): Long {
        var id = intent.getLongExtra(longKey, -1)
        if (id < 0) {
            id = intent.getStringExtra(stringKey)?.toLongOrNull() ?: -1
        }
        return id
    }
}

data class TimelineBar(val label: String, val durationMs: Long)

enum class StatsTimeRange {
    TODAY, WEEK, MONTH, YEAR, ALL
}

private fun computeTimelineBars(
    entries: List<PlaybackTimeEntry>,
    range: StatsTimeRange,
    now: LocalDate,
    zoneId: ZoneId
): List<TimelineBar> {
    if (entries.isEmpty()) return emptyList()

    return when (range) {
        StatsTimeRange.TODAY -> {
            val buckets = (0 until 6).map { i ->
                val hourStart = i * 4
                val hourEnd = hourStart + 4
                TimelineBar(
                    label = "${hourStart}h",
                    durationMs = 0L
                )
            }.toMutableList()
            entries.forEach { entry ->
                val hour = java.time.Instant.ofEpochMilli(entry.timePlayed)
                    .atZone(zoneId).hour
                val bucketIndex = (hour / 4).coerceIn(0, 5)
                buckets[bucketIndex] = buckets[bucketIndex].copy(
                    durationMs = buckets[bucketIndex].durationMs + entry.totalPlayDurationMs
                )
            }
            buckets
        }
        StatsTimeRange.WEEK -> {
            val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val dayTotals = LongArray(7)
            val cut = now.with(DayOfWeek.MONDAY).atStartOfDay(zoneId).toInstant().toEpochMilli()
            entries.filter { it.timePlayed >= cut }.forEach { entry ->
                val dayOfWeek = java.time.Instant.ofEpochMilli(entry.timePlayed)
                    .atZone(zoneId).dayOfWeek.value - 1 // Mon=0
                dayTotals[dayOfWeek] += entry.totalPlayDurationMs
            }
            dayNames.mapIndexed { i, name -> TimelineBar(name, dayTotals[i]) }
        }
        StatsTimeRange.MONTH -> {
            val daysInMonth = now.lengthOfMonth()
            val buckets = 4
            val bucketTotals = LongArray(buckets)
            val cut = now.withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val daysPerBucket = kotlin.math.ceil(daysInMonth.toDouble() / buckets).toInt()
            entries.filter { it.timePlayed >= cut }.forEach { entry ->
                val dayOfMonth = java.time.Instant.ofEpochMilli(entry.timePlayed)
                    .atZone(zoneId).dayOfMonth
                val bucketIndex = ((dayOfMonth - 1) / daysPerBucket).coerceIn(0, buckets - 1)
                bucketTotals[bucketIndex] += entry.totalPlayDurationMs
            }
            bucketTotals.mapIndexed { i, total -> TimelineBar("W${i + 1}", total) }
        }
        StatsTimeRange.YEAR -> {
            val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val monthTotals = LongArray(12)
            val cut = now.withDayOfYear(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            entries.filter { it.timePlayed >= cut }.forEach { entry ->
                val month = java.time.Instant.ofEpochMilli(entry.timePlayed)
                    .atZone(zoneId).monthValue - 1
                monthTotals[month] += entry.totalPlayDurationMs
            }
            monthNames.mapIndexed { i, name -> TimelineBar(name, monthTotals[i]) }
        }
        StatsTimeRange.ALL -> {
            val yearTotals = entries.groupBy {
                java.time.Instant.ofEpochMilli(it.timePlayed).atZone(zoneId).year
            }.mapValues { (_, group) -> group.sumOf { it.totalPlayDurationMs } }
            yearTotals.entries.map { TimelineBar(it.key.toString(), it.value) }
                .sortedBy { it.label }
        }
    }
}

enum class ReloadType {
    Songs,
    Albums,
    Artists,
    Playlists,
    Genres,
    Folders,
    Years,
    Suggestions
}
