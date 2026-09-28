package com.openminis.app.data

import android.content.Context
import androidx.room.RoomDatabase
import com.openminis.app.data.db.NextAppDatabase
import com.openminis.app.data.db.NextDatabaseProvider
import com.openminis.app.data.repository.NextFolderRepository
import com.openminis.app.data.repository.NextMessageRepository
import com.openminis.app.data.repository.NextSessionRepository
import com.openminis.app.data.repository.NextSubtreeDeletionRepository

/** Next-only persistence graph; constructing it never opens legacy stores. */
class NextAppGraph private constructor(
    val database: NextAppDatabase,
    val sessions: NextSessionRepository,
    val messages: NextMessageRepository,
    val folders: NextFolderRepository,
    val subtreeDeletion: NextSubtreeDeletionRepository,
) {
    fun close() {
        database.close()
        NextDatabaseProvider.closeForProcessTeardown()
    }

    companion object {
        fun open(context: Context): NextAppGraph {
            val database = NextDatabaseProvider.getInstance(context)
            return NextAppGraph(
                database = database,
                sessions = NextSessionRepository(database.sessionDao()),
                messages = NextMessageRepository(database.messageDao()),
                folders = NextFolderRepository(database.folderDao()),
                subtreeDeletion = NextSubtreeDeletionRepository(database),
            )
        }
    }
}
