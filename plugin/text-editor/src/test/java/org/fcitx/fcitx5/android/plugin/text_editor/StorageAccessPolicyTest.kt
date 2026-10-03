package org.fcitx.fcitx5.android.plugin.text_editor

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageAccessPolicyTest {
    @Test fun modernAndroidRequiresManagerEvenWithLegacyWrite() {
        assertEquals(StorageAccessRequirement.MANAGE_ALL_FILES,
            StorageAccessPolicy.requirement(30, false, true))
        assertEquals(StorageAccessRequirement.MANAGE_ALL_FILES,
            StorageAccessPolicy.requirement(37, false, false))
    }

    @Test fun managerGrantUnlocksModernStorageWithoutLegacyPermission() {
        assertEquals(StorageAccessRequirement.NONE,
            StorageAccessPolicy.requirement(37, true, false))
    }

    @Test fun legacyDevicesUseRuntimeWriteGrant() {
        assertEquals(StorageAccessRequirement.LEGACY_WRITE,
            StorageAccessPolicy.requirement(29, true, false))
        assertEquals(StorageAccessRequirement.NONE,
            StorageAccessPolicy.requirement(23, false, true))
    }
}
