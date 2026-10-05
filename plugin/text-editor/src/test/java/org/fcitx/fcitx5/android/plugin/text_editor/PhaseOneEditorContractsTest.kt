/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PhaseOneEditorContractsTest {

    @Test
    fun `default text size is applied only after explicit option selection`() {
        assertFalse(EditorOptionsActivity.shouldApplyDefaultTextSize(7L, 7L))
        assertTrue(EditorOptionsActivity.shouldApplyDefaultTextSize(7L, 8L))
    }

    @Test
    fun `dirty tracking is constant time and resets after successful save`() {
        val state = EditorDirtyState()

        assertFalse(state.isDirty)
        state.recordEdit()
        assertTrue(state.isDirty)
        state.markPersisted()
        assertFalse(state.isDirty)
        state.recordEdit()
        assertTrue(state.isDirty)
    }

    @Test
    fun `discard closes without saving and always deletes draft`() {
        assertEquals(
            PendingEditsEffect(
                continueClosing = true,
                saveBeforeClosing = false,
                deleteDraft = true,
            ),
            PendingEditsPolicy.effect(PendingEditsChoice.DISCARD),
        )
    }

    @Test
    fun `cancel preserves editor and draft while save deletes stale draft`() {
        assertEquals(
            PendingEditsEffect(false, false, false),
            PendingEditsPolicy.effect(PendingEditsChoice.CANCEL),
        )
        assertEquals(
            PendingEditsEffect(true, true, true),
            PendingEditsPolicy.effect(PendingEditsChoice.SAVE),
        )
    }

    @Test
    fun `draft is restored only when it exists and is not older than source`() {
        assertFalse(PendingEditsPolicy.shouldRestoreDraft(false, 20, 10))
        assertFalse(PendingEditsPolicy.shouldRestoreDraft(true, 9, 10))
        assertTrue(PendingEditsPolicy.shouldRestoreDraft(true, 10, 10))
        assertTrue(PendingEditsPolicy.shouldRestoreDraft(true, 11, 10))
    }

    @Test
    fun `directory opened during creation is not loaded again on first resume`() {
        val coordinator = DirectoryLoadCoordinator()

        assertTrue(coordinator.onDirectoryOpened())
        assertFalse(coordinator.onResume(hasCurrentDirectory = true))
        assertTrue(coordinator.onResume(hasCurrentDirectory = true))
    }

    @Test
    fun `resume without a current directory never requests a load`() {
        val coordinator = DirectoryLoadCoordinator()

        assertFalse(coordinator.onResume(hasCurrentDirectory = false))
    }

    @Test
    fun `binary extension wins over misleading text MIME`() {
        assertEquals(
            TextFileVerdict.BINARY,
            TextFileClassifier.classify("archive.ZIP", "text/plain"),
        )
    }

    @Test
    fun `known text extension wins over generic MIME`() {
        assertEquals(
            TextFileVerdict.TEXT,
            TextFileClassifier.classify("settings.JSON", "application/octet-stream"),
        )
    }

    @Test
    fun `unknown type requires sniffing and NUL marks binary`() {
        assertEquals(
            TextFileVerdict.NEEDS_SNIFFING,
            TextFileClassifier.classify("README", "application/octet-stream"),
        )
        assertEquals(TextFileVerdict.TEXT, TextFileClassifier.sniff("hello\n".toByteArray()))
        assertEquals(TextFileVerdict.BINARY, TextFileClassifier.sniff(byteArrayOf(1, 0, 2)))
    }

    @Test
    fun `default editor text size is bounded`() {
        assertEquals(8, EditorOptionsActivity.clampTextSize(1))
        assertEquals(14, EditorOptionsActivity.clampTextSize(14))
        assertEquals(32, EditorOptionsActivity.clampTextSize(99))
    }

    @Test
    fun `large YAML keeps its grammar and starts with one bounded page`() {
        val size = 12_070_870L

        assertTrue(TextFileSupport.isLargeFile(size))
        assertEquals("source.yaml", TextFileSupport.detectScopeName("PY_c.dict.yaml"))
        assertEquals(
            TextFileSupport.LARGE_FILE_FIRST_PAGE_BYTES,
            TextFileSupport.largeFileInitialPageBytes(size),
        )
        assertEquals(151, TextFileSupport.largeFileInitialPageBytes(151))
        assertEquals(
            TextFileSupport.LARGE_FILE_FIRST_PAGE_BYTES,
            TextFileSupport.largeFileInitialPageBytes(-1),
        )
    }

    @Test
    fun `new text file name avoids collisions case-insensitively`() {
        assertEquals("untitled.txt", NewFileNaming.uniqueName("untitled", "txt", emptySet()))
        assertEquals("note.txt", NewFileNaming.uniqueName("note", ".txt", emptySet()))
        assertEquals(
            "untitled 2.txt",
            NewFileNaming.uniqueName("untitled", "txt", setOf("untitled.txt")),
        )
        assertEquals(
            "untitled 3.txt",
            NewFileNaming.uniqueName("untitled", "txt", setOf("UNTITLED.TXT", "Untitled 2.txt")),
        )
    }

    @Test
    fun `new folder name avoids collisions case-insensitively`() {
        assertEquals("新建文件夹", NewFileNaming.uniqueDirectoryName("新建文件夹", emptySet()))
        assertEquals(
            "新建文件夹 2",
            NewFileNaming.uniqueDirectoryName("新建文件夹", setOf("新建文件夹")),
        )
        assertEquals(
            "New folder 3",
            NewFileNaming.uniqueDirectoryName("New folder", setOf("new folder", "New Folder 2")),
        )
    }

    @Test
    fun `markdown extension selects markdown scope`() {
        assertEquals("text.html.markdown", TextFileSupport.detectScopeName("README.md"))
        assertEquals("text.html.markdown", TextFileSupport.detectScopeName("guide.markdown"))
    }

    @Test
    fun `markdown grammar avoids variable-length look-behind unsupported on Android`() {
        val grammar = File(
            "src/main/extra-assets/textmate/grammars/markdown.tmLanguage.json"
        ).readText()

        assertTrue(grammar.contains("\"name\": \"markup.strikethrough.markdown\""))
        assertFalse(grammar.contains("(?<=\\\\w~~)"))
        assertFalse(grammar.contains("(?<=_\\\\1)"))
    }

    @Test
    fun `manual theme cycles day night and system`() {
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            ManualTheme.mode(ManualTheme.MODE_DAY),
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            ManualTheme.mode(ManualTheme.MODE_NIGHT),
        )
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            ManualTheme.mode(ManualTheme.MODE_SYSTEM),
        )
    }

    @Test
    fun `unsupported classification takes priority over file size`() {
        assertEquals(
            FileOpenRejection.UNSUPPORTED,
            FileOpenPolicy.rejection(isSupported = false, size = 200, maxSize = 100),
        )
        assertEquals(
            FileOpenRejection.TOO_LARGE,
            FileOpenPolicy.rejection(isSupported = true, size = 200, maxSize = 100),
        )
        assertEquals(null, FileOpenPolicy.rejection(true, 100, 100))
    }

    @Test
    fun `bak needs content sniffing before selecting its icon`() {
        assertEquals(
            TextFileVerdict.NEEDS_SNIFFING,
            TextFileClassifier.classify("notes.bak", "application/octet-stream"),
        )
        assertEquals(TextFileVerdict.TEXT, TextFileClassifier.sniff("backup text".toByteArray()))
        assertEquals(TextFileVerdict.BINARY, TextFileClassifier.sniff(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun `external change requires stable repeated provider metadata`() {
        val baseline = ExternalFileSnapshot(lastModified = 100, size = 20)

        assertFalse(
            ExternalChangePolicy.isConfirmedChange(
                baseline,
                ExternalFileSnapshot(lastModified = 101, size = 20),
                ExternalFileSnapshot(lastModified = 100, size = 20),
            )
        )
        assertTrue(
            ExternalChangePolicy.isConfirmedChange(
                baseline,
                ExternalFileSnapshot(lastModified = 101, size = 20),
                ExternalFileSnapshot(lastModified = 101, size = 20),
            )
        )
    }

    @Test
    fun `unknown timestamps do not cause external change false positives`() {
        assertFalse(
            ExternalChangePolicy.isConfirmedChange(
                ExternalFileSnapshot(lastModified = 0, size = 20),
                ExternalFileSnapshot(lastModified = 42, size = 20),
                ExternalFileSnapshot(lastModified = 42, size = 20),
            )
        )
        assertTrue(
            ExternalChangePolicy.isConfirmedChange(
                ExternalFileSnapshot(lastModified = 0, size = 20),
                ExternalFileSnapshot(lastModified = 0, size = 21),
                ExternalFileSnapshot(lastModified = 0, size = 21),
            )
        )
    }

    @Test
    fun `return and refresh paths never require blocking progress`() {
        assertFalse(
            DirectoryLoadPresentation.shouldShowBlockingProgress(
                hasSnapshot = true,
                backgroundRefresh = false,
            )
        )
        assertFalse(
            DirectoryLoadPresentation.shouldShowBlockingProgress(
                hasSnapshot = false,
                backgroundRefresh = true,
            )
        )
        assertTrue(
            DirectoryLoadPresentation.shouldShowBlockingProgress(
                hasSnapshot = false,
                backgroundRefresh = false,
            )
        )
    }

    @Test
    fun `file metadata line uses requested time and compact size format`() {
        val zone = java.time.ZoneId.of("UTC")
        val instant = java.time.LocalDateTime.of(2026, 8, 28, 5, 34)
            .atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            "26-08-28 05:34 27.5KB",
            FileEntryMetadataFormatter.formatLine(instant, 27.5.times(1024).toLong(), zone),
        )
        assertEquals("27B", FileEntryMetadataFormatter.formatSize(27))
        assertEquals(null, FileEntryMetadataFormatter.formatModifiedTime(0, zone))
    }

    @Test
    fun `resolved icon is a pure function of the pre-read verdict`() {
        assertEquals(
            RegularFileIcon.EDITABLE_TEXT,
            FileIconPolicy.regularFileIcon(TextFileVerdict.TEXT),
        )
        assertEquals(RegularFileIcon.UNKNOWN, FileIconPolicy.regularFileIcon(TextFileVerdict.BINARY))
        assertEquals(RegularFileIcon.UNKNOWN, FileIconPolicy.regularFileIcon(null))
    }

    @Test
    fun `all ambiguous extensions are sniffed rather than only bak`() {
        listOf("notes.BAK", "README", "archive.unknown").forEach { name ->
            assertEquals(
                TextFileVerdict.NEEDS_SNIFFING,
                TextFileClassifier.classify(name, "application/octet-stream"),
            )
        }
        assertEquals(TextFileVerdict.TEXT, TextFileClassifier.classify("source.md", null))
        assertEquals(TextFileVerdict.BINARY, TextFileClassifier.classify("image.png", "text/plain"))
    }

    @Test
    fun `directory snapshot round trips and rejects a different root`() {
        val entries = listOf(
            DirectorySnapshotEntry(
                "content://root/a", "a b.txt", "text/plain", 42, 1_788_000_000_000,
                false, TextFileVerdict.TEXT,
            ),
            DirectorySnapshotEntry(
                "content://root/backup", "notes.bak", "application/octet-stream", 84,
                1_788_000_000_001, false, TextFileVerdict.TEXT,
            ),
            DirectorySnapshotEntry("content://root/folder", "资料", null, -1, 0, true),
        )
        val encoded = DirectorySnapshotCodec.encode("content://root", entries)

        assertEquals(entries, DirectorySnapshotCodec.decode(encoded, "content://root"))
        assertEquals(null, DirectorySnapshotCodec.decode(encoded, "content://other"))
    }

    @Test
    fun `directory snapshot is bounded for safe cold start`() {
        val entries = (0..DirectorySnapshotCodec.MAX_ENTRIES + 20).map {
            DirectorySnapshotEntry("content://root/$it", "$it", null, it.toLong(), 0, false)
        }

        assertEquals(
            DirectorySnapshotCodec.MAX_ENTRIES,
            DirectorySnapshotCodec.decode(
                DirectorySnapshotCodec.encode("content://root", entries),
                "content://root",
            )?.size,
        )
    }

    @Test
    fun `provider IO policy rejects the main thread`() {
        val mainThreadId = Thread.currentThread().id
        val policy = ProviderIoThreadPolicy(mainThreadId)

        try {
            policy.checkCallAllowed()
            fail("main-thread provider I/O must be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        policy.checkCallAllowed(currentThreadId = mainThreadId + 1)
    }

    @Test
    fun `release tags compare numerically not lexicographically`() {
        // A string compare would rank "0.3.9" above "0.3.10"; segment-wise numeric comparison
        // must not, or the updater would offer a downgrade.
        assertTrue(UpdateChecker.isNewerVersion("0.3.10", "0.3.9"))
        assertFalse(UpdateChecker.isNewerVersion("0.3.9", "0.3.10"))
        assertTrue(UpdateChecker.isNewerVersion("0.4", "0.3.4"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewerVersion("0.3.4", "0.3.4"))
        // Missing segments count as zero, so a shorter tag is not spuriously "newer".
        assertFalse(UpdateChecker.isNewerVersion("0.3", "0.3.0"))
        assertTrue(UpdateChecker.isNewerVersion("0.3.1", "0.3"))
    }

    @Test
    fun `version normalisation strips the tag prefix and suffixes`() {
        assertEquals("0.3.4", UpdateChecker.normalizeVersion("v0.3.4"))
        assertEquals("0.3.4", UpdateChecker.normalizeVersion("V0.3.4"))
        assertEquals("0.3.4", UpdateChecker.normalizeVersion("0.3.4"))
        assertEquals("0.4.0", UpdateChecker.normalizeVersion("v0.4.0-rc1"))
        assertEquals("0.4.0", UpdateChecker.normalizeVersion("0.4.0+build7"))
    }

    @Test
    fun `non numeric versions never report an update`() {
        // A malformed feed must fail closed: offering a bogus update is worse than staying quiet.
        assertFalse(UpdateChecker.isNewerVersion("nightly", "0.3.4"))
        assertFalse(UpdateChecker.isNewerVersion("0.3.5", "nightly"))
    }

    @Test
    fun `hiding the UI does not disable syntax highlighting`() {
        assertFalse(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_BACKGROUND))
        assertTrue(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_MODERATE))
        assertTrue(LowMemoryPolicy.shouldDegrade(LowMemoryPolicy.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun `background trims are undone when the editor returns to the foreground`() {
        assertTrue(LowMemoryPolicy.shouldRestoreOnResume(LowMemoryPolicy.TRIM_MEMORY_BACKGROUND))
        assertTrue(LowMemoryPolicy.shouldRestoreOnResume(LowMemoryPolicy.TRIM_MEMORY_MODERATE))
        assertTrue(LowMemoryPolicy.shouldRestoreOnResume(LowMemoryPolicy.TRIM_MEMORY_COMPLETE))
        assertFalse(LowMemoryPolicy.shouldRestoreOnResume(LowMemoryPolicy.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(LowMemoryPolicy.shouldRestoreOnResume(LowMemoryPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
        assertFalse(LowMemoryPolicy.shouldRestoreOnResume(0))
    }
}
