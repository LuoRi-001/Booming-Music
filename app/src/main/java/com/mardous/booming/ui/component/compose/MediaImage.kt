package com.mardous.booming.ui.component.compose

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImagePainter.State
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import com.mardous.booming.R
import com.mardous.booming.data.model.Song

@Composable
fun MediaImage(
    model: Any?,
    placeholderIcon: Int = R.drawable.ic_music_note_24dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val sizeResolver = rememberConstraintsSizeResolver()
    val platformContext = LocalPlatformContext.current

    // Use a stable key derived from the model's identity (Song.id for Song models)
    // This prevents the ImageRequest from being recreated on every recomposition,
    // which was causing intermittent cover loading failures when the Song object
    // changed referentially while representing the same data.
    val stableKey = when (model) {
        is Song -> model.id
        else -> model
    }

    val imageRequest = remember(stableKey) {
        ImageRequest.Builder(platformContext)
            .data(model)
            .size(sizeResolver)
            .build()
    }

    // Key the painter by the model identity so a song change always rebuilds
    // it fresh. Without this, the reused painter's set_input() sees an
    // "equal" request (Song data class equality) and skips restart(), leaving
    // a stale Loading state behind when the previous job was cancelled.
    val painter = key(stableKey) {
        rememberAsyncImagePainter(
            model = imageRequest,
            contentScale = ContentScale.Crop
        )
    }
    val state by painter.state.collectAsState()
    val currentState = state
    when {
        currentState is State.Error -> {
            // Diagnostic: report which song failed and why, so failures in the
            // stats ranking can be traced to fetch vs decode.
            val song = model as? Song
            Log.w(
                "MediaImage",
                "cover error: songId=${song?.id} title=${song?.title} cause=${currentState.result.throwable}"
            )
            MediaPlaceholder(
                iconRes = placeholderIcon,
                // Keep the size resolver mounted in every branch: the
                // ConstraintsSizeResolver's size() suspends until the item is
                // measured, and only a mounted resolver is ever measured. If
                // it is unmounted while loading (as it was before), a restart
                // issued in that window would suspend forever on size(),
                // leaving the placeholder stuck with no fetch ever starting.
                modifier = modifier.then(sizeResolver)
            )
        }
        currentState is State.Loading -> {
            MediaPlaceholder(
                iconRes = placeholderIcon,
                modifier = modifier.then(sizeResolver)
            )
        }
        else -> {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = modifier.then(sizeResolver)
            )
        }
    }
}

@Composable
fun MediaPlaceholder(
    @DrawableRes iconRes: Int,
    iconScale: Float = 0.5f,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize(iconScale)
        )
    }
}