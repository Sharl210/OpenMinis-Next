package com.openminis.app.data.repository

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.openminis.app.data.db.ChatDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The half of `conversation_query`'s paging that the tool-level tests cannot see.
 *
 * `AgentToolExecutorConversationQueryTest` drives a fake `ConversationTranscriptSource`,
 * which is the right level for the cursor arithmetic but means the SQL is never built.
 * Deleting the `OFFSET` from this statement, or the `sort_order` tie-breaker beside it,
 * leaves every one of those tests green — verified by mutation, not assumed. So the
 * statement itself is pinned here, by capturing what the repository hands the DAO.
 *
 * A JDK proxy rather than a hand-written fake, matching
 * `ChatExporterRuntimeTreeSourceTest`: `ChatDao` is a large Room interface and the
 * subject is one query, not a re-implementation of Room.
 */
class ChatRepositorySearchPagingTest {

    private class RecordingChatDao : InvocationHandler {
        var sql: String? = null
        var boundArgs: List<Any?> = emptyList()
        var argCount: Int = -1

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            this,
        ) as ChatDao

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val raw = args?.toList().orEmpty()
            // A suspend DAO method carries a trailing Continuation; this fake answers
            // synchronously, so the continuation is only stripped.
            val arguments =
                if (raw.lastOrNull() is kotlin.coroutines.Continuation<*>) raw.dropLast(1) else raw
            return when (method.name) {
                // Empty rows are enough: the statement is the subject, and an empty
                // result set keeps `extractTextForOffload` out of it.
                "runMessageSearchQuery" -> {
                    val query = arguments[0] as SupportSQLiteQuery
                    sql = query.sql
                    // The bound values are the point: the statement's TEXT is byte
                    // identical whether the offset is bound or silently dropped, so an
                    // assertion on `sql` alone cannot tell those two apart.
                    //
                    // Read by binding the query to a recording program rather than by
                    // asking it for a list of arguments — the `SimpleSQLiteQuery` on this
                    // classpath declares only `bindTo, bind, getSql, getArgCount`, with no
                    // argument accessor. `bindTo` is the canonical route and is
                    // version-independent.
                    val program = RecordingProgram()
                    query.bindTo(program)
                    boundArgs = program.values
                    argCount = query.argCount
                    emptyList<Any>()
                }
                "toString" -> "RecordingChatDao"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error(
                    "RecordingChatDao: unexpected ${method.name}(...). The search reached for a DAO " +
                        "method this fake does not model — model it explicitly instead of letting it " +
                        "pass silently.",
                )
            }
        }
    }

    /**
     * Records what a statement binds. [SupportSQLiteQuery.bindTo] walks the program
     * one value at a time, which is how the arguments are read here — the query class
     * itself exposes no list of them.
     */
    private class RecordingProgram : SupportSQLiteProgram {
        val values = mutableListOf<Any?>()

        override fun bindNull(index: Int) {
            values += null
        }

        override fun bindLong(index: Int, value: Long) {
            values += value
        }

        override fun bindDouble(index: Int, value: Double) {
            values += value
        }

        override fun bindString(index: Int, value: String) {
            values += value
        }

        override fun bindBlob(index: Int, value: ByteArray) {
            values += value
        }

        override fun clearBindings() = Unit

        override fun close() = Unit
    }

    private fun search(dao: RecordingChatDao, limit: Int = 4, offset: Int = 0) = runBlocking {
        ChatRepository(dao.dao).searchMessages(
            sessionIds = listOf("session-1"),
            keywords = listOf("needle"),
            limit = limit,
            startMs = null,
            endMs = null,
            offset = offset,
        )
    }

    @Test
    fun `the search statement pages in SQL rather than re-reading the top`() {
        val dao = RecordingChatDao()
        search(dao, limit = 4, offset = 7)

        val sql = requireNotNull(dao.sql) { "the search never reached the DAO" }
        assertTrue(
            "without OFFSET every page re-reads the same rows, which is the whole defect: $sql",
            sql.contains("OFFSET"),
        )
        assertTrue("the window still needs a limit: $sql", sql.contains("LIMIT"))
    }

    @Test
    fun `the row offset the caller asked for is the one bound to the statement`() {
        val dao = RecordingChatDao()
        search(dao, limit = 4, offset = 7)

        // Over-fetch is `limit * 3`; the offset follows it. Checking the tail rather
        // than a position keeps the assertion about the contract (last bound value is
        // the offset) instead of about how many WHERE clauses happen to be present.
        assertEquals(
            // Long, not Int: `bindTo` routes an Integer argument through bindLong, so
            // the recorded value is boxed as a Long. Asserting on Int here fails with
            // "expected [12, 7] but was [12, 7]" — identical text, different types —
            // so the element type is worth naming rather than discovering twice.
            "bound args must end with the over-fetch window and the caller's offset" +
                " [argCount=${dao.argCount}]",
            listOf(12L, 7L),
            dao.boundArgs.takeLast(2),
        )
    }

    @Test
    fun `the default offset reaches the statement as zero`() {
        val dao = RecordingChatDao()
        runBlocking {
            ChatRepository(dao.dao).searchMessages(
                sessionIds = listOf("session-1"),
                keywords = listOf("needle"),
                limit = 4,
                startMs = null,
                endMs = null,
            )
        }

        assertEquals(" [argCount=${dao.argCount}]", listOf(12L, 0L), dao.boundArgs.takeLast(2))
    }

    /**
     * The tie-breaker is not decoration. Rows written in the same millisecond all sort
     * equally under `created_at DESC`, and SQLite is free to order equal rows
     * differently between two statements — so a `LIMIT/OFFSET` walk over them can
     * re-serve a row it already served, or skip one, even with the offset arithmetic
     * perfectly correct. Page mode's own DAO query has carried
     * `sort_order ASC, created_at ASC` for exactly this reason; search did not.
     */
    @Test
    fun `the search statement orders by a stable key, not by timestamp alone`() {
        val dao = RecordingChatDao()
        search(dao)

        val sql = requireNotNull(dao.sql)
        assertTrue(
            "a LIMIT/OFFSET over created_at alone can repeat or skip same-millisecond rows: $sql",
            sql.contains("sort_order"),
        )
    }
}
