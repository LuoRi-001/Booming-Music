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

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.mardous.booming.core.model.PaletteColor
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
        // Runs in a post because the child cover fragment's view is not created
        // until after this fragment's onViewCreated.
        view?.post { applyCoverBlend() }
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
            }
            // 二次检查：fragment状态可能在视图绘制后被异步恢复
            view?.postDelayed({
                if (coverFrag.isLyricsViewVisible) {
                    coverFrag.forceHideLyricsView()
                }
            }, 200)
        }
    }

    /**
     * Cover bottom blend: a full-height gradient view inside the cover layout,
     * sitting above the cover but below the lyrics view (and its toggle button).
     * Only the bottom 20% fades to a color, tinted with the surface color like
     * every other player surface, and it fades together with the cover because
     * both share the crossfade container. Idempotent, and safe to call
     * repeatedly: it is re-asserted on every palette delivery so song changes
     * cannot leave the cover without it.
     */
    private fun applyCoverBlend() {
        whichFragment<CoverPagerFragment>(R.id.playerAlbumCoverFragment).coverBottomBlendView?.let { blend ->
            if (blend.background == null) {
                blend.background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setColors(
                        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
                        floatArrayOf(0f, 0.8f, 1f)
                    )
                }
            }
            blend.isVisible = true
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

    override fun onColorChanged(color: PaletteColor) {
        super.onColorChanged(color)
        // Palette delivery is the reliable per-song hook (every song change ends
        // with a new palette), so re-assert the blend here: the cover fragment's
        // view can be recreated while the player stays open.
        applyCoverBlend()
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldTopMaskColor = binding.topMask.backgroundTintList?.defaultColor
            ?: Color.TRANSPARENT
        val oldCloseColor = binding.close.iconTint?.defaultColor ?: Color.WHITE
        val mutableList = mutableListOf(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor),
            binding.topMask.tintTarget(oldTopMaskColor, scheme.surfaceColor),
            binding.close.iconButtonTintTarget(oldCloseColor, scheme.onSurfaceColor)
        )
        whichFragment<CoverPagerFragment>(R.id.playerAlbumCoverFragment).coverBottomBlendView?.let { blend ->
            mutableList.add(
                blend.tintTarget(
                    blend.backgroundTintList?.defaultColor ?: Color.TRANSPARENT,
                    scheme.surfaceColor
                )
            )
        }

        val oldLabelColor = binding.nextSongLabel.currentTextColor
        mutableList.add(binding.nextSongLabel.tintTarget(oldLabelColor, scheme.onSurfaceVariantColor))
        val oldTextColor = binding.nextSongText.currentTextColor
        mutableList.add(binding.nextSongText.tintTarget(oldTextColor, scheme.onSurfaceColor))

        mutableList.addAll(playerControlsFragment.getTintTargets(scheme))
        return mutableList
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key == DISPLAY_NEXT_SONG) {
            setupNextSongVisibility()
        }
    }
}
