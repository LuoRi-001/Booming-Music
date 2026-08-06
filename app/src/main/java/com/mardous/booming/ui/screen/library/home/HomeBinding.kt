package com.mardous.booming.ui.screen.library.home

import com.mardous.booming.databinding.FragmentHomeBinding

class HomeBinding(homeBinding: FragmentHomeBinding) {
    val root = homeBinding.root
    val container = homeBinding.container
    val appBarLayout = homeBinding.appBarLayout
    val toolbar = homeBinding.appBarLayout.toolbar
    val recommendationsSection = homeBinding.homeContent.absPlaylists.recommendationsSection
    val recyclerView = homeBinding.homeContent.recyclerView
    val progressIndicator = homeBinding.homeContent.progressIndicator
    val empty = homeBinding.homeContent.empty
}
