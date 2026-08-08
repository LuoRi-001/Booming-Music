package com.mardous.booming.ui.adapters

import android.util.Log
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

    // Visible by default so the card participates in the very first layout
    // after re-entering the screen — the content height is then complete
    // from the start and the home screen's scroll restore lands directly.
    var isVisible: Boolean = true
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
        // Use the last measured height as a placeholder so the card keeps its
        // full height from the first frame while Compose renders async data —
        // otherwise the card grows in stages and the home screen's scroll
        // restore shows a visible jump between the partial and full states.
        val cachedHeight = libraryViewModel.statsCardHeightPx
        Log.i("HomeScroll", "stats card placeholder=$cachedHeight")
        if (cachedHeight > 0) {
            val params = composeView.layoutParams
            if (params != null) {
                params.height = cachedHeight
                composeView.layoutParams = params
            } else {
                composeView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    cachedHeight
                )
            }
        }
        // Once the layout settles, remember the rendered height for next time.
        // Store even when detached — the height is a real layout result and
        // must survive quick in/out navigation.
        var reportTask: Runnable? = null
        composeView.addOnLayoutChangeListener { _, _, height, _, _, _, _, _, _ ->
            if (height > 0) {
                reportTask?.let { composeView.removeCallbacks(it) }
                val view = composeView
                reportTask = Runnable {
                    libraryViewModel.statsCardHeightPx = view.height
                    Log.i("HomeScroll", "stats card report height=${view.height}")
                }
                composeView.postDelayed(reportTask, 200)
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
