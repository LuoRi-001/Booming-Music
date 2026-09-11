/*
 * Copyright (c) 2025 Christians Martínez Alvarado
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

package com.mardous.booming.core.palette

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.content.ContextCompat
import com.mardous.booming.ui.theme.PaletteStyle
import com.mardous.booming.ui.theme.dynamicColorSchemes
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Process wide holder for the palette derived from the current cover.
 *
 * The library screen repaints itself from [scheme] whenever the song changes,
 * and both the Compose theme and every activity created afterwards read the
 * same seed, so the whole app agrees on one palette without any of them
 * running the scheme generation twice.
 */
object CoverColorState {

    /** Palette built from one seed, in the two variants the theme can need. */
    class CoverScheme(val seed: Int, val light: ColorScheme, val dark: ColorScheme) {
        fun forNightMode(isDark: Boolean): ColorScheme = if (isDark) dark else light
    }

    private val mutableScheme = MutableStateFlow<CoverScheme?>(null)

    /** Emits the palette of the current cover; null while the feature is off. */
    val scheme: StateFlow<CoverScheme?> = mutableScheme.asStateFlow()

    /** Seed for callers that theme resources instead of Compose (see AppTheme). */
    val activeSeed: Int?
        get() = if (isEnabled) current?.seed else null

    private var current: CoverScheme? = null

    /**
     * Application context the palette was initialised with, for painters that
     * cannot hold one of their own: a drawable inflated from XML is built
     * through a no-argument constructor and is handed neither a context nor
     * its attributes.
     */
    var appContext: Context? = null
        private set

    // The pure black theme asks for an OLED black surface at the theme level,
    // where a cover tint would only ever fight it, so it wins and this stays
    // out of the way.
    val isEnabled: Boolean
        get() = Preferences.isCoverColorEnabled && !Preferences.blackTheme

    /** Called once from [com.mardous.booming.App] to restore the last palette. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Only worth the scheme generation when there is something to restore;
        // for everyone else (feature off by default) this stays free.
        val seed = coverColorSeed()
        if (seed != null) {
            apply(build(seed))
        }
    }

    /**
     * Publishes the palette for [seed], or clears it when null. Cheap enough
     * to be called on every song change: a repeated seed is a no-op.
     */
    suspend fun update(seed: Int?) {
        val wanted = seed.takeIf { isEnabled }
        // The preference write goes through SharedPreferences.edit().apply(),
        // which copies the whole map and posts the commit: not something to do
        // on every song change when the seed on screen has not moved.
        if (wanted != null && wanted != Preferences.coverColorSeed) {
            Preferences.coverColorSeed = wanted
        }
        if (wanted == current?.seed) return
        apply(wanted?.let { withContext(Dispatchers.Default) { build(it) } })
    }

    private fun coverColorSeed(): Int? =
        Preferences.coverColorSeed.takeIf { isEnabled && it != 0 }

    private fun apply(scheme: CoverScheme?) {
        current = scheme
        mutableScheme.value = scheme
    }

    // Content is the style the now playing screen already uses for its own
    // palette, so a song that tints the player tints the library identically.
    // Its neutral palette is deliberately understated, though, so the surface
    // family is blended towards the seed: otherwise the background reads as
    // "still white" and no song change ever shows on it.
    private fun build(seed: Int): CoverScheme {
        val schemes = dynamicColorSchemes(Color(seed), PaletteStyle.Content, systemContrast())
        val tint = Color(seed)
        return CoverScheme(
            seed,
            tintSurfaces(schemes.lightColorScheme, tint),
            tintSurfaces(schemes.darkColorScheme, tint)
        )
    }

    private fun tintSurfaces(scheme: ColorScheme, tint: Color): ColorScheme {
        fun tinted(color: Color) = lerp(color, tint, SURFACE_TINT_ALPHA)
        return scheme.copy(
            background = tinted(scheme.background),
            surface = tinted(scheme.surface),
            surfaceDim = tinted(scheme.surfaceDim),
            surfaceBright = tinted(scheme.surfaceBright),
            surfaceContainer = tinted(scheme.surfaceContainer),
            surfaceContainerHigh = tinted(scheme.surfaceContainerHigh),
            surfaceContainerHighest = tinted(scheme.surfaceContainerHighest),
            surfaceContainerLow = tinted(scheme.surfaceContainerLow),
            surfaceContainerLowest = tinted(scheme.surfaceContainerLowest)
        )
    }

    // How much of the raw cover colour the surface family takes on; one knob
    // for the whole feature, raise it if the backgrounds should pop more.
    private const val SURFACE_TINT_ALPHA = 0.07f

    private fun systemContrast(): Double {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0.0
        val context = appContext ?: return 0.0
        val manager = ContextCompat.getSystemService(context, UiModeManager::class.java)
        return manager?.contrast?.toDouble() ?: 0.0
    }
}
