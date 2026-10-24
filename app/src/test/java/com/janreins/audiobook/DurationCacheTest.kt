package com.janreins.audiobook

import androidx.test.core.app.ApplicationProvider
import com.janreins.audiobook.data.DurationCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DurationCacheTest {
    @Test fun keyIncludesDocumentSizeAndModificationTime() {
        assertEquals("primary:Books/chapter.mp3|42|123", DurationCache.key("primary:Books/chapter.mp3", 42, 123))
    }
    @Test fun jsonRoundTrip() {
        val values = mapOf("a|1|2" to 0L, "quoted\"key" to 123456789L)
        assertEquals(values, DurationCache.deserialize(DurationCache.serialize(values)))
    }
    @Test fun unreadableDurationIsRetriedNotCached() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("duration_cache", android.content.Context.MODE_PRIVATE)
            .edit().putString("entries", DurationCache.serialize(mapOf("old|1|2" to 0L))).commit()
        val cache = DurationCache(context)
        assertNull("a 0 saved by an older version is read again", cache.get("old|1|2"))
        cache.put("new|1|2", 0L)
        assertNull(cache.get("new|1|2"))
        cache.put("new|1|2", 5_000L)
        assertEquals(5_000L, cache.get("new|1|2"))
        cache.put("new|1|2", 0L)
        assertNull(cache.get("new|1|2"))
        cache.put("ok|1|2", 7_000L)
        cache.save(setOf("ok|1|2"))
        assertEquals(7_000L, DurationCache(context).get("ok|1|2"))
    }
    @Test fun badJsonIsEmpty() {
        assertTrue(DurationCache.deserialize("broken").isEmpty())
        assertTrue(DurationCache.deserialize("{\"a\":\"invalid\"}").isEmpty())
    }
}
