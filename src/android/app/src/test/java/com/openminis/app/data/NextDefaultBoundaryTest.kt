package com.openminis.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NextDefaultBoundaryTest {
    @Test
    fun `next graph paths remain isolated from legacy files`() {
        val files = File("/tmp/openminis-next-boundary")
        assertNotEquals(File(files, "openminis.db").canonicalFile, NextDataRoot.databaseFile(files).canonicalFile)
        assertEquals("openminis-next", NextDataRoot.root(files).name)
        assertEquals("openminis-next.db", NextDataRoot.databaseFile(files).name)
    }
}
