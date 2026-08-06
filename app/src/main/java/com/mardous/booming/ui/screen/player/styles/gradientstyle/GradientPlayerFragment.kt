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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.mardous.booming.R
import com.mardous.booming.coil.DEFAULT_SONG_IMAGE
import com.mardous.booming.coil.songImage
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.*
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentGradientPlayerBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.component.views.getPlaceholderDrawable
import com.mardous.booming.ui.screen.player.cover.CoverPagerFragment
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences

class GradientPlayerFragment : AbsPlayerFragment(R.layout.fragment_gradient_player),
    SharedPreferences.OnSharedPreferenceChangeListener,
    View.OnClickListener {

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: GradientPlayerControlsFragment

    private var errorDrawable: Drawable? = null

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerBinding.bind(view)
        errorDrawable = view.context.getPlaceholderDrawable(DEFAULT_SONG_IMAGE)
        setupListeners()
        setupNextSongVisibility()
        setupBackPress()
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarContainer) { v: View, insets: WindowInsetsCompat ->
            val statusBar = insets.getInsets(Type.systemBars())
            v.updatePadding(left = statusBar.left, top = statusBar.top, right = statusBar.right)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.playbackControlsFragment) { v: View, insets: WindowInsetsCompat ->
            val navigationBar = insets.getInsets(Type.systemBars())
            v.updatePadding(bottom = navigationBar.bottom)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            insets
        }
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            playerViewModel.nextSongFlow.collect { nextSong ->
                if (nextSong != Song.emptySong) {
                    _binding?.nextSongAlbumArt?.songImage(nextSong)
                    _binding?.nextSongText?.text = nextSong.title
                } else {
                    _binding?.nextSongText?.setText(R.string.list_end)
                    _binding?.nextSongAlbumArt?.setImageDrawable(errorDrawable)
                }
            }
        }
        Preferences.registerOnSharedPreferenceChangeListener(this)
        // 强制确保进入播放页时不显示歌词
        forceHideLyricsOnEnter()
    }

    /**
     * 进入播放页时强制隐藏歌词视图，确保用户看到的是封面而不是歌词。
     * 歌词只在用户主动点击封面后才显示。
     * 使用延迟二次检查，防止fragment状态恢复异步覆盖我们的设置。
     */
    private fun forceHideLyricsOnEnter() {
        view?.post {
            val coverFrag = whichFragment<CoverPagerFragment>(R.id.playerAlbumCoverFragment)
            if (coverFrag.isLyricsViewVisible) {
                coverFrag.forceHideLyricsView()
                binding.mask.alpha = 1f
            }
            // 二次检查：fragment状态可能在视图绘制后被异步恢复
            view?.postDelayed({
                if (coverFrag.isLyricsViewVisible) {
                    coverFrag.forceHideLyricsView()
                    binding.mask.alpha = 1f
                }
            }, 200)
        }
    }

    private fun setupListeners() {
        binding.close.setOnClickListener(this)
        binding.nextSongText.setOnClickListener(this)
        binding.nextSongAlbumArt.setOnClickListener(this)
    }

    private fun setupNextSongVisibility() {
        val showNextSong = Preferences.isShowNextSong
        _binding?.let {
            it.nextSongAlbumArt.isVisible = showNextSong
            it.nextSongLabel.isVisible = showNextSong
            it.nextSongText.isVisible = showNextSong
            it.topMask.isVisible = showNextSong
        }
    }

    private fun setupBackPress() {
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val coverFrag = whichFragment<CoverPagerFragment>(R.id.playerAlbumCoverFragment)
                if (coverFrag.isLyricsViewVisible) {
                    // 使用forceHideLyricsView直接隐藏歌词，不依赖动画。
                    // hideLyrics(true)有"!isShowLyricsOnCover || isAnimatingLyrics"守卫，
                    // 在状态不一致或动画进行中时会被跳过，导致返回键直接退出播放页。
                    coverFrag.forceHideLyricsView()
                    // 手动恢复mask alpha：forceHideLyricsView内部虽然会触发
                    // onLyricsVisibilityChange回调，但不能保证AnimatorSet启动了。
                    binding.mask.alpha = 1f
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onClick(v: View) {
        when (v) {
            binding.close -> requireActivity().onBackPressedDispatcher.onBackPressed()
            binding.nextSongText, binding.nextSongAlbumArt -> onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        controlsFragment.setFavorite(isFavorite, withAnimation)
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_playing_queue)
        menu.removeItem(R.id.action_show_lyrics)
        menu.removeItem(R.id.action_sound_settings)
        menu.removeItem(R.id.action_favorite)
    }

    override fun onCreateChildFragments() {
        // 进入渐变播放页时强制重置歌词偏好，确保始终从封面开始
        Preferences.showLyricsOnCover = false
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun onDestroyView() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
        _binding = null
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldMaskColor = binding.mask.backgroundTintList?.defaultColor
            ?: Color.TRANSPARENT
        val oldTopMaskColor = binding.topMask.backgroundTintList?.defaultColor
            ?: Color.TRANSPARENT
        val oldCloseColor = binding.close.iconTint?.defaultColor ?: Color.WHITE
        val mutableList = mutableListOf(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor),
            binding.mask.tintTarget(oldMaskColor, scheme.surfaceColor),
            binding.topMask.tintTarget(oldTopMaskColor, scheme.surfaceColor),
            binding.close.iconButtonTintTarget(oldCloseColor, scheme.onSurfaceColor)
        )

        val oldLabelColor = binding.nextSongLabel.currentTextColor
        mutableList.add(binding.nextSongLabel.tintTarget(oldLabelColor, scheme.onSurfaceVariantColor))
        val oldTextColor = binding.nextSongText.currentTextColor
        mutableList.add(binding.nextSongText.tintTarget(oldTextColor, scheme.onSurfaceColor))

        mutableList.addAll(playerControlsFragment.getTintTargets(scheme))
        return mutableList
    }

    override fun onLyricsVisibilityChange(animatorSet: AnimatorSet, lyricsVisible: Boolean) {
        if (lyricsVisible) {
            animatorSet.play(ObjectAnimator.ofFloat(binding.mask, View.ALPHA, 0f))
        } else {
            animatorSet.play(ObjectAnimator.ofFloat(binding.mask, View.ALPHA, 1f))
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key == DISPLAY_NEXT_SONG) {
            setupNextSongVisibility()
        }
    }
}
