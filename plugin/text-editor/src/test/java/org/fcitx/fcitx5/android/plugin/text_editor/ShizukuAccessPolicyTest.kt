package org.fcitx.fcitx5.android.plugin.text_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShizukuAccessPolicyTest {
    @Test fun acceptsSharedStorageRoot() {
        assertEquals("/storage/emulated/0", ShizukuAccessPolicy.validatePath("/storage/emulated/0").path)
    }

    @Test fun acceptsSharedStorage() {
        assertEquals("/storage/emulated/0/Android/data/example/files/a.yaml",
            ShizukuAccessPolicy.validatePath("/storage/emulated/0/Android/data/example/files/a.yaml").path)
    }

    @Test fun rejectsTraversalAndPrivatePaths() {
        listOf("/data/media/0/a", "/data/system/a", "relative", "/storage/emulated/0/../../a").forEach {
            assertThrows(IllegalArgumentException::class.java) { ShizukuAccessPolicy.validatePath(it) }
        }
    }

    @Test fun boundsBinderReads() {
        ShizukuAccessPolicy.validateRead(Long.MAX_VALUE, ShizukuAccessPolicy.CHUNK_BYTES)
        listOf(-1L to 1, 0L to 0, 0L to (ShizukuAccessPolicy.CHUNK_BYTES + 1)).forEach { (offset, length) ->
            assertThrows(IllegalArgumentException::class.java) { ShizukuAccessPolicy.validateRead(offset, length) }
        }
    }
}
