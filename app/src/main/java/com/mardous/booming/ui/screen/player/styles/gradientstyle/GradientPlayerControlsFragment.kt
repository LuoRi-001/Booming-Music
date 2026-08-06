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

package com.mardous.booming.ui.screen.player.styles.gradientstyle

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.media3.common.Player
import com.google.android.material.button.MaterialButton
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.iconButtonTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentGradientPlayerPlaybackControlsBinding
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.resources.applyColor
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_NEXT
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_PREVIOUS
import com.mardous.booming.ui.component.views.MusicSlider
import kotlinx.coroutines.flow.combine

class GradientPlayerControlsFragment : AbsPlayerControlsFragment(R.layout.fragment_gradient_player_playback_controls) {

    private var _binding: FragmentGradientPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!

    override val musicSlider: MusicSlider?
        get() = binding.progressSlider

    override val repeatButton: MaterialButton
        get() = binding.loopShuffleButton

    override val shuffleButton: MaterialButton
        get() = binding.loopShuffleButton

    override val songCurrentProgress: TextView
        get() = binding.songCurrentProgress

    override val songTotalTime: TextView
        get() = binding.songTotalTime

    override val songTitleView: TextView?
        get() = binding.title

    override val songArtistView: TextView?
        get() = binding.text

    override val songInfoView: TextView
        get() = binding.songInfo

    private var isFavorite: Boolean = false
    private var popupMenu: PopupMenu? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerPlaybackControlsBinding.bind(view)
        setupListeners()
        setViewAction(binding.favorite, NowPlayingAction.ToggleFavoriteState)
        popupMenu = playerFragment?.inflateMenuInView(binding.menu)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            if (view.resources.isLandscape) {
                val systemBars = insets.getInsets(Type.systemBars())
                v.updatePadding(top = systemBars.top)
            }
            insets
        }
        setupTrackCounter()
    }

    private fun setupTrackCounter() {
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            combine(
                playerViewModel.queueFlow,
                playerViewModel.positionFlow
            ) { queue, position -> Pair(queue, position) }
                .collect { (queue, position) ->
                    if (queue.isNotEmpty()) {
                        val current = position.current.coerceIn(0, queue.size - 1)
                        _binding?.trackCounter?.text = "${current + 1}/${queue.size}"
                    } else {
                        _binding?.trackCounter?.text = "-/-"
                    }
                }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.playPauseButton.setOnClickListener(this)
        binding.openQueueButton.setOnClickListener(this)
        binding.loopShuffleButton.setOnClickListener(this)
        binding.nextButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_NEXT))
        binding.previousButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_PREVIOUS))
        // 5 function buttons
        binding.songInfoButton.setOnClickListener(this)
        binding.soundButton.setOnClickListener(this)
        binding.sleepTimerButton.setOnClickListener(this)
        binding.tagEditorButton.setOnClickListener(this)
    }

    override fun onClick(view: View) {
        super.onClick(view)
        when (view) {
            binding.playPauseButton -> playerViewModel.togglePlayPause()
            binding.openQueueButton -> playerFragment?.onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
            binding.loopShuffleButton -> cycleLoopShuffle()
            binding.songInfoButton -> {
                playerViewModel.currentSong.let { song ->
                    playerFragment?.onQuickActionEvent(NowPlayingAction.SongDetails)
                }
            }
            binding.soundButton -> playerFragment?.onQuickActionEvent(NowPlayingAction.SoundSettings)
            binding.sleepTimerButton -> playerFragment?.onQuickActionEvent(NowPlayingAction.SleepTimer)
            binding.tagEditorButton -> playerFragment?.onQuickActionEvent(NowPlayingAction.TagEditor)
        }
    }

    /**
     * Cycles through 3 states: List loop (REPEAT_MODE_ALL) → Single loop (REPEAT_MODE_ONE) → Shuffle
     *
     * Player state updates are asynchronous (sent via custom commands to the service),
     * so we maintain a local cycle state to avoid reading stale Flow values.
     * 0 = 列表循环 (REPEAT_MODE_ALL, shuffle off)
     * 1 = 单曲循环 (REPEAT_MODE_ONE, shuffle off)
     * 2 = 随机播放 (shuffle on)
     */
    private var loopShuffleCycle: Int = -1

    private fun cycleLoopShuffle() {
        // Initialize local cycle tracker from current player state on first use
        if (loopShuffleCycle < 0) {
            loopShuffleCycle = if (playerViewModel.shuffleModeEnabled) 2
                else when (playerViewModel.repeatMode) {
                    Player.REPEAT_MODE_ONE -> 1
                    else -> 0
                }
        }

        when (loopShuffleCycle) {
            0 -> { // 列表循环 → 单曲循环
                playerViewModel.cycleRepeatMode() // ALL → ONE
                loopShuffleCycle = 1
            }
            1 -> { // 单曲循环 → 随机播放
                playerViewModel.cycleRepeatMode() // ONE → OFF
                playerViewModel.toggleShuffleMode()
                loopShuffleCycle = 2
            }
            2 -> { // 随机播放 → 列表循环
                playerViewModel.toggleShuffleMode()
                playerViewModel.cycleRepeatMode() // OFF → ALL
                loopShuffleCycle = 0
            }
        }
    }

    override fun onUpdateRepeatMode(repeatMode: Int) {
        val shuffleOn = playerViewModel.shuffleModeEnabled
        if (!shuffleOn) {
            val iconResource = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one_24dp
                else -> R.drawable.ic_repeat_24dp
            }
            _binding?.loopShuffleButton?.let {
                it.setIconResource(iconResource)
                it.applyColor(
                    getPlaybackControlsColor(repeatMode != Player.REPEAT_MODE_OFF),
                    isIconButton = true
                )
            }
        }
    }

    override fun onUpdateShuffleMode(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled) {
            _binding?.loopShuffleButton?.let {
                it.setIconResource(R.drawable.ic_shuffle_24dp)
                it.applyColor(
                    getPlaybackControlsColor(true),
                    isIconButton = true
                )
            }
        } else {
            // Update to show current repeat mode icon
            onUpdateRepeatMode(playerViewModel.repeatMode)
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldControlColor = binding.nextButton.iconTint.defaultColor
        val oldSliderColor = binding.progressSlider.currentColor
        val oldPrimaryTextColor = binding.title.currentTextColor
        val oldSecondaryTextColor = binding.text.currentTextColor

        val oldLoopShuffleColor = getPlaybackControlsColor(
            isShuffleModeOn || isRepeatModeOn,
            scheme.onSurfaceColor,
            scheme.onSurfaceVariantColor
        )
        val newLoopShuffleColor = getPlaybackControlsColor(
            isShuffleModeOn || isRepeatModeOn,
            scheme.onSurfaceColor,
            scheme.onSurfaceVariantColor
        )

        return listOfNotNull(
            binding.progressSlider.progressView?.tintTarget(oldSliderColor, scheme.onSurfaceColor),
            binding.menu.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.favorite.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.playPauseButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.nextButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.previousButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.openQueueButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.loopShuffleButton.iconButtonTintTarget(oldLoopShuffleColor, newLoopShuffleColor),
            binding.songInfoButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.soundButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.sleepTimerButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.tagEditorButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.trackCounter.tintTarget(oldSecondaryTextColor, scheme.onSurfaceColor),
            binding.title.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.text.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songInfo.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songCurrentProgress.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songTotalTime.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor)
        )
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {
        _binding?.let { nonNullBinding ->
            nonNullBinding.title.text = currentSong.title
            nonNullBinding.text.text = getSongArtist(currentSong)
        }
    }

    override fun onExtraInfoChanged(extraInfo: String?) {
        _binding?.let { nonNullBinding ->
            if (isExtraInfoEnabled()) {
                nonNullBinding.songInfo.text = extraInfo
                nonNullBinding.songInfo.isVisible = true
            } else {
                nonNullBinding.songInfo.isVisible = false
            }
        }
    }

    override fun onUpdatePlayPause(isPlaying: Boolean) {
        if (isPlaying) {
            _binding?.playPauseButton?.setIconResource(R.drawable.ic_pause_24dp)
        } else {
            _binding?.playPauseButton?.setIconResource(R.drawable.ic_play_24dp)
        }
    }

    internal fun setFavorite(isFavorite: Boolean, withAnimation: Boolean) {
        if (this.isFavorite != isFavorite) {
            this.isFavorite = isFavorite
            playerFragment?.let { nonNullPlayerFragment ->
                with(nonNullPlayerFragment) {
                    binding.favorite.setIsFavorite(isFavorite, withAnimation)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
