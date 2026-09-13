package ai.tournesol.privacybolt.matrix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionFenceTest {
    @Test fun delayedRefreshCannotRecreateClearedCredentials() {
        val fence = SessionFence()
        val old = fence.capture()
        var token: String? = "old"
        fence.replace { token = null }
        assertThrows(CancellationException::class.java) { fence.use(old) { token = "late refresh" } }
        assertNull(token)
    }

    @Test fun oldAuthErrorCannotRevokeNewSignIn() {
        val fence = SessionFence()
        val old = fence.capture()
        val new = fence.replace()
        var token: String? = "new account"
        assertNull(fence.replaceIfCurrent(old) { token = null })
        assertEquals("new account", fence.use(new) { token })
    }

    @Test fun aRestoreThatFinishesAfterLogoutCannotPublishItsClient() {
        val fence = SessionFence()
        val restore = fence.capture()
        fence.replace()
        var published = false
        assertThrows(CancellationException::class.java) { fence.use(restore) { published = true } }
        assertFalse(published)
    }

    @Test fun credentialClearWaitsForAnAlreadyRunningWriteThenWins() {
        val fence = SessionFence()
        val old = fence.capture()
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        var token: String? = null
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writer = workers.submit {
                fence.use(old) {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                    token = "refresh"
                }
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val logout = workers.submit {
                clearing.countDown()
                fence.replace { token = null }
            }
            assertTrue(clearing.await(5, TimeUnit.SECONDS))
            assertFalse(logout.isDone)
            finishWrite.countDown()
            writer.get(5, TimeUnit.SECONDS)
            logout.get(5, TimeUnit.SECONDS)
            assertNull(token)
            assertThrows(CancellationException::class.java) { fence.use(old) { token = "late" } }
        } finally {
            finishWrite.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun failedLocalClearStillRevokesOldCallbacks() {
        val fence = SessionFence()
        val old = fence.capture()
        assertThrows(IllegalStateException::class.java) { fence.replace { error("disk failure") } }
        assertThrows(CancellationException::class.java) { fence.use(old) { fail("stale callback ran") } }
    }

    @Test fun activeAccountCanRotateTokensRepeatedly() {
        val fence = SessionFence()
        val owner = fence.capture()
        var token = "first"
        fence.use(owner) { token = "second" }
        assertEquals("second", fence.use(owner) { token })
        fence.use(owner) { token = "third" }
        assertEquals("third", fence.use(owner) { token })
    }
}
