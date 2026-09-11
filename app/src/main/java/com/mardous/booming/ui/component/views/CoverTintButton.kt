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
import android.util.AttributeSet
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.isNightMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A [MaterialButton] that keeps its container on the cover palette.
 *
 * The filled icon button style paints itself with a surface role, and roles are
 * resolved once per activity: on a screen that follows the cover, an inflated
 * button would keep the theme's near white circle and read as a white dot. The
 * button follows the palette on its own because the rows it sits in are not
 * rebound when the song changes.
 */
class CoverTintButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    // Whatever the style resolved against the activity theme, restored when the
    // feature is off.
    private val themeTint = backgroundTintList

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
        val coverTint = colorScheme?.let { ColorStateList.valueOf(it.surfaceContainerLow.toArgb()) }
        if (coverTint != null) {
            backgroundTintList = coverTint
        } else {
            themeTint?.let { backgroundTintList = it }
        }
    }
}
