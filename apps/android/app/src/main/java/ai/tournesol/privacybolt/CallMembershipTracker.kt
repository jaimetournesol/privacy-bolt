package ai.tournesol.privacybolt

/** Widget state can contain only changed members. Absence is not a departure. */
internal class CallMembershipTracker {
    data class Change(val key: String, val present: Boolean)

    private val present = mutableSetOf<String>()
    private val joined = mutableSetOf<String>()

    @Synchronized
    fun update(me: String, changes: List<Change>, baseline: Boolean): Boolean {
        for (change in changes) {
            if (change.key.isBlank() || change.key == me || change.key.startsWith("_${me}_")) continue
            if (change.present) {
                if (present.add(change.key) && !baseline) joined.add(change.key)
            } else {
                present.remove(change.key)
            }
        }
        return shouldEnd()
    }

    @Synchronized
    fun shouldEnd(): Boolean = joined.isNotEmpty() && joined.none { it in present }
}
