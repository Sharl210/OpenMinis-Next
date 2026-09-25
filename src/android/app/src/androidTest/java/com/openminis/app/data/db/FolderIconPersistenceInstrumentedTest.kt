package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.agent.SoulIcon
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room persistence coverage for the group identity editor's icon field.
 * The icon is intentionally tested as a stored token: image normalization is
 * owned by [SoulIcon], while this test proves the DAO survives a read boundary.
 */
@RunWith(AndroidJUnit4::class)
class FolderIconPersistenceInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun iconUpdatePersistsEmojiAndDataUriAndReset() = runBlocking {
        val folder = FolderEntity(
            id = "folder-icon-test",
            name = "Work",
            createdAt = 1L,
            updatedAt = 1L,
        )
        dao.insertFolder(folder)

        dao.updateFolderIdentity(
            id = folder.id,
            name = "Work renamed",
            description = "notes",
            icon = "⚡",
            updatedAt = 2L,
        )
        assertEquals("⚡", dao.getFolder(folder.id)?.icon)
        assertEquals("Work renamed", dao.getFolder(folder.id)?.name)

        val pngDataUri = SoulIcon.DATA_URI_PREFIX + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        dao.updateFolderIdentity(
            id = folder.id,
            name = "Work renamed",
            description = "notes",
            icon = pngDataUri,
            updatedAt = 3L,
        )
        assertEquals(pngDataUri, dao.getFolder(folder.id)?.icon)

        dao.updateFolderIdentity(
            id = folder.id,
            name = "Work renamed",
            description = "notes",
            icon = null,
            updatedAt = 4L,
        )
        assertNull(dao.getFolder(folder.id)?.icon)
    }
}
