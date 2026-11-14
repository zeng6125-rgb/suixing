package com.suixing.agent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AssistantAvatarLoadTest {
    @get:Rule val temp = TemporaryFolder()

    @After fun tearDown() = unmockkAll()

    @Test fun concurrentConsumersDecodeTheSameColdAvatarOnce() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        val path = "avatar.png"
        temp.newFile(path)
        val bitmap = mockk<Bitmap>()
        val decoded = AtomicInteger()
        val firstDecoder = CountDownLatch(1)
        val releaseDecoder = CountDownLatch(1)
        val secondLookup = CountDownLatch(1)
        val watchLookup = AtomicBoolean()

        // Android's JVM stubs do not implement LruCache. Supply its get/put
        // behavior while exercising the store's actual lock and decode path.
        val cacheField = AssistantAvatarStore::class.java.getDeclaredField("pictures").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as LruCache<String, Bitmap>
        val entries = ConcurrentHashMap<String, Bitmap>()
        mockkObject(cache)
        every { cache.get(any()) } answers {
            val found = entries[firstArg<String>()]
            if (watchLookup.get()) secondLookup.countDown()
            found
        }
        every { cache.put(any(), any()) } answers { entries.put(firstArg(), secondArg()) }
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeFile(any()) } answers {
            if (decoded.incrementAndGet() == 1) {
                firstDecoder.countDown()
                assertTrue("first decode was not released", releaseDecoder.await(5, TimeUnit.SECONDS))
            }
            bitmap
        }

        val first = async(Dispatchers.Default) { AssistantAvatarStore.load(context, path) }
        try {
            assertTrue("first decode did not start", firstDecoder.await(5, TimeUnit.SECONDS))
            watchLookup.set(true)
            val second = async(Dispatchers.Default) { AssistantAvatarStore.load(context, path) }
            assertTrue("second consumer did not check the cache", secondLookup.await(5, TimeUnit.SECONDS))
            releaseDecoder.countDown()
            assertSame(bitmap, first.await())
            assertSame(bitmap, second.await())
            assertEquals(1, decoded.get())
        } finally {
            releaseDecoder.countDown()
        }
    }
}
