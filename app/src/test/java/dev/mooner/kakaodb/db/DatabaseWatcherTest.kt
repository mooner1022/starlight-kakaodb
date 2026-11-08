package dev.mooner.kakaodb.db

import dev.mooner.kakaodb.db.DatabaseWatcher.withSettleCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseWatcherTest {

    private fun now() = System.nanoTime() / 1_000_000

    /** No waiting for the files to go quiet before a check (what the debounce did), but a last check once they do. */
    @Test
    fun `passes changes on at once, and checks once more when they settle`() = runBlocking {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val received = Collections.synchronizedList(arrayListOf<Long>())
        val job = launch(Dispatchers.Default) {
            changes.withSettleCheck(SETTLE).collect { received += now() }
        }
        while (changes.subscriptionCount.value == 0)
            delay(5)

        val first = now()
        changes.emit(Unit)
        delay(100)
        assertEquals(2, received.size, "the change, then the settle check: $received")
        assertTrue(received[0] - first < 20, "passed on at once: ${received[0] - first}ms")
        assertTrue(received[1] - first >= SETTLE, "settle check after ${SETTLE}ms: ${received[1] - first}ms")

        // A burst: each change passes, and a single settle check follows the last one.
        received.clear()
        repeat(4) {
            changes.emit(Unit)
            delay(15)
        }
        val last = now() - 15
        delay(150)
        assertEquals(5, received.size, "four changes and one settle check: $received")
        assertTrue(received.last() - last >= SETTLE - 5, "settle check after the last change: ${received.last() - last}ms")
        job.cancel()
    }

    private companion object {
        const val SETTLE = 50L
    }
}
