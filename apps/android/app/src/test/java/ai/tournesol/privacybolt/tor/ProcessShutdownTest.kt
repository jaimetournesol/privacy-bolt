package ai.tournesol.privacybolt.tor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class ProcessShutdownTest {
    private fun child(delayMillis: Long): Pair<Process, Int> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes = File(ShutdownFixture::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        val process = ProcessBuilder(java, "-cp", classes, ShutdownFixture::class.java.name, "$delayMillis")
            .redirectErrorStream(true).start()
        return try {
            process to process.inputStream.bufferedReader().readLine().toInt()
        } catch (t: Throwable) { process.destroyForcibly(); process.waitFor(); throw t }
    }

    @Test fun stopWaitsForProcessExitBeforeItsPortCanBeReused() {
        val (process, port) = child(300)
        try {
            val started = System.nanoTime()
            stopAndReap(process)
            assertFalse(process.isAlive)
            assertTrue("Returned before the shutdown hook finished", System.nanoTime() - started >= 250_000_000)
            ServerSocket(port).use { assertTrue(it.isBound) }
            stopAndReap(process) // repeated cleanup remains safe
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
    }

    @Test fun stuckTorShutdownIsReapedAfterTheGracePeriod() {
        val (process, port) = child(10_000)
        try {
            stopAndReap(process, graceMillis = 100)
            assertFalse(process.isAlive)
            ServerSocket(port).use { assertTrue(it.isBound) }
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
    }
}
