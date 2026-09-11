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

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Picks the seed colour of a cover image.
 *
 * Port of qplayer's AndroidColorExtractor. The bitmap is squashed onto a
 * 32x32 grid and every pixel votes into a 24 slot hue histogram with a
 * saturation * value weight; the mean colour of the heaviest bucket wins.
 *
 * The histogram is what makes this work on real artwork: album covers are
 * routinely dominated by white or black, and a plain average over all pixels
 * would answer a washed out gray for them. Dropping near gray, near black and
 * near white pixels before they vote has the same goal - they carry no hue
 * worth building a palette from.
 */
object CoverSeedExtractor {

    private const val SAMPLE_SIZE = 32
    private const val HUE_BINS = 24

    private const val MIN_SATURATION = 0.2f
    private const val MIN_VALUE = 0.15f
    private const val MAX_VALUE = 0.95f

    /**
     * Opaque ARGB colour for [bitmap], or null when the image holds nothing
     * usable at all (empty or fully transparent).
     */
    fun dominantColor(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        val sample = if (bitmap.width == SAMPLE_SIZE && bitmap.height == SAMPLE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, false)
        }

        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (sample !== bitmap) {
            sample.recycle()
        }

        val binWeight = DoubleArray(HUE_BINS)
        val binRed = DoubleArray(HUE_BINS)
        val binGreen = DoubleArray(HUE_BINS)
        val binBlue = DoubleArray(HUE_BINS)

        val hsv = FloatArray(3)
        var sumRed = 0.0
        var sumGreen = 0.0
        var sumBlue = 0.0

        for (pixel in pixels) {
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            sumRed += red
            sumGreen += green
            sumBlue += blue

            Color.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation < MIN_SATURATION || value < MIN_VALUE || value > MAX_VALUE) continue

            val weight = (saturation * value).toDouble()
            val bin = (hsv[0] / 360f * HUE_BINS).toInt() % HUE_BINS
            binWeight[bin] += weight
            binRed[bin] += red * weight
            binGreen[bin] += green * weight
            binBlue[bin] += blue * weight
        }

        var heaviest = -1
        for (bin in 0 until HUE_BINS) {
            if (binWeight[bin] > 0.0 && (heaviest < 0 || binWeight[bin] > binWeight[heaviest])) {
                heaviest = bin
            }
        }
        if (heaviest >= 0) {
            val weight = binWeight[heaviest]
            return Color.rgb(
                (binRed[heaviest] / weight).roundToInt().coerceIn(0, 255),
                (binGreen[heaviest] / weight).roundToInt().coerceIn(0, 255),
                (binBlue[heaviest] / weight).roundToInt().coerceIn(0, 255)
            )
        }

        // Nothing passed the filters (grayscale / near black cover), so there
        // is no hue to speak of and the plain average is the honest answer.
        val count = pixels.size
        return Color.rgb(
            (sumRed / count).roundToInt().coerceIn(0, 255),
            (sumGreen / count).roundToInt().coerceIn(0, 255),
            (sumBlue / count).roundToInt().coerceIn(0, 255)
        )
    }
}
