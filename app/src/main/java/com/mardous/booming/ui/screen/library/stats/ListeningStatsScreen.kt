package com.mardous.booming.ui.screen.library.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mardous.booming.R
import com.mardous.booming.data.local.room.PlayCountEntity
import com.mardous.booming.data.mapper.toSong
import com.mardous.booming.ui.component.compose.MediaImage
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.library.StatsTimeRange
import com.mardous.booming.ui.screen.library.TimelineBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    libraryViewModel: LibraryViewModel,
    onBackClick: () -> Unit
) {
    var selectedRange by rememberSaveable { mutableStateOf(StatsTimeRange.WEEK) }
    val ranking by libraryViewModel.listeningStatsRanking(selectedRange).observeAsState(emptyList())
    val timelineBars by libraryViewModel.getTimelineBars(selectedRange).observeAsState(emptyList())

    // Get the total duration for the selected range
    val selectedDuration = when (selectedRange) {
        StatsTimeRange.TODAY -> libraryViewModel.totalDurationForToday().observeAsState(0L)
        StatsTimeRange.WEEK -> libraryViewModel.totalDurationForThisWeek().observeAsState(0L)
        StatsTimeRange.MONTH -> libraryViewModel.totalDurationForThisMonth().observeAsState(0L)
        StatsTimeRange.YEAR -> libraryViewModel.totalDurationForThisYear().observeAsState(0L)
        StatsTimeRange.ALL -> libraryViewModel.totalDurationAllTime().observeAsState(0L)
    }

    val totalPlays = ranking.sumOf { it.playCount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.listening_stats),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_24dp),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 200.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Time range tabs
            item {
                RangeTabsRow(
                    selected = selectedRange,
                    onSelected = { selectedRange = it }
                )
            }

            // Hero section - 2 cards
            item {
                StatsHeroSection(
                    durationMs = selectedDuration.value,
                    playCount = totalPlays
                )
            }

            // Timeline section
            item {
                TimelineSection(
                    bars = timelineBars,
                    range = selectedRange
                )
            }

            // Section: Top songs
            item {
                SectionLabel(text = stringResource(R.string.stats_section_top_songs))
            }

            val rankingList = ranking
            if (rankingList.isEmpty()) {
                item {
                    EmptyState()
                }
            } else {
                // Key by song id so a ranking reorder (which happens on every
                // play count update while listening) reuses the item's
                // composition for the same song instead of swapping the item
                // content at a position. Position-based reuse was causing
                // cover state to be shared across different songs and a fetch
                // storm on every list emission.
                itemsIndexed(rankingList, key = { _, entity -> entity.id }) { index, entity ->
                    SongRankingItem(
                        entity = entity,
                        rank = index + 1,
                        maxDuration = rankingList.firstOrNull()?.totalPlayDurationMs ?: 1L
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeTabsRow(
    selected: StatsTimeRange,
    onSelected: (StatsTimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val ranges = listOf(
        StatsTimeRange.TODAY to R.string.listening_stats_today,
        StatsTimeRange.WEEK to R.string.listening_stats_week,
        StatsTimeRange.MONTH to R.string.listening_stats_month,
        StatsTimeRange.YEAR to R.string.listening_stats_year,
        StatsTimeRange.ALL to R.string.listening_stats_all_time,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ranges.forEach { (range, labelRes) ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelected(range) },
                label = {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (range == selected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.Transparent,
                    selectedBorderColor = Color.Transparent,
                    enabled = true,
                    selected = range == selected
                )
            )
        }
    }
}

@Composable
private fun StatsHeroSection(
    durationMs: Long,
    playCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeroCard(
            title = stringResource(R.string.stats_hero_listening),
            value = formatDurationCompact(durationMs),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        HeroCard(
            title = stringResource(R.string.stats_hero_plays),
            value = if (playCount > 0) playCount.toString() else "--",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HeroCard(
    title: String,
    value: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = contentColor.copy(alpha = 0.85f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun TimelineSection(
    bars: List<TimelineBar>,
    range: StatsTimeRange,
    modifier: Modifier = Modifier
) {
    if (bars.isEmpty()) return

    val maxDuration = bars.maxOf { it.durationMs }.coerceAtLeast(1L)
    val isVertical = range == StatsTimeRange.WEEK || range == StatsTimeRange.TODAY

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.stats_section_listening_timeline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (isVertical) {
                // Vertical bars for week/today
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    bars.forEach { bar ->
                        val progress = bar.durationMs.toFloat() / maxDuration.toFloat()
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatDurationCompact(bar.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(if (progress > 0f) progress else 0f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            Text(
                                text = bar.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                // Horizontal bars for month/year/all
                bars.forEach { bar ->
                    val progress = bar.durationMs.toFloat() / maxDuration.toFloat()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = bar.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text(
                            text = formatDurationCompact(bar.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 48.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SongRankingItem(
    entity: PlayCountEntity,
    rank: Int,
    maxDuration: Long
) {
    val song = entity.toSong()
    val progress by animateFloatAsState(
        targetValue = if (maxDuration > 0) entity.totalPlayDurationMs.toFloat() / maxDuration.toFloat() else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "progress"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (rank) {
            1 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            2, 3 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Rank badge
                RankBadge(rank = rank, modifier = Modifier.size(28.dp))

                // Album art
                MediaImage(
                    model = song,
                    contentDescription = song.title,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                // Song info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Duration + play count
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatDurationCompact(entity.totalPlayDurationMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.stats_n_plays, entity.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = when (rank) {
                    1 -> MaterialTheme.colorScheme.primary
                    2, 3 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val (bgColor, textColor) = when (rank) {
        1 -> Color(0xFFFFD700) to Color(0xFF6B5500)
        2 -> Color(0xFFC0C0C0) to Color(0xFF555555)
        3 -> Color(0xFFCD7F32) to Color(0xFF5C3A1E)
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (rank <= 3) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = bgColor
                )
            }
        } else {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.stats_empty_no_data),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.stats_empty_no_data_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun formatDurationCompact(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
