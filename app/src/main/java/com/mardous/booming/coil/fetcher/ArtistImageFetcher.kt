package com.mardous.booming.coil.fetcher

import android.content.ContentResolver
import android.content.SharedPreferences
import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mardous.booming.coil.CustomArtistImageManager
import com.mardous.booming.coil.model.ArtistImage
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.util.ImageSize
import com.mardous.booming.util.PREFERRED_IMAGE_SIZE
import com.mardous.booming.util.Preferences.requireString
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import kotlin.math.min

class ArtistImageFetcher(
    private val loader: ImageLoader,
    private val options: Options,
    private val customImageManager: CustomArtistImageManager,
    private val repository: Repository,
    private val image: ArtistImage,
    private val imageSize: String
) : Fetcher {

    companion object {
        // Maximum 4 queries per artist
        private const val MAX_RESULT_PER_PAGE = 5
        private const val MAX_RESULT_COUNT = 20
    }

    private val contentResolver: ContentResolver
        get() = options.context.contentResolver

    override suspend fun fetch(): FetchResult? {
        // 1. Custom image (fastest)
        if (customImageManager.hasCustomImage(image)) {
            val imageFile = customImageManager.getCustomImageFile(image)
            if (imageFile?.isFile == true) {
                return SourceFetchResult(
                    source = ImageSource(imageFile.toOkioPath(), options.fileSystem),
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(imageFile.extension),
                    dataSource = DataSource.DISK
                )
            }
        }

        // 2. Local MediaStore cover (fast — avoid network if we have local art)
        if (image.id > 0 || image.id == Artist.VARIOUS_ARTISTS_ID) {
            try {
                val stream = contentResolver.openInputStream(image.coverUri)
                if (stream != null) {
                    return SourceFetchResult(
                        source = ImageSource(
                            source = stream.source().buffer(),
                            fileSystem = options.fileSystem,
                            metadata = null
                        ),
                        mimeType = contentResolver.getType(image.coverUri),
                        dataSource = DataSource.DISK
                    )
                }
            } catch (_: Exception) {
                // Local cover unavailable, fall through to network
            }
        }

        // 3. Network (Deezer) — slowest, only if no local image
        if (!image.isNameUnknown && NetworkFeature.Images.Artists.isAvailable) {
            var pageIndex = 0
            var revisedResults = 0
            var deezerArtist = repository.deezerArtist(image.name, MAX_RESULT_PER_PAGE, pageIndex)
            val total = min(deezerArtist?.total ?: 0, MAX_RESULT_COUNT)
            while (deezerArtist != null && revisedResults < total) {
                val (matched, imageUrl) = deezerArtist.getBestImage(image.name, imageSize)
                if (matched) {
                    if (imageUrl != null) {
                        val data = loader.components.map(imageUrl, options)
                        val output = loader.components.newFetcher(data, options, loader)
                        val (fetcher) = checkNotNull(output) { "no supported fetcher for $imageUrl" }
                        return fetcher.fetch()
                    }
                    break
                }
                revisedResults += deezerArtist.result.size
                if (revisedResults < total) {
                    deezerArtist = repository.deezerArtist(image.name, min((total - revisedResults), MAX_RESULT_PER_PAGE), pageIndex++)
                }
            }
        }

        return null
    }

    class Factory(
        private val preferences: SharedPreferences,
        private val customImageManager: CustomArtistImageManager,
        private val repository: Repository
    ) : Fetcher.Factory<ArtistImage> {
        override fun create(
            data: ArtistImage,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return ArtistImageFetcher(
                loader = imageLoader,
                options = options,
                customImageManager = customImageManager,
                repository = repository,
                image = data,
                imageSize = preferences.requireString(PREFERRED_IMAGE_SIZE, ImageSize.MEDIUM)
            )
        }
    }
}