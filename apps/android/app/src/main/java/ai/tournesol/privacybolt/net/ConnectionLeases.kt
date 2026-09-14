package ai.tournesol.privacybolt.net

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes the last consumer's shutdown with a new consumer's startup.
 * Tokens are unique to an operation, so a late release cannot remove its successor.
 * Readiness and network requests run outside this lock. */
internal class ConnectionLeases(
    private val allowed: () -> Boolean,
    private val start: suspend () -> Unit,
    private val stop: suspend () -> Unit,
) {
    class Lease internal constructor()
    private val transition = Mutex()
    private val owners = mutableMapOf<Lease, Job?>()

    suspend fun acquire(job: Job? = null): Lease? = transition.withLock {
        if (!allowed()) return@withLock null
        start()
        Lease().also { owners[it] = job }
    }

    suspend fun release(lease: Lease) = withContext(NonCancellable) {
        transition.withLock {
            val existed = owners.containsKey(lease)
            owners.remove(lease)
            if (existed && owners.isEmpty()) stop()
        }
    }

    suspend fun <T> use(block: suspend () -> T): T? = coroutineScope {
        val lease = acquire(currentCoroutineContext().job) ?: return@coroutineScope null
        try { block() } finally { release(lease) }
    }

    /** Caller must close admission first (Pause or the account's sign-out fence).
     * Never join while holding transition: a cancelling worker releases under it. */
    suspend fun drain() {
        val current = currentCoroutineContext().job
        val jobs = transition.withLock { owners.values.filterNotNull().filter { it !== current }.distinct() }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
}
