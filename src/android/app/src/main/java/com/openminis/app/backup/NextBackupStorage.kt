package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import java.io.File

/**
 * Next-only backup locations. Existing `.minisbak` storage remains untouched;
 * callers opt into this object when they are ready to write/read the Next
 * package boundary.
 */
object NextBackupStorage {
    fun packagesDirectory(filesDir: File): File = NextDataRoot.backupDirectory(filesDir)

    fun stagingDirectory(filesDir: File): File = NextDataRoot.backupStagingDirectory(filesDir)

    fun stagingDirectory(filesDir: File, backupId: String): File =
        NextDataRoot.backupStagingDirectory(filesDir, backupId)

    fun historyFile(filesDir: File): File = NextDataRoot.backupHistoryFile(filesDir)
}
