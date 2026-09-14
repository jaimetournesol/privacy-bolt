package ai.tournesol.privacybolt

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import ai.tournesol.privacybolt.matrix.MatrixRepo
import ai.tournesol.privacybolt.tor.TorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Bounded catch-up after Android stops the long-running connection. No sticky FGS loop. */
class BackgroundSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("pp_app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("paused", false) || !MatrixRepo.hasSavedSession(applicationContext)) return Result.success()
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return Result.success()
        val completed = withTimeoutOrNull(120_000) {
            ConnectionLifecycle.withConnection {
                if (!ConnectionLifecycle.awaitReady()) return@withConnection false
                val account = MatrixRepo.restoreAccount(applicationContext) ?: return@withConnection false
                MatrixRepo.startSync(account)
                delay(20_000)
                true
            }
        } ?: false
        return if (completed) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "pp-background-catchup"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
