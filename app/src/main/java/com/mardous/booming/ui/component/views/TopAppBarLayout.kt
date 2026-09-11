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

package com.mardous.booming.ui.component.views

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.AttrRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.databinding.CollapsingAppbarLayoutBinding
import com.mardous.booming.databinding.SimpleAppbarLayoutBinding
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TopAppBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1,
) : AppBarLayout(context, attrs, defStyleAttr) {
    private var simpleAppbarBinding: SimpleAppbarLayoutBinding? = null
    private var collapsingAppbarBinding: CollapsingAppbarLayoutBinding? = null

    private var coverColorJob: Job? = null
    private var coverColorAnimator: Animator? = null

    val mode: AppBarMode = Preferences.appBarMode

    init {
        if (mode == AppBarMode.COLLAPSING) {
            collapsingAppbarBinding =
                CollapsingAppbarLayoutBinding.inflate(LayoutInflater.from(context), this, true)
            val isLandscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) {
                fitsSystemWindows = false
            }

        } else {
            simpleAppbarBinding =
                SimpleAppbarLayoutBinding.inflate(LayoutInflater.from(context), this, true)
            statusBarForeground = MaterialShapeDrawable.createWithElevationOverlay(context)
        }
    }

    // The library screen keeps its activity while songs change, so an app bar
    // inflated earlier would otherwise keep showing the palette of whatever
    // song was playing when its fragment was created. Following the palette
    // from here also covers the plain case of a newly created fragment, whose
    // bar would be inflated with the theme colours of the previous song.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        coverColorJob = owner.lifecycleScope.launch {
            CoverColorState.scheme.collect { applyCoverColors(it) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coverColorJob?.cancel()
        coverColorJob = null
        coverColorAnimator?.cancel()
        coverColorAnimator = null
        simpleAppbarBinding = null
        collapsingAppbarBinding = null
    }

    /**
     * Repaints the bar from a cover palette; null puts the theme colours
     * back. The bar is inflated with ?colorSurface and lifts to
     * ?colorSurfaceContainer while scrolling, so both states have to move
     * together or they would end up showing different songs' palettes.
     */
    private fun applyCoverColors(published: CoverColorState.CoverScheme?) {
        // The pure black theme turns the feature off without republishing, so
        // a palette still sitting in the state must not outlive it.
        val scheme = published.takeIf { CoverColorState.isEnabled }
        val colorScheme = scheme?.forNightMode(resources.isNightMode)
        val surface = colorScheme?.surface?.toArgb() ?: themeColor(colorSurface)
        val lifted = colorScheme?.surfaceContainer?.toArgb() ?: themeColor(colorSurfaceContainer)

        val background = materialShapeBackground
        if (background == null) {
            backgroundTintList = ColorStateList.valueOf(surface)
        } else {
            // A scroll animates the bar between two colours it read off the
            // background as it was installed — the resting colour and the lift
            // colour — and it only ever reads them at that moment. The fill is
            // put on the palette and the drawable reinstalled around it, which
            // is what makes it read them again: left as they were at inflation,
            // a scroll mixes back to the theme colour and drags the status bar
            // strip along with it, until something else forces a new capture.
            val target = if (isLifted) lifted else surface
            val current = background.fillColor?.defaultColor ?: target
            background.fillColor = ColorStateList.valueOf(surface)
            setBackground(null)
            setBackground(background)
            setLiftOnScrollColor(ColorStateList.valueOf(lifted))
            coverColorAnimator?.cancel()
            coverColorAnimator = ValueAnimator
                .ofArgb(current, target)
                .apply {
                    duration = COVER_COLOR_ANIMATION_DURATION
                    addUpdateListener {
                        background.fillColor = ColorStateList.valueOf(it.animatedValue as Int)
                    }
                    start()
                }
        }
        // Whatever reserved a status bar strip — the compact bar in init, or a
        // fragment through setupStatusBarForeground, both of which took the
        // theme colour of their inflation — has to follow the palette too, or
        // the strip keeps showing the song that was playing back then. Tinting
        // it in place rather than replacing it: the bar only keeps the strip in
        // step with its own colour while the strip is a drawable it recognises.
        statusBarForeground?.setTint(if (isLifted) lifted else surface)
    }

    private fun themeColor(@AttrRes attr: Int): Int =
        MaterialColors.getColor(this, attr, Color.TRANSPARENT)

    fun pinWhenScrolled() {
        simpleAppbarBinding?.root?.updateLayoutParams<LayoutParams> {
            scrollFlags = SCROLL_FLAG_NO_SCROLL
        }
    }

    val toolbar: MaterialToolbar
        get() = if (mode == AppBarMode.COLLAPSING) {
            collapsingAppbarBinding?.toolbar!!
        } else {
            simpleAppbarBinding?.toolbar!!
        }

    var title: CharSequence
        get() = if (mode == AppBarMode.COLLAPSING) {
            collapsingAppbarBinding?.collapsingToolbarLayout?.title.toString()
        } else {
            simpleAppbarBinding?.toolbar?.title.toString()
        }
        set(value) {
            if (mode == AppBarMode.COLLAPSING) {
                collapsingAppbarBinding?.collapsingToolbarLayout?.title = value
            } else {
                simpleAppbarBinding?.toolbar?.title = value
            }
        }

    enum class AppBarMode {
        COLLAPSING,
        SIMPLE
    }

    private companion object {
        const val COVER_COLOR_ANIMATION_DURATION = 300L

        val colorSurface = com.google.android.material.R.attr.colorSurface
        val colorSurfaceContainer = com.google.android.material.R.attr.colorSurfaceContainer
    }
}
