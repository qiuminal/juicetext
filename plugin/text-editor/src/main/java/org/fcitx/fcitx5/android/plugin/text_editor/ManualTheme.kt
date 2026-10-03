/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 暗黑模式管理：三档「白天 / 黑夜 / 跟随系统」循环切换，缺省为白天。
 *
 * - 存储：SharedPreferences("text_editor") 的 "night_mode"（0=白天,1=黑夜,2=跟随系统）。
 * - 旧版布尔键 "dark_mode" 若存在则迁移（true→黑夜, false→白天）。
 * - 应用：映射到 MODE_NIGHT_NO / MODE_NIGHT_YES / MODE_NIGHT_FOLLOW_SYSTEM。
 */
internal object ManualTheme {
    const val MODE_DAY = 0
    const val MODE_NIGHT = 1
    const val MODE_SYSTEM = 2

    private const val PREFS_NAME = "text_editor"
    private const val PREF_NIGHT_MODE = "night_mode"
    private const val PREF_DARK_MODE = "dark_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取已保存的档位（默认白天），必要时从旧版布尔值迁移。 */
    fun currentMode(context: Context): Int {
        val prefs = prefs(context)
        if (prefs.contains(PREF_NIGHT_MODE)) return prefs.getInt(PREF_NIGHT_MODE, MODE_DAY)
        // 迁移旧版 dark_mode 布尔值；从未设置过则保持缺省白天。
        if (prefs.contains(PREF_DARK_MODE)) {
            val migrated = if (prefs.getBoolean(PREF_DARK_MODE, false)) MODE_NIGHT else MODE_DAY
            prefs.edit().putInt(PREF_NIGHT_MODE, migrated).remove(PREF_DARK_MODE).apply()
            return migrated
        }
        return MODE_DAY
    }

    /** 把档位映射为 AppCompatDelegate 的夜间模式常量。 */
    fun mode(m: Int): Int = when (m) {
        MODE_DAY -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** 启动时按存储档位应用。 */
    fun apply(context: Context) {
        val target = mode(currentMode(context))
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    /** 保存并应用指定档位。 */
    fun setMode(context: Context, m: Int) {
        prefs(context).edit().putInt(PREF_NIGHT_MODE, m).remove(PREF_DARK_MODE).apply()
        AppCompatDelegate.setDefaultNightMode(mode(m))
    }

    /** 循环切换：白天 → 黑夜 → 跟随系统 → 白天，返回切换后的档位。 */
    fun cycle(context: Context): Int {
        val next = when (currentMode(context)) {
            MODE_DAY -> MODE_NIGHT
            MODE_NIGHT -> MODE_SYSTEM
            else -> MODE_DAY
        }
        setMode(context, next)
        return next
    }

    /** 当前档位对应的图标资源。 */
    fun iconRes(mode: Int): Int = when (mode) {
        MODE_DAY -> R.drawable.ic_theme_light
        MODE_NIGHT -> R.drawable.ic_theme_dark
        else -> R.drawable.ic_theme_system
    }
}
