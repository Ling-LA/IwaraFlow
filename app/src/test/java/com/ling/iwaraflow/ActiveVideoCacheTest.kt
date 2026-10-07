package com.ling.iwaraflow

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveVideoCacheTest {
    @Test fun activeVideoCanExceedIdleBudgetWithoutEvictingItsBeginning() {
        val context = RuntimeEnvironment.getApplication()
        val evictor = ActiveVideoCacheEvictor(10)
        val cache = SimpleCache(File(context.cacheDir, "cache-${java.util.UUID.randomUUID()}"), evictor, StandaloneDatabaseProvider(context))
        fun write(key: String, size: Int) {
            val hole = cache.startReadWrite(key, 0, size.toLong())
            try {
                val file = cache.startFile(key, 0, size.toLong())
                file.writeBytes(ByteArray(size))
                cache.commitFile(file, size.toLong())
            } finally { cache.releaseHoleSpan(hole) }
        }
        try {
            write("old", 10)
            evictor.owners[1] = setOf("current")
            write("current", 30)
            assertEquals(0L, cache.getCachedBytes("old", 0, 10))
            assertEquals(30L, cache.getCachedLength("current", 0, 30))
            evictor.owners.remove(1)
            write("another", 10)
            assertEquals(0L, cache.getCachedBytes("current", 0, 30))
            assertEquals(10L, cache.cacheSpace)
        } finally { cache.release() }
    }
}
