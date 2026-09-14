package ai.tournesol.privacybolt

import org.junit.Assert.*
import org.junit.Test

class CallFocusRoutesTest {
    private val bob = "b".repeat(56) + ".onion"
    private val charlie = "c".repeat(56) + ".onion"

    @Test fun differentRemoteBoxesKeepDistinctStableEndpoints() {
        val started = mutableMapOf<Int, String>()
        val pool = CallFocusRoutes(18444, 17444, 16, { p, h -> started[p] = h }, { p, h -> started[p] = h }, { started.remove(it) })
        val first = pool.routeFor(bob)
        val second = pool.routeFor(charlie)
        assertNotEquals(first.authPort, second.authPort)
        assertNotEquals(first.mediaPort, second.mediaPort)
        assertEquals(first, pool.routeFor(bob))
        assertEquals(bob, started[first.authPort]); assertEquals(bob, started[first.mediaPort])
        assertEquals(charlie, started[second.authPort]); assertEquals(charlie, started[second.mediaPort])
        pool.close(); assertTrue(started.isEmpty())
    }

    @Test fun halfStartedRouteRollsBackAndCanRetryWithoutLosingSlot() {
        val started = mutableSetOf<Int>()
        var failMedia = true
        val pool = CallFocusRoutes(18444, 17444, 1, { p, _ -> started.add(p) }, { p, _ ->
            started.add(p)
            if (failMedia) throw IllegalStateException("bind failed")
        }, { started.remove(it) })
        try { pool.routeFor(bob); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertTrue(started.isEmpty())
        failMedia = false
        assertEquals(CallFocusRoutes.Route(18444, 17444), pool.routeFor(bob))
        pool.close()
    }

    @Test fun capacityAndClosedStateDoNotRedirectExistingRoutes() {
        val started = mutableSetOf<Int>()
        val pool = CallFocusRoutes(18444, 17444, 1, { p, _ -> started.add(p) }, { p, _ -> started.add(p) }, { started.remove(it) })
        val first = pool.routeFor(bob)
        try { pool.routeFor(charlie); fail("Expected capacity limit") } catch (_: IllegalStateException) { }
        assertEquals(first, pool.routeFor(bob))
        pool.close()
        try { pool.routeFor(bob); fail("Expected closed route") } catch (_: IllegalStateException) { }
        assertTrue(started.isEmpty())
    }
}
