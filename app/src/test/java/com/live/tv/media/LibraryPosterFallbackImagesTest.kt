package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryPosterFallbackImagesTest {
    private class Worker : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command); assertTrue(queue.size <= 2) }
        val size get() = queue.size
        fun runNext() { queue.removeFirst().run() }
        fun drain() { while (queue.isNotEmpty()) runNext() }
    }
    private class Fixture(memoryBytes: Int = 12 * 1024 * 1024) {
        val worker = Worker(); val callbacks = ArrayDeque<Runnable>()
        val fetches = mutableListOf<String>(); val lookups = mutableListOf<String>()
        val events = mutableListOf<String>()
        val pictures = mutableMapOf<String, Bitmap>()
        var clock = 100L; var revision = 1; var missing = false
        val resolver = object : PosterResolver {
            override fun prepare(work: LibraryWork) = PosterLookup("fallback:${work.key}:$revision", work, "$revision", emptyList())
            override fun resolve(lookup: PosterLookup, request: VodRequest): Bitmap? {
                request.check(); lookups.add(lookup.key); events.add("fallback:${lookup.work.vodId}")
                return if (missing) null else pictures.getOrPut(lookup.key) { Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888) }
            }
        }
        val store = PosterImageStore(worker, { callbacks.add(it) }, { url, _ ->
            fetches.add(url); events.add(url)
            if ("broken" in url) null else Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        }, memoryBytes = memoryBytes, resolver = resolver, now = { clock }, sourceIdentity = { revision.toString() })
        fun scope() = LibraryPosterImages(store)
        fun deliver() { while (callbacks.isNotEmpty()) callbacks.removeFirst().run() }
    }
    private fun work(id: String = "film", poster: String = "http://127.0.0.1/broken.jpg") =
        LibraryWork("source", id, 1, 1, id, poster, "2026", "剧情片")
    private fun view() = ImageView(ApplicationProvider.getApplicationContext())
    private fun bitmap(view: ImageView) = (view.drawable as? BitmapDrawable)?.bitmap

    @Test fun xiguaOriginCannotOccupyBothPosterWorkers() {
        val f = Fixture()
        val images = f.scope()
        val first = view()
        val second = view()
        val backup = view()
        images.load(first, "https://vod.xiguazyimg.com/broken-1.jpg")
        images.load(second, "https://vod.xiguazyimg.com/broken-2.jpg")
        assertEquals(1, f.worker.size)
        images.load(backup, "https://backup.example/poster.jpg")
        assertEquals(2, f.worker.size)
        f.worker.drain()
        f.deliver()
        assertEquals(listOf("https://vod.xiguazyimg.com/broken-1.jpg", "https://backup.example/poster.jpg",
            "https://vod.xiguazyimg.com/broken-2.jpg"), f.fetches)
        assertNotNull(backup.drawable)
    }

    @Test fun failedPrimaryUsesSharedFallbackAndSuccessfulArtSurvivesFailureTtl() {
        val f = Fixture(); val images = f.scope(); val first = view(); val selected = work()
        images.load(first, selected); f.worker.drain(); f.deliver()
        assertNotNull(first.drawable)
        f.clock += PosterImageStore.FAILURE_TTL_MILLIS + 1
        val rebound = view(); images.load(rebound, selected)
        assertSame(bitmap(first), bitmap(rebound))
        assertEquals(1, f.fetches.size); assertEquals(1, f.lookups.size)
        assertEquals(0, f.worker.size)
        images.close()
    }

    @Test fun twoDifferentFilmsSharingOneFailedUrlReceiveTheirOwnMatchingFallback() {
        val f = Fixture(); val images = f.scope(); val first = view(); val second = view()
        val a = work("first"); val b = work("second")
        images.load(first, a); images.load(second, b); f.worker.drain(); f.deliver()
        assertEquals(1, f.fetches.size)
        assertEquals(2, f.lookups.size)
        assertSame(f.pictures[f.resolver.prepare(a)!!.key], bitmap(first))
        assertSame(f.pictures[f.resolver.prepare(b)!!.key], bitmap(second))
        assertNotSame(bitmap(first), bitmap(second))
        images.close()
    }

    @Test fun knownDefaultUrlIsNeverFetchedEvenWhenItWouldReturnAnImage() {
        val f = Fixture(); val images = f.scope(); val poster = view()
        images.load(poster, work(poster = "http://127.0.0.1/movie_default_small.png"))
        f.worker.drain(); f.deliver()
        assertTrue(f.fetches.isEmpty()); assertEquals(1, f.lookups.size); assertNotNull(poster.drawable)
        images.close()
    }

    @Test fun failedLookupHasABoundedCooldownAndSourceChangesBypassTheOldFailureKey() {
        val f = Fixture(); f.missing = true
        val images = f.scope(); val poster = view(); val selected = work()
        images.load(poster, selected); f.worker.drain(); f.deliver()
        repeat(4) { images.load(poster, selected); f.worker.drain(); f.deliver() }
        assertEquals(1, f.fetches.size); assertEquals(1, f.lookups.size); assertNull(poster.drawable)
        f.revision++; f.missing = false
        images.load(poster, selected); f.worker.drain(); f.deliver()
        assertEquals(1, f.fetches.size); assertEquals(2, f.lookups.size); assertNotNull(poster.drawable)
        images.close()
    }

    @Test fun sourceChangeDoesNotReuseAFormerSourcesSuccessfulFallback() {
        val f = Fixture(); val images = f.scope(); val poster = view(); val selected = work()
        images.load(poster, selected); f.worker.drain(); f.deliver(); val previous = bitmap(poster)
        f.revision++
        images.load(poster, selected); f.worker.drain(); f.deliver()
        assertNotSame(previous, bitmap(poster)); assertEquals(2, f.lookups.size)
        images.close()
    }

    @Test fun unsuccessfulFallbackCanRetryAfterItsFailureCooldown() {
        val f = Fixture(); f.missing = true
        val images = f.scope(); val poster = view(); val selected = work()
        images.load(poster, selected); f.worker.drain(); f.deliver(); assertNull(poster.drawable)
        f.clock += PosterImageStore.FAILURE_TTL_MILLIS + 1; f.missing = false
        images.load(poster, selected); f.worker.drain(); f.deliver()
        assertNotNull(poster.drawable); assertEquals(2, f.fetches.size); assertEquals(2, f.lookups.size)
        images.close()
    }

    @Test fun onlyOneWorkerCanRunFallbackWhileVisibleNormalImagesStillStart() {
        val f = Fixture(); val images = f.scope()
        images.load(view(), work("a", "http://127.0.0.1/default.png"))
        images.load(view(), work("b", "http://127.0.0.1/default.png"))
        assertEquals("one fallback reserves the other worker", 1, f.worker.size)
        val visible = view(); images.load(visible, "http://127.0.0.1/visible.jpg")
        assertEquals(2, f.worker.size)
        f.worker.runNext(); f.worker.runNext(); f.deliver()
        assertEquals(listOf("http://127.0.0.1/visible.jpg"), f.fetches)
        assertEquals(1, f.lookups.size)
        assertNotNull(visible.drawable)
        images.close(); f.worker.drain()
    }

    @Test fun rebindDuringPrimaryToFallbackTransitionDoesNotRestartFallback() {
        val f = Fixture(); val images = f.scope(); val poster = view(); val selected = work()
        images.load(poster, selected); f.worker.runNext()
        repeat(4) { images.load(poster, selected) }
        f.worker.drain(); f.deliver()
        assertEquals(1, f.fetches.size); assertEquals(1, f.lookups.size); assertNotNull(poster.drawable)
        images.close()
    }

    @Test fun speculativeFallbackIsReusedWhenTheFilmBecomesVisible() {
        val f = Fixture(); val warm = f.scope(); val selected = work()
        warm.prefetchWorks(listOf(selected)); warm.prefetchWorks(listOf(selected))
        f.worker.drain(); f.deliver(); warm.close()
        val images = f.scope(); val poster = view(); images.load(poster, selected)
        assertNotNull(poster.drawable); assertEquals(1, f.fetches.size); assertEquals(1, f.lookups.size)
        images.close()
    }

    @Test fun liveWarmupProtectsSuccessfulFallbackFromLaterOrdinaryPrefetch() {
        val f = Fixture(memoryBytes = 2 * 120 * 180 * 4)
        val warm = f.scope(); val library = f.scope(); val selected = work()
        warm.warmFirstPage(listOf(selected)); f.worker.drain()
        val expected = f.pictures[f.resolver.prepare(selected)!!.key]
        library.prefetch((0 until 3).map { "http://127.0.0.1/ahead-$it.jpg" }); f.worker.drain()
        val poster = view(); library.load(poster, selected)
        assertSame("completed warm fallback must be available immediately on entry", expected, bitmap(poster))
        assertEquals("the same matching-art search must not run again", 1, f.lookups.size)
        assertEquals(0, f.worker.size)
        warm.close(); library.close(); f.worker.drain()
    }

    @Test fun aWarmFallbackRunsAheadOfLookaheadButVisiblePostersStillTakePriority() {
        val f = Fixture(); val warm = f.scope(); val library = f.scope()
        val broken = work("first", "http://127.0.0.1/broken-first.jpg")
        val second = work("second", "http://127.0.0.1/warm-second.jpg")
        val third = work("third", "http://127.0.0.1/warm-third.jpg")
        warm.warmFirstPage(listOf(broken, second, third))
        library.prefetch((0 until 24).map { "http://127.0.0.1/ahead-$it.jpg" })
        val visible = view(); val target = "http://127.0.0.1/visible.jpg"
        library.load(visible, target)
        repeat(4) { f.worker.runNext() }; f.deliver()
        assertEquals(listOf(broken.poster, second.poster, target, "fallback:first"), f.events.take(4))
        assertEquals(1, f.lookups.size)
        assertNotNull(visible.drawable)
        warm.close(); library.close(); f.worker.drain()
    }

    @Test fun aSourceRevisionChangeReleasesOldFallbackProtectionAndAQueuedFallbackCannotRestoreIt() {
        val f = Fixture(memoryBytes = 2 * 120 * 180 * 4); val warm = f.scope(); val library = f.scope()
        val old = work("old", "http://127.0.0.1/broken-old.jpg")
        warm.warmFirstPage(listOf(old)); f.worker.drain()
        val oldKey = f.resolver.prepare(old)!!.key
        library.prefetch((0 until 3).map { "http://127.0.0.1/ahead-$it.jpg" }); f.worker.drain()
        assertNotNull(f.store.cached(oldKey))
        f.revision++
        assertNull(f.store.cached(oldKey))
        val pending = work("pending", "http://127.0.0.1/broken-pending.jpg")
        warm.warmFirstPage(listOf(pending)); f.worker.runNext()
        val pendingKey = f.resolver.prepare(pending)!!.key
        f.revision++; f.worker.drain()
        assertNull(f.store.cached(pendingKey))
        assertNotNull(f.store.cached("http://127.0.0.1/ahead-2.jpg"))
        warm.close(); library.close()
    }

    @Test fun ordinaryPrefetchOnTheWarmClientDoesNotReplaceItsFallbackProtection() {
        val f = Fixture(memoryBytes = 2 * 120 * 180 * 4); val warm = f.scope(); val selected = work()
        warm.warmFirstPage(listOf(selected)); f.worker.drain()
        val expected = f.pictures[f.resolver.prepare(selected)!!.key]
        warm.prefetchWorks((0 until 3).map { work("ahead-$it", "http://127.0.0.1/ahead-$it.jpg") }); f.worker.drain()
        val library = f.scope(); val poster = view(); library.load(poster, selected)
        assertSame(expected, bitmap(poster))
        assertEquals(1, f.lookups.size)
        warm.close(); library.close()
    }

    @Test fun closingOneScreenDoesNotCancelAnotherScreensSharedFallback() {
        val f = Fixture(); val first = f.scope(); val second = f.scope(); val selected = work()
        val old = view(); val current = view()
        first.load(old, selected); second.load(current, selected); f.worker.runNext(); first.close()
        f.worker.drain(); f.deliver()
        assertNull(old.drawable); assertNotNull(current.drawable); assertEquals(1, f.lookups.size)
        second.close()
    }

    @Test fun recycledAndClosedViewsRejectAlreadyPostedFallbackCompletion() {
        val f = Fixture(); val images = f.scope(); val poster = view()
        images.load(poster, work("previous")); f.worker.drain()
        images.load(poster, work("current")); f.deliver(); assertNull(poster.drawable)
        f.worker.drain(); images.close(); f.deliver(); assertNull(poster.drawable)
    }
}
