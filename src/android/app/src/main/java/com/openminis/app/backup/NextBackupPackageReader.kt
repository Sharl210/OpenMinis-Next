package com.openminis.app.backup

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Read-side identity gate for the opt-in Next backup package.
 *
 * This intentionally does not share [BackupPackageReader]'s legacy `minisbak`
 * acceptance path. Payload/integrity work can be added after the namespace and
 * data boundary are stable.
 */
class NextBackupPackageReader(private val root: File) {
    private val manifestFile = File(root, "manifest.json")

    fun readManifest(): BackupManifest {
        if (!manifestFile.isFile) {
            throw BackupException("Next package has no manifest.json")
        }
        return NextBackupFormat.decodeManifest(
            manifestFile.readText(StandardCharsets.UTF_8),
        )
    }
}
