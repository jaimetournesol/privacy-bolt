package ai.tournesol.privacybolt.net

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionLeasesTest {
    @Test fun signoutWaitsForWorkerCleanupAndRejectsNewWork() = runBlocking {
        var allowed = true
        var stops = 0
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val pool = ConnectionLeases({ allowed }, {}, { stops++ })
        val worker = launch {
            pool.use {
                try { started.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); finishCleanup.await() }
                }
            }
        }
        started.await()
        allowed = false
        val drained = async { pool.drain() }
        cleaning.await()
        assertFalse(drained.isCompleted)
        assertNull(pool.acquire())
        finishCleanup.complete(Unit)
        drained.await()
        worker.join()
        assertEquals(1, stops)
    }

    @Test fun catchupCannotStopForegroundOrBackup() = runBlocking {
        var stops = 0
        val pool = ConnectionLeases({ true }, {}, { stops++ })
        val catchup = pool.acquire()!!
        val backup = pool.acquire()!!
        val foreground = pool.acquire()!!
        pool.release(catchup)
        pool.release(backup)
        assertEquals(0, stops)
        pool.release(foreground)
        assertEquals(1, stops)
    }

    @Test fun foregroundWaitsForInFlightShutdownThenRestarts() = runBlocking {
        val stopping = CompletableDeferred<Unit>()
        val finishStop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val pool = ConnectionLeases({ true }, { events.add("start") }, {
            events.add("stopping"); stopping.complete(Unit)
            finishStop.await(); events.add("stopped")
        })
        val worker = pool.acquire()!!
        val release = launch { pool.release(worker) }
        stopping.await()
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire()!! }
        assertFalse(foreground.isCompleted)
        finishStop.complete(Unit)
        release.join()
        val lease = foreground.await()
        assertEquals(listOf("start", "stopping", "stopped", "start"), events)
        pool.release(worker) // stale completion must not release the new UI owner
        assertEquals(4, events.size)
        pool.release(lease)
    }

    @Test fun cancelledWorkerReleasesItsConnection() = runBlocking {
        var stops = 0
        val acquired = CompletableDeferred<Unit>()
        val pool = ConnectionLeases({ true }, {}, { yield(); stops++ })
        val worker = launch {
            pool.use { acquired.complete(Unit); awaitCancellation() }
        }
        acquired.await()
        worker.cancelAndJoin()
        assertEquals(1, stops)
    }

    @Test fun pauseRejectsWaitingAcquisitionAfterShutdown() = runBlocking {
        var paused = false
        var starts = 0
        val stopping = CompletableDeferred<Unit>()
        val finishStop = CompletableDeferred<Unit>()
        val pool = ConnectionLeases({ !paused }, { starts++ }, {
            stopping.complete(Unit); finishStop.await()
        })
        val old = pool.acquire()!!
        val release = launch { pool.release(old) }
        stopping.await()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire() }
        paused = true
        finishStop.complete(Unit)
        release.join()
        assertNull(pending.await())
        assertEquals(1, starts)
    }

    @Test fun failedWorkStillReleasesAndDuplicateReleaseIsHarmless() = runBlocking {
        var stops = 0
        val pool = ConnectionLeases({ true }, {}, { stops++ })
        try { pool.use { throw IllegalStateException("test") } }
        catch (_: IllegalStateException) { }
        assertEquals(1, stops)
        val next = pool.acquire()!!
        pool.release(next)
        pool.release(next)
        assertEquals(2, stops)
    }
}
