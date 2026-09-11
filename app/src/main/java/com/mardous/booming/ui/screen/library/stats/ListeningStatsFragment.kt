package com.mardous.booming.ui.screen.library.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import com.google.android.material.color.MaterialColors
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ListeningStatsFragment : Fragment() {

    private val libraryViewModel: LibraryViewModel by activityViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_listening_stats, container, false).also { view ->
            (view as ComposeView).setContent {
                BoomingMusicTheme {
                    ListeningStatsScreen(
                        libraryViewModel = libraryViewModel,
                        onBackClick = { findNavController().navigateUp() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The Compose content paints its own background, but the view itself
        // carries a plain theme background in the XML; it is what peeks out
        // around the stats content (and what any transition samples), and it
        // would stay white while the rest of the app follows the cover. Keep
        // it in lockstep with the palette.
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            CoverColorState.scheme.collect { published ->
                val scheme = published.takeIf { CoverColorState.isEnabled }
                val colorScheme = scheme?.forNightMode(resources.isNightMode)
                view.setBackgroundColor(
                    colorScheme?.background?.toArgb()
                        ?: MaterialColors.getColor(view, android.R.attr.colorBackground, Color.TRANSPARENT)
                )
            }
        }
        // Pop behavior: this is a ComposeView, and once it is detached from
        // the container the Compose content is no longer drawn. Playing the
        // return transition (Fade) in the container overlay then renders
        // blank frames for the whole fade duration (white flash). So skip
        // the exit animation on pop entirely — the view is removed in the
        // same transaction frame and the home reenter fade covers the swap.
        Fade().let {
            enterTransition = it
            exitTransition = it
            reenterTransition = it
            returnTransition = null
        }
        postponeEnterTransition()
        // Release the transition a couple of frames after the first pre-draw
        // so the Compose content has a chance to render before the cross-fade
        // starts. `post` (message queue) is used instead of postOnAnimation:
        // the latter is skipped when frames are dropped, which would leave
        // the transition postponed forever and make the pop transition flash
        // white and re-add this view before fading to the home screen.
        view.doOnPreDraw {
            view.post { view.post { startPostponedEnterTransition() } }
        }
    }

    override fun onDestroyView() {
        // Safety net: never leave the enter transition postponed, or popping
        // this fragment would flash white and re-add the view mid-transition.
        startPostponedEnterTransition()
        super.onDestroyView()
    }
}
