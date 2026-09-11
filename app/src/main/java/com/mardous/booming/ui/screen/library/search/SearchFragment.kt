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

package com.mardous.booming.ui.screen.library.search

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.transition.TransitionManager
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.data.SearchFilter
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.search.SearchQuery
import com.mardous.booming.databinding.FragmentSearchBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.dip
import com.mardous.booming.extensions.hideSoftKeyboard
import com.mardous.booming.extensions.isEmpty
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.navigation.albumDetailArgs
import com.mardous.booming.extensions.navigation.artistDetailArgs
import com.mardous.booming.extensions.navigation.asFragmentExtras
import com.mardous.booming.extensions.navigation.genreDetailArgs
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.extensions.resources.focusAndShowKeyboard
import com.mardous.booming.extensions.resources.onVerticalScroll
import com.mardous.booming.extensions.resources.reactionToKey
import com.mardous.booming.extensions.resources.setupStatusBarForeground
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.ui.ISearchCallback
import com.mardous.booming.ui.adapters.SearchAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onAlbumMenu
import com.mardous.booming.ui.component.menu.onArtistMenu
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import kotlinx.coroutines.flow.collectLatest
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

/**
 * @author Christians M. A. (mardous)
 */
class SearchFragment : AbsMainActivityFragment(R.layout.fragment_search),
    View.OnClickListener, MaterialButtonToggleGroup.OnButtonCheckedListener, ISearchCallback {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModel<SearchViewModel>()

    private lateinit var searchAdapter: SearchAdapter
    private lateinit var voiceSearchLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchBinding.bind(view)
        setSupportActionBar(binding.toolbar)
        materialSharedAxis(view)
        view.applyHorizontalWindowInsets()

        setupRecyclerView()

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            viewModel.searchFilter.collect { searchFilter ->
                if (searchFilter != null) {
                    val compatibleModes = searchFilter.getCompatibleModes().map { it.buttonId }

                    binding.modeButtonGroup.children.map { it as MaterialButton }
                        .filter { !compatibleModes.contains(it.id) }
                        .forEach { it.isVisible = false }
                    binding.modeButtonGroup.isSelectionRequired = true
                    binding.modeButtonGroup.check(compatibleModes.first())

                    binding.searchView.hint = searchFilter.getName()
                    binding.filterScrollView.isVisible = searchFilter.getCompatibleModes().size > 1
                }
            }
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            viewModel.searchResult.collect { result ->
                searchAdapter.dataSet = result
            }
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            viewModel.queueFlow.collectLatest { (songs, startPos, songClickBehavior) ->
                playerViewModel.openSongs(startPos, songs, songClickBehavior)
                if (!songClickBehavior.isAbleToPlay) {
                    showToast(R.string.added_title_to_playing_queue)
                }
            }
        }

        // Observe playlists-related changes
        libraryViewModel.getPlaylists().observe(viewLifecycleOwner) {
            viewModel.refresh()
        }

        binding.appBar.setupStatusBarForeground()
        // The whole top block — bar, strip behind the status bar and the
        // keyboard button — is made of theme colours resolved when this screen
        // was inflated, which for a screen the user keeps coming back to would
        // mean the palette of an old song.
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            CoverColorState.scheme.collect { published ->
                val colorScheme = published.takeIf { CoverColorState.isEnabled }
                    ?.forNightMode(resources.isNightMode)
                val surface = colorScheme?.surface?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.appBar, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT
                    )
                val primaryContainer = colorScheme?.primaryContainer?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.keyboardPopup,
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        Color.TRANSPARENT
                    )
                val onPrimaryContainer = colorScheme?.onPrimaryContainer?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.keyboardPopup,
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                        Color.TRANSPARENT
                    )
                binding.appBar.setBackgroundColor(surface)
                binding.toolbar.setBackgroundColor(surface)
                // Tinted in place rather than replaced: the bar only keeps the
                // strip in step with its own colour while the strip is a
                // drawable it recognises.
                binding.appBar.statusBarForeground?.setTint(surface)
                binding.keyboardPopup.backgroundTintList = ColorStateList.valueOf(primaryContainer)
                binding.keyboardPopup.iconTint = ColorStateList.valueOf(onPrimaryContainer)
                binding.keyboardPopup.setTextColor(onPrimaryContainer)
                // The song/album/artist chips are checkable filled buttons, which
                // the style paints ?colorSurfaceContainer while unselected and
                // ?colorPrimary once picked — a near white row of chips until the
                // activity is recreated. Repainted here as the same pair of roles,
                // so the selected chip stays the one that stands out.
                val surfaceContainer = colorScheme?.surfaceContainer?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.modeButtonGroup,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                        Color.TRANSPARENT
                    )
                val primary = colorScheme?.primary?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.modeButtonGroup, androidx.appcompat.R.attr.colorPrimary, Color.TRANSPARENT
                    )
                val onPrimary = colorScheme?.onPrimary?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.modeButtonGroup, com.google.android.material.R.attr.colorOnPrimary, Color.TRANSPARENT
                    )
                val onSurfaceVariant = colorScheme?.onSurfaceVariant?.toArgb()
                    ?: MaterialColors.getColor(
                        binding.modeButtonGroup,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        Color.TRANSPARENT
                    )
                binding.modeButtonGroup.children.filterIsInstance<MaterialButton>().forEach { button ->
                    button.backgroundTintList = checkedPairTint(surfaceContainer, primary)
                    val label = checkedPairTint(onSurfaceVariant, onPrimary)
                    button.setTextColor(label)
                    button.iconTint = label
                }
            }
        }
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.voiceSearch.setOnClickListener(this)
        binding.clearText.setOnClickListener(this)
        binding.modeButtonGroup.addOnButtonCheckedListener(this)
        binding.searchView.apply {
            reactionToKey(KeyEvent.KEYCODE_ENTER) {
                hideSoftKeyboard()
            }
            doAfterTextChanged {
                search(it?.toString())
            }
            focusAndShowKeyboard()
        }
        binding.keyboardPopup.setOnClickListener {
            if (!searchAdapter.isInQuickSelectMode) {
                binding.searchView.focusAndShowKeyboard()
            }
        }

        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace(dip(R.dimen.fab_size_padding)))
        }

        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.keyboardPopup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it.getWithSpace()
            }
        }

        KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) {
            if (it) {
                binding.keyboardPopup.hide()
            } else {
                binding.keyboardPopup.show()
            }
        }

        voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null) {
                val result = it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val newQuery = result?.get(0)
                binding.searchView.setText(newQuery, TextView.BufferType.EDITABLE)
            }
        }

        if (savedInstanceState == null) {
            val filterMode = arguments?.let {
                BundleCompat.getSerializable(it, MODE, SearchQuery.FilterMode::class.java)
            }
            binding.modeButtonGroup.check(filterMode?.buttonId ?: View.NO_ID)
            binding.searchView.setText(arguments?.getString(QUERY))
            viewModel.updateFilter(arguments?.let {
                BundleCompat.getParcelable(it, FILTER, SearchFilter::class.java)
            })
        }
    }

    private val adapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            super.onChanged()
            val isEmpty = searchAdapter.isEmpty
            if (!isEmpty) {
                binding.recyclerView.scheduleLayoutAnimation()
            }
            binding.empty.isVisible = isEmpty
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        searchAdapter = SearchAdapter(requireActivity(), emptyList(), this).apply {
            registerAdapterDataObserver(adapterDataObserver)
        }
        binding.recyclerView.apply {
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
                .apply { animation.duration = resources.getInteger(R.integer.short_anim_time).toLong() }
            layoutManager = LinearLayoutManager(activity)
            adapter = searchAdapter
            onVerticalScroll(viewLifecycleOwner,
                onScrollDown = { binding.keyboardPopup.shrink() },
                onScrollUp = { binding.keyboardPopup.extend() }
            )
        }
    }

    override fun onClick(view: View) {
        when (view) {
            binding.voiceSearch -> startVoiceSearch()
            binding.clearText -> binding.searchView.text?.clear()
        }
    }

    override fun onButtonChecked(
        group: MaterialButtonToggleGroup,
        checkedId: Int,
        isChecked: Boolean
    ) {
        val searchMode = SearchQuery.FilterMode.entries
            .firstOrNull { it.buttonId == group.checkedButtonId }
        viewModel.updateQuery(mode = searchMode)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            android.R.id.home -> {
                findNavController().navigateUp()
                true
            }

            else -> false
        }
    }

    override fun songClick(song: Song, results: List<Any>) {
        viewModel.songClick(song, results)
    }

    override fun songMenuItemClick(song: Song, menuItem: MenuItem): Boolean {
        return song.onSongMenu(this, menuItem)
    }

    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>) {
        findNavController().navigate(
            R.id.nav_album_detail,
            albumDetailArgs(album.id),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun albumMenuItemClick(
        album: Album,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>
    ): Boolean {
        return album.onAlbumMenu(this, menuItem)
    }

    override fun artistClick(artist: Artist, sharedElements: Array<Pair<View, String>>) {
        findNavController().navigate(
            R.id.nav_artist_detail,
            artistDetailArgs(artist),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun artistMenuItemClick(
        artist: Artist,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>
    ): Boolean {
        return artist.onArtistMenu(this, menuItem)
    }

    override fun playlistClick(playlist: PlaylistWithSongs) {
        findNavController().navigate(R.id.nav_playlist_detail, playlistDetailArgs(playlist.playlistEntity.playListId))
    }

    override fun playlistMenuItemClick(playlist: PlaylistWithSongs, menuItem: MenuItem): Boolean {
        return playlist.onPlaylistMenu(this, menuItem)
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        selection.onSongsMenu(this, menuItem)
    }

    override fun genreClick(genre: Genre) {
        findNavController().navigate(R.id.nav_genre_detail, genreDetailArgs(genre))
    }

    // Unselected/selected pair for the mode chips, mirroring the state list
    // Material's own button selector would have resolved against the theme.
    private fun checkedPairTint(unchecked: Int, checked: Int) = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checkable, android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(checked, unchecked)
    )

    private fun search(query: String?) {
        if (query == null) return
        TransitionManager.beginDelayedTransition(binding.appBar)
        binding.voiceSearch.isGone = query.isNotEmpty()
        binding.clearText.isVisible = query.isNotEmpty()
        viewModel.updateQuery(query = query)
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            showToast(R.string.speech_not_supported)
        }
    }

    private fun hideSoftKeyboard() {
        activity?.hideSoftKeyboard()
        binding.searchView.clearFocus()
    }

    override fun onMediaContentChanged() {
        super.onMediaContentChanged()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        hideSoftKeyboard()
        super.onDestroyView()
        searchAdapter.unregisterAdapterDataObserver(adapterDataObserver)
        binding.modeButtonGroup.removeOnButtonCheckedListener(this)
        binding.searchView.setOnKeyListener(null)
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        searchAdapter.actionMode?.finish()
    }

    companion object {
        const val MODE = "mode"
        private const val FILTER = "filter"
        private const val QUERY = "query"
    }
}