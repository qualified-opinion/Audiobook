package com.janreins.audiobook.data

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.janreins.audiobook.player.EndOfTrackStore
import com.janreins.audiobook.player.EotTarget
import com.janreins.audiobook.player.SkipIntervals
import com.janreins.audiobook.data.model.BookProgress
import com.janreins.audiobook.data.model.Bookmark
import com.janreins.audiobook.ui.theme.AppThemeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages simple, local, and 100% private persistence using Android's SharedPreferences.
 * No data ever leaves the device.
 */
class PreferencesManager(context: Context) : EndOfTrackStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "audiobook_player_preferences"
        private const val KEY_FOLDER_URI = "key_folder_uri"
        private const val KEY_LAST_PLAYED_BOOK_ID = "key_last_played_book_id"
        private const val KEY_PLAYBACK_SPEED = "key_playback_speed"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val PREFIX_POSITION = "pos_"
        private const val PREFIX_BOOKMARKS = "bookmarks_"
        const val KEY_SKIP_BACK_SECONDS = "skip_back_seconds"
        const val KEY_SKIP_FORWARD_SECONDS = "skip_forward_seconds"
    }

    // --- Theme Mode ---
    fun saveThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getThemeMode(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return try {
            AppThemeMode.valueOf(raw)
        } catch (e: Exception) {
            AppThemeMode.SYSTEM
        }
    }

    // --- Selected Folder URI ---
    fun saveFolderUri(uriString: String?) {
        prefs.edit().putString(KEY_FOLDER_URI, uriString).apply()
    }

    fun getFolderUri(): String? {
        return prefs.getString(KEY_FOLDER_URI, null)
    }

    fun setResumeUnavailable() { prefs.edit { putBoolean("resume_unavailable", true) } }
    fun consumeResumeUnavailable(): Boolean {
        val unavailable = prefs.getBoolean("resume_unavailable", false)
        if (unavailable) prefs.edit { remove("resume_unavailable") }
        return unavailable
    }

    // --- Last Played Book ---
    fun saveLastPlayedBookId(bookId: String?) {
        prefs.edit().putString(KEY_LAST_PLAYED_BOOK_ID, bookId).apply()
    }

    fun getLastPlayedBookId(): String? {
        return prefs.getString(KEY_LAST_PLAYED_BOOK_ID, null)
    }

    // --- Playback Position Memory (per audiobook) ---
    fun savePlaybackPosition(bookId: String, positionMs: Long) {
        prefs.edit().putLong(PREFIX_POSITION + bookId, positionMs).apply()
    }

    fun getPlaybackPosition(bookId: String): Long {
        return prefs.getLong(PREFIX_POSITION + bookId, 0L)
    }

    fun getBookProgress(bookId: String) = BookProgress(
        prefs.getInt("track_$bookId", 0), getPlaybackPosition(bookId))

    fun saveBookProgress(bookId: String, progress: BookProgress) {
        prefs.edit().putInt("track_$bookId", progress.trackIndex)
            .putLong(PREFIX_POSITION + bookId, progress.positionMs).apply()
    }

    fun hasBookProgress(bookId: String) =
        prefs.contains(PREFIX_POSITION + bookId) || prefs.contains("track_$bookId")

    fun isMigrated(bookId: String) = prefs.getBoolean("migrated_$bookId", false)
    fun markMigrated(bookId: String) { prefs.edit().putBoolean("migrated_$bookId", true).apply() }

    // --- Playback Speed ---
    fun savePlaybackSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply()
    }

    fun getPlaybackSpeed(): Float {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    }

    // Defaults: 15 seconds back, 30 seconds forward.
    fun getSkipBackSeconds() = SkipIntervals.sanitize(prefs.getInt(KEY_SKIP_BACK_SECONDS, SkipIntervals.DEFAULT_BACK))
    fun saveSkipBackSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SKIP_BACK_SECONDS, SkipIntervals.sanitize(seconds)).apply()
    }
    fun getSkipForwardSeconds() = SkipIntervals.sanitize(
        prefs.getInt(KEY_SKIP_FORWARD_SECONDS, SkipIntervals.DEFAULT_FORWARD), SkipIntervals.DEFAULT_FORWARD)
    fun saveSkipForwardSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SKIP_FORWARD_SECONDS, SkipIntervals.sanitize(seconds, SkipIntervals.DEFAULT_FORWARD)).apply()
    }
    fun getSmartRewindEnabled() = prefs.getBoolean("smart_rewind_enabled", true)
    fun saveSmartRewindEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("smart_rewind_enabled", enabled).apply()
    }
    fun getSleepFadeOut() = prefs.getBoolean("sleep_fade_out", true)
    fun saveSleepFadeOut(enabled: Boolean) {
        prefs.edit().putBoolean("sleep_fade_out", enabled).apply()
    }

    override fun endOfTrackTarget(): EotTarget? {
        val book = prefs.getString("eot_book", null) ?: return null
        return EotTarget(book, prefs.getInt("eot_track", 0),
            prefs.getLong("eot_updated_at", -1L), prefs.getLong("eot_wall_time", -1L))
    }

    fun saveEndOfTrackTarget(bookId: String, trackIndex: Int,
                             elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
                             wallTimeMs: Long = System.currentTimeMillis()) =
        saveEndOfTrackTarget(EotTarget(bookId, trackIndex, elapsedRealtimeMs, wallTimeMs))

    override fun saveEndOfTrackTarget(target: EotTarget) {
        prefs.edit().putString("eot_book", target.bookId).putInt("eot_track", target.trackIndex)
            .putLong("eot_updated_at", target.updatedAtElapsedRealtimeMs)
            .putLong("eot_wall_time", target.updatedAtWallTimeMs).apply()
    }

    override fun clearEndOfTrackTarget() {
        prefs.edit().remove("eot_book").remove("eot_track").remove("eot_updated_at")
            .remove("eot_wall_time").apply()
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    // --- Bookmarks Management ---
    fun saveBookmarks(bookId: String, bookmarks: List<Bookmark>) {
        val jsonArray = JSONArray()
        for (b in bookmarks) {
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("audiobookId", b.audiobookId)
            obj.put("positionMs", b.positionMs)
            obj.put("trackIndex", b.trackIndex)
            obj.put("title", b.title)
            obj.put("createdAt", b.createdAt)
            jsonArray.put(obj)
        }
        prefs.edit().putString(PREFIX_BOOKMARKS + bookId, jsonArray.toString()).apply()
    }

    fun getBookmarks(bookId: String): List<Bookmark> {
        val rawJson = prefs.getString(PREFIX_BOOKMARKS + bookId, null) ?: return emptyList()
        val list = mutableListOf<Bookmark>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        audiobookId = obj.optString("audiobookId", bookId),
                        positionMs = obj.optLong("positionMs", 0L),
                        title = obj.optString("title", "Bookmark"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        trackIndex = obj.optInt("trackIndex", 0)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedWith(compareBy<Bookmark> { it.trackIndex }.thenBy { it.positionMs })
    }

    fun addBookmark(bookmark: Bookmark) {
        val existing = getBookmarks(bookmark.audiobookId).toMutableList()
        existing.add(bookmark)
        saveBookmarks(bookmark.audiobookId, existing)
    }

    fun deleteBookmark(bookId: String, bookmarkId: String) {
        val existing = getBookmarks(bookId).filterNot { it.id == bookmarkId }
        saveBookmarks(bookId, existing)
    }
}
