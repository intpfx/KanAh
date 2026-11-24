package com.live.tv.media

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryPosterResolverTest {
    private fun work() = LibraryWork("original", "film", 1, 1, "同一部电影", "http://127.0.0.1/broken.jpg", "2026", "剧情片")
    private fun sources(count: Int = 3) = JSONObject().put("sources", JSONArray((listOf("original") + (1..count).map { "other-$it" }).map {
        JSONObject().put("id", it).put("revision", 1).put("epoch", 1).put("searchable", true).put("requiresParser", false)
    }))
    private fun item(title: String = "同一部电影", year: String = "2026", type: String = "剧情片", picture: String = "match") =
        JSONObject().put("title", title).put("year", year).put("typeName", type).put("poster", "http://127.0.0.1/$picture.jpg")
    private fun page(source: String, vararg items: JSONObject) = JSONObject().put("sourceId", source)
        .put("revision", 1).put("epoch", 1).put("items", JSONArray(items.toList()))
    private fun bitmap() = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)

    @Test fun matchingOtherSourceSuppliesOnlyArtworkWithoutChangingTheOriginalReference() {
        val original = work(); val calls = mutableListOf<String>(); val picture = bitmap()
        val resolver = LibraryPosterResolver({ sources() }, { source, query, _ ->
            calls.add(source); assertEquals(original.title, query); page(source, item(type = "悬疑片"))
        }, { _, _ -> picture })
        assertSame(picture, resolver.resolve(resolver.prepare(original)!!, VodRequest()))
        assertEquals(listOf("other-1"), calls)
        assertEquals("original", original.sourceId); assertEquals("film", original.vodId)
        assertEquals("http://127.0.0.1/broken.jpg", original.poster)
    }

    @Test fun remakeDifferentSeasonUnknownYearDifferentKindAndDefaultImagesAreRejected() {
        val downloaded = mutableListOf<String>()
        val resolver = LibraryPosterResolver({ sources(1) }, { source, _, _ -> page(source,
            item(year = "2025"), item(title = "同一部电影第二季"), item(year = ""), item(type = "国产剧"),
            item(picture = "movie_default_small"), item(picture = "no_pic"), item(picture = "placeholder"))
        }, { url, _ -> downloaded.add(url); bitmap() })
        assertNull(resolver.resolve(resolver.prepare(work())!!, VodRequest()))
        assertTrue(downloaded.isEmpty())
    }

    @Test fun missingYearOrTypeDoesNotRiskAssigningAnUnrelatedPoster() {
        val resolver = LibraryPosterResolver({ sources() }, { _, _, _ -> fail("no search expected"); JSONObject() }, { _, _ -> bitmap() })
        assertNull(resolver.prepare(work().copy(year = "")))
        assertNull(resolver.prepare(work().copy(type = "")))
        assertNull(resolver.prepare(work().copy(year = "2025/2026")))
    }

    @Test fun lookupIsLimitedToThreeOtherSourcesAndThreeImageAttempts() {
        val searched = mutableListOf<String>(); val downloaded = mutableListOf<String>()
        val resolver = LibraryPosterResolver({ sources(8) }, { source, _, _ ->
            searched.add(source); page(source, *(1..20).map { item(picture = "$source-$it") }.toTypedArray())
        }, { url, _ -> downloaded.add(url); null })
        val lookup = resolver.prepare(work())!!
        assertEquals(3, lookup.sources.size)
        assertNull(resolver.resolve(lookup, VodRequest()))
        assertEquals(3, downloaded.size)
        val emptySearches = mutableListOf<String>()
        val empty = LibraryPosterResolver({ sources(8) }, { source, _, _ -> emptySearches.add(source); page(source) }, { _, _ -> null })
        assertNull(empty.resolve(empty.prepare(work())!!, VodRequest()))
        assertEquals(3, emptySearches.size)
    }

    @Test fun failedSourceAndFailedImageCanFallThroughToTheNextConfiguredSource() {
        val requested = mutableListOf<String>(); val picture = bitmap()
        val resolver = LibraryPosterResolver({ sources() }, { source, _, _ ->
            if (source == "other-1") throw VodException("HTTP_503", "unavailable")
            page(source, item(picture = source))
        }, { url, _ -> requested.add(url); if ("other-2" in url) null else picture })
        assertSame(picture, resolver.resolve(resolver.prepare(work())!!, VodRequest()))
        assertEquals(listOf("http://127.0.0.1/other-2.jpg", "http://127.0.0.1/other-3.jpg"), requested)
    }

    @Test fun repeatedCandidateUrlAndTinyPlaceholderAreNotAcceptedOrRetried() {
        var downloads = 0
        val resolver = LibraryPosterResolver({ sources() }, { source, _, _ -> page(source, item()) }, { _, _ ->
            downloads++; Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        })
        assertNull(resolver.resolve(resolver.prepare(work())!!, VodRequest()))
        assertEquals(1, downloads)
    }

    @Test fun changedSourceRevisionInvalidatesBothLookupKeyAndPendingImageResult() {
        val state = sources(); val picture = bitmap()
        val resolver = LibraryPosterResolver({ state }, { source, _, _ -> page(source, item()) }, { _, _ ->
            state.getJSONArray("sources").getJSONObject(1).put("revision", 2); picture
        })
        val previous = resolver.prepare(work())!!
        try { resolver.resolve(previous, VodRequest()); fail("old source must be rejected") }
        catch (error: VodException) { assertEquals("STALE_SELECTION", error.code) }
        assertNotEquals(previous.key, resolver.prepare(work())!!.key)
    }

    @Test fun cancellationStopsBeforeAnyFurtherSourceOrImageRequest() {
        val request = VodRequest(); var searches = 0; var downloads = 0
        val resolver = LibraryPosterResolver({ sources() }, { source, _, _ ->
            searches++; request.cancel(); page(source, item())
        }, { _, _ -> downloads++; bitmap() })
        try { resolver.resolve(resolver.prepare(work())!!, request); fail("cancelled lookup must stop") }
        catch (error: VodException) { assertEquals("CANCELLED", error.code) }
        assertEquals(1, searches); assertEquals(0, downloads)
    }

    @Test fun changedSearchabilityAndRemovedSourcesChangeFallbackCacheIdentity() {
        val state = sources(); val resolver = LibraryPosterResolver({ state }, { source, _, _ -> page(source) }, { _, _ -> null })
        val first = resolver.prepare(work())!!.key
        state.getJSONArray("sources").getJSONObject(1).put("searchable", false)
        val second = resolver.prepare(work())!!.key
        assertNotEquals(first, second)
        state.getJSONArray("sources").remove(2)
        assertNotEquals(second, resolver.prepare(work())!!.key)
    }

    @Test fun placeholderDetectionIncludesWrappedDefaultUrlsWithoutBlockingNormalPosters() {
        for (path in listOf("movie_default_small.png", "no_pic.jpg", "nopic.png", "placeholder.jpg", "404.png")) {
            assertFalse(PosterMatch.usableUrl("https://proxy.invalid/https://origin.invalid/pics/$path"))
        }
        assertTrue(PosterMatch.usableUrl("https://127.0.0.1/poster/a40de8.jpg"))
        assertTrue(PosterMatch.same(work().copy(title = " Same Film "), item(title = "same  film")))
    }

    @Test fun exactEmbeddedOriginCanRestoreTheSamePosterWithoutOtherSourcesOrMetadata() {
        val original = "https://www.pianku0.cc/doubanio/view/photo/s_ratio_poster/public/p2936417029.jpg"
        val selected = work().copy(poster = "https://img.ukuapi.com/$original", year = "", type = "")
        val calls = mutableListOf<String>(); val picture = bitmap()
        val resolver = LibraryPosterResolver({ sources(0) }, { _, _, _ -> fail("metadata-free lookup must not search"); JSONObject() },
            { url, _ -> calls.add(url); picture })
        assertSame(picture, resolver.resolve(resolver.prepare(selected)!!, VodRequest()))
        assertEquals(listOf(original), calls)
    }

    @Test fun crossSourceMatchingHasPriorityAndImageBudgetReservesTheEmbeddedOrigin() {
        val original = "https://www.pianku0.cc/doubanio/view/photo/s_ratio_poster/public/p2936417029.jpg"
        val selected = work().copy(poster = "https://img.ukuapi.com/$original")
        val calls = mutableListOf<String>(); val picture = bitmap()
        val resolver = LibraryPosterResolver({ sources() }, { source, _, _ ->
            page(source, *(1..10).map { item(picture = "candidate-$it") }.toTypedArray())
        }, { url, _ -> calls.add(url); if (url == original) picture else null })
        assertSame(picture, resolver.resolve(resolver.prepare(selected)!!, VodRequest()))
        assertEquals(3, calls.size); assertEquals(original, calls.last())
    }

    @Test fun embeddedOriginUnwrappingDoesNotGuessHostsRoutesOrImageIds() {
        val original = "https://www.pianku0.cc/doubanio/view/photo/s_ratio_poster/public/p2936417029.jpg"
        assertEquals(original, PosterMatch.embeddedOriginal("https://img.ukuapi.com/$original"))
        for (value in listOf("https://other.invalid/$original", "http://img.ukuapi.com/$original",
            "https://img.ukuapi.com/${original.replace("www.pianku0.cc", "other.invalid")}",
            "https://img.ukuapi.com/${original.replace("p2936417029.jpg", "movie_default_small.png")}",
            "https://img.ukuapi.com/$original?replacement=1")) assertNull(PosterMatch.embeddedOriginal(value))
    }
}
