package com.openminis.app.data

import java.io.File

/**
 * Stable names for the opt-in Next data boundary.
 *
 * This object is deliberately independent of Android Context and Room. It only
 * defines names and deterministic paths so runtime/backup code can opt in one
 * target at a time without renaming the existing database or migrating all
 * application data.
 */
object NextDataRoot {
    const val DIRECTORY_NAME = "openminis-next"
    const val DATABASE_NAME = "openminis-next.db"

    const val RUNTIME_DIRECTORY_NAME = "runtime"
    const val RUNTIME_TREE_FILE_NAME = "session-tree.json"

    const val BACKUP_DIRECTORY_NAME = "backups"
    const val BACKUP_STAGING_DIRECTORY_NAME = "backup-staging"
    const val BACKUP_HISTORY_DIRECTORY_NAME = "backup-history"
    const val BACKUP_HISTORY_FILE_NAME = "records.json"
    const val RESTORE_VERSIONS_DIRECTORY_NAME = "restored-versions"
    const val RESTORE_CURRENT_POINTER_NAME = "restored-current"

    fun root(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

    fun databaseFile(filesDir: File): File = File(root(filesDir), DATABASE_NAME)

    fun runtimeDirectory(filesDir: File): File =
        File(root(filesDir), RUNTIME_DIRECTORY_NAME)

    fun runtimeTreeFile(filesDir: File): File =
        File(runtimeDirectory(filesDir), RUNTIME_TREE_FILE_NAME)

    fun backupDirectory(filesDir: File): File =
        File(root(filesDir), BACKUP_DIRECTORY_NAME)

    fun backupStagingDirectory(filesDir: File): File =
        File(root(filesDir), BACKUP_STAGING_DIRECTORY_NAME)

    fun backupStagingDirectory(filesDir: File, backupId: String): File {
        requireSafeSegment(backupId, "backupId")
        return File(backupStagingDirectory(filesDir), backupId)
    }

    fun backupHistoryFile(filesDir: File): File =
        File(File(root(filesDir), BACKUP_HISTORY_DIRECTORY_NAME), BACKUP_HISTORY_FILE_NAME)

    fun restoredVersionsDirectory(filesDir: File): File =
        File(root(filesDir), RESTORE_VERSIONS_DIRECTORY_NAME)

    fun restoredVersionDirectory(filesDir: File, versionId: String): File {
        requireSafeSegment(versionId, "versionId")
        return File(restoredVersionsDirectory(filesDir), versionId)
    }

    fun restoredCurrentPointerFile(filesDir: File): File =
        File(root(filesDir), RESTORE_CURRENT_POINTER_NAME)

    private fun requireSafeSegment(value: String, name: String) {
        require(value.isNotBlank() && value != "." && value != "..") {
            "$name must be a non-empty path segment"
        }
        require('/' !in value && '\\' !in value && ".." !in value) {
            "$name contains unsafe path characters"
        }
    }
}
