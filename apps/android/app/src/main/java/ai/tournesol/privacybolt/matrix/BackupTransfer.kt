package ai.tournesol.privacybolt.matrix

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.*
import java.io.File
import java.security.MessageDigest

/** One upload owns its timeline and acknowledgements, independent of the Files screen. */
internal object BackupTransfer {
    private data class Remote(val id: String, val name: String, val size: Long)

    suspend fun upload(client: Client, context: Context, roomId: String, uri: Uri,
        name: String, mime: String, size: Long, stillCurrent: () -> Boolean,
        progress: ((Int, Int) -> Unit)?): Boolean {
        val room = client.getRoom(roomId) ?: return false
        val timeline = room.timeline()
        val items = ArrayList<TimelineItem>()
        fun remote(): List<Remote> = synchronized(items) {
            items.mapNotNull { item ->
                val event = runCatching { item.asEvent() }.getOrNull() ?: return@mapNotNull null
                if (event.localSendState != null && event.localSendState !is EventSendState.Sent) return@mapNotNull null
                val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message ?: return@mapNotNull null
                val file = kind.content.msgType as? MessageType.File ?: return@mapNotNull null
                Remote(event.eventOrTransactionId.toString(), file.content.filename ?: kind.content.body, file.content.info?.size?.toLong() ?: 0)
            }
        }
        val listener = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) = synchronized(items) {
                for (d in diff) when (d) {
                    is TimelineDiff.Append -> items.addAll(d.values)
                    is TimelineDiff.PushBack -> items.add(d.value)
                    is TimelineDiff.PushFront -> items.add(0,d.value)
                    is TimelineDiff.Insert -> items.add(d.index.toInt(),d.value)
                    is TimelineDiff.Set -> if(d.index.toInt()<items.size)items[d.index.toInt()]=d.value
                    is TimelineDiff.Remove -> if(d.index.toInt()<items.size)items.removeAt(d.index.toInt())
                    is TimelineDiff.Reset -> {items.clear();items.addAll(d.values)}
                    is TimelineDiff.Clear -> items.clear()
                    is TimelineDiff.PopBack -> if(items.isNotEmpty())items.removeAt(items.lastIndex)
                    is TimelineDiff.PopFront -> if(items.isNotEmpty())items.removeAt(0)
                    is TimelineDiff.Truncate -> while(items.size>d.length.toInt())items.removeAt(items.lastIndex)
                }
            }
        })
        val directory = File(context.cacheDir,"pp_bk/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        suspend fun send(file: File): Boolean {
            if(!stillCurrent())return false
            val previous=remote().map { it.id }.toSet()
            val handle=timeline.sendFile(UploadParameters(UploadSource.File(file.absolutePath),null,null,null,null),FileInfo(mime,file.length().toULong(),null,null))
            handle.join()
            return withTimeoutOrNull(20 * 60_000L) {
                while(stillCurrent()) {
                    if(remote().any { it.id !in previous && it.name==file.name && it.size==file.length() })return@withTimeoutOrNull true
                    delay(500)
                }
                false
            } ?: false
        }
        try {
            // Bounded initial history for resume. Missing old parts merely causes a safe
            // re-upload; acknowledgement of new parts never depends on an open Files UI.
            timeline.paginateBackwards(200u.toUShort())
            if(size<=MatrixRepo.CHUNK_THRESHOLD) {
                val file=File(directory,name)
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    BackupStreams.copyExact(input,output,size)
                    check(input.read()==-1) { "File grew during backup" }
                }} ?: return false
                val sent=send(file)
                if(sent)progress?.invoke(1,1)
                return sent
            }
            val digest=MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                BackupStreams.hashExact(input,digest,size)
                check(input.read()==-1) { "File grew during backup" }
            } ?: return false
            val expected=digest.digest().joinToString("") { "%02x".format(it) }
            val id="sha256-$expected"
            val total=((size+MatrixRepo.CHUNK_BYTES-1)/MatrixRepo.CHUNK_BYTES).toInt()
            val second=MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                for(index in 0 until total) {
                    currentCoroutineContext().ensureActive()
                    if(!stillCurrent())return false
                    val count=minOf(MatrixRepo.CHUNK_BYTES,size-index*MatrixRepo.CHUNK_BYTES)
                    val part=File(directory,"$name~~pp~$id~$index~$total")
                    part.outputStream().use { output -> BackupStreams.copyExact(input,output,count,second) }
                    if(remote().none { it.name==part.name && it.size==count } && !send(part))return false
                    part.delete()
                    progress?.invoke(index+1,total)
                }
                check(input.read()==-1) { "File grew during backup" }
            } ?: return false
            return second.digest().joinToString("") { "%02x".format(it) }==expected
        } finally {
            listener.cancel()
            timeline.destroy()
            directory.deleteRecursively()
        }
    }
}
