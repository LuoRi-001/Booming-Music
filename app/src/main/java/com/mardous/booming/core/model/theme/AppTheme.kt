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

package com.mardous.booming.core.model.theme

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.util.GeneralTheme
import com.mardous.booming.util.Preferences

class AppTheme private constructor(
    val id: String,
    @StyleRes
    val themeRes: Int,
    val applyDynamicColors: Boolean,
    val seedColor: Int? = null
) {

    val isBlackTheme: Boolean
        get() = id == GeneralTheme.BLACK

    enum class Mode(@StyleRes val themeRes: Int) {
        Light(R.style.Theme_Booming_Light),
        Dark(R.style.Theme_Booming),
        Black(R.style.Theme_Booming_Black),
        FollowSystem(R.style.Theme_Booming_FollowSystem)
    }

    companion object {
        fun createAppTheme(context: Context): AppTheme {
            val generalTheme = Preferences.generalTheme
            val themeMode = Preferences.getThemeMode(generalTheme)
            if (DynamicColors.isDynamicColorAvailable()) {
                // A cover palette takes priority over the wallpaper one: the
                // user asked for it explicitly, and the library screen behind
                // this activity is already painted with it.
                val coverSeed = CoverColorState.activeSeed
                if (coverSeed != null) {
                    return AppTheme(
                        id = generalTheme,
                        themeRes = themeMode.themeRes,
                        applyDynamicColors = true,
                        seedColor = coverSeed
                    )
                }
                if (Preferences.isMaterialYouTheme) {
                    return AppTheme(
                        id = generalTheme,
                        themeRes = themeMode.themeRes,
                        applyDynamicColors = true
                    )
                }
                if (context is ContextThemeWrapper) {
                    return AppTheme(
                        id = generalTheme,
                        themeRes = themeMode.themeRes,
                        applyDynamicColors = true,
                        seedColor = ContextCompat.getColor(context, R.color.md_theme_primary)
                    )
                }
            }
            return AppTheme(
                id = generalTheme,
                themeRes = themeMode.themeRes,
                applyDynamicColors = false
            )
        }
    }
}