package ai.tournesol.privacybolt.matrix

/** Serialize ownership changes with callbacks that publish the visible timeline. */
internal class TimelineGuard {
    private var generation = 0L

    @Synchronized
    fun begin(reset: () -> Unit): Long {
        generation += 1
        reset()
        return generation
    }

    @Synchronized
    fun update(owner: Long, action: () -> Unit): Boolean {
        if (owner != generation) return false
        action()
        return true
    }

    /** SDK listeners may deliver their first update before returning a handle. */
    fun <T> listener(owner: Long, deliver: (T) -> Unit) = Listener(this, owner, deliver)

    class Listener<T> internal constructor(
        private val guard: TimelineGuard,
        private val owner: Long,
        private val deliver: (T) -> Unit,
    ) {
        private val pending = ArrayList<T>()
        private var active = false

        @Synchronized
        fun onUpdate(value: T) {
            if (active) guard.update(owner) { deliver(value) }
            else guard.update(owner) { pending.add(value) }
        }

        @Synchronized
        fun activate(install: () -> Unit): Boolean {
            return try {
                guard.update(owner) {
                    install()
                    active = true
                    pending.forEach(deliver)
                }
            } finally {
                pending.clear()
            }
        }
    }
}

data class ChatTimelineState(
    val roomId: String? = null,
    val messages: List<ChatMsg> = emptyList(),
    val ready: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)
