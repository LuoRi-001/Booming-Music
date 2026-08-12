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

package com.mardous.booming.ui.screen.equalizer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.transition.Fade
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class EqualizerFragment : Fragment() {

    private val arguments: EqualizerFragmentArgs by navArgs()

    private val libraryViewModel: LibraryViewModel by activityViewModel()
    private val equalizerViewModel: EqualizerViewModel by viewModel()
    private val playerViewModel: PlayerViewModel by activityViewModel()

    private var popScheduled = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Paint the theme's background while Compose bootstraps. A
            // ComposeView is transparent until its content renders, so the
            // first frames after navigating here would otherwise show the
            // home screen / collapsing panel underneath.
            val background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.colorBackground)
            ).let { attrs ->
                val color = attrs.getColor(0, 0xFF000000.toInt())
                attrs.recycle()
                color
            }
            setBackgroundColor(background)
            setContent {
                BoomingMusicTheme {
                    EqualizerScreen(
                        libraryViewModel = libraryViewModel,
                        eqViewModel = equalizerViewModel,
                        onBackClick = { schedulePopBack() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // This is a ComposeView: once it is detached from the container the
        // Compose content is no longer drawn, so a return transition (which
        // plays inside the container overlay) renders blank frames for the
        // whole animation — the same white flash the stats page had. Skip it
        // on pop; the page beneath covers the swap.
        Fade().let {
            enterTransition = it
            exitTransition = it
            reenterTransition = it
            returnTransition = null
        }
        postponeEnterTransition()
        // Double-post: release via the message queue so the release still
        // runs even when a frame is dropped. A stuck postpone blocks the
        // container's operation queue and a pop re-shows this view instead
        // of removing it (white flash → reappear → fade).
        view.doOnPreDraw {
            view.post { view.post { startPostponedEnterTransition() } }
        }
        if (arguments.fromPlayer) {
            // Collapse the player panel only now that this page is attached.
            // Collapsing it at the click would expose the home screen for a
            // frame before this view lands (nav commits asynchronously) —
            // with the panel still up, the reveal is covered by this page.
            (activity as? MainActivity)?.collapsePanel()
            // The player is a sliding panel, not a nav destination: popping
            // would land on the underlying screen (home) before the panel
            // expands. Intercept back so the panel expands over this page
            // first, then pop underneath it — the home screen never shows.
            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() = schedulePopBack()
                }
            )
        }
    }

    private fun schedulePopBack() {
        if (popScheduled) return
        popScheduled = true
        (activity as? MainActivity)?.expandPanel()
        // Wait for the panel expansion animation to cover this page before
        // popping, so the underlying screen is never exposed.
        view?.postDelayed({ findNavController().navigateUp() }, 350)
    }

    override fun onDestroyView() {
        // Safety net: never leave the enter transition postponed — a stuck
        // postpone blocks the container's operation queue, so a pop would
        // re-show this view instead of removing it (white flash → reappear
        // → fade).
        startPostponedEnterTransition()
        super.onDestroyView()
        if (arguments.fromPlayer && playerViewModel.queue.isNotEmpty()) {
            (activity as? MainActivity)?.expandPanel()
        }
    }
}
