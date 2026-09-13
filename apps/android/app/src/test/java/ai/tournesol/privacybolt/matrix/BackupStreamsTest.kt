package ai.tournesol.privacybolt.matrix

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BackupStreamsTest {
    @Test fun exactCopyPreservesBytesAndHashAcrossShortReads() {
        val data=ByteArray(700_001) { (it%251).toByte() }
        val source=object:ByteArrayInputStream(data) {
            override fun read(buffer:ByteArray,offset:Int,length:Int)=super.read(buffer,offset,minOf(length,17))
        }
        val output=ByteArrayOutputStream();val hash=MessageDigest.getInstance("SHA-256")
        BackupStreams.copyExact(source,output,data.size.toLong(),hash)
        assertArrayEquals(data,output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data),hash.digest())
    }
    @Test(expected=EOFException::class) fun truncatedSourceCannotBeMarkedComplete() {
        BackupStreams.copyExact(ByteArrayInputStream(byteArrayOf(1,2)),ByteArrayOutputStream(),3)
    }
    @Test fun zeroLengthReadsDoNotHangOrSkipAByte() {
        val input=object:InputStream() {
            var value=0
            override fun read()=if(value<3)value++ else -1
            override fun read(buffer:ByteArray,offset:Int,length:Int)=0
        }
        val output=ByteArrayOutputStream()
        BackupStreams.copyExact(input,output,3)
        assertArrayEquals(byteArrayOf(0,1,2),output.toByteArray())
    }
}
