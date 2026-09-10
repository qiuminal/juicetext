/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal object ManualTheme {
    private const val PREFS_NAME = "text_editor"
    private const val PREF_DARK_MODE = "dark_mode"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_DARK_MODE, true)

    fun apply(context: Context) {
        val target = mode(isDark(context))
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    fun toggle(context: Context): Boolean {
        val dark = !isDark(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DARK_MODE, dark)
            .apply()
        AppCompatDelegate.setDefaultNightMode(mode(dark))
        return dark
    }

    fun mode(dark: Boolean): Int = if (dark) {
        AppCompatDelegate.MODE_NIGHT_YES
    } else {
        AppCompatDelegate.MODE_NIGHT_NO
    }
}
