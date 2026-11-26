package tv.scenichome.launcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReadsTest {
    @Test
    fun sortingTheSameTextSearchReusesPagesButServerSortedBrowseReadsAgain() = runBlocking {
        val recent = RecentReads<Pair<String, Int>, TitlePage>(8, 60) { 0L }
        val query = Lookup(null, "Interstellar", emptySet(), emptySet(), null, null, SearchOrder.DEFAULT, 0)
        var calls = 0
        suspend fun loadTwoPages(key: Lookup) {
            for (page in 1..2) key.readPage(page, recent) {
                calls++
                TitlePage(emptyList(), 2)
            }
        }
        loadTwoPages(query)
        loadTwoPages(query.copy(order = SearchOrder.RANK, years = setOf(2014)))
        assertEquals(2, calls)
        loadTwoPages(query.copy(query = "Arrival"))
        assertEquals(4, calls)
        loadTwoPages(query.copy(query = ""))
        loadTwoPages(query.copy(query = "", order = SearchOrder.RANK))
        assertEquals(8, calls)
    }

    @Test
    fun expiresEvictsAndRetriesWithoutKeepingFailures() = runBlocking {
        var now = 0L
        var calls = 0
        val pages = RecentReads<String, Int>(2, 60) { now }
        suspend fun read(key: String) = pages.get(key) { ++calls }

        assertEquals(1, read("page 1"))
        assertEquals(2, read("page 2"))
        now = 59
        assertEquals(1, read("page 1"))
        assertEquals(3, read("page 3"))
        assertEquals(4, read("page 2"))
        now = 119
        assertEquals(5, read("page 2"))
        val failure = runCatching { pages.get("retry") { error("offline") } }
        assertEquals("offline", failure.exceptionOrNull()?.message)
        assertEquals(6, read("retry"))
    }

    @Test
    fun concurrentReadersShareWorkAndInvalidationWinsOverAnOldReply() = runBlocking {
        val reads = RecentReads<Int, String>(1, 30) { 0L }
        val response = CompletableDeferred<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { reads.get(1) { response.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { reads.get(1) { error("duplicate request") } }
        reads.clear()
        assertEquals("new session", reads.get(1) { "new session" })
        response.complete("old session")
        assertEquals("old session", first.await())
        assertEquals("old session", second.await())
        assertEquals("new session", reads.get(1) { error("old reply replaced fresh data") })
    }
}
