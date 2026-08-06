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

import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns
import androidx.annotation.RequiresApi
import com.mardous.booming.core.sort.AlbumSortMode
import com.mardous.booming.core.sort.ArtistSortMode
import com.mardous.booming.data.local.MediaQueryDispatcher
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.extensions.utilities.collapseSpaces
import com.mardous.booming.util.Preferences
import java.util.LinkedHashMap

interface ArtistRepository {
    fun artists(): List<Artist>
    fun artists(query: String): List<Artist>
    fun artist(artistId: Long): Artist
    fun albumArtists(): List<Artist>
    fun albumArtist(artistName: String): Artist
    fun albumArtists(query: String): List<Artist>
    fun similarAlbumArtists(artist: Artist): List<Artist>
}

class RealArtistRepository(
    private val songRepository: RealSongRepository,
    private val albumRepository: RealAlbumRepository
) : ArtistRepository {

    private val filterSingles: Boolean
        get() = Preferences.ignoreSingles

    override fun artists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
        )
        val minimumSongCount = Preferences.minimumSongCountForArtist
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs)).filter {
            it.songCount >= minimumSongCount
        }
        return sortArtists(artists)
    }

    override fun artist(artistId: Long): Artist {
        if (artistId == Artist.VARIOUS_ARTISTS_ID) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(songs)
                    .filter { Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(it.albumArtistName, ignoreCase = true) }
                    .sorted()
            }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums, filterSingles)
        }

        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                AudioColumns.ARTIST_ID + "=?",
                arrayOf(artistId.toString()),
                DEFAULT_SORT_ORDER
            )
        )
        return Artist(
            id = artistId,
            albums = albumRepository.splitIntoAlbums(
                songs = songs,
                sortMode = AlbumSortMode.ArtistAlbums
            ),
            filterSingles = filterSingles
        )
    }

    override fun artists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(AudioColumns.ARTIST + " LIKE ?", arrayOf("%$query%"), DEFAULT_SORT_ORDER)
        )
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs))
        return sortArtists(artists)
    }

    override fun albumArtists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, "lower(${AudioColumns.ALBUM_ARTIST})")
        )
        val minimumSongCount = Preferences.minimumSongCountForArtist
        val albumArtists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs)).filter {
            it.songCount >= minimumSongCount
        }
        return sortArtists(albumArtists)
    }

    override fun albumArtist(artistName: String): Artist {
        if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(artistName, ignoreCase = true)) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(songs)
                    .filter { Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(it.albumArtistName, ignoreCase = true) }
                    .sorted()
            }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums, filterSingles, isAlbumArtist = true)
        }

        val separators = Preferences.artistSeparators
        // Songs without an ALBUM_ARTIST tag (only ARTIST) are grouped under
        // their artist name in the album-artist lists, so match both columns.
        // lower(NULL) never matches, which previously made clicking such an
        // artist open an empty detail page that immediately navigated back.
        var songs = songRepository.songs(
            songRepository.makeSongCursor(
                "(lower(${AudioColumns.ALBUM_ARTIST})=? OR lower(${AudioColumns.ARTIST})=?)",
                arrayOf(artistName.lowercase(), artistName.lowercase()),
                DEFAULT_SORT_ORDER
            )
        )
        // Split match: the artist name is one segment of a multi-artist tag
        // (e.g. "A、B" and artist "B"). SQL can't split, so fetch candidate
        // songs containing any separator and filter in Kotlin.
        if (songs.isEmpty() && separators.isNotEmpty()) {
            val separatorConditions = separators.map {
                "instr(lower(${AudioColumns.ALBUM_ARTIST}), ?) > 0 OR instr(lower(${AudioColumns.ARTIST}), ?) > 0"
            }
            val selection = "(${separatorConditions.joinToString(" OR ")})"
            val selectionValues = separators.flatMap { listOf(it.toString(), it.toString()) }.toTypedArray()
            val candidates = songRepository.songs(
                songRepository.makeSongCursor(selection, selectionValues, DEFAULT_SORT_ORDER)
            )
            val separatorValues = separators.map { it.toString() }.toTypedArray()
            songs = candidates.filter { song ->
                (song.albumArtistName ?: song.artistName)
                    .split(*separatorValues)
                    .any { it.trim().equals(artistName, ignoreCase = true) }
            }
        }
        val albums = albumRepository.splitIntoAlbums(
            songs = songs,
            sortMode = AlbumSortMode.ArtistAlbums
        )
        return Artist(
            id = albums.firstOrNull()?.artistId ?: -1,
            albums = albums,
            filterSingles = filterSingles,
            isAlbumArtist = true,
            displayName = artistName
        )
    }

    override fun albumArtists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                "${AudioColumns.ALBUM_ARTIST} LIKE ?",
                arrayOf("%$query%"),
                DEFAULT_SORT_ORDER
            )
        )
        val artists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs))
        return sortArtists(artists)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun similarAlbumArtists(artist: Artist): List<Artist> {
        val genreNames = artist.songs.mapNotNull { it.genreName }.distinct()
        if (genreNames.isEmpty()) {
            return arrayListOf()
        }
        val selectionBuilder = StringBuilder("${AudioColumns.GENRE} IN(?")
        for (i in 1 until genreNames.size) {
            selectionBuilder.append(",?")
        }
        selectionBuilder.append(")")
        val songs = songRepository.makeSongCursor(
            MediaQueryDispatcher()
                .setProjection(RealSongRepository.getBaseProjection())
                .setSelection(selectionBuilder.toString())
                .setSelectionArguments(genreNames.toTypedArray())
                .addSelection("(${AudioColumns.ALBUM_ARTIST} NOT NULL AND ${AudioColumns.ALBUM_ARTIST} != ?)")
                .addArguments(artist.name)
        ).let {
            songRepository.songs(it)
        }
        return splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs, sorted = false)).take(MAX_SIMILAR_ARTISTS)
    }

    private fun splitIntoArtists(albums: List<Album>): List<Artist> {
        val filterSingles = this.filterSingles
        return albums.groupBy { it.artistId }
            .map {
                Artist(
                    id = it.key,
                    albums = with(AlbumSortMode.ArtistAlbums) { it.value.sorted() },
                    filterSingles = filterSingles
                )
            }
    }

    fun splitIntoAlbumArtists(albums: List<Album>): List<Artist> {
        val filterSingles = this.filterSingles
        val separators = Preferences.artistSeparators
        // A song tagged with multiple artists (e.g. "A、B") produces one
        // group per artist segment, so each artist appears independently in
        // the list. Albums are shared across the resulting artists.
        val separatorValues = separators.map { it.toString() }.toTypedArray()
        val grouped = LinkedHashMap<String, Pair<String, MutableList<Album>>>()
        for (album in albums) {
            val rawName = album.albumArtistName ?: album.artistName
            if (rawName.isNullOrEmpty()) continue
            val segments = if (separators.isEmpty()) listOf(rawName)
            else rawName.split(*separatorValues)
            for (segment in segments) {
                val key = segment.trim().collapseSpaces().lowercase()
                if (key.isEmpty()) continue
                val displayName = segment.trim().collapseSpaces()
                grouped.getOrPut(key) { displayName to mutableListOf() }.second.add(album)
            }
        }
        return grouped.map { (key, entry) ->
            val (displayName, currentAlbums) = entry
            val sortedAlbums = with(AlbumSortMode.ArtistAlbums) { currentAlbums.sorted() }
            if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(key, ignoreCase = true)) {
                Artist(Artist.VARIOUS_ARTISTS_ID, sortedAlbums, filterSingles, isAlbumArtist = true)
            } else if (displayName.equals(
                    (currentAlbums[0].albumArtistName ?: currentAlbums[0].artistName).collapseSpaces(),
                    ignoreCase = true
                )
            ) {
                // Single-artist name: keep the album's real artist id
                Artist(currentAlbums[0].artistId, sortedAlbums, filterSingles, isAlbumArtist = true)
            } else {
                // Split artist: the segment shares the album's artist id with
                // its siblings, so synthesize a unique id per segment to keep
                // stable-id RecyclerViews (ArtistAdapter.getItemId) distinct.
                Artist(
                    id = key.hashCode().toLong() and Long.MAX_VALUE,
                    albums = sortedAlbums,
                    filterSingles = filterSingles,
                    isAlbumArtist = true,
                    displayName = displayName
                )
            }
        }
    }

    private fun sortArtists(artists: List<Artist>): List<Artist> {
        return with(ArtistSortMode.AllArtists) { artists.sorted() }
    }

    companion object {
        private const val MAX_SIMILAR_ARTISTS = 10
        const val DEFAULT_SORT_ORDER =
            MediaStore.Audio.Artists.ARTIST + ", " + MediaStore.Audio.Albums.ALBUM + ", " + MediaStore.Audio.Media.DEFAULT_SORT_ORDER
    }
}