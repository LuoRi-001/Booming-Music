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

package com.mardous.booming.ui.component.preferences.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.R
import com.mardous.booming.core.model.HomeCardInfo
import com.mardous.booming.databinding.DialogRecyclerViewBinding
import com.mardous.booming.extensions.create
import com.mardous.booming.ui.adapters.preference.HomeCardInfoAdapter
import com.mardous.booming.util.HOME_CARDS
import com.mardous.booming.util.Preferences

class HomeCardsPreferenceDialog : DialogFragment() {

    private lateinit var adapter: HomeCardInfoAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogRecyclerViewBinding.inflate(layoutInflater)

        var homeCards = Preferences.homeCards
        if (savedInstanceState != null && savedInstanceState.containsKey(HOME_CARDS)) {
            homeCards = BundleCompat.getParcelableArrayList(
                savedInstanceState, HOME_CARDS, HomeCardInfo::class.java
            )!!
        }

        adapter = HomeCardInfoAdapter(homeCards.toMutableList())

        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = adapter
        adapter.attachToRecyclerView(binding.recyclerView)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_cards_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                updateHomeCards(adapter.items)
            }
            .setNeutralButton(R.string.reset_action, null)
            .create { dialog ->
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    adapter.items = Preferences.getDefaultHomeCardInfos().toMutableList()
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(HOME_CARDS, ArrayList(adapter.items))
    }

    private fun updateHomeCards(homeCards: List<HomeCardInfo>) {
        if (homeCards.any { it.visible }) {
            Preferences.homeCards = homeCards
        }
    }
}
