package ai.tournesol.privacybolt

import org.junit.Assert.*
import org.junit.Test

class CallTurnRoutesTest {
    private val alice = "a".repeat(56) + ".onion"
    private val bob = "b".repeat(56) + ".onion"

    @Test fun simultaneousFocusesNeverRedirectAnExistingRelay() {
        val targets = mutableMapOf<Int, String>()
        val routes = CallTurnRoutes(13478, 2, { port, host -> targets[port] = host }, { targets.remove(it) })
        val first = routes.portFor(alice)
        val second = routes.portFor(bob)
        assertNotEquals(first, second)
        assertEquals(first, routes.portFor(alice))
        assertEquals(alice, targets[first])
        assertEquals(bob, targets[second])
        routes.close()
        assertTrue(targets.isEmpty())
        assertTrue(runCatching { routes.portFor(alice) }.isFailure)
    }

    @Test fun failedOrInvalidRouteDoesNotConsumeAPort() {
        var fail = true
        val routes = CallTurnRoutes(13478, 1, { _, _ -> if (fail) error("bind failed") }, {})
        assertTrue(runCatching { routes.portFor("example.com") }.isFailure)
        assertTrue(runCatching { routes.portFor(alice) }.isFailure)
        fail = false
        assertEquals(13478, routes.portFor(alice))
        assertTrue(runCatching { routes.portFor(bob) }.isFailure)
        assertEquals(13478, routes.portFor(alice))
    }

    @Test fun cleanupFailureDoesNotLeaveOtherRelaysOpen() {
        val closed = mutableSetOf<Int>()
        val routes = CallTurnRoutes(13478, 2, { _, _ -> }, { closed.add(it); error("cleanup failed") })
        routes.portFor(alice); routes.portFor(bob)
        routes.close()
        assertEquals(setOf(13478, 13479), closed)
        assertTrue(runCatching { routes.portFor(alice) }.isFailure)
    }
}
