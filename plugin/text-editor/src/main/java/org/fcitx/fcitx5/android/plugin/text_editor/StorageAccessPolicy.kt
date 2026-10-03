package org.fcitx.fcitx5.android.plugin.text_editor

internal enum class StorageAccessRequirement { NONE, MANAGE_ALL_FILES, LEGACY_WRITE }

internal object StorageAccessPolicy {
    fun requirement(sdk: Int, managerGranted: Boolean, writeGranted: Boolean): StorageAccessRequirement =
        if (sdk >= 30) {
            if (managerGranted) StorageAccessRequirement.NONE else StorageAccessRequirement.MANAGE_ALL_FILES
        } else {
            if (writeGranted) StorageAccessRequirement.NONE else StorageAccessRequirement.LEGACY_WRITE
        }
}
