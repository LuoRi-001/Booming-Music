package com.mardous.booming.ui.screen.library.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mardous.booming.R
import com.mardous.booming.extensions.materialSharedAxis
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
        materialSharedAxis(view)
    }
}
