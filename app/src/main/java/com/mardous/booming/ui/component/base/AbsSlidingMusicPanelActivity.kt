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

package com.mardous.booming.ui.component.base

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.animation.Animator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.AttrRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING
import com.google.android.material.bottomsheet.BottomSheetBehavior.from
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.mardous.booming.MediaControllerOwner
import com.mardous.booming.R
import com.mardous.booming.core.model.CategoryInfo
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.action.QueueClearingBehavior
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.core.palette.CoverSeedExtractor
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.search.SearchQuery
import com.mardous.booming.databinding.SlidingMusicPanelLayoutBinding
import com.mardous.booming.extensions.applyWindowInsets
import com.mardous.booming.extensions.currentFragment
import com.mardous.booming.extensions.dip
import com.mardous.booming.extensions.getBottomInsets
import com.mardous.booming.extensions.hasT
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.resources.animateBackgroundColor
import com.mardous.booming.extensions.resources.animateTintColor
import com.mardous.booming.extensions.resources.darkenColor
import com.mardous.booming.extensions.resources.hide
import com.mardous.booming.extensions.resources.isColorLight
import com.mardous.booming.extensions.resources.peekHeightAnimate
import com.mardous.booming.extensions.resources.show
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.IBackConsumer
import com.mardous.booming.ui.screen.info.PlayInfoFragment
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.library.search.SearchFragment
import com.mardous.booming.ui.screen.lyrics.LyricsEditorFragment
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.other.MiniPlayerFragment
import com.mardous.booming.ui.screen.permissions.PermissionsActivity
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.screen.player.styles.defaultstyle.DefaultPlayerFragment
import com.mardous.booming.ui.screen.player.styles.expressivestyle.ExpressivePlayerFragment
import com.mardous.booming.ui.screen.player.styles.fullcoverstyle.FullCoverPlayerFragment
import com.mardous.booming.ui.screen.player.styles.gradientstyle.GradientPlayerFragment
import com.mardous.booming.ui.screen.player.styles.m3style.M3PlayerFragment
import com.mardous.booming.ui.screen.player.styles.peekplayerstyle.PeekPlayerFragment
import com.mardous.booming.ui.screen.player.styles.plainstyle.PlainPlayerFragment
import com.mardous.booming.util.ADAPTIVE_CONTROLS
import com.mardous.booming.util.ADD_EXTRA_CONTROLS
import com.mardous.booming.util.CAROUSEL_EFFECT
import com.mardous.booming.util.CIRCLE_PLAY_BUTTON
import com.mardous.booming.util.COVER_COLOR
import com.mardous.booming.util.ENABLE_ROTATION_LOCK
import com.mardous.booming.util.HOLD_TAB_TO_SEARCH
import com.mardous.booming.util.LIBRARY_CATEGORIES
import com.mardous.booming.util.NOW_PLAYING_IMAGE_CORNER_RADIUS
import com.mardous.booming.util.NOW_PLAYING_SCREEN
import com.mardous.booming.util.NOW_PLAYING_SMALL_IMAGE
import com.mardous.booming.util.PLAYER_BLUR_RADIUS
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.SQUIGGLY_SEEK_BAR
import com.mardous.booming.util.SWIPE_DOWN_TO_DISMISS
import com.mardous.booming.util.TAB_TITLES_MODE
import com.mardous.booming.util.USE_FOLDER_ART
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author Christians M. A. (mardous)
 */
abstract class AbsSlidingMusicPanelActivity : AbsBaseActivity(),
    MediaController.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    protected lateinit var binding: SlidingMusicPanelLayoutBinding

    protected val mediaControllerOwner by lazy { MediaControllerOwner(this, this) }

    protected val libraryViewModel: LibraryViewModel by viewModel()
    protected val playerViewModel: PlayerViewModel by viewModel()
    protected val lyricsViewModel: LyricsViewModel by viewModel()

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var nowPlayingScreen: NowPlayingScreen

    private var miniPlayerFragment: MiniPlayerFragment? = null
    private var windowInsets: WindowInsetsCompat? = null

    var isInOneTabMode: Boolean = false

    val navigationView: NavigationBarView
        get() = binding.navigationView
    val slidingPanel: FrameLayout
        get() = binding.sheetView

    private var playerFragment: AbsPlayerFragment? = null
    private var paletteColor: Int = 0
    private var restoreExpanded = false
    private var pendingHideJob: Job? = null
    private val coverColorAnimators = mutableListOf<Animator>()

    var panelState: Int
        get() = bottomSheetBehavior.state
        set(value) { bottomSheetBehavior.state = value }
    private var panelStateBefore: Int? = null
    private var panelStateCurrent: Int? = null
    val isBottomNavVisible: Boolean
        get() = navigationView.isVisible && navigationView is BottomNavigationView

    val isBottomSheetHidden: Boolean
        get() = panelState == STATE_COLLAPSED && bottomSheetBehavior.peekHeight == 0

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (handleBackPress()) {
                return
            }
            val navHostFragment = whichFragment<NavHostFragment>(R.id.fragment_container)
            val currentFragment = navHostFragment.currentFragment()
            if (currentFragment is IBackConsumer && currentFragment.handleBackPress()) {
                return
            }
            if (!navHostFragment.navController.navigateUp()) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
        }

        binding = SlidingMusicPanelLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.sheetView) { _, insets ->
            insets.also { windowInsets = it }
        }

        chooseFragmentForTheme()
        setupNavigationView()
        setupSlidingUpPanel()
        setupBottomSheet()

        launchAndRepeatWithViewLifecycle {
            playerViewModel.colorSchemeFlow.collect { scheme ->
                paletteColor = scheme.surfaceColor
                onPaletteColorChanged()
            }
        }

        launchAndRepeatWithViewLifecycle {
            playerViewModel.queueFlow.collect { queue ->
                val currentFragment = currentFragment(R.id.fragment_container)
                if (currentFragment !is LyricsEditorFragment &&
                    currentFragment !is PlayInfoFragment) {
                    // The queue can briefly report empty right after the
                    // media session reconnects: the service is recreated in
                    // the background and its timeline is empty until the
                    // restore finishes. Hiding the sheet on that transient
                    // dismisses the player just as the app comes back, so
                    // only hide once the queue has stayed empty.
                    if (queue.isEmpty()) {
                        pendingHideJob?.cancel()
                        pendingHideJob = lifecycleScope.launch {
                            delay(QUEUE_EMPTY_HIDE_DELAY_MS)
                            hideBottomSheet(true)
                        }
                    } else {
                        pendingHideJob?.cancel()
                        pendingHideJob = null
                        hideBottomSheet(false)
                        // The panel was expanded when the activity got
                        // recreated (backgrounded on a lock screen / low
                        // memory); the queue is empty until the media
                        // session reconnects, so defer restoring the player
                        // until the queue comes back.
                        if (restoreExpanded) {
                            restoreExpanded = false
                            expandPanel()
                        }
                    }
                }
            }
        }

        launchAndRepeatWithViewLifecycle {
            playerViewModel.currentSongFlow.collect { currentSong ->
                lyricsViewModel.updateSong(currentSong)
            }
        }

        launchAndRepeatWithViewLifecycle {
            playerViewModel.currentSongFlow
                .distinctUntilChangedBy { it.id }
                .collect { publishCoverPalette(it) }
        }

        launchAndRepeatWithViewLifecycle {
            CoverColorState.scheme.collect { applyCoverPalette(it) }
        }

        launchAndRepeatWithViewLifecycle {
            mediaControllerOwner.isConnected.collect { event ->
                val isConnected = event.getContentIfNotConsumed()
                if (isConnected == true) {
                    mediaControllerOwner.get()?.let { onConnected(it) }
                }
            }
        }

        mediaControllerOwner.attachTo(this)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    protected open fun onConnected(controller: MediaController) {
        mediaControllerOwner.addPlayerListener(playerViewModel, lifecycle)
        playerViewModel.setMediaController(controller)
    }

    override fun onDisconnected(controller: MediaController) {
        playerViewModel.setMediaController(null)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreExpanded = savedInstanceState.getBoolean(PANEL_WAS_EXPANDED, false)
        if (playerViewModel.queue.isEmpty() || savedInstanceState.getBoolean(BOTTOM_SHEET_HIDDEN)) {
            hideBottomSheet(true)
        } else if (restoreExpanded) {
            restoreExpanded = false
            expandPanel()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(BOTTOM_SHEET_HIDDEN, isBottomSheetHidden)
        outState.putBoolean(PANEL_WAS_EXPANDED, panelState == STATE_EXPANDED)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Preferences.registerOnSharedPreferenceChangeListener(this)
        if (bottomSheetBehavior.state == STATE_EXPANDED) {
            setMiniPlayerAlphaProgress(1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearNavigationViewGestures()
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        miniPlayerFragment = null
        playerFragment = null
    }

    private fun setupNavigationView() {
        navigationView.labelVisibilityMode = Preferences.bottomTitlesMode
        if (navigationView is NavigationRailView) {
            navigationView.applyWindowInsets(left = true, top = true)
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = from(binding.sheetView)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
        bottomSheetBehavior.isHideable = Preferences.swipeDownToDismiss
        bottomSheetBehavior.significantVelocityThreshold = 300
        setMiniPlayerAlphaProgress(0F)
    }

    private fun setupSlidingUpPanel() {
        binding.sheetView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.sheetView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (nowPlayingScreen == NowPlayingScreen.Peek) {
                    slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
                when (panelState) {
                    STATE_EXPANDED -> onPanelExpanded()
                    STATE_COLLAPSED -> onPanelCollapsed()
                    else -> {
                        // playerFragment!!.onHide()
                    }
                }
            }
        })
    }

    fun setBottomNavVisibility(
        visible: Boolean,
        animate: Boolean = false,
        hideBottomSheet: Boolean = playerViewModel.queue.isEmpty(),
    ) {
        if (isInOneTabMode) {
            hideBottomSheet(hide = hideBottomSheet, animate = animate, isBottomNavVisible = false)
            return
        }
        val isBottomNavView = (navigationView is BottomNavigationView)
        if (visible xor navigationView.isVisible) {
            val mAnimate = animate && isBottomNavView && panelState == STATE_COLLAPSED
            if (mAnimate) {
                if (visible) {
                    navigationView.bringToFront()
                    navigationView.show()
                } else {
                    navigationView.hide()
                }
            } else {
                navigationView.isVisible = visible
                if (visible && isBottomNavView && panelState != STATE_EXPANDED) {
                    navigationView.bringToFront()
                }
            }
        }
        hideBottomSheet(
            hide = hideBottomSheet,
            animate = animate,
            isBottomNavVisible = visible && navigationView is BottomNavigationView
        )
    }

    private fun hideBottomSheet(
        hide: Boolean,
        animate: Boolean = false,
        isBottomNavVisible: Boolean = navigationView.isVisible && navigationView is BottomNavigationView
    ) {
        val miniPlayerHeight = dip(R.dimen.mini_player_height)
        val bottomNavHeight = dip(R.dimen.bottom_nav_height)

        val bottomInsets = windowInsets.getBottomInsets(this)
        val heightOfBar =  bottomInsets + miniPlayerHeight
        val heightOfBarWithTabs = heightOfBar + bottomNavHeight
        if (hide) {
            bottomSheetBehavior.peekHeight = (-bottomInsets).coerceAtLeast(0)
            panelState = STATE_COLLAPSED
            libraryViewModel.setLibraryMargins(
                fabBottomMargin = LibraryMargin(
                    margin = if (isBottomNavVisible) bottomNavHeight else 0,
                    additionalSpace = dip(R.dimen.fab_margin_top_left_right),
                    bottomInsets = windowInsets.getBottomInsets(this)
                ),
                bottomSheetMargin = LibraryMargin(
                    margin = 0,
                    bottomInsets = windowInsets.getBottomInsets(this)
                )
            )
        } else {
            if (playerViewModel.queue.isNotEmpty()) {
                slidingPanel.elevation = 0f
                navigationView.elevation = 5f
                if (isBottomNavVisible) {
                    if (animate) {
                        bottomSheetBehavior.peekHeightAnimate(heightOfBarWithTabs)
                    } else {
                        bottomSheetBehavior.peekHeight = heightOfBarWithTabs
                    }
                    libraryViewModel.setLibraryMargins(
                        fabBottomMargin = LibraryMargin(
                            margin = miniPlayerHeight + bottomNavHeight,
                            additionalSpace = dip(R.dimen.fab_margin_top_left_right),
                            bottomInsets = windowInsets.getBottomInsets(this)
                        ),
                        bottomSheetMargin = LibraryMargin(
                            margin = miniPlayerHeight,
                            bottomInsets = windowInsets.getBottomInsets(this)
                        )
                    )
                } else {
                    if (animate) {
                        bottomSheetBehavior.peekHeightAnimate(heightOfBar).doOnEnd {
                            slidingPanel.bringToFront()
                        }
                    } else {
                        bottomSheetBehavior.peekHeight = heightOfBar
                        slidingPanel.bringToFront()
                    }
                    libraryViewModel.setLibraryMargins(
                        fabBottomMargin = LibraryMargin(
                            margin = miniPlayerHeight,
                            additionalSpace = dip(R.dimen.fab_margin_top_left_right),
                            bottomInsets = windowInsets.getBottomInsets(this)
                        ),
                        bottomSheetMargin = LibraryMargin(
                            margin = miniPlayerHeight,
                            bottomInsets = windowInsets.getBottomInsets(this)
                        )
                    )
                }
            }
        }
    }

    fun collapsePanel() {
        panelState = STATE_COLLAPSED
    }

    fun expandPanel() {
        panelState = STATE_EXPANDED
    }

    fun getBottomSheetBehavior() = bottomSheetBehavior

    protected open fun onPanelCollapsed() {
        setMiniPlayerAlphaProgress(0f)
        // restore values
        setLightStatusBar()
        setLightNavigationBar()
        playerFragment?.onHide()
    }

    protected open fun onPanelExpanded() {
        setMiniPlayerAlphaProgress(1f)
        onPaletteColorChanged()
        playerFragment?.onShow()
    }

    protected fun updateTabs() {
        clearNavigationViewGestures()
        navigationView.menu.clear()
        val currentTabs: List<CategoryInfo> = Preferences.libraryCategories
        for (tab in currentTabs) {
            if (tab.visible) {
                val menu = tab.category
                navigationView.menu.add(0, menu.id, 0, menu.titleRes)
                    .setIcon(menu.iconRes)
            }
        }
        setupNavigationViewGestures()
        if (navigationView.menu.size == 1) {
            isInOneTabMode = true
            navigationView.isVisible = false
        } else {
            isInOneTabMode = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNavigationViewGestures() {
        if (!Preferences.holdTabToSearch)
            return

        val selectedCategories = Preferences.libraryCategories.filter { it.visible }
        for (info in selectedCategories) {
            val filterMode = SearchQuery.FilterMode.entries.firstOrNull {
                it.name == info.category.name
            }

            val gestureDetector = GestureDetector(this, object : SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    findNavController(R.id.fragment_container)
                        .navigate(R.id.nav_search, Bundle().apply {
                            putSerializable(SearchFragment.MODE, filterMode)
                        })
                }
            })
            navigationView.setItemOnTouchListener(info.category.id) { _: View, event: MotionEvent ->
                gestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun clearNavigationViewGestures() {
        for (index in 0 until navigationView.menu.size) {
            navigationView.setItemOnTouchListener(navigationView.menu[index].itemId, null)
        }
    }

    private fun setMiniPlayerAlphaProgress(progress: Float) {
        if (progress < 0) return
        val alpha = 1 - progress
        miniPlayerFragment?.view?.alpha = 1 - (progress / 0.2F)
        miniPlayerFragment?.view?.isGone = alpha == 0f
        if (!resources.isLandscape) {
            binding.navigationView.translationY = progress * 500
            binding.navigationView.alpha = alpha
        }
        binding.playerContainer.alpha = (progress - 0.2F) / 0.2F
    }

    /**
     * Reads the cover of [song] and republishes the palette built from it.
     * The cover is decoded tiny here: the extractor only ever looks at a
     * 32x32 grid, so a bigger request would just be wasted decode time on
     * every song change.
     */
    private suspend fun publishCoverPalette(song: Song) {
        if (!CoverColorState.isEnabled || song.id < 0) {
            CoverColorState.update(null)
            return
        }
        val context = this
        // Small as it is, this is a decode plus a pass over the pixels, and it
        // used to run between the song change and the palette reaching the
        // screen: keep it off the main dispatcher.
        val seed = withContext(Dispatchers.Default) {
            SingletonImageLoader.get(context)
                .execute(
                    ImageRequest.Builder(context)
                        .data(song)
                        .scale(Scale.FILL)
                        .size(COVER_SAMPLE_SIZE)
                        .build()
                )
                .image
                ?.toBitmap(COVER_SAMPLE_SIZE, COVER_SAMPLE_SIZE)
                ?.let(CoverSeedExtractor::dominantColor)
        }
        CoverColorState.update(seed)
    }

    /**
     * Paints the library chrome from a cover palette, or back to the theme
     * colours when there is none. Songs change without this activity ever
     * being recreated, so nothing here can lean on the theme being reapplied.
     */
    private fun applyCoverPalette(published: CoverColorState.CoverScheme?) {
        coverColorAnimators.forEach(Animator::cancel)
        coverColorAnimators.clear()

        val sheet = binding.sheetView
        val navigation = binding.navigationView
        // The pure black theme turns the feature off without republishing, so
        // a palette still sitting in the state must not outlive it.
        val colorScheme = published
            .takeIf { CoverColorState.isEnabled }
            ?.forNightMode(resources.isNightMode)

        fun color(view: View, @AttrRes attr: Int, role: (ColorScheme) -> ComposeColor): Int =
            colorScheme?.let { role(it).toArgb() }
                ?: MaterialColors.getColor(view, attr, Color.TRANSPARENT)

        // The bottom bar is filled with the container colour and the rail with
        // the surface one, so each has to be asked for the role it was
        // inflated with.
        val navigationIsRail = navigation is NavigationRailView

        val surface = color(binding.fragmentContainer, colorSurface) { it.surface }
        val surfaceContainerLow =
            color(sheet, colorSurfaceContainerLow) { it.surfaceContainerLow }
        val navigationContainer = color(
            navigation,
            if (navigationIsRail) colorSurface else colorSurfaceContainer
        ) { if (navigationIsRail) it.surface else it.surfaceContainer }
        val secondaryContainer =
            color(navigation, colorSecondaryContainer) { it.secondaryContainer }
        val onSecondaryContainer =
            color(navigation, colorOnSecondaryContainer) { it.onSecondaryContainer }
        val onSurfaceVariant = color(navigation, colorOnSurfaceVariant) { it.onSurfaceVariant }
        val secondary = color(navigation, colorSecondary) { it.secondary }

        coverColorAnimators += binding.fragmentContainer.animateBackgroundColor(
            surface, COVER_COLOR_ANIMATION_DURATION
        )
        coverColorAnimators += sheet.animateTintColor(
            sheet.backgroundTintList?.defaultColor ?: surfaceContainerLow,
            surfaceContainerLow,
            COVER_COLOR_ANIMATION_DURATION
        )
        coverColorAnimators += navigation.animateTintColor(
            navigation.backgroundTintList?.defaultColor ?: navigationContainer,
            navigationContainer,
            COVER_COLOR_ANIMATION_DURATION
        )
        navigation.itemIconTintList =
            navigationItemColors(onSecondaryContainer, onSurfaceVariant)
        navigation.itemTextColor = navigationItemColors(secondary, onSurfaceVariant)
        navigation.itemActiveIndicatorColor = ColorStateList.valueOf(secondaryContainer)

        // The tint helpers hand back unstarted animators (the player runs
        // them through an AnimatorSet), so they have to be kicked off here.
        coverColorAnimators.forEach(Animator::start)
    }

    /** Checked first: ColorStateList stops at the first matching state. */
    private fun navigationItemColors(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked)
    )

    private fun onPaletteColorChanged() {
        if (panelState == STATE_EXPANDED) {
            val isColorLight = paletteColor.isColorLight
            when (nowPlayingScreen) {
                NowPlayingScreen.Default,
                NowPlayingScreen.Plain,
                NowPlayingScreen.Peek,
                NowPlayingScreen.M3,
                NowPlayingScreen.Expressive -> {
                    setLightStatusBar(isColorLight)
                    setLightNavigationBar(isColorLight)
                }
                NowPlayingScreen.FullCover -> {
                    setLightNavigationBar(isColorLight)
                    setLightStatusBar(false)
                }
                NowPlayingScreen.Gradient -> {
                    val navigationbarColor = paletteColor.darkenColor
                    setLightNavigationBar(navigationbarColor.isColorLight)
                    setLightStatusBar(isColorLight)
                }
            }
        }
    }

    private fun handleBackPress(): Boolean {
        if (panelState == STATE_EXPANDED || (panelState == STATE_SETTLING && panelStateBefore != STATE_EXPANDED)) {
            collapsePanel()
            return true
        }
        return false
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        when (key) {
            TAB_TITLES_MODE -> navigationView.labelVisibilityMode = Preferences.bottomTitlesMode
            HOLD_TAB_TO_SEARCH -> {
                if (preferences.getBoolean(key, true)) {
                    setupNavigationViewGestures()
                } else {
                    clearNavigationViewGestures()
                }
            }
            LIBRARY_CATEGORIES -> updateTabs()
            COVER_COLOR -> lifecycleScope.launch {
                // Toggling either way has to rebuild this activity, which is
                // themed from the cover seed and cannot be repainted out of
                // it. The palette must exist (or be cleared) before the
                // recreation: every screen resolves its theme while the new
                // activity is being created, and the cover load is async, so
                // a recreation racing it would leave e.g. the settings
                // elements in the previous theme.
                publishCoverPalette(playerViewModel.currentSong)
                recreate()
            }
            NOW_PLAYING_SCREEN -> {
                chooseFragmentForTheme()
                slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = if (nowPlayingScreen != NowPlayingScreen.Peek) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
                miniPlayerFragment?.setupButtonStyle()
            }

            ADAPTIVE_CONTROLS -> miniPlayerFragment?.setupButtonStyle()
            ADD_EXTRA_CONTROLS -> miniPlayerFragment?.setupExtraControls()
            SQUIGGLY_SEEK_BAR -> miniPlayerFragment?.setUpProgressStyle()

            CAROUSEL_EFFECT,
            NOW_PLAYING_SMALL_IMAGE,
            PLAYER_BLUR_RADIUS,
            CIRCLE_PLAY_BUTTON -> {
                chooseFragmentForTheme()
            }

            NOW_PLAYING_IMAGE_CORNER_RADIUS -> {
                miniPlayerFragment?.setupImageStyle()
                chooseFragmentForTheme()
            }

            SWIPE_DOWN_TO_DISMISS -> bottomSheetBehavior.isHideable =
                Preferences.swipeDownToDismiss

            ENABLE_ROTATION_LOCK -> {
                requestedOrientation = if (preferences.getBoolean(key, false)) {
                    ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            USE_FOLDER_ART -> {
                if (preferences.getBoolean(key, false)) {
                    if (hasT() && checkSelfPermission(READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                        MaterialAlertDialogBuilder(this)
                            .setMessage(R.string.permission_read_images_denied)
                            .setPositiveButton(R.string.action_grant) { _, _ ->
                                startActivity(
                                    Intent()
                                        .setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", packageName, null))
                                )
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun chooseFragmentForTheme() {
        nowPlayingScreen = Preferences.nowPlayingScreen

        val fragment: AbsPlayerFragment = when (nowPlayingScreen) {
            NowPlayingScreen.FullCover -> FullCoverPlayerFragment()
            NowPlayingScreen.Gradient -> GradientPlayerFragment()
            NowPlayingScreen.Peek -> PeekPlayerFragment()
            NowPlayingScreen.Plain -> PlainPlayerFragment()
            NowPlayingScreen.M3 -> M3PlayerFragment()
            NowPlayingScreen.Expressive -> ExpressivePlayerFragment()
            else -> DefaultPlayerFragment()
        }

        supportFragmentManager.commit {
            replace(R.id.player_container, fragment)
        }
        supportFragmentManager.executePendingTransactions()
        playerFragment = whichFragment(R.id.player_container)
        miniPlayerFragment = whichFragment(R.id.mini_player_container)
        miniPlayerFragment?.view?.setOnClickListener { expandPanel() }
    }

    private val bottomSheetCallback = object : BottomSheetCallback() {
        @SuppressLint("SwitchIntDef")
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (panelStateCurrent != null) {
                panelStateBefore = panelStateCurrent
            }
            panelStateCurrent = newState
            when (newState) {
                STATE_EXPANDED -> onPanelExpanded()
                STATE_COLLAPSED -> onPanelCollapsed()
                STATE_HIDDEN -> playerViewModel.clearQueue(QueueClearingBehavior.RemoveAllSongs)
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            setMiniPlayerAlphaProgress(slideOffset)
        }
    }

    companion object {
        private const val BOTTOM_SHEET_HIDDEN = "is_bottom_sheet_hidden"
        private const val PANEL_WAS_EXPANDED = "panel_was_expanded"
        // Wait out the transient empty queue right after the media session
        // reconnects (service recreated in background) before hiding the
        // sheet; a real queue clear stays empty and is still honored.
        private const val QUEUE_EMPTY_HIDE_DELAY_MS = 500L

        // Comfortably above the 32x32 the extractor works on, and far below
        // what the covers are shown at, so this never drives a big decode.
        private const val COVER_SAMPLE_SIZE = 64
        private const val COVER_COLOR_ANIMATION_DURATION = 300L

        // The roles Material gives each piece of the library chrome.
        private val colorSurface = com.google.android.material.R.attr.colorSurface
        private val colorSurfaceContainer =
            com.google.android.material.R.attr.colorSurfaceContainer
        private val colorSurfaceContainerLow =
            com.google.android.material.R.attr.colorSurfaceContainerLow
        private val colorSecondaryContainer =
            com.google.android.material.R.attr.colorSecondaryContainer
        private val colorOnSecondaryContainer =
            com.google.android.material.R.attr.colorOnSecondaryContainer
        private val colorOnSurfaceVariant =
            com.google.android.material.R.attr.colorOnSurfaceVariant
        private val colorSecondary = com.google.android.material.R.attr.colorSecondary
    }
}