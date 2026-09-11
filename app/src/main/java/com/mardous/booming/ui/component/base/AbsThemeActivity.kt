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

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewGroupCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.createAppTheme
import com.mardous.booming.extensions.hasQ
import com.mardous.booming.extensions.resources.isColorLight
import com.mardous.booming.extensions.resources.surfaceColor
import com.mardous.booming.util.Preferences

/**
 * @author Christians M. A. (mardous)
 */
abstract class AbsThemeActivity : AppCompatActivity() {

    private var windowInsetsController: WindowInsetsControllerCompat? = null

    // The seed this activity's theme was built from. Screens whose content is
    // made of theme colours, rather than of a handful of views that can be
    // repainted, compare it against the palette of the moment: they are the
    // ones that can only be brought up to date by rebuilding the activity.
    private var themedCoverSeed: Int? = null

    /** True when the cover palette moved on after this activity was themed. */
    val isCoverThemeStale: Boolean
        get() = CoverColorState.activeSeed.let { it != null && it != themedCoverSeed }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        updateTheme()
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))
        super.onCreate(savedInstanceState)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (hasQ()) {
            window.isNavigationBarContrastEnforced = false
            window.decorView.isForceDarkAllowed = false
        }
        if (Preferences.rotationLockEnabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        ViewGroupCompat.installCompatInsetsDispatch(window.decorView)
    }

    private fun updateTheme() {
        val appTheme = createAppTheme()
        setTheme(appTheme.themeRes)
        if (appTheme.applyDynamicColors) {
            val dynamicColorsOptions = DynamicColorsOptions.Builder()
                .setOnAppliedCallback {
                    if (appTheme.isBlackTheme) {
                        setTheme(R.style.BlackThemeOverlay)
                    }
                }
            if (appTheme.seedColor != null) {
                dynamicColorsOptions.setContentBasedSource(appTheme.seedColor)
            }
            DynamicColors.applyToActivityIfAvailable(this, dynamicColorsOptions.build())
        }
        // Only a theme that was actually built from the cover is worth
        // comparing later: with the feature off the theme falls back to its
        // own seed, and "stale" would then mean recreating forever.
        themedCoverSeed = CoverColorState.activeSeed.takeIf { appTheme.seedColor == it }
        if (Preferences.isCustomFont) {
            setTheme(R.style.CustomFontThemeOverlay)
        }
    }

    fun setLightStatusBar(lightStatusBar: Boolean = surfaceColor().isColorLight) {
        windowInsetsController?.isAppearanceLightStatusBars = lightStatusBar
    }

    fun setLightNavigationBar(lightNavigationBar: Boolean = surfaceColor().isColorLight) {
        windowInsetsController?.isAppearanceLightNavigationBars = lightNavigationBar
    }

    override fun onDestroy() {
        super.onDestroy()
        windowInsetsController = null
    }
}