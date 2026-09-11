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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.isNightMode

/**
 * A rounded surface rectangle painted with the cover palette, drawn over the
 * theme coloured layer of [R.drawable.popupmenu_background] and painting
 * nothing at all while the feature is off, so that layer shows through.
 *
 * Popup menus are built from the theme of the activity they are opened from,
 * and that theme only ever carries the palette of the song that was playing
 * when the activity was created. This resolves the palette as it paints
 * instead.
 *
 * A drawable inflated from XML is constructed by name through DrawableInflater,
 * which looks up a public no-argument constructor: the context and the
 * attributes of the tag are not passed, so everything this needs — palette,
 * night mode, corner radius — has to be read while drawing.
 */
class CoverSurfaceDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cornerRadius = 0f

    override fun draw(canvas: Canvas) {
        val context = CoverColorState.appContext ?: return
        val colorScheme = CoverColorState.scheme.value
            ?.takeIf { CoverColorState.isEnabled }
            ?.forNightMode(context.resources.isNightMode)
            ?: return
        if (cornerRadius == 0f) {
            cornerRadius = context.resources
                .getDimensionPixelSize(R.dimen.m3_popup_window_corner_size).toFloat()
        }
        paint.color = colorScheme.surface.toArgb()
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            cornerRadius,
            cornerRadius,
            paint
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
