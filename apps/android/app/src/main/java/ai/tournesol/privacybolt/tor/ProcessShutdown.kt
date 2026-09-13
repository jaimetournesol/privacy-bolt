package ai.tournesol.privacybolt.tor

import java.util.concurrent.TimeUnit

/** For the disposable Tor client process, never for a database migration. */
internal fun stopAndReap(process: Process, graceMillis: Long = 5_000) {
    if (process.isAlive) {
        process.destroy()
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }
    process.waitFor()
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
}
