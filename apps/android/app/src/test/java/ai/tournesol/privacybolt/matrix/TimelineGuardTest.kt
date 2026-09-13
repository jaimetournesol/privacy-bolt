package ai.tournesol.privacybolt.matrix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TimelineGuardTest {
    @Test fun initialCallbacksWaitForTheRoomToBeInstalled() {
        val guard = TimelineGuard()
        val visible = mutableListOf<String>()
        var installed = false
        val owner = guard.begin { }
        val listener = guard.listener<String>(owner) {
            assertTrue("A callback ran before its room was installed", installed)
            visible.add(it)
        }
        listener.onUpdate("first")
        listener.onUpdate("second")
        assertTrue(visible.isEmpty())
        assertTrue(listener.activate { installed = true })
        listener.onUpdate("third")
        assertEquals(listOf("first", "second", "third"), visible)
    }

    @Test fun supersededConstructionCannotInstallOrPublishItsBufferedUpdates() {
        val guard = TimelineGuard()
        var installed = false
        var delivered = false
        val owner = guard.begin { }
        val listener = guard.listener<String>(owner) { delivered = true }
        listener.onUpdate("old room")
        guard.begin { }
        assertFalse(listener.activate { installed = true })
        listener.onUpdate("late old room")
        assertFalse(installed)
        assertFalse(delivered)
    }

    @Test fun lateCallbackCannotReplaceTheNewRoomsMessages() {
        val guard = TimelineGuard()
        var visible = ""
        val alice = guard.begin { visible = "loading Alice" }
        val bob = guard.begin { visible = "loading Bob" }
        assertFalse(guard.update(alice) { visible = "Alice's private message" })
        assertEquals("loading Bob", visible)
        assertTrue(guard.update(bob) { visible = "Bob's message" })
        assertEquals("Bob's message", visible)
    }

    @Test fun invalidationRejectsAnAlreadyQueuedCallback() {
        val guard = TimelineGuard()
        val ready = CountDownLatch(1)
        val deliver = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var visible = "cached chat"
        try {
            val owner = guard.begin { }
            val callback = executor.submit<Boolean> {
                ready.countDown()
                check(deliver.await(3, TimeUnit.SECONDS))
                guard.update(owner) { visible = "late private message" }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            guard.begin { visible = "" }
            deliver.countDown()
            assertFalse(callback.get(3, TimeUnit.SECONDS))
            assertEquals("", visible)
        } finally { deliver.countDown(); executor.shutdownNow() }
    }
}
