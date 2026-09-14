package ai.tournesol.privacybolt

import android.app.Application
import ai.tournesol.privacybolt.matrix.MatrixRepo

class PpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConnectionLifecycle.install(this)
        // Load the remembered agent ids before ANY room list is built. The human/AI split
        // consults them, and it has to fail closed: on a cold start the roster hasn't
        // synced yet, and without this an agent room would render in Messaging until it did.
        runCatching { MatrixRepo.initAgentMemory(this) }
        // A background process start must not consume/restart Android's foreground-
        // service budget. The process observer owns visible UI; WorkManager owns
        // bounded background recovery, including a cold worker-only launch.
        val paused = runCatching {
            getSharedPreferences("pp_app", MODE_PRIVATE).getBoolean("paused", false)
        }.getOrDefault(false)
        if (!paused && runCatching { MatrixRepo.hasSavedSession(this) }.getOrDefault(false))
            BackgroundSyncWorker.schedule(this)
    }
}
