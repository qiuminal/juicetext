/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ItemEditorTabBinding

/**
 * Renders the horizontal tab strip.
 *
 * Styling is intentionally cheap: the two possible backgrounds are plain XML shapes referenced by
 * resource id (never re-inflated), and the active state only flips visibility flags plus the text
 * colour. That keeps [onBindViewHolder] allocation-free, which matters because the strip is
 * rebound whenever the user switches tabs or a tab's dirty state changes.
 */
class TabBarAdapter(
    private val tabs: List<EditorTab>,
    private val activeIndex: () -> Int,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit,
) : RecyclerView.Adapter<TabBarAdapter.TabVH>() {

    class TabVH(val binding: ItemEditorTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
        val binding = ItemEditorTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabVH(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: TabVH, position: Int) {
        val tab = tabs[position]
        val isActive = position == activeIndex()
        val binding = holder.binding
        val context = binding.root.context

        binding.tabName.text = tab.displayName
        // INVISIBLE (not GONE) reserves the dot's width, so a tab does not resize when it becomes
        // dirty or clean — that resize was part of the reported "tabs change width" behaviour.
        binding.tabDirtyDot.visibility = if (tab.isDirty) View.VISIBLE else View.INVISIBLE

        val root = binding.root
        if (isActive) {
            root.setBackgroundResource(R.drawable.bg_tab_active)
            root.alpha = 1f
            binding.tabName.setTextColor(
                resolveThemeColor(context, android.R.attr.textColorPrimary)
            )
            binding.tabIndicator.visibility = View.VISIBLE
            // The active tab must not show a leading separator against its neighbour.
            binding.tabDivider.visibility = View.INVISIBLE
        } else {
            root.setBackgroundResource(R.drawable.bg_tab_inactive)
            root.alpha = 0.75f
            binding.tabName.setTextColor(
                resolveThemeColor(context, android.R.attr.textColorSecondary)
            )
            binding.tabIndicator.visibility = View.INVISIBLE
            binding.tabDivider.visibility = View.VISIBLE
        }

        root.setOnClickListener { onTabClick(position) }
        binding.tabClose.setOnClickListener { onTabClose(position) }
    }

    private companion object {
        /** Resolves a theme attribute to a concrete colour for the current day/night configuration. */
        fun resolveThemeColor(context: android.content.Context, attr: Int): Int {
            val typed = android.util.TypedValue()
            val resolved = context.theme.resolveAttribute(attr, typed, true)
            return if (resolved && typed.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, typed.resourceId)
            } else {
                typed.data
            }
        }
    }
}
