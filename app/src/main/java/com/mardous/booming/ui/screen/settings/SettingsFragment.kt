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

package com.mardous.booming.ui.screen.settings

import android.animation.Animator
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.databinding.FragmentSettingsBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.getOnBackPressedDispatcher
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.resources.animateBackgroundColor
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.base.AbsThemeActivity

/**
 * @author Christians M. A. (mardous)
 */
class SettingsFragment : AbsMainActivityFragment(R.layout.fragment_settings), NavController.OnDestinationChangedListener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var childNavController: NavController? = null
    private var contentFrameAnimator: Animator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Every row on this screen is a theme colour that was resolved when it
        // was inflated, so they can only pick up a palette that moved on by
        // being inflated again — with a freshly themed activity. Entry only:
        // rebuilding while the user is sitting here would be a flash for every
        // song change, and the rows below are a preference list, not something
        // this screen repaints itself.
        val host = activity as? AbsThemeActivity
        if (host != null && host.isCoverThemeStale) {
            host.recreate()
            return
        }
        _binding = FragmentSettingsBinding.bind(view)
        with(binding.appBarLayout.toolbar) {
            setNavigationIcon(R.drawable.ic_back_24dp)
            isTitleCentered = false
            setNavigationOnClickListener {
                getOnBackPressedDispatcher().onBackPressed()
            }
        }

        // The content frame is inflated with a plain ?colorSurface and the
        // preference lists are transparent over it, so it is the white
        // background this screen shows while the rest of the app follows the
        // cover; keep it in lockstep with the palette.
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            CoverColorState.scheme.collect { published ->
                val scheme = published.takeIf { CoverColorState.isEnabled }
                val colorScheme = scheme?.forNightMode(resources.isNightMode)
                val surface = colorScheme?.surface?.toArgb()
                    ?: MaterialColors.getColor(
                        view,
                        com.google.android.material.R.attr.colorSurface,
                        Color.TRANSPARENT
                    )
                contentFrameAnimator?.cancel()
                contentFrameAnimator =
                    binding.contentFrame.animateBackgroundColor(surface, COVER_COLOR_ANIMATION_DURATION)
                        .also(Animator::start)
            }
        }

        materialSharedAxis(view)
        view.applyHorizontalWindowInsets()

        val navHostFragment = childFragmentManager.findFragmentById(R.id.contentFrame) as NavHostFragment
        childNavController = navHostFragment.navController.apply {
            addOnDestinationChangedListener(this@SettingsFragment)
        }
    }

    override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
        binding.appBarLayout.title = destination.label ?: getString(R.string.settings_title)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    override fun onResume() {
        super.onResume()
        getOnBackPressedDispatcher().addCallback(viewLifecycleOwner, onBackPressedCallback)
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
        contentFrameAnimator?.cancel()
        childNavController?.removeOnDestinationChangedListener(this)
    }

    private companion object {
        const val COVER_COLOR_ANIMATION_DURATION = 300L
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (mainActivity.panelState != BottomSheetBehavior.STATE_COLLAPSED) {
                mainActivity.collapsePanel()
                return
            }
            if (childNavController?.popBackStack() == false) {
                remove()
                getOnBackPressedDispatcher().onBackPressed()
                return
            }
        }
    }
}