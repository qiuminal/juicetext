/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.net.Uri
import kotlinx.coroutines.Job

class EditorTab(
    var uri: Uri,
    var displayName: String,
    var editor: ArrowTabCodeEditor? = null,
    var originalText: String = "",
    var isLargeFile: Boolean = false,
    var isDirty: Boolean = false,
    var loadedLastModified: Long = 0L,
    var loadedFileSize: Long = -1L,
) {
    /** Monotonic edit version; dirty checks must stay O(1), never compare the whole document. */
    internal var editGeneration: Long = 0L
    internal var savedGeneration: Long = 0L
    internal var suppressDirtyTracking: Boolean = false
    internal var suppressDraft: Boolean = false
    internal var draftJob: Job? = null

    internal var largeFilePager: TextFileEditActivity.LargeFilePager? = null
    internal var largeFileFullyLoaded: Boolean = false
    internal var largeFileLoadInFlight: Boolean = false
}
