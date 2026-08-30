package com.mardous.booming.ui.screen.lyrics

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mardous.booming.R
import com.mardous.booming.extensions.hasS
import com.mardous.booming.ui.component.compose.BottomSheetDialogSurface
import com.mardous.booming.ui.theme.SliderTokens
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.roundToInt

/**
 * 歌词效果调节弹窗:字体(大小/粗体/行距)、歌词偏移(默认/蓝牙)、
 * 以及若干与设置页共用的显示开关。所有改动写入与设置页相同的
 * SharedPreferences key,由 LyricsViewModel 的 settings flow 实时刷新歌词。
 */
class LyricsEffectsFragment : BottomSheetDialogFragment() {

    private val viewModel: LyricsViewModel by activityViewModel()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.let {
            it.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BoomingMusicTheme {
                    LyricsEffectsBottomSheet(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsEffectsBottomSheet(viewModel: LyricsViewModel) {
    val settings by viewModel.playerLyricsViewSettings.collectAsState()

    val fontSizeMin = integerResource(R.integer.lyrics_font_size_min).toFloat()
    val fontSizeMax = integerResource(R.integer.lyrics_font_size_max).toFloat()
    val spacingMin = integerResource(R.integer.lyrics_spacing_min).toFloat()
    val spacingMax = integerResource(R.integer.lyrics_spacing_max).toFloat()
    val blurLevelMin = integerResource(R.integer.lyrics_blur_level_min).toFloat()
    val blurLevelMax = integerResource(R.integer.lyrics_blur_level_max).toFloat()

    // Slider 拖动中只更新本地状态,松手(onValueChangeFinished)才提交偏好,
    // 避免拖动期间每 tick 重建 settings 与歌词重排
    var fontSize by remember(settings.syncedStyle.fontSize) {
        mutableFloatStateOf(settings.syncedStyle.fontSize.value)
    }
    var lineSpacing by remember(viewModel.rawLineSpacing) {
        mutableFloatStateOf(viewModel.rawLineSpacing.toFloat())
    }
    var defaultOffset by remember(settings.offsetDefaultMs) {
        mutableFloatStateOf(settings.offsetDefaultMs.toFloat())
    }
    var bluetoothOffset by remember(settings.offsetBluetoothMs) {
        mutableFloatStateOf(settings.offsetBluetoothMs.toFloat())
    }

    BottomSheetDialogSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Text(
                text = stringResource(R.string.action_lyrics_effects),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SectionTitle(stringResource(R.string.lyrics_effects_font_title))

            EffectSliderRow(
                title = stringResource(R.string.lyrics_font_size_title),
                value = fontSize,
                valueRange = fontSizeMin..fontSizeMax,
                valueText = "%.0f".format(fontSize),
                onValueChange = { fontSize = it },
                onValueChangeFinished = { viewModel.setSyncedFontSize(fontSize.toInt()) }
            )

            EffectSwitchRow(
                title = stringResource(R.string.lyrics_bold_synced_title),
                checked = settings.syncedStyle.fontWeight == FontWeight.Bold,
                onStateChange = viewModel::setSyncedBoldFont
            )

            EffectSliderRow(
                title = stringResource(R.string.lyrics_line_spacing_title),
                value = lineSpacing,
                valueRange = spacingMin..spacingMax,
                valueText = "%.0f".format(lineSpacing),
                onValueChange = { lineSpacing = it },
                onValueChangeFinished = { viewModel.setLineSpacing(lineSpacing.toInt()) }
            )

            SectionTitle(stringResource(R.string.lyrics_offset_title))

            EffectSliderRow(
                title = stringResource(R.string.lyrics_default_offset_title),
                value = defaultOffset,
                valueRange = -2000f..2000f,
                valueText = "%+.0f ms".format(defaultOffset),
                onValueChange = { defaultOffset = (it / 50f).toInt() * 50f },
                onValueChangeFinished = { viewModel.setDefaultOffsetMs(defaultOffset.toLong()) }
            )

            EffectSliderRow(
                title = stringResource(R.string.lyrics_bluetooth_offset_title),
                value = bluetoothOffset,
                valueRange = -2000f..2000f,
                valueText = "%+.0f ms".format(bluetoothOffset),
                onValueChange = { bluetoothOffset = (it / 50f).toInt() * 50f },
                onValueChangeFinished = { viewModel.setBluetoothOffsetMs(bluetoothOffset.toLong()) }
            )

            EffectSwitchRow(
                title = stringResource(R.string.lyrics_show_translation_title),
                checked = settings.showTranslation,
                onStateChange = viewModel::setShowTranslation
            )

            EffectSwitchRow(
                title = stringResource(R.string.lyrics_center_horizontally_title),
                checked = settings.isCenterHorizontally,
                onStateChange = viewModel::setCenterHorizontally
            )

            EffectSwitchRow(
                title = stringResource(R.string.lyrics_progressive_coloring_title),
                checked = settings.progressiveColoring,
                onStateChange = viewModel::setProgressiveColoring
            )

            if (hasS()) {
                EffectSwitchRow(
                    title = stringResource(R.string.lyrics_blur_effect_title),
                    checked = settings.blurEffect,
                    onStateChange = viewModel::setBlurEffect
                )

                var blurLevel by remember(settings.blurLevel) {
                    mutableFloatStateOf(settings.blurLevel.toFloat())
                }
                EffectSliderRow(
                    title = stringResource(R.string.lyrics_blur_level_title),
                    value = blurLevel,
                    valueRange = blurLevelMin..blurLevelMax,
                    valueText = "%.0f".format(blurLevel),
                    onValueChange = { blurLevel = it.roundToInt().toFloat() },
                    onValueChangeFinished = { viewModel.setBlurLevel(blurLevel.toInt()) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun EffectSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(SliderTokens.MediumTrackHeight)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EffectSwitchRow(
    title: String,
    checked: Boolean,
    onStateChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Switch,
                onClick = { onStateChange(!checked) }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = { onStateChange(it) }
        )
    }
}
