package com.live.tv.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryWarmupTest {
    private fun sources(revision: Int = 1, epoch: Int = 1, id: String = "source") = JSONObject().put("sources",
        JSONArray(listOf(JSONObject().put("id", id).put("name", "Fixture").put("revision", revision)
            .put("epoch", epoch).put("searchable", true).put("requiresParser", false))))
    private fun page(title: String = "Original") = JSONObject().put("ok", true).put("browse",
        JSONObject().put("page", 1).put("items", JSONArray(listOf(JSONObject().put("title", title)))))

    @Test fun warmFirstPageCanBeReadRepeatedlyWithoutSharingMutableJson() {
        val cache = LibraryFirstPageCache { 100L }; val state = sources()
        val ticket = cache.begin(state)!!
        val fetched = page()
        assertTrue(cache.complete(ticket, state, fetched))
        fetched.getJSONObject("browse").getJSONArray("items").getJSONObject(0).put("title", "Changed")
        val handedOff = cache.firstPage(state)!!
        assertEquals("Original", handedOff.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
        handedOff.getJSONObject("browse").getJSONArray("items").getJSONObject(0).put("title", "Changed by first screen")
        assertEquals("Original", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
        assertNull(cache.begin(state))
    }

    @Test fun longLiveSessionStillHandsOffWarmPage() {
        var clock = 100L; val cache = LibraryFirstPageCache { clock }; val state = sources()
        assertTrue(cache.complete(cache.begin(state)!!, state, page("Ready before watching live")))
        clock += LibraryFirstPageCache.TTL_MILLIS + 1
        val warmed = cache.firstPage(state)
        assertNotNull("a long live session must retain the completed first page for immediate library entry", warmed)
        assertEquals("Ready before watching live", warmed!!.getJSONObject("browse").getJSONArray("items")
            .getJSONObject(0).getString("title"))
    }

    @Test fun usablePartialDirectoryRemainsAvailableWithoutRestartingBackgroundRequests() {
        val cache = LibraryFirstPageCache { 100L }; val state = sources()
        val partial = page()
        partial.getJSONObject("browse").put("sources", JSONArray(listOf(
            JSONObject().put("sourceId", "source").put("status", "success"),
            JSONObject().put("sourceId", "slow-source").put("status", "error").put("code", "SEARCH_TIMEOUT"))))
        assertTrue(cache.complete(cache.begin(state)!!, state, partial))
        assertEquals("error", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("sources").getJSONObject(1).getString("status"))
        repeat(3) {
            assertEquals("Original", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
            assertNull("repeated landing reads must not restart the background request", cache.begin(state))
        }
    }

    @Test fun foregroundMissDoesNotConsumeAPendingWarmPage() {
        val cache = LibraryFirstPageCache { 100L }; val state = sources()
        val pending = cache.begin(state)!!
        assertNull(cache.firstPage(state))
        assertTrue("valid posters can still be prefetched", cache.complete(pending, state, page("Late")))
        assertEquals("Late", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
        assertNull(cache.begin(state))
    }

    @Test fun foregroundMissBeforeWarmJobStartsDoesNotPreventWarmup() {
        val cache = LibraryFirstPageCache { 100L }; val state = sources()
        assertNull(cache.firstPage(state))
        assertNotNull(cache.begin(state))
    }

    @Test fun changedRevisionEpochAndRemovedSourceInvalidateTheWarmPage() {
        for (changed in listOf(sources(revision = 2), sources(epoch = 2), sources(id = "replacement"),
            JSONObject().put("sources", JSONArray()), sources().put("errorCode", "SOURCE_STORE"))) {
            val cache = LibraryFirstPageCache { 100L }; val original = sources()
            assertTrue(cache.complete(cache.begin(original)!!, original, page()))
            assertNull(cache.firstPage(changed))
            assertNotNull("the warmup observes the changed configuration", cache.begin(changed))
            assertNull("invalidated pages cannot reappear", cache.firstPage(original))
        }
    }

    @Test fun pendingWarmResultCannotPublishAfterSourceIdentityChanges() {
        val cache = LibraryFirstPageCache { 100L }; val ticket = cache.begin(sources())!!
        assertFalse(cache.complete(ticket, sources(revision = 2), page()))
        assertNotNull(cache.begin(sources(revision = 2)))
        assertNull(cache.firstPage(sources(revision = 2)))
    }

    @Test fun beginningWarmupForChangedSourcesInvalidatesAnOlderPendingTicket() {
        val cache = LibraryFirstPageCache { 100L }; val old = cache.begin(sources())!!
        val current = sources(epoch = 2)
        assertNull(cache.firstPage(current))
        val replacement = cache.begin(current)!!
        assertFalse(cache.complete(old, sources(), page("Old")))
        assertNull(cache.firstPage(current))
        assertTrue(cache.complete(replacement, current, page("New")))
        assertEquals("New", cache.firstPage(current)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
    }

    @Test fun staleReaderCannotCancelANewerTicketOrDiscardItsCompletedPage() {
        val cache = LibraryFirstPageCache { 100L }; val original = sources()
        assertTrue(cache.complete(cache.begin(original)!!, original, page("Old")))
        val current = sources(epoch = 2); val replacement = cache.begin(current)!!

        assertNull("a stale reader sees no page from the replacement source", cache.firstPage(original))
        assertTrue("the stale read must leave the newer writer able to publish", cache.complete(replacement, current, page("New")))
        assertNull(cache.firstPage(original))
        assertEquals("New", cache.firstPage(current)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
        assertNull("the stale read must not reset refresh throttling", cache.begin(current))
    }

    @Test fun lateOldCompletionCannotReplaceOrClearANewerWarmResult() {
        val cache = LibraryFirstPageCache { 100L }; val old = cache.begin(sources())!!
        val current = sources(epoch = 2); val replacement = cache.begin(current)!!
        assertTrue(cache.complete(replacement, current, page("New")))
        assertFalse(cache.complete(old, sources(), page("Old")))
        assertEquals("New", cache.firstPage(current)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
    }

    @Test fun repeatedResumesAreCoalescedAndRefreshKeepsThePreviousLandingPageAvailable() {
        var clock = 100L; val cache = LibraryFirstPageCache { clock }; val state = sources()
        val ticket = cache.begin(state)!!
        assertNull(cache.begin(state))
        assertTrue(cache.complete(ticket, state, page()))
        clock += LibraryFirstPageCache.TTL_MILLIS - 1
        assertNotNull(cache.firstPage(state)); assertNull(cache.begin(state))
        clock++
        val refreshed = cache.begin(state)!!
        assertEquals("Original", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
        assertTrue(cache.complete(refreshed, state, page("Refreshed")))
        assertEquals("Refreshed", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
    }

    @Test fun failedBackgroundRefreshDoesNotDiscardTheSuccessfulLandingSnapshot() {
        var clock = 100L; val cache = LibraryFirstPageCache { clock }; val state = sources()
        assertTrue(cache.complete(cache.begin(state)!!, state, page("Last successful page")))
        clock += LibraryFirstPageCache.TTL_MILLIS
        assertFalse(cache.complete(cache.begin(state)!!, state, page("Failed refresh").put("ok", false)))
        assertEquals("Last successful page", cache.firstPage(state)!!.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("title"))
    }

    @Test fun failuresAreNotReusedOrRetriedOnEveryResume() {
        val cache = LibraryFirstPageCache { 100L }; val state = sources()
        val ticket = cache.begin(state)!!
        assertFalse(cache.complete(ticket, state, page().put("ok", false)))
        assertNull(cache.firstPage(state)); assertNull(cache.begin(state))
    }

    @Test fun sourceAvailabilityChangesInvalidateWarmMetadata() {
        for (field in listOf("searchable", "requiresParser")) {
            val cache = LibraryFirstPageCache { 100L }; val original = sources()
            assertTrue(cache.complete(cache.begin(original)!!, original, page()))
            val changed = sources()
            val row = changed.getJSONArray("sources").getJSONObject(0)
            row.put(field, !row.getBoolean(field))
            assertNull(cache.firstPage(changed))
        }
    }
}
