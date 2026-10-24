package com.janreins.audiobook.data

import android.content.Context
import org.json.JSONObject

class DurationCache(context: Context) {
    private val prefs = context.getSharedPreferences("duration_cache", Context.MODE_PRIVATE)
    private val entries = deserialize(prefs.getString("entries", "{}").orEmpty()).toMutableMap()

    /**
     * Only real durations are kept. 0 means the file could not be read (for example while it was still
     * syncing), so it is read again on the next scan instead of staying "0:00" until the file changes.
     */
    fun get(key: String): Long? = entries[key]?.takeIf { it > 0L }
    fun put(key: String, durationMs: Long) {
        if (durationMs > 0L) entries[key] = durationMs else entries.remove(key)
    }
    fun save(seen: Set<String>) {
        if (entries.size > 5000) entries.keys.retainAll(seen)
        prefs.edit().putString("entries", serialize(entries)).apply()
    }

    companion object {
        fun key(documentId: String, size: Long, lastModified: Long) = "$documentId|$size|$lastModified"
        fun serialize(map: Map<String, Long>): String = JSONObject(map).toString()
        fun deserialize(json: String): Map<String, Long> = try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getLong(it) }
        } catch (_: Exception) { emptyMap() }
    }
}
