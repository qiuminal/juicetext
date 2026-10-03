/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Completion adapter that mirrors sora-editor's DefaultCompletionItemAdapter but uses
 * [R.layout.completion_result_item] and computes the item height from the current font scale.
 *
 * The library adapter returns a hard-coded 45dp item height, which is too small once the system font
 * is enlarged: the label and the secondary description line stop fitting, so the description ends up
 * flush with (or clipped by) the popup's bottom edge.
 */
class CompletionItemAdapter : EditorCompletionAdapter() {

    private var itemHeightPx = 0

    override fun getItemHeight(): Int {
        if (itemHeightPx > 0) return itemHeightPx
        val metrics = getContext().resources.displayMetrics
        val labelLine = lineHeight(metrics.scaledDensity * LABEL_SP)
        val descLine = lineHeight(metrics.scaledDensity * DESC_SP)
        val padding = (PADDING_TOP_DP + DESC_MARGIN_TOP_DP + PADDING_BOTTOM_DP) * metrics.density
        itemHeightPx = (labelLine + descLine + padding).toInt()
        return itemHeightPx
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup, isCurrent: Boolean): View {
        val view = convertView ?: LayoutInflater.from(getContext())
            .inflate(R.layout.completion_result_item, parent, false)
        val item = getItem(position)

        view.findViewById<TextView>(R.id.result_item_label).apply {
            text = item.label
            setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))
        }
        view.findViewById<TextView>(R.id.result_item_desc).apply {
            text = item.desc
            setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))
        }
        view.setTag(position)
        view.setBackgroundColor(
            if (isCurrent) getThemeColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT) else 0
        )
        view.findViewById<ImageView>(R.id.result_item_image).setImageDrawable(item.icon)

        return view
    }

    private fun lineHeight(textSizePx: Float): Float {
        val paint = Paint()
        paint.textSize = textSizePx
        val fm = paint.fontMetrics
        return fm.bottom - fm.top
    }

    companion object {
        private const val LABEL_SP = 15f
        private const val DESC_SP = 11f
        private const val PADDING_TOP_DP = 4f
        private const val DESC_MARGIN_TOP_DP = 3f
        private const val PADDING_BOTTOM_DP = 6f
    }
}
