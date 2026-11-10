package org.librehu.core.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class LoggedExecutorTest {
    @Test
    fun anErrorOfATaskIsLoggedNotLost() {
        val logged = CopyOnWriteArrayList<String>()
        val e = LoggedExecutor("test", { logged += it })
        e.execute { throw IllegalStateException("frame 10 not handled") }
        // One thread: the error is logged before the next task starts.
        e.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(logged.single(), logged.single().contains("IllegalStateException: frame 10 not handled"))
        assertEquals(1, e.errors)
        e.shutdownNow()
    }

    @Test
    fun theSameErrorAgainIsCountedNotLoggedAgain() {
        val logged = CopyOnWriteArrayList<String>()
        val e = LoggedExecutor("test", { logged += it })
        val bad = Runnable { throw IllegalArgumentException("bad value") }
        repeat(5) { e.execute(bad) }
        e.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(1, logged.size)
        assertEquals(5, e.errors)
        e.shutdownNow()
    }

    @Test
    fun aPeriodicTaskThatThrowsIsLogged() {
        val logged = CopyOnWriteArrayList<String>()
        val e = LoggedExecutor("test", { logged += it })
        var runs = 0
        val f = e.scheduleWithFixedDelay({ if (++runs == 3) throw RuntimeException("third run") }, 0, 5, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 2_000
        while (!f.isDone && System.currentTimeMillis() < deadline) Thread.sleep(5)
        e.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(f.isDone)
        assertTrue(logged.single().contains("third run"))
        e.shutdownNow()
    }
}
