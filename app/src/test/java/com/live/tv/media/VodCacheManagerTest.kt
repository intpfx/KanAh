package com.live.tv.media

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodCacheManagerTest {
    private fun ref(epoch: Int = 1) = JSONObject().put("sourceId", "fixture").put("vodId", "movie")
        .put("lineId", "line").put("episodeKey", "episode").put("revision", 1).put("epoch", epoch)
    private fun spec(mime: String = "video/mp4") = JSONObject().put("url", "https://fixture.invalid/movie.mp4")
        .put("id", "native-id").put("mimeType", mime).put("title", "Local fixture").put("headers", JSONObject()).put("vodRef", ref())
        .put("resourceTitle", "Resource title").put("episodeLabel", "第01集")
        .put("episodeItems", org.json.JSONArray().put(JSONObject().put("label", "第01集").put("ref", ref())))
    private fun <T> background(block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        return try { pool.submit<T> { block() }.get(10, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
    }
    private fun await(manager: VodCacheManager, state: String): JSONObject = background {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var row = JSONObject()
        while (System.nanoTime() < end) {
            val rows = manager.status().getJSONArray("caches")
            if (rows.length() > 0) { row = rows.getJSONObject(0); if (row.optString("state") == state) return@background row }
            Thread.sleep(10)
        }
        fail("Expected $state, got $row"); row
    }
    private fun directory() = java.nio.file.Files.createTempDirectory("vod-cache-fixture").toFile()
    private class StorageDirectory(path: File, var total: Long, var available: Long) : File(path.absolutePath) {
        override fun getTotalSpace() = total
        override fun getUsableSpace() = available
    }

    @Test fun automaticBudgetUsesAvailableStorageAndReservesTheGreaterOfOneGiBOrTenPercent() {
        val context: Context = ApplicationProvider.getApplicationContext(); val gib = 1024L * 1024 * 1024
        for ((total, available, reserve) in listOf(Triple(8 * gib, 5 * gib, gib), Triple(64 * gib, 20 * gib, 64 * gib / 10))) {
            val dir = StorageDirectory(directory(), total, available)
            val manager = VodCacheManager(context, dir, resolver = { _, _ -> error("Status must not resolve media") }, upstream = { error("Status must not open network") })
            try {
                val initial = background { manager.status() }
                assertEquals("automatic", initial.getString("budgetMode"))
                assertEquals(reserve, initial.getLong("reservedBytes"))
                assertEquals(available, initial.getLong("availableBytes"))
                assertEquals(available - reserve, initial.getLong("remainingBytes"))
                assertEquals(available - reserve, initial.getLong("maxBytes"))
                assertTrue(initial.getLong("maxBytes") > gib)
                dir.available = reserve + 123
                assertEquals(123, background { manager.status().getLong("remainingBytes") })
                dir.available = reserve - 1
                assertEquals(0, background { manager.status().getLong("remainingBytes") })
                assertFalse(File(dir, "media").exists())
            } finally { background { manager.close() }; dir.deleteRecursively() }
        }
    }

    @Test fun explicitBudgetAndReserveRemainInjectableAndStatusUsesTheSmallerAllowance() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = StorageDirectory(directory(), 10_000, 8_000)
        val manager = VodCacheManager(context, dir, 5_000, 500)
        try {
            val initial = background { manager.status() }
            assertEquals("fixed", initial.getString("budgetMode"))
            assertEquals(5_000, initial.getLong("maxBytes")); assertEquals(500, initial.getLong("reservedBytes"))
            assertEquals(5_000, initial.getLong("remainingBytes"))
            dir.available = 900
            assertEquals(400, background { manager.status().getLong("remainingBytes") })
            assertEquals(5_000, background { manager.status().getLong("maxBytes") })
        } finally { background { manager.close() }; dir.deleteRecursively() }
    }

    @Test fun completedMp4PersistsAndPlaysCacheOnlyWithoutUpstreamOrUrlsInStatus() {
        val context: Context = ApplicationProvider.getApplicationContext(); val dir = directory(); val body = ByteArray(12_345) { (it % 251).toByte() }
        var accesses = 0
        val manager = VodCacheManager(context, dir, 100_000, 0, { _, _ -> spec() }, { accesses++; androidx.media3.datasource.DataSource.Factory { ByteArrayDataSource(body) } })
        try {
            val first = background { manager.enqueue(ref()) }; await(manager, "complete")
            assertEquals(1, accesses)
            context.getSharedPreferences("media-progress", 0).edit().putLong("native-id", 12_000).putLong("native-id:updatedAt", 1234).commit()
            val publicRow = background { manager.status().getJSONArray("caches").getJSONObject(0) }
            assertEquals("Resource title", publicRow.getString("resourceTitle"))
            assertEquals("第01集", publicRow.getString("episodeLabel"))
            assertEquals(12_000, publicRow.getLong("positionMs"))
            assertEquals(1234, publicRow.getLong("lastPlayedAt"))
            assertFalse(publicRow.has("spec")); assertFalse(publicRow.has("resources")); assertFalse(publicRow.has("url"))
            context.getSharedPreferences("media-progress", 0).edit().remove("native-id").remove("native-id:updatedAt").commit()
            assertFalse(background { manager.status().toString() }.contains("fixture.invalid"))
            assertFalse(background { manager.status().toString() }.contains("headers"))
            assertTrue(background { manager.hasCompleted(ref()) }); assertFalse(background { manager.hasCompleted(ref(2)) })
            assertEquals(first.getJSONObject("cache").getString("id"), background { manager.enqueue(ref()) }.getJSONObject("cache").getString("id"))
        } finally { background { manager.close() } }
        val resumed = VodCacheManager(context, dir, 100_000, 0, { _, _ -> error("No network resolution") }, { error("No network factory") })
        try {
            background {
                val cached = resumed.completedSpec(ref())!!
                assertEquals("Resource title", cached.getString("resourceTitle"))
                assertEquals("第01集", cached.getString("episodeLabel"))
                assertEquals("episode", cached.getJSONArray("episodeItems").getJSONObject(0).getJSONObject("ref").getString("episodeKey"))
                val source = resumed.playbackFactory(cached).createDataSource()
                try {
                    source.open(DataSpec(Uri.parse(cached.getString("url"))))
                    val actual = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; actual.write(buffer, 0, count) }
                    assertArrayEquals(body, actual.toByteArray())
                } finally { source.close() }
            }
        } finally { background { resumed.close() }; dir.deleteRecursively() }
    }
    @Test fun hlsRetryReusesCompletedSegmentAndLivePlaylistFailsImmediately() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = directory()
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nfirst.ts\n#EXTINF:4,\nsecond.ts\n#EXT-X-ENDLIST\n"
        val firstOpens = AtomicLong()
        val secondOpens = AtomicLong()
        val manager = VodCacheManager(context, dir, 100_000, 0, { _, _ -> spec("application/x-mpegURL") }, {
            androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.DataSource {
                    private var delegate: ByteArrayDataSource? = null
                    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
                    override fun open(data: DataSpec): Long {
                        val bytes = when (data.uri.lastPathSegment) {
                            "first.ts" -> {
                                firstOpens.incrementAndGet()
                                byteArrayOf(1, 2, 3)
                            }
                            "second.ts" -> {
                                if (secondOpens.incrementAndGet() == 1L) throw java.net.SocketException("Connection reset")
                                byteArrayOf(4, 5, 6)
                            }
                            else -> manifest.toByteArray()
                        }
                        return ByteArrayDataSource(bytes).also { delegate = it }.open(data)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
                    override fun getUri() = delegate?.uri
                    override fun close() { delegate?.close() }
                }
            }
        })
        try {
            background { manager.enqueue(ref()) }
            await(manager, "complete")
            assertTrue(background { manager.hasCompleted(ref()) })
            assertEquals(1L, firstOpens.get())
            assertEquals(2L, secondOpens.get())
        } finally {
            background { manager.close() }
            dir.deleteRecursively()
        }
        val liveDir = directory()
        val liveReads = AtomicLong()
        val live = VodCacheManager(context, liveDir, 100_000, 0, { _, _ -> spec("application/x-mpegURL") }, {
            androidx.media3.datasource.DataSource.Factory {
                liveReads.incrementAndGet()
                ByteArrayDataSource(manifest.replace("#EXT-X-ENDLIST\n", "").toByteArray())
            }
        })
        try {
            background { live.enqueue(ref()) }
            val failure = await(live, "failed")
            assertEquals("CACHE_UNSUPPORTED", failure.getString("errorCode"))
            assertTrue(failure.getString("error").contains("直播"))
            assertEquals(1L, liveReads.get())
            assertFalse(background { live.hasCompleted(ref()) })
        } finally {
            background { live.close() }
            liveDir.deleteRecursively()
        }
    }
    @Test fun clearEpisodesCancelsActiveAndQueuedJobsAndPreservesPlaybackHistory() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = directory()
        val entered = CountDownLatch(1)
        val progress = context.getSharedPreferences("media-progress", Context.MODE_PRIVATE)
        progress.edit().putLong("native-id", 42_000).apply()
        val work = LibraryWork("fixture", "movie", 1, 1, "Local fixture")
        val history = RecentVodHistory(context)
        history.record(work, ref(), "第01集")
        val activeRef = ref().put("episodeKey", "active")
        val queuedRef = ref().put("episodeKey", "queued")
        val manager = VodCacheManager(context, dir, 100_000, 0, { reference, request ->
            if (reference.getString("episodeKey") == "active") {
                entered.countDown()
                while (!request.isCancelled()) Thread.sleep(5)
                request.check()
            }
            assertEquals("episode", reference.getString("episodeKey"))
            spec()
        }, { androidx.media3.datasource.DataSource.Factory { ByteArrayDataSource(ByteArray(256)) } })
        try {
            background { manager.enqueue(ref()) }
            await(manager, "complete")
            background { manager.enqueue(activeRef); manager.enqueue(queuedRef) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertEquals(0, background { manager.clearEpisodes() })
            val status = background { manager.status() }
            assertEquals(0, status.getJSONArray("caches").length())
            assertEquals(0L, status.getLong("usedBytes"))
            assertEquals(42_000L, progress.getLong("native-id", 0))
            assertEquals("episode", history.entries().single().getString("episodeKey"))
        } finally {
            background { manager.close() }
            dir.deleteRecursively()
        }
    }

    @Test fun cancellingResolverRemovesPartialStateAndPreventsLateCompletion() {
        val context: Context = ApplicationProvider.getApplicationContext(); val dir = directory(); val entered = CountDownLatch(1)
        val manager = VodCacheManager(context, dir, 100_000, 0, { _, request -> entered.countDown(); while (!request.isCancelled()) Thread.sleep(5); request.check(); spec() }, { error("Cancelled resolver must not start network") })
        try {
            val id = background { manager.enqueue(ref()) }.getJSONObject("cache").getString("id")
            assertTrue(entered.await(2, TimeUnit.SECONDS)); background { manager.cancel(id) }
            background { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(manager.status().getJSONArray("caches").length()>0 && System.nanoTime()<end) Thread.sleep(10); assertEquals(0,manager.status().getJSONArray("caches").length()) }
            assertFalse(background { manager.hasCompleted(ref()) })
        } finally { background { manager.close() }; dir.deleteRecursively() }
    }
    @Test fun queuedCacheCancellationDoesNotWaitForAnotherEpisodeDownload() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val manager=VodCacheManager(context,dir,100_000,0,{_,_->entered.countDown();release.await();spec()},{androidx.media3.datasource.DataSource.Factory{ByteArrayDataSource(ByteArray(256))}})
        try{
            background{manager.enqueue(ref())};assertTrue(entered.await(2,TimeUnit.SECONDS))
            val second=ref().put("episodeKey","next-episode")
            val id=background{manager.enqueue(second)}.getJSONObject("cache").getString("id")
            background{manager.cancel(id)}
            background{val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(manager.status().getJSONArray("caches").length()>1&&System.nanoTime()<end)Thread.sleep(10);assertEquals(1,manager.status().getJSONArray("caches").length())}
        }finally{release.countDown();background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun failedMp4ResumesPrivatePartialBytesAfterRestartAndCancelRemovesCompletedData() {
        val context: Context=ApplicationProvider.getApplicationContext();val dir=directory();val body=ByteArray(16_384){(it%123).toByte()}
        var failOnce=true;val positions=mutableListOf<Long>()
        fun network(): androidx.media3.datasource.DataSource.Factory = androidx.media3.datasource.DataSource.Factory { object: androidx.media3.datasource.DataSource {
            val data=ByteArrayDataSource(body);var reads=0
            override fun addTransferListener(listener:androidx.media3.datasource.TransferListener){}
            override fun open(spec:DataSpec):Long{synchronized(positions){positions.add(spec.position)};return data.open(spec)}
            override fun read(b:ByteArray,o:Int,l:Int):Int{if(failOnce && reads++>0){failOnce=false;throw java.io.IOException("Fixture interrupted")};return data.read(b,o,minOf(l,4096))}
            override fun getUri()=data.uri
            override fun close(){data.close()}
        } }
        val first=VodCacheManager(context,dir,100_000,0,{_,_->spec()},{network()})
        try{background{first.enqueue(ref())};val failed = await(first,"failed")
            assertEquals("CACHE_IO", failed.getString("errorCode"))
            assertTrue(failed.getString("errorDetail").contains("IOException"))
            assertFalse(failed.toString().contains("Fixture interrupted"))
            assertTrue(background{first.status().getLong("usedBytes")}>0)}finally{background{first.close()}}
        val resumed=VodCacheManager(context,dir,100_000,0,{_,_->spec()},{network()})
        try{
            assertEquals("CACHE_IO", await(resumed,"failed").getString("errorCode"))
            val id=background{resumed.enqueue(ref())}.getJSONObject("cache").getString("id");await(resumed,"complete")
            assertTrue(positions.any{it>0});assertTrue(background{resumed.hasCompleted(ref())})
            background{resumed.cancel(id)}
            background{val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(resumed.status().getJSONArray("caches").length()>0&&System.nanoTime()<end)Thread.sleep(10);assertEquals(0,resumed.status().getLong("usedBytes"))}
        }finally{background{resumed.close()};dir.deleteRecursively()}
    }
    @Test fun networkRetriesResumePartialBytesAndStopAfterThreeRetries() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (persistentFailure in listOf(false, true)) {
            val dir = directory()
            val body = ByteArray(16_384)
            val positions = mutableListOf<Long>()
            val source = androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.DataSource {
                    private val data = ByteArrayDataSource(body)
                    private var fail = false
                    private var reads = 0

                    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
                    override fun open(spec: DataSpec): Long {
                        positions.add(spec.position)
                        fail = persistentFailure || positions.size == 1
                        return data.open(spec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (fail && reads++ > 0) throw java.net.SocketTimeoutException()
                        return data.read(buffer, offset, minOf(1024, length))
                    }
                    override fun getUri() = data.uri
                    override fun close() = data.close()
                }
            }
            val manager = VodCacheManager(context, dir, 100_000, 0, { _, _ -> spec() }, { source })
            try {
                background { manager.enqueue(ref()) }
                val row = await(manager, if (persistentFailure) "failed" else "complete")
                assertEquals(if (persistentFailure) 4 else 2, positions.size)
                assertEquals(0L, positions.first())
                assertTrue(positions.drop(1).all { it > 0 })
                if (persistentFailure) {
                    assertEquals("CACHE_TIMEOUT", row.getString("errorCode"))
                    assertTrue(background { manager.status().getLong("usedBytes") } > 0)
                }
            } finally {
                background { manager.close() }
                dir.deleteRecursively()
            }
        }
    }

    @Test fun missingCompletedFilesInvalidateOfflineCacheAndNormalPlaybackNeverCreatesCache() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory()
        val manager=VodCacheManager(context,dir,100_000,0,{_,_->spec()},{androidx.media3.datasource.DataSource.Factory{ByteArrayDataSource(ByteArray(256))}})
        try{
            background{assertFalse(manager.hasCompleted(ref()));assertNull(manager.completedSpec(ref()));manager.playbackFactory(spec());assertEquals(0,manager.status().getJSONArray("caches").length())}
            assertFalse(File(dir,"media").exists())
            background{manager.enqueue(ref())};await(manager,"complete")
            val fragment=File(dir,"media").walkTopDown().first{it.name.endsWith(".v3.exo")};assertTrue(fragment.delete())
            assertFalse(background{manager.hasCompleted(ref())});assertEquals("failed",background{manager.status().getJSONArray("caches").getJSONObject(0).getString("state")})
        }finally{background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun downloadStopsAtHardBudgetAndNeverReportsPartialAsComplete() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory()
        val manager=VodCacheManager(context,dir,100,0,{_,_->spec()},{androidx.media3.datasource.DataSource.Factory{ByteArrayDataSource(ByteArray(1024))}})
        try{background{manager.enqueue(ref())};val row=await(manager,"failed");assertEquals("CACHE_QUOTA_LIMIT",row.getString("errorCode"));assertTrue(row.getString("error").contains("配额"));assertFalse(background{manager.hasCompleted(ref())});assertTrue(background{manager.status().getLong("usedBytes")}<=100)}
        finally{background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun sinkBoundsCommittedPlusPendingBytesBeforeWritingAndReleasesReservationOnFailure() {
        val pending = AtomicLong(); var writes = 0; var closed = false
        val sink = BudgetSink(object : DataSink { override fun open(spec: DataSpec) {}; override fun write(b: ByteArray, o: Int, l: Int) { writes += l }; override fun close() { closed = true } }, { 6 }, pending, 10, 0, { 100 })
        sink.open(DataSpec(Uri.parse("https://fixture.invalid/"))); sink.write(ByteArray(4),0,4)
        assertEquals(4,pending.get()); assertEquals("CACHE_QUOTA_LIMIT", assertThrows(CacheBudgetException::class.java) { sink.write(ByteArray(1),0,1) }.reason); assertEquals(4,writes)
        sink.close(); assertEquals(0,pending.get()); assertTrue(closed)
        val noSpace = BudgetSink(object : DataSink { override fun open(spec:DataSpec) {}; override fun write(b:ByteArray,o:Int,l:Int) { error("Must refuse first") }; override fun close() {} },{0},pending,100,50,{50})
        assertEquals("CACHE_STORAGE_LOW", assertThrows(CacheBudgetException::class.java){noSpace.write(ByteArray(1),0,1)}.reason)
    }
    @Test fun automaticSinkCanExceedOldGiBCapButCannotOverflowItsCountersOrConsumeSystemReserve() {
        val gib = 1024L * 1024 * 1024; val pending = AtomicLong(); var writes = 0
        val delegate = object : DataSink { override fun open(spec: DataSpec) {}; override fun write(b: ByteArray, o: Int, l: Int) { writes += l }; override fun close() {} }
        val automatic = BudgetSink(delegate, { 2 * gib }, pending, Long.MAX_VALUE, gib, { 4 * gib })
        automatic.write(ByteArray(4), 0, 4)
        assertEquals(4, writes); assertEquals(4, pending.get())
        automatic.close(); assertEquals(0, pending.get())
        val overflow = BudgetSink(delegate, { Long.MAX_VALUE - 1 }, AtomicLong(2), Long.MAX_VALUE, 0, { Long.MAX_VALUE })
        assertEquals("CACHE_QUOTA_LIMIT", assertThrows(CacheBudgetException::class.java) { overflow.write(ByteArray(1), 0, 1) }.reason)
        val lowDisk = BudgetSink(delegate, { 2 * gib }, pending, Long.MAX_VALUE, gib, { gib })
        assertEquals("CACHE_STORAGE_LOW", assertThrows(CacheBudgetException::class.java) { lowDisk.write(ByteArray(1), 0, 1) }.reason)
        assertEquals(4, writes); assertEquals(0, pending.get())
    }
    @Test fun quotaAndStorageFailuresPersistDistinctPublicErrorCodesAfterRestart() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for ((quota, reserve, expected) in listOf(Triple(100L, 0L, "CACHE_QUOTA_LIMIT"), Triple(100_000L, 10_000L, "CACHE_STORAGE_LOW"))) {
            val dir = StorageDirectory(directory(), 100_000, 10_000)
            val manager = VodCacheManager(context, dir, quota, reserve, { _, _ -> spec() }, { androidx.media3.datasource.DataSource.Factory { ByteArrayDataSource(ByteArray(1024)) } })
            try {
                background { manager.enqueue(ref()) }
                val failed = await(manager, "failed")
                assertEquals(expected, failed.getString("errorCode"))
                assertTrue(failed.getString("error"), failed.getString("error").contains(if (expected == "CACHE_QUOTA_LIMIT") "配额" else "系统余量"))
                assertFalse(background { manager.hasCompleted(ref()) })
            } finally { background { manager.close() } }
            val resumed = VodCacheManager(context, dir, quota, reserve, { _, _ -> error("Status must not retry a failed download") }, { error("Status must not open network") })
            try {
                val failed = background { resumed.status().getJSONArray("caches").getJSONObject(0) }
                assertEquals("failed", failed.getString("state")); assertEquals(expected, failed.getString("errorCode"))
                assertFalse(failed.toString().contains("fixture.invalid")); assertFalse(failed.toString().contains("headers"))
            } finally { background { resumed.close() }; dir.deleteRecursively() }
        }
    }
    @Test fun wrappedBudgetFailureKeepsItsPublicReasonWithoutLeakingTransportDetails() {
        val context: Context = ApplicationProvider.getApplicationContext(); val dir = directory()
        val manager = VodCacheManager(context, dir, 100_000, 0, { _, _ ->
            throw java.io.IOException("https://private.invalid/signed?token=private-token", CacheBudgetException("CACHE_STORAGE_LOW", 1024L * 1024 * 1024))
        }, { error("Resolver failure cannot start network") })
        try {
            background { manager.enqueue(ref()) }
            val failed = await(manager, "failed")
            assertEquals("CACHE_STORAGE_LOW", failed.getString("errorCode"))
            assertTrue(failed.getString("error").contains("1 GiB"))
            assertFalse(failed.toString().contains("private-token"))
        } finally { background { manager.close() }; dir.deleteRecursively() }
    }
    @Test fun statusCountsUncommittedDownloadBytesAndCancellationReleasesAllUsage() {
        val context: Context = ApplicationProvider.getApplicationContext(); val dir = directory()
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val manager = VodCacheManager(context, dir, 100_000, 0, { _, _ -> spec() }, {
            androidx.media3.datasource.DataSource.Factory { object : androidx.media3.datasource.DataSource {
                val data = ByteArrayDataSource(ByteArray(8192)); var reads = 0
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
                override fun open(spec: DataSpec) = data.open(spec)
                override fun read(b: ByteArray, o: Int, l: Int): Int {
                    if (reads++ > 0) { blocked.countDown(); release.await() }
                    return data.read(b, o, minOf(l, 4096))
                }
                override fun getUri() = data.uri
                override fun close() { data.close() }
            } }
        })
        try {
            val id = background { manager.enqueue(ref()) }.getJSONObject("cache").getString("id")
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            assertEquals(4096, background { manager.status().getLong("usedBytes") })
            background { manager.cancel(id) }
            background {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (manager.status().getJSONArray("caches").length() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(0, manager.status().getJSONArray("caches").length())
                assertEquals(0, manager.status().getLong("usedBytes"))
            }
        } finally { release.countDown(); background { manager.close() }; dir.deleteRecursively() }
    }
    @Test fun fragmentCommitAndReservationReleaseAreAtomicForUsageSnapshotsEvenIfCloseFails() {
        val pending = AtomicLong(); val committed = AtomicLong(); val committedInClose = CountDownLatch(1); val releaseClose = CountDownLatch(1)
        val sink = BudgetSink(object : DataSink {
            override fun open(spec: DataSpec) {}
            override fun write(b: ByteArray, o: Int, l: Int) {}
            override fun close() { committed.set(4); committedInClose.countDown(); releaseClose.await() }
        }, { committed.get() }, pending, 10, 0, { 100 })
        sink.write(ByteArray(4), 0, 4)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val close = pool.submit { sink.close() }
            assertTrue(committedInClose.await(2, TimeUnit.SECONDS))
            val reading = CountDownLatch(1)
            val usage = pool.submit<Long> { reading.countDown(); synchronized(pending) { committed.get() + pending.get() } }
            assertTrue(reading.await(2, TimeUnit.SECONDS)); assertFalse(usage.isDone)
            releaseClose.countDown(); close.get(2, TimeUnit.SECONDS)
            assertEquals(4, usage.get(2, TimeUnit.SECONDS).toLong()); assertEquals(0, pending.get())
        } finally { releaseClose.countDown(); pool.shutdownNow() }
        val failing = BudgetSink(object : DataSink {
            override fun open(spec: DataSpec) {}
            override fun write(b: ByteArray, o: Int, l: Int) { throw java.io.IOException("Fixture write failed") }
            override fun close() { throw java.io.IOException("Fixture close failed") }
        }, { 0 }, pending, 10, 0, { 100 })
        assertThrows(java.io.IOException::class.java) { failing.write(ByteArray(1), 0, 1) }
        assertEquals(1, pending.get())
        assertThrows(java.io.IOException::class.java) { failing.close() }
        assertEquals(0, pending.get())
    }
    private class BlockingManifest : androidx.media3.datasource.DataSource {
        val reading = CountDownLatch(1); val closed = CountDownLatch(1); private var openedUri: Uri? = null
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
        override fun open(spec: DataSpec): Long { openedUri = spec.uri; return -1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reading.countDown()
            // A streaming response may ignore the Java thread interrupt; closing its body must unblock it.
            while (closed.count > 0) try { closed.await() } catch (_: InterruptedException) {}
            throw java.io.IOException("Fixture stream closed")
        }
        override fun getUri(): Uri? = openedUri
        override fun close() { closed.countDown() }
    }
    @Test fun cancelClosesBlockedPreflightAndNextQueuedEpisodeCanDownload() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory();val blocked=BlockingManifest()
        val manager=VodCacheManager(context,dir,100_000,0,{selected,_->spec(if(selected.optString("episodeKey")=="episode") "application/x-mpegURL" else "video/mp4")},{resolved->
            androidx.media3.datasource.DataSource.Factory{if(resolved.optString("mimeType")=="video/mp4")ByteArrayDataSource(ByteArray(256)) else blocked}
        })
        try{
            val id=background{manager.enqueue(ref())}.getJSONObject("cache").getString("id")
            assertTrue(blocked.reading.await(2,TimeUnit.SECONDS))
            val second=ref().put("episodeKey","second");background{manager.enqueue(second);manager.cancel(id)}
            assertTrue(blocked.closed.await(2,TimeUnit.SECONDS))
            await(manager,"complete");assertTrue(background{manager.hasCompleted(second)})
            assertFalse(background{manager.status().toString()}.contains(id));assertEquals(256,background{manager.status().getLong("usedBytes")})
        }finally{blocked.close();background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun preflightTotalDeadlineClosesSlowManifestWithoutUserCancellation() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory();val blocked=BlockingManifest()
        val opens = AtomicLong()
        val manager=VodCacheManager(context,dir,100_000,0,{_,_->spec("application/x-mpegURL")},{androidx.media3.datasource.DataSource.Factory{
            if (opens.getAndIncrement() == 0L) blocked else BlockingManifest()
        }},manifestTimeoutMillis=150)
        try{
            background{manager.enqueue(ref())};assertTrue(blocked.reading.await(2,TimeUnit.SECONDS))
            val failed=await(manager,"failed");assertTrue(failed.getString("error").contains("超时"))
            assertEquals(4L, opens.get())
            assertEquals(0,blocked.closed.count);assertFalse(background{manager.hasCompleted(ref())});assertEquals(0,background{manager.status().getLong("usedBytes")})
        }finally{blocked.close();background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun unknownLengthManifestIsByteBoundedAndNeverCachedWhenOversized() {
        val context:Context=ApplicationProvider.getApplicationContext();val dir=directory();val count=AtomicLong()
        val bytes=("#EXTM3U\n"+"# comment\n".repeat(100_000)).toByteArray()
        val manager=VodCacheManager(context,dir,2_000_000,0,{_,_->spec("application/x-mpegURL")},{androidx.media3.datasource.DataSource.Factory{
            object:androidx.media3.datasource.DataSource{
                val delegate=ByteArrayDataSource(bytes)
                override fun addTransferListener(listener:androidx.media3.datasource.TransferListener){}
                override fun open(spec:DataSpec):Long{delegate.open(spec);return -1}
                override fun read(b:ByteArray,o:Int,l:Int):Int=delegate.read(b,o,l).also{if(it>0)count.addAndGet(it.toLong())}
                override fun getUri()=delegate.uri
                override fun close(){delegate.close()}
            }
        }})
        try{
            background{manager.enqueue(ref())};assertTrue(await(manager,"failed").getString("error").contains("过大"))
            assertTrue(count.get()<=512L*1024+1);assertFalse(background{manager.hasCompleted(ref())});assertEquals(0,background{manager.status().getLong("usedBytes")})
        }finally{background{manager.close()};dir.deleteRecursively()}
    }
    @Test fun manifestLimitsApplyAcrossChildPlaylistsAndCancelledReadIsRejected() {
        val aggregate=AtomicLong(6);val request=VodRequest();val source=ManifestDataSource(ByteArrayDataSource(ByteArray(6)),request,aggregate,8,10)
        try{assertThrows(VodException::class.java){source.open(DataSpec(Uri.parse("https://fixture.invalid/child.m3u8")))}}
        finally{source.close()}
        val cancelled=VodRequest().also{it.cancel()};val neverOpened=ManifestDataSource(ByteArrayDataSource(ByteArray(8)),cancelled,AtomicLong())
        assertThrows(VodException::class.java){neverOpened.open(DataSpec(Uri.parse("https://fixture.invalid/manifest.m3u8")))}
    }
}
