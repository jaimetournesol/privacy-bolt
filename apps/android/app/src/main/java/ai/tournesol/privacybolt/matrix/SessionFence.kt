package ai.tournesol.privacybolt.matrix

import java.util.concurrent.CancellationException

/** Serializes account changes with credential reads/writes and client publication.
 * Blocks must be local, synchronous operations: never call SDK/network code here.
 * Opaque tickets cannot accidentally become valid again after another sign-in. */
internal class SessionFence {
    class Ticket internal constructor()
    private var current = Ticket()

    @Synchronized fun capture(): Ticket = current

    @Synchronized fun <T> use(ticket: Ticket, action: () -> T): T {
        if (ticket !== current) throw CancellationException("Account changed")
        return action()
    }

    /** Revoke first, even if local cleanup throws. A failed clear must never restore
     * the old callbacks' authority to write credentials. */
    @Synchronized fun replace(action: (Ticket) -> Unit = {}): Ticket {
        current = Ticket()
        action(current)
        return current
    }

    @Synchronized fun replaceIfCurrent(ticket: Ticket, action: (Ticket) -> Unit): Ticket? {
        if (ticket !== current) return null
        return replace(action)
    }
}
