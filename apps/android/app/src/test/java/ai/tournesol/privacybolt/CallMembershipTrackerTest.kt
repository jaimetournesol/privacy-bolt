package ai.tournesol.privacybolt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMembershipTrackerTest {
    private val me = "@alice:box.onion"
    private val bob = "_@bob:other.onion_DEVICE_m.call"
    private fun change(key: String, present: Boolean) = CallMembershipTracker.Change(key, present)

    @Test fun ownRefreshAndMissingPeersDoNotEndALiveCall() {
        val tracker = CallMembershipTracker()
        assertFalse(tracker.update(me, listOf(change(bob, true)), false))
        assertFalse(tracker.update(me, listOf(change("_${me}_DEVICE_m.call", false)), false))
        assertFalse(tracker.update(me, emptyList(), false))
        assertFalse(tracker.shouldEnd())
        assertTrue(tracker.update(me, listOf(change(bob, false)), false))
    }

    @Test fun PartialUpdatesPreserveOtherParticipants() {
        val tracker = CallMembershipTracker()
        val charlie = "_@charlie:third.onion_DEVICE_m.call"
        tracker.update(me, listOf(change(bob, true)), false)
        tracker.update(me, listOf(change(charlie, true)), false)
        assertFalse(tracker.update(me, listOf(change(bob, false)), false))
        assertTrue(tracker.update(me, listOf(change(charlie, false)), false))
    }

    @Test fun refreshedMemberCancelsPendingDeparture() {
        val tracker = CallMembershipTracker()
        tracker.update(me, listOf(change(bob, true)), false)
        assertTrue(tracker.update(me, listOf(change(bob, false)), false))
        assertFalse(tracker.update(me, listOf(change(bob, true)), false))
        assertFalse(tracker.shouldEnd())
    }

    @Test fun staleBaselineDoesNotArmAnAutomaticHangup() {
        val tracker = CallMembershipTracker()
        tracker.update(me, listOf(change(bob, true)), true)
        assertFalse(tracker.update(me, listOf(change(bob, false)), false))
        tracker.update(me, listOf(change(bob, true)), false)
        assertTrue(tracker.update(me, listOf(change(bob, false)), false))
    }
}
