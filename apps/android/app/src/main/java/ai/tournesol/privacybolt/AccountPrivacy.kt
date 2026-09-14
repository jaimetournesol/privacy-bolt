package ai.tournesol.privacybolt

import android.content.Context
import ai.tournesol.privacybolt.net.TorNet
import ai.tournesol.privacylodge.backup.BackupSyncStore
import ai.tournesol.privacylodge.backup.BackupSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared across activities; an agent WebView must obey the same lock as chats. */
object AccountPrivacy {
    val gate = MutableStateFlow(Gate.Locked)

    suspend fun clearLocalAccess(context: Context) {
        gate.value = Gate.Locked
        withContext(Dispatchers.Main) { ConnectionLifecycle.cancelForeground() }
        // Independent privacy stores must still be cleared if a service/provider is
        // unavailable. Never log exception messages here: they can contain identities.
        runCatching { PpSyncService.stop(context) }
        runCatching { BackgroundSyncWorker.cancel(context) }
        runCatching { BackupSyncWorker.cancelAll(context) }
        ConnectionLifecycle.drain()
        runCatching { BackupSyncStore.clearAccount(context) }
        runCatching { TorNet.stopAll() }
        val permissions = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        for (permission in permissions) {
            var flags = 0
            if (permission.isReadPermission) flags = flags or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(permission.uri, flags) }
        }
        runCatching { context.getSharedPreferences("pp_agents", Context.MODE_PRIVATE).edit().clear().commit() }
        withContext(Dispatchers.Main) {
            AccountWebViews.closeAll()
            val cookies = android.webkit.CookieManager.getInstance()
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                cookies.removeAllCookies {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
            }
            cookies.flush()
            runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        }
    }
}
