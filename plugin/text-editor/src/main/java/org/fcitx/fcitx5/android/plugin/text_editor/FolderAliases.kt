/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context

/**
 * 文件夹别名存储：把用户给某个目录起的别名按「绝对路径 → 别名」存放于 APP 私有数据中。
 *
 * 别名只影响列表显示，不改动文件系统、不写入被浏览的目录，删除别名即恢复原名显示。
 * 独立的 prefs 文件避免与编辑器/主题配置混在一起。
 */
internal object FolderAliases {
    private const val PREFS_NAME = "folder_aliases"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取别名；未设置或为空白时返回 null。 */
    fun get(context: Context, path: String): String? =
        prefs(context).getString(path, null)?.takeIf { it.isNotBlank() }

    /** 保存别名；传 null 或空白表示删除该目录的别名。 */
    fun set(context: Context, path: String, alias: String?) {
        val editor = prefs(context).edit()
        if (alias.isNullOrBlank()) editor.remove(path) else editor.putString(path, alias.trim())
        editor.apply()
    }
}
