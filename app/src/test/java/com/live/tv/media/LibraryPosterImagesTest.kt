package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryPosterImagesTest {
    private class Worker : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command); assertTrue("only two workers", tasks.size <= 2) }
        fun runNext() { tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) runNext() }
        val size get() = tasks.size
    }
    private class Fixture(memoryBytes: Int = 12 * 1024 * 1024) {
        val worker = Worker()
        val callbacks = ArrayDeque<Runnable>()
        val fetches = mutableListOf<String>()
        val bitmaps = mutableMapOf<String, Bitmap>()
        var sourceRevision = 1
        val store = PosterImageStore(worker, { callbacks.add(it) }, { url, _ ->
            fetches.add(url)
            bitmaps.getOrPut(url) { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) } }
        }, memoryBytes, sourceIdentity = { sourceRevision.toString() })
        fun scope() = LibraryPosterImages(store)
        fun deliver() { while (callbacks.isNotEmpty()) callbacks.removeFirst().run() }
    }
    private fun view() = ImageView(ApplicationProvider.getApplicationContext())
    private fun url(name: String) = "http://127.0.0.1/poster/$name.png"
    private fun work(name: String) = LibraryWork("source", name, 1, 1, name, url(name))
    private fun bitmap(view: ImageView) = (view.drawable as? BitmapDrawable)?.bitmap

    @Test fun prefetchAndMultipleScreensShareOneFetchAndOneScreenCanCloseIndependently() {
        val f = Fixture(); val warm = f.scope(); val first = f.scope(); val second = f.scope()
        val a = view(); val b = view(); val target = url("shared")
        warm.prefetch(listOf(target, target)); first.load(a, target); second.load(b, target)
        assertEquals(1, f.worker.size)
        first.close(); warm.close()
        f.worker.drain(); f.deliver()
        assertEquals(listOf(target), f.fetches)
        assertNull(a.drawable)
        assertSame(f.bitmaps[target], bitmap(b))
        second.close()
    }

    @Test fun visibleWorkIsSelectedBeforeQueuedSpeculation() {
        val f = Fixture(); val images = f.scope()
        images.prefetch((0 until 24).map { url("ahead-$it") })
        val selected = view(); images.load(selected, url("visible"))
        f.worker.runNext(); f.worker.runNext(); f.worker.runNext()
        assertEquals(listOf(url("ahead-0"), url("ahead-1"), url("visible")), f.fetches.take(3))
        f.deliver(); assertNotNull(selected.drawable)
        images.close(); f.worker.drain()
    }

    @Test fun speculativeQueueAndPerClientWindowAreBoundedAcrossScreens() {
        val f = Fixture(); val scopes = (0..3).map { f.scope() }
        scopes.forEachIndexed { owner, images -> images.prefetch((0..199).map { url("$owner-$it") }) }
        f.worker.drain()
        assertEquals(PosterImageStore.MAX_QUEUED_PREFETCH + 2, f.fetches.size)
        assertEquals(24, f.fetches.count { "/0-" in it })
        assertTrue(f.fetches.none { "/2-" in it || "/3-" in it })
        scopes.forEach { it.close() }
    }

    @Test fun changingPrefetchWindowDropsOldQueuedPosters() {
        val f = Fixture(); val images = f.scope()
        images.prefetch((0..23).map { url("old-$it") })
        images.prefetch(listOf(url("next")))
        f.worker.drain()
        assertEquals(listOf(url("next")), f.fetches)
        images.close()
    }

    @Test fun closingAScreenKeepsCompletedPostersForTheNextScreen() {
        val f = Fixture(); val first = f.scope(); val original = view(); val target = url("reopen")
        first.load(original, target); f.worker.drain(); f.deliver(); first.close()
        val second = f.scope(); val reopened = view(); second.load(reopened, target)
        assertSame(bitmap(original), bitmap(reopened))
        assertEquals(listOf(target), f.fetches)
        assertEquals(0, f.worker.size)
        second.close()
    }

    @Test fun recycledViewRejectsAlreadyPostedCompletionForItsPreviousPoster() {
        val f = Fixture(); val images = f.scope(); val recycled = view()
        images.load(recycled, url("previous")); f.worker.drain()
        images.load(recycled, url("current")); f.deliver()
        assertNull(recycled.drawable)
        f.worker.drain(); f.deliver()
        assertSame(f.bitmaps[url("current")], bitmap(recycled))
        images.close()
    }

    @Test fun repeatedBindingDoesNotCancelOrRestartAnInFlightVisiblePoster() {
        val f = Fixture(); val images = f.scope(); val poster = view()
        images.load(poster, url("same")); images.load(poster, url("same")); images.load(poster, url("same"))
        f.worker.drain(); f.deliver()
        assertEquals(listOf(url("same")), f.fetches)
        assertNotNull(poster.drawable)
        images.close()
    }

    @Test fun cancelRejectsPostedCallbacksWithoutFlushingTheSharedBitmap() {
        val f = Fixture(); val images = f.scope(); val poster = view(); val target = url("cancel")
        images.load(poster, target); f.worker.drain(); images.cancel(); f.deliver()
        assertNull(poster.drawable)
        images.load(poster, target)
        assertSame(f.bitmaps[target], bitmap(poster))
        assertEquals(1, f.fetches.size)
        images.close()
    }

    @Test fun memoryCacheEvictsLeastRecentlyUsedImagesWithinItsByteLimit() {
        val f = Fixture(2 * 8 * 8 * 4); val images = f.scope(); val poster = view()
        for (name in listOf("first", "second", "third")) {
            images.load(poster, url(name)); f.worker.drain(); f.deliver()
        }
        assertNull(f.store.cached(url("first")))
        assertNotNull(f.store.cached(url("second")))
        assertNotNull(f.store.cached(url("third")))
        assertFalse(f.bitmaps[url("first")]!!.isRecycled)
        images.close()
    }

    @Test fun liveWarmupKeepsAllTwentyFourPostersAvailableAfterTheLibraryPrefetchesAhead() {
        val worker = Worker()
        val fetched = mutableListOf<String>()
        val store = PosterImageStore(worker, { it.run() }, { target, _ ->
            fetched.add(target)
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        })
        val warm = LibraryPosterImages(store)
        val library = LibraryPosterImages(store)
        val firstPage = (0 until 24).map { index ->
            LibraryWork("source", "$index", 1, 1, "Film $index", url("landing-$index"))
        }
        warm.warmFirstPage(firstPage)
        worker.drain()
        library.prefetch((24 until 36).map { url("ahead-$it") })
        worker.drain()
        val beforeEntry = fetched.toList()
        val immediateHits = firstPage.count { work -> view().also { library.load(it, work) }.drawable != null }
        assertEquals("the warmed first page must render without waiting for another GET", 24, immediateHits)
        assertEquals("entering the library must not enqueue refetches", 0, worker.size)
        assertEquals(beforeEntry, fetched)
        warm.close(); library.close(); worker.drain()
    }

    @Test fun ordinaryPrefetchDoesNotAcquireLandingProtection() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val images = f.scope()
        images.prefetchWorks((0 until 4).map { work("ordinary-$it") }); f.worker.drain()
        assertNull(f.store.cached(url("ordinary-0")))
        assertNull(f.store.cached(url("ordinary-1")))
        assertNotNull(f.store.cached(url("ordinary-3")))
        images.close()
    }

    @Test fun cancellingTheWarmOwnerReleasesItsProtectionWithoutFlushingAnotherScreensPosters() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val warm = f.scope(); val library = f.scope()
        warm.warmFirstPage((0 until 4).map { work("landing-$it") }); f.worker.drain()
        library.prefetch((0 until 4).map { url("ahead-$it") }); f.worker.drain()
        library.cancel()
        assertNotNull(f.store.cached(url("landing-0")))
        warm.cancel()
        assertNull(f.store.cached(url("landing-0")))
        assertNotNull(f.store.cached(url("ahead-3")))
        assertFalse(f.bitmaps[url("landing-0")]!!.isRecycled)
        warm.close(); library.close()
    }

    @Test fun cancellingWarmupKeepsAnotherScreensSharedVisibleRequestAlive() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val warm = f.scope(); val library = f.scope()
        val selected = work("shared-warm"); val poster = view()
        warm.warmFirstPage(listOf(selected)); library.load(poster, selected)
        warm.cancel(); f.worker.drain(); f.deliver()
        assertNotNull(poster.drawable)
        assertEquals(listOf(selected.poster), f.fetches)
        library.prefetch((0 until 3).map { url("ahead-$it") }); f.worker.drain()
        assertNull(f.store.cached(selected.poster))
        assertFalse(bitmap(poster)!!.isRecycled)
        warm.close(); library.close()
    }

    @Test fun aReplacementLandingBatchTransfersReadyOverlapAndReleasesTheOldSelection() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val warm = f.scope(); val next = f.scope(); val library = f.scope()
        warm.warmFirstPage((0 until 4).map { work("old-$it") }); f.worker.drain()
        val retained = f.store.cached(url("old-0"))
        next.warmFirstPage(listOf(work("old-0"), work("new"))); f.worker.drain()
        warm.close()
        library.prefetch((0 until 4).map { url("ahead-$it") }); f.worker.drain()
        assertSame(retained, f.store.cached(url("old-0")))
        assertNotNull(f.store.cached(url("new")))
        assertNull(f.store.cached(url("old-1")))
        assertEquals(1, f.fetches.count { it == url("old-0") })
        next.close(); library.close()
    }

    @Test fun replacingTheWarmBatchReusesMatchingInFlightJobs() {
        val f = Fixture(); val warm = f.scope()
        val selected = listOf(work("a"), work("b"), work("c"))
        repeat(4) { warm.warmFirstPage(selected) }
        f.worker.drain()
        assertEquals(selected.map(LibraryWork::poster), f.fetches)
        selected.forEach { assertNotNull(f.store.cached(it.poster)) }
        warm.close()
    }

    @Test fun sourceChangesReleaseReadyLandingImagesAndDiscardPendingWarmResults() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val warm = f.scope(); val library = f.scope()
        warm.warmFirstPage(listOf(work("old"))); f.worker.drain()
        library.prefetch((0 until 3).map { url("ahead-$it") }); f.worker.drain()
        assertNotNull(f.store.cached(url("old")))
        f.sourceRevision++
        assertNull(f.store.cached(url("old")))
        assertNotNull(f.store.cached(url("ahead-2")))
        warm.warmFirstPage((0 until 4).map { work("pending-$it") })
        f.sourceRevision++
        f.worker.drain()
        repeat(4) { assertNull(f.store.cached(url("pending-$it"))) }
        warm.close(); library.close()
    }

    @Test fun aStaleCatalogIdentityCannotEnqueueOrProtectItsLandingPosters() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4); val warm = f.scope(); val library = f.scope()
        warm.warmFirstPage(listOf(work("old")), expectedIdentity = "1"); f.worker.drain()
        library.prefetch((0 until 3).map { url("ahead-$it") }); f.worker.drain()
        assertNotNull(f.store.cached(url("old")))
        val before = f.fetches.toList()
        f.sourceRevision = 2
        warm.warmFirstPage(listOf(work("stale-catalog")), expectedIdentity = "1")
        assertEquals("stale catalog posters must never enter the queue", 0, f.worker.size)
        assertEquals(before, f.fetches)
        assertNull("the former source's protection must also be released", f.store.cached(url("old")))
        assertNull(f.store.cached(url("stale-catalog")))
        val fresh = work("current").copy(revision = 2)
        warm.warmFirstPage(listOf(fresh), expectedIdentity = "2"); f.worker.drain()
        library.prefetch((0 until 3).map { url("later-$it") }); f.worker.drain()
        warm.warmFirstPage(listOf(work("late-old-catalog")), expectedIdentity = "1")
        assertEquals(0, f.worker.size)
        assertNotNull(f.store.cached(fresh.poster))
        warm.close(); library.close()
    }

    @Test fun sourceChangesWhilePreparingTargetsRejectTheWholeExpectedLandingBatch() {
        val worker = Worker(); var identity = "A"; val fetched = mutableListOf<String>()
        val resolver = object : PosterResolver {
            override fun prepare(work: LibraryWork): PosterLookup? { identity = "B"; return null }
            override fun resolve(lookup: PosterLookup, request: VodRequest): Bitmap? = null
        }
        val store = PosterImageStore(worker, { it.run() }, { target, _ ->
            fetched.add(target); Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }, resolver = resolver, sourceIdentity = { identity })
        val warm = LibraryPosterImages(store)
        warm.warmFirstPage(listOf(work("old-catalog")), expectedIdentity = "A")
        assertEquals(0, worker.size)
        assertTrue(fetched.isEmpty())
        assertNull(store.cached(url("old-catalog")))
        warm.close()
    }

    @Test fun landingWorkCanReplaceOrdinaryQueuedSpeculationWithoutExceedingTheQueueLimit() {
        val f = Fixture(memoryBytes = 2 * 8 * 8 * 4)
        val normal = listOf(f.scope(), f.scope()); val warm = f.scope()
        normal.forEachIndexed { index, images -> images.prefetch((0 until 24).map { url("ahead-$index-$it") }) }
        val selected = (0 until 24).map { work("landing-$it") }
        warm.warmFirstPage(selected); f.worker.drain()
        assertEquals(PosterImageStore.MAX_QUEUED_PREFETCH + 2, f.fetches.size)
        assertEquals(selected.map(LibraryWork::poster), f.fetches.filter { "landing-" in it })
        assertEquals(url("landing-0"), f.fetches[2])
        selected.forEach { assertNotNull(f.store.cached(it.poster)) }
        normal.forEach { it.close() }; warm.close()
    }

    @Test fun landingWindowAndCombinedCacheRemainByteBoundedAcrossMultipleWarmOwners() {
        val worker = Worker(); val fetched = mutableListOf<String>()
        val store = PosterImageStore(worker, { it.run() }, { target, _ ->
            fetched.add(target); Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        })
        val first = LibraryPosterImages(store); val second = LibraryPosterImages(store); val library = LibraryPosterImages(store)
        first.warmFirstPage((0 until 96).map { work("first-$it") }); worker.drain()
        assertEquals(24, fetched.size)
        val selected = (0 until 24).map { work("second-$it") }
        second.warmFirstPage(selected); worker.drain()
        library.prefetch((0 until 24).map { url("ahead-$it") }); worker.drain()
        val bytes = fetched.distinct().sumOf { store.cached(it)?.allocationByteCount?.toLong() ?: 0L }
        assertTrue("landing plus normal cache are bounded by 24 MiB + 12 MiB", bytes <= 36L * 1024 * 1024)
        assertNull(store.cached(url("first-0")))
        selected.forEach { assertNotNull(store.cached(it.poster)) }
        first.close()
        selected.forEach { assertNotNull(store.cached(it.poster)) }
        second.close(); library.close()
    }

    @Test fun credentialsFragmentsUnsupportedSchemesAndLongUrlsNeverEnterTheQueue() {
        val f = Fixture(); val images = f.scope(); val poster = view()
        val invalid = listOf("file:///tmp/poster.png", "https://name:secret@127.0.0.1/poster.png",
            "https://127.0.0.1/poster.png#fragment", "https://127.0.0.1/" + "x".repeat(8192))
        images.prefetch(invalid); invalid.forEach { images.load(poster, it) }; f.worker.drain()
        assertTrue(f.fetches.isEmpty()); assertNull(poster.drawable)
        images.close()
    }

    @Test fun oddSizedImagesDecodeToAtMost512PixelsOnEitherSide() {
        val source = Bitmap.createBitmap(1281, 641, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { source.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val decoded = LibraryPosterImages.decode(bytes)
        assertNotNull(decoded)
        assertEquals(512, decoded!!.width)
        assertEquals(256, decoded.height)
        assertTrue(decoded.allocationByteCount <= 512 * 512 * 4)
        assertNull(LibraryPosterImages.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun posterDecodeKeepsTargetResolutionInsteadOfRoundingDownAnotherSamplingStep() {
        val source = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { source.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val decoded = LibraryPosterImages.decode(bytes)!!
        assertEquals(512, decoded.height)
        assertEquals(341, decoded.width)
        assertEquals(Bitmap.Config.ARGB_8888, decoded.config)
        assertTrue(decoded.allocationByteCount <= 512 * 512 * 4)
    }
}
