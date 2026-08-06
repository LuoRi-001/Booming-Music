package com.mardous.booming.ui.screen.library.home

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.databinding.FragmentHomeBinding
import com.mardous.booming.R
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.ContentType
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.Suggestion
import com.mardous.booming.extensions.dp
import com.mardous.booming.extensions.isNullOrEmpty
import com.mardous.booming.extensions.navigation.albumDetailArgs
import com.mardous.booming.extensions.navigation.artistDetailArgs
import com.mardous.booming.extensions.navigation.asFragmentExtras
import com.mardous.booming.extensions.navigation.detailArgs
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.extensions.resources.addPaddingRelative
import com.mardous.booming.extensions.resources.destroyOnDetach
import com.mardous.booming.extensions.resources.primaryColor
import com.mardous.booming.extensions.resources.setupStatusBarForeground
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.toHtml
import com.mardous.booming.extensions.topLevelTransition
import com.mardous.booming.ui.IAlbumCallback
import com.mardous.booming.ui.IArtistCallback
import com.mardous.booming.ui.IHomeCallback
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.HomeAdapter
import com.mardous.booming.ui.adapters.StatsCardFooterAdapter
import com.mardous.booming.ui.adapters.album.AlbumAdapter
import com.mardous.booming.ui.adapters.artist.ArtistAdapter
import com.mardous.booming.ui.adapters.song.SongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onAlbumMenu
import com.mardous.booming.ui.component.menu.onAlbumsMenu
import com.mardous.booming.ui.component.menu.onArtistMenu
import com.mardous.booming.ui.component.menu.onArtistsMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.screen.library.ReloadType
import com.mardous.booming.ui.theme.BoomingMusicTheme

/**
 * @author Christians M. A. (mardous)
 */
// Warm color palette for shuffle button background (ARGB)
private val WARM_COLORS = longArrayOf(
    0xFFFF6B6B, // coral
    0xFFFF8A65, // salmon
    0xFFFF9800, // orange
    0xFFFF5722, // deep orange
    0xFFFFAB40, // amber-orange
    0xFFFF7043, // warm burnt orange
    0xFFE8614C, // terracotta
    0xFFFF8C42, // tangerine
)

class HomeFragment : AbsMainActivityFragment(R.layout.fragment_home),
    ISongCallback,
    IAlbumCallback,
    IArtistCallback,
    IHomeCallback,
    IScrollHelper {

    private var _binding: HomeBinding? = null
    private val binding get() = _binding!!

    private var homeAdapter: HomeAdapter? = null
    private var statsFooterAdapter: StatsCardFooterAdapter? = null

    private val currentContent: SuggestedResult
        get() = libraryViewModel.getSuggestions().value ?: SuggestedResult.Idle

    // 你的推荐 state
    private var songState = mutableStateOf<List<Song>>(emptyList())
    private var refreshKey = mutableStateOf(0L)
    private var allCachedSongs: List<Song> = emptyList()
    private var isSongsLoaded = false
    private var hasSuggestionsLoaded = false

    // Warm accent color for shuffle button — picked once per fragment lifetime
    private val warmShuffleColor: Long = WARM_COLORS.random()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val homeBinding = FragmentHomeBinding.bind(view)
        _binding = HomeBinding(homeBinding)
        binding.appBarLayout.setupStatusBarForeground()
        setSupportActionBar(binding.toolbar)
        topLevelTransition(view)

        setupTitle()
        setupRecommendations()
        checkForMargins()

        homeAdapter = HomeAdapter(arrayListOf(), this).also {
            it.registerAdapterDataObserver(adapterDataObserver)
        }
        val statsFooterAdapter = StatsCardFooterAdapter(
            libraryViewModel = libraryViewModel,
            onClick = {
                findNavController().navigate(R.id.nav_listening_stats)
            }
        ).also { this.statsFooterAdapter = it }
        val concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            homeAdapter,
            statsFooterAdapter
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = concatAdapter
            addPaddingRelative(bottom = 8.dp(resources))
            destroyOnDetach()
        }
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(
                bottom = it.getWithSpace(16.dp(resources), includeInsets = false)
            )
        }
        libraryViewModel.getSuggestions().apply {
            observe(viewLifecycleOwner) { result ->
                if (result.isLoading && homeAdapter.isNullOrEmpty) {
                    binding.progressIndicator.show()
                } else {
                    binding.progressIndicator.hide()
                }
                homeAdapter?.dataSet = result.data
                hasSuggestionsLoaded = true
                if (result.data.isNotEmpty()) {
                    this@HomeFragment.statsFooterAdapter?.isVisible = true
                }
            }
        }.also { liveData ->
            if (liveData.value == SuggestedResult.Idle) {
                libraryViewModel.forceReload(ReloadType.Suggestions)
            }
        }

        applyWindowInsetsFromView(view)
    }

    private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            checkIsEmpty()
        }
    }

    private fun setupTitle() {
        binding.appBarLayout.toolbar.setNavigationOnClickListener {
            findNavController().navigate(R.id.nav_search)
        }
        val hexColor = String.format("#%06X", 0xFFFFFF and primaryColor())
        val appName = "Booming <font color=$hexColor>Music</font>".toHtml()
        binding.appBarLayout.title = appName
    }

    private fun setupRecommendations() {
        binding.recommendationsSection.apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BoomingMusicTheme {
                    YourRecommendationsSection(
                        songs = songState.value,
                        refreshKey = refreshKey.value,
                        shuffleColor = warmShuffleColor,
                        onSongClick = { song -> onRecommendationSongClick(song) },
                        onShuffleClick = { onRecommendationShuffleClick() }
                    )
                }
            }
        }
        loadRecommendations()
    }

    private fun loadRecommendations() {
        if (!isSongsLoaded) {
            libraryViewModel.allSongs().observe(viewLifecycleOwner) { allSongs ->
                allCachedSongs = allSongs
                isSongsLoaded = true
                pickRandomSongs()
            }
        } else {
            pickRandomSongs()
        }
    }

    private fun pickRandomSongs() {
        if (allCachedSongs.isNotEmpty()) {
            songState.value = allCachedSongs.shuffled().take(5)
            refreshKey.value = System.nanoTime()
            binding.recommendationsSection.visibility = View.VISIBLE
        }
    }

    private fun onRecommendationSongClick(song: Song) {
        val others = allCachedSongs
            .filter { it.id != song.id }
            .shuffled()
            .take(29)
        val playlist = listOf(song) + others
        if (playlist.isNotEmpty()) {
            playerViewModel.openQueue(playlist, position = 0, shuffleMode = OpenShuffleMode.On)
        }
    }

    private fun onRecommendationShuffleClick() {
        val picks = allCachedSongs.shuffled().take(30)
        if (picks.isNotEmpty()) {
            playerViewModel.openAndShuffleQueue(picks)
        }
    }

    private fun checkIsEmpty() {
        binding.empty.isVisible = hasSuggestionsLoaded && !currentContent.isLoading && homeAdapter.isNullOrEmpty
    }

    private fun checkForMargins() {
        checkForMargins(binding.recyclerView)
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
        // Refresh recommendations on return
        pickRandomSongs()
    }

    override fun onPause() {
        super.onPause()
        binding.recyclerView.stopScroll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        homeAdapter?.unregisterAdapterDataObserver(adapterDataObserver)
        binding.recyclerView.adapter = null
        binding.recyclerView.layoutManager = null
        homeAdapter = null
        _binding = null
    }

    override fun onMediaContentChanged() {
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    override fun onFavoriteContentChanged() {
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createSuggestionAdapter(suggestion: Suggestion): RecyclerView.Adapter<*> {
        return when (suggestion.type) {
            ContentType.RecentArtists -> ArtistAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Artist>),
                itemLayoutRes = R.layout.item_artist,
                callback = this
            )

            ContentType.RecentAlbums -> AlbumAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Album>),
                itemLayoutRes = R.layout.item_album_gradient,
                callback = this
            )

            ContentType.History -> SongAdapter(
                activity = mainActivity,
                dataSet = (suggestion.items as List<Song>),
                itemLayoutRes = R.layout.item_image,
                callback = this
            )

            else -> throw IllegalArgumentException("Unexpected suggestion type: ${suggestion.type}")
        }
    }

    override fun suggestionClick(suggestion: Suggestion) {
        findNavController().navigate(R.id.nav_detail_list, detailArgs(suggestion.type))
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = song.onSongMenu(this, menuItem)

    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        songs.onSongsMenu(this, menuItem)
    }

    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>?) {
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
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = album.onAlbumMenu(this, menuItem)

    override fun albumsMenuItemClick(albums: List<Album>, menuItem: MenuItem) {
        albums.onAlbumsMenu(this, menuItem)
    }

    override fun artistClick(artist: Artist, sharedElements: Array<Pair<View, String>>?) {
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
        sharedElements: Array<Pair<View, String>>?
    ): Boolean = artist.onArtistMenu(this, menuItem)

    override fun artistsMenuItemClick(artists: List<Artist>, menuItem: MenuItem) {
        artists.onArtistsMenu(this, menuItem)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_library, menu)
        menu.removeItem(R.id.action_scan)
        menu.removeItem(R.id.action_equalizer)
        menu.removeItem(R.id.action_grid_size)
        menu.removeItem(R.id.action_view_type)
        menu.removeItem(R.id.action_sort_order)
        menu.findItem(R.id.action_settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_settings) {
            findNavController().navigate(R.id.nav_settings)
            return true
        }
        return false
    }

    override fun scrollToTop() {
        binding.container.scrollTo(0, 0)
        binding.appBarLayout.setExpanded(true)
    }
}
