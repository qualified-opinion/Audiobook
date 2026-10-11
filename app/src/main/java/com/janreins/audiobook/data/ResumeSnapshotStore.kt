package com.janreins.audiobook.data

import androidx.core.net.toUri
import android.content.Context
import com.janreins.audiobook.data.model.AudioTrack
import com.janreins.audiobook.data.model.Audiobook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Strings only: the versioned codec needs neither Android URIs nor a library scan. */
data class ResumeSnapshot(
    val bookId: String, val title: String, val fileName: String, val uri: String,
    val durationMs: Long, val sizeBytes: Long, val lastModified: Long,
    val folderUri: String, val tracks: List<Track>,
    val coverUri: String? = null, val coverKey: String? = null,
    val formattedDuration: String = ""
) {
    data class Track(val uri: String, val documentId: String, val fileName: String, val title: String,
                     val durationMs: Long, val sizeBytes: Long, val lastModified: Long)

    fun toJson(): String = JSONObject().put("version", 1).put("bookId", bookId)
        .put("title", title).put("fileName", fileName).put("uri", uri)
        .put("durationMs", durationMs).put("sizeBytes", sizeBytes).put("lastModified", lastModified)
        .put("folderUri", folderUri).put("coverUri", coverUri ?: JSONObject.NULL)
        .put("coverKey", coverKey ?: JSONObject.NULL).put("formattedDuration", formattedDuration)
        .put("tracks", JSONArray().apply {
            tracks.forEach { t -> put(JSONObject().put("uri", t.uri).put("documentId", t.documentId)
                .put("fileName", t.fileName).put("title", t.title).put("durationMs", t.durationMs)
                .put("sizeBytes", t.sizeBytes).put("lastModified", t.lastModified)) }
        }).toString()

    companion object {
        fun fromJson(json: String): ResumeSnapshot? = try {
            val o = JSONObject(json)
            val version = o.get("version")
            require(version is Number && version.toDouble() == 1.0)
            fun text(objectValue: JSONObject, name: String) =
                (objectValue.get(name) as? String) ?: throw IllegalArgumentException("Invalid $name")
            fun required(name: String) = text(o, name).also { require(it.isNotBlank()) }
            val array = o.getJSONArray("tracks")
            require(array.length() > 0)
            val tracks = (0 until array.length()).map { i ->
                val t = array.getJSONObject(i)
                Track(text(t, "uri").also { require(it.isNotBlank()) }, text(t, "documentId"),
                    text(t, "fileName"), text(t, "title"), t.getLong("durationMs"),
                    t.getLong("sizeBytes"), t.getLong("lastModified"))
            }
            ResumeSnapshot(required("bookId"), text(o, "title"), text(o, "fileName"),
                required("uri"), o.getLong("durationMs"), o.getLong("sizeBytes"), o.getLong("lastModified"),
                required("folderUri"), tracks,
                if (o.isNull("coverUri")) null else text(o, "coverUri"),
                if (o.isNull("coverKey")) null else text(o, "coverKey"), o.optString("formattedDuration", ""))
        } catch (_: Exception) { null }
    }
}

fun ResumeSnapshot.toBook() = Audiobook(bookId, uri.toUri(), title, fileName, durationMs, sizeBytes,
    formattedDuration, tracks.map { AudioTrack(it.uri.toUri(), it.documentId, it.fileName, it.title,
        it.durationMs, it.sizeBytes, it.lastModified) }, coverUri?.toUri(), coverKey, lastModified)

fun Audiobook.toResumeSnapshot(folderUri: String): ResumeSnapshot {
    val savedTracks = tracks.ifEmpty {
        listOf(AudioTrack(uri, id, fileName, title, durationMs, sizeBytes, lastModified))
    }
    return ResumeSnapshot(id, title, fileName, uri.toString(), durationMs, sizeBytes, lastModified, folderUri,
        savedTracks.map { ResumeSnapshot.Track(it.uri.toString(), it.documentId, it.fileName, it.title,
            it.durationMs, it.sizeBytes, it.lastModified) }, coverUri?.toString(), coverKey, formattedDuration)
}

/** All instances share a lock so a scan cannot race a receiver read or a book change. */
class ResumeSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "resume"))
    private val file get() = File(directory, "last_book.json")

    fun read(deleteInvalid: Boolean = true): ResumeSnapshot? = synchronized(lock) {
        if (!file.exists()) return@synchronized null
        val snapshot = try { ResumeSnapshot.fromJson(file.readText()) } catch (_: Exception) { null }
        if (snapshot == null && deleteInvalid) file.delete()
        snapshot
    }

    fun write(snapshot: ResumeSnapshot): Unit = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create resume directory")
        val temp = File.createTempFile("last_book", ".tmp", directory)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(snapshot.toJson().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("Cannot replace resume snapshot")
        } finally { temp.delete() }
    }

    fun clear(): Unit = synchronized(lock) { file.delete(); Unit }
    companion object { private val lock = Any() }
}
