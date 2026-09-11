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

package com.mardous.booming.ui.component.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.isNightMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A [FloatingActionButton] that keeps its container on the cover palette.
 *
 * The library screens hold on to their activity while songs change, and the
 * shuffle button is inflated once with the theme of the song playing then —
 * nothing rebinds it — so it follows the palette on its own.
 */
class CoverTintFab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.floatingActionButtonStyle
) : FloatingActionButton(context, attrs, defStyleAttr) {

    // Whatever the style resolved against the activity theme, restored when the
    // feature is off. The M3 primary button asks for this role, and the getter
    // is not guaranteed to have it before the style is applied, hence the
    // fallback to the theme attribute.
    private val themeTint = backgroundTintList ?: ColorStateList.valueOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT)
    )

    private val themeIconTint = imageTintList

    private var coverColorJob: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        coverColorJob = owner.lifecycleScope.launch {
            CoverColorState.scheme.collect { applyCoverTint(it) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coverColorJob?.cancel()
        coverColorJob = null
    }

    private fun applyCoverTint(published: CoverColorState.CoverScheme?) {
        // The pure black theme turns the feature off without republishing, so a
        // palette still sitting in the state must not outlive it.
        val colorScheme = published
            .takeIf { CoverColorState.isEnabled }
            ?.forNightMode(resources.isNightMode)
        if (colorScheme == null) {
            backgroundTintList = themeTint
            themeIconTint?.let { imageTintList = it }
        } else {
            // The style pairs these two roles, so the glyph has to move with
            // the container it sits on.
            backgroundTintList = ColorStateList.valueOf(colorScheme.primaryContainer.toArgb())
            imageTintList = ColorStateList.valueOf(colorScheme.onPrimaryContainer.toArgb())
        }
    }
}
