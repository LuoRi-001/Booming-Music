package com.mardous.booming.ui.screen.library.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.ui.component.compose.MediaImage
import kotlin.random.Random

private val CARD_SIZE_DP = 128f

// 5 regions where cards can appear
private val REGION_CENTERS = listOf(
    0.20f to 0.20f,
    0.80f to 0.16f,
    0.52f to 0.50f,
    0.12f to 0.72f,
    0.85f to 0.68f,
)

private data class CardLayout(
    val xDp: Float,
    val yDp: Float,
    val rotation: Float,
)

private fun generateCardLayouts(
    songCount: Int,
    containerW: Float,
    containerH: Float,
    seed: Long,
): List<CardLayout> {
    val rng = Random(seed)
    return List(songCount) { i ->
        val (cx, cy) = REGION_CENTERS[i % REGION_CENTERS.size]
        val halfSize = CARD_SIZE_DP / 2f
        val jitterX = rng.nextFloat() * 60f - 30f
        val jitterY = rng.nextFloat() * 60f - 30f
        val xDp = (cx * containerW - halfSize + jitterX).coerceIn(0f, containerW - CARD_SIZE_DP)
        val yDp = (cy * containerH - halfSize + jitterY).coerceIn(0f, containerH - CARD_SIZE_DP)
        val rotation = rng.nextFloat() * 240f - 120f
        CardLayout(
            xDp = xDp,
            yDp = yDp,
            rotation = rotation,
        )
    }
}

@Composable
fun YourRecommendationsSection(
    songs: List<Song>,
    refreshKey: Long,
    shuffleColor: Long = 0xFFFF7043,
    onSongClick: (Song) -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        // Header — stacked title + bottom-aligned shuffle button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    StackedChar("你")
                    StackedChar("的")
                }
                Spacer(modifier = Modifier.height(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    StackedChar("推")
                    StackedChar("荐")
                }
            }

            FilledTonalIconButton(
                onClick = onShuffleClick,
                modifier = Modifier.size(90.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color(shuffleColor),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shuffle_24dp),
                    contentDescription = "随机播放",
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Free-form collage container
        val collageH = 400.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(collageH)
        ) {
            val cw = maxWidth.value
            val ch = collageH.value

            val cardLayouts = remember(songs, refreshKey) {
                val seed = refreshKey * 31 + songs.sumOf { it.id }
                generateCardLayouts(songs.size, cw, ch, seed)
            }

            songs.forEachIndexed { index, song ->
                val layout = cardLayouts.getOrNull(index) ?: return@forEachIndexed

                // Use canvas-level rotation + clip to avoid RenderNode hardware layer clipping issues
                Box(
                    modifier = Modifier
                        .offset(x = layout.xDp.dp, y = layout.yDp.dp)
                        .size(CARD_SIZE_DP.dp)
                        .drawWithContent {
                            // Create a rounded rect path matching the card size
                            val cornerPx = 16.dp.toPx()
                            val roundRectPath = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        Rect(0f, 0f, size.width, size.height),
                                        CornerRadius(cornerPx, cornerPx)
                                    )
                                )
                            }
                            // Rotate canvas first, then clip to rounded rect in rotated space
                            // This guarantees the rounded corners stay intact after rotation
                            rotate(layout.rotation, pivot = center) {
                                clipPath(roundRectPath) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                        .clickable(onClick = { onSongClick(song) })
                ) {
                    MediaImage(
                        model = song,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Title overlay
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StackedChar(char: String) {
    Text(
        text = char,
        fontSize = 84.sp,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
