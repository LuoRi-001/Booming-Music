package com.mardous.booming.coil.fetcher

import android.content.ContentUris
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.kyant.taglib.TagLib
import com.mardous.booming.coil.model.AudioCover
import com.mardous.booming.coil.util.AudioCoverUtils
import com.mardous.booming.data.local.TagLibMutex
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.extensions.media.asAlbumCoverUri
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.util.ImageSize
import com.mardous.booming.util.PREFERRED_IMAGE_SIZE
import com.mardous.booming.util.Preferences.requireString
import okio.buffer
import okio.source
import java.io.File
import java.io.InputStream

class AudioCoverFetcher(
    private val loader: ImageLoader,
    private val options: Options,
    private val repository: Repository,
    private val cover: AudioCover,
    private val imageSize: String
) : Fetcher {

    private val contentResolver get() = options.context.contentResolver

    override suspend fun fetch(): FetchResult? {
        val stream = try {
            // Always attempt local extraction first, regardless of albumId.
            // The albumId is only needed for the MediaStore album art URI;
            // folder art and TagLib embedded art can be read from the audio
            // file directly.
            val localStream = if (cover.isIgnoreMediaStore) {
                // Primary: folder art + TagLib embedded art from audio file.
                AudioCoverUtils.fallback(cover.path, cover.isUseFolderArt)
                    ?: tagLibCoverStream()
                    ?: embeddedCoverStream()
            } else {
                null
            }
            // Fallback: MediaStore album art (requires valid albumId).
            // Also used as the primary source when isIgnoreMediaStore = false.
            if (localStream != null) {
                localStream
            } else if (cover.albumId >= 0) {
                openAlbumArt()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to decode cover image for ${cover.path}", e)
            null
        }

        if (stream == null) {
            val artistUnknown = cover.artistName.isArtistNameUnknown()
            val networkAvailable = NetworkFeature.Images.Albums.isAvailable
            if (!artistUnknown && networkAvailable) {
                val imageUrl = if (cover.isAlbum) {
                    repository.deezerAlbum(cover.artistName, cover.albumName)?.getBestImage(cover.albumName, imageSize)
                        ?: repository.deezerTrack(cover.artistName, cover.title)?.getBestImage(imageSize)
                } else {
                    repository.deezerTrack(cover.artistName, cover.title)?.getBestImage(imageSize)
                }
                if (imageUrl != null) {
                    val data = loader.components.map(imageUrl, options)
                    val output = loader.components.newFetcher(data, options, loader)
                    val (fetcher) = checkNotNull(output) { "no supported fetcher for $imageUrl" }
                    return fetcher.fetch()
                } else {
                    Log.w(TAG, "No Deezer image for: title=${cover.title} artist=${cover.artistName} album=${cover.albumName}")
                }
            } else {
                Log.w(TAG, "Skipping Deezer: artistUnknown=$artistUnknown networkAvailable=$networkAvailable path=${cover.path} albumId=${cover.albumId}")
            }
            return null
        }
        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = options.fileSystem,
                metadata = null
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    /**
     * Embedded art via libtaglib.so. The native call is serialized through
     * [TagLibMutex]: the stats ranking fires many of these concurrently, and
     * overlapping JNI calls can intermittently return null in optimized
     * builds, which would make covers disappear on re-fetch.
     */
    private fun tagLibCoverStream(): InputStream? {
        val fd = openAudioFileFd()
        if (fd == null) {
            Log.w(TAG, "TagLib: no fd (uri=${cover.uri} path=${cover.path})")
            return null
        }
        return fd.use { pfd ->
            pfd.dup().use { dupFd ->
                synchronized(TagLibMutex.lock) {
                    val bytes = TagLib.getFrontCover(dupFd.detachFd())?.data
                    if (bytes == null) {
                        Log.w(TAG, "TagLib: no embedded cover (path=${cover.path} albumId=${cover.albumId})")
                        null
                    } else {
                        Log.d(TAG, "TagLib: ${bytes.size} bytes (path=${cover.path})")
                        bytes.inputStream()
                    }
                }
            }
        }
    }

    /**
     * Embedded art via the platform MediaMetadataRetriever. Used when TagLib
     * reports no cover, as a second chance before the MediaStore album art
     * URI (which is a table lookup that may be absent even for files with
     * embedded covers).
     */
    private fun embeddedCoverStream(): InputStream? {
        val mmr = MediaMetadataRetriever()
        var result: InputStream? = null
        try {
            mmr.setDataSource(options.context, cover.uri)
            val bytes = mmr.embeddedPicture
            if (bytes == null) {
                Log.w(TAG, "MMR: no embedded picture (path=${cover.path})")
            } else {
                Log.d(TAG, "MMR: ${bytes.size} bytes (path=${cover.path})")
                result = bytes.inputStream()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MMR: failed (uri=${cover.uri} path=${cover.path})", e)
        } finally {
            mmr.release()
        }
        return result
    }

    /**
     * Opens the audio file for reading, trying the Song's own content URI
     * first, then the alternate MediaStore volume, then the absolute path.
     *
     * Songs built from [com.mardous.booming.data.local.room.PlayCountEntity]
     * carry no volumeName, so their URI was resolved through getAudioContentUri()
     * (external_primary on Android 11+). Some devices report files under a
     * different volume, so retry the other one before giving up.
     */
    private fun openAudioFileFd(): ParcelFileDescriptor? {
        // 1. Song's own content URI.
        try {
            contentResolver.openFileDescriptor(cover.uri, "r")?.let { return it }
        } catch (e: Exception) { /* fall through */ }

        // 2. Alternate MediaStore volume (external <-> external_primary).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val songId = try {
                ContentUris.parseId(cover.uri)
            } catch (e: Exception) {
                -1L
            }
            if (songId >= 0) {
                val altVolume = if (cover.uri.toString().contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
                    MediaStore.VOLUME_EXTERNAL
                } else {
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                }
                try {
                    contentResolver.openFileDescriptor(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(altVolume), songId),
                        "r"
                    )?.let { return it }
                } catch (e: Exception) { /* fall through */ }
            }
        }

        // 3. Absolute file path (may be blocked by scoped storage on API 30+,
        //    but works on devices that grant broad storage access).
        if (cover.path.isNotEmpty()) {
            try {
                return ParcelFileDescriptor.open(File(cover.path), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) { /* fall through */ }
        }
        return null
    }

    /**
     * MediaStore album art, trying both the legacy "external" volume and the
     * "external_primary" volume used on Android 11+.
     */
    private fun openAlbumArt(): InputStream? {
        try {
            contentResolver.openInputStream(cover.albumId.asAlbumCoverUri())?.let {
                Log.d(TAG, "AlbumArt: ok (albumId=${cover.albumId} path=${cover.path})")
                return it
            }
        } catch (e: Exception) { /* fall through */ }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                contentResolver.openInputStream(
                    Uri.parse("content://media/${MediaStore.VOLUME_EXTERNAL_PRIMARY}/audio/albumart/${cover.albumId}")
                )?.let {
                    Log.d(TAG, "AlbumArt: ok via primary volume (albumId=${cover.albumId} path=${cover.path})")
                    return it
                }
            } catch (e: Exception) { /* fall through */ }
        }
        Log.w(TAG, "AlbumArt: no album art (albumId=${cover.albumId} path=${cover.path})")
        return null
    }

    class Factory(
        private val preferences: SharedPreferences,
        private val repository: Repository
    ) : Fetcher.Factory<AudioCover> {
        override fun create(
            data: AudioCover,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return AudioCoverFetcher(
                loader = imageLoader,
                options = options,
                repository = repository,
                cover = data,
                imageSize = preferences.requireString(PREFERRED_IMAGE_SIZE, ImageSize.MEDIUM)
            )
        }
    }

    companion object {
        private const val TAG = "AudioCoverFetcher"
    }
}