package com.mardous.booming.ui.adapters

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.library.stats.ListeningStatsCard

class StatsCardFooterAdapter(
    private val libraryViewModel: LibraryViewModel,
    private val onClick: () -> Unit
) : RecyclerView.Adapter<StatsCardFooterAdapter.ViewHolder>() {

    var isVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    notifyItemInserted(0)
                } else {
                    notifyItemRemoved(0)
                }
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val composeView = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ListeningStatsCard(
                    libraryViewModel = libraryViewModel,
                    onCardClick = onClick
                )
            }
        }
        return ViewHolder(composeView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // No-op: Compose handles all rendering
    }

    override fun getItemCount(): Int = if (isVisible) 1 else 0

    override fun getItemId(position: Int): Long = "stats_footer".hashCode().toLong()

    inner class ViewHolder(itemView: ComposeView) : RecyclerView.ViewHolder(itemView)
}
