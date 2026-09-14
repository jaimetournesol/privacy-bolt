package ai.tournesol.privacybolt

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.tournesol.privacybolt.matrix.MatrixRepo
import ai.tournesol.privacybolt.net.ConnectionLeases
import ai.tournesol.privacybolt.tor.TorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Process UI, continuous sync and bounded workers share the same Tor connection.
 * A worker owns only its lease, never the entire process's network lifetime. */
internal object ConnectionLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private lateinit var leases: ConnectionLeases
    private var foregroundJob: Job? = null

    fun install(context: Context) {
        app = context.applicationContext
        leases = ConnectionLeases(
            allowed = { !isPaused() && !MatrixRepo.isSigningOut },
            start = { TorManager.start(app) },
            stop = {
                withContext(Dispatchers.IO) {
                    try { withTimeoutOrNull(5_000) { MatrixRepo.pauseSync() } }
                    finally { TorManager.stop() }
                }
            },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { reconnectForeground() }
            override fun onStop(owner: LifecycleOwner) {
                foregroundJob?.cancel()
                foregroundJob = null
            }
        })
    }

    fun isPaused(): Boolean = app.getSharedPreferences("pp_app", Context.MODE_PRIVATE).getBoolean("paused", false)

    /** Also used by explicit Resume; returning to an already-open Conductor is covered
     * by the process observer even when MainActivity is not the visible activity. */
    fun reconnectForeground() {
        if (isPaused() || foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            try {
                leases.use {
                    // Cold login/restore remains owned by AppViewModel. Here we recover a
                    // retained account after bounded background work stopped its transport.
                    try {
                        if (MatrixRepo.isLoggedIn && awaitReady() && !isPaused()) {
                            if (MatrixRepo.status.value.isBlank()) MatrixRepo.startSync()
                            ensureActive()
                            PpSyncService.start(app)
                        }
                    } catch (cancelled: CancellationException) {
                        // An account ticket can expire during a concurrent cold restore.
                        // That cancels this refresh, not the visible UI's transport lease.
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        Log.w("PpConnection", "Session refresh failed; foreground connection retained")
                    }
                    awaitCancellation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("PpConnection", "Foreground reconnect failed; retry is available")
            }
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            TorManager.state.first { it is TorManager.State.Ready || it is TorManager.State.Failed } is TorManager.State.Ready
        } == true && !isPaused()

    suspend fun <T> withConnection(block: suspend () -> T): T? = leases.use(block)

    suspend fun drain() = leases.drain()

    fun cancelForeground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }
}
