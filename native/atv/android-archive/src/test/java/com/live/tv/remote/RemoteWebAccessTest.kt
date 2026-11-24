package com.live.tv.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteWebAccessTest {
    private var now = 1_000L
    private val access = RemoteWebAccess(clock = { now })

    @Test fun noGrantUntilExplicitTvOpenAndOpeningAgainPreservesScannedCode() {
        assertNull(access.currentAccess())
        assertFalse(access.managementAvailable())
        assertNull(access.unlock("123456"))
        val grant = access.openAccess()
        assertTrue(grant.code.matches(Regex("[0-9]{6}")))
        now += 1_000
        assertEquals(grant, access.openAccess())
        now = grant.expiresAt
        assertNull(access.currentAccess())
        assertNull(access.unlock(grant.code))
        assertFalse(access.managementAvailable())
    }

    @Test fun globalWrongAttemptBudgetRequiresExplicitRenewal() {
        val grant = access.openAccess()
        repeat(RemoteWebAccess.MAX_FAILURES) { assertNull(access.unlock("invalid")) }
        assertNull(access.unlock(grant.code))
        assertFalse(access.managementAvailable())
        assertEquals(grant, access.openAccess())
        assertNull(access.unlock(grant.code))
        val renewed = access.renewAccess()
        assertNotNull(access.unlock(renewed.code))
    }

    @Test fun sessionsOutliveGrantButExpireWithoutSlidingAndLogoutIsScoped() {
        val grant = access.openAccess()
        val first = access.unlock(grant.code)!!
        val second = access.unlock(grant.code)!!
        assertEquals(43, first.length)
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
        now = grant.expiresAt
        assertTrue(access.authorized(first))
        access.closeAccess()
        assertTrue(access.authorized(first))
        access.logout(first)
        assertFalse(access.authorized(first))
        assertTrue(access.authorized(second))
        now = 1_000L + RemoteWebAccess.SESSION_MS
        assertFalse(access.authorized(second))
    }

    @Test fun sessionCapEvictsOldestRatherThanGrowingWithoutBound() {
        val grant = access.openAccess()
        val sessions = (0..RemoteWebAccess.MAX_SESSIONS).map { access.unlock(grant.code)!! }
        assertFalse(access.authorized(sessions.first()))
        sessions.drop(1).forEach { assertTrue(access.authorized(it)) }
        assertFalse(access.authorized(""))
        assertFalse(access.authorized(grant.code))
    }
}
