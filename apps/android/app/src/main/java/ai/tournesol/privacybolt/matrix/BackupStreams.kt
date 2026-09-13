package ai.tournesol.privacybolt.matrix

import java.io.InputStream
import java.io.OutputStream
import java.io.EOFException
import java.security.MessageDigest

internal object BackupStreams {
    fun copyExact(input: InputStream, output: OutputStream, count: Long, digest: MessageDigest? = null) {
        require(count >= 0)
        val buffer = ByteArray(256 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("The file ended before its reported size")
            if (read == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("The file ended before its reported size")
                output.write(byte); digest?.update(byte.toByte()); remaining--
            } else {
                output.write(buffer, 0, read); digest?.update(buffer, 0, read); remaining -= read
            }
        }
    }
    fun hashExact(input: InputStream, digest: MessageDigest, count: Long) =
        copyExact(input, object : OutputStream() { override fun write(value: Int) {} ; override fun write(buffer: ByteArray, offset: Int, length: Int) {} }, count, digest)
}
