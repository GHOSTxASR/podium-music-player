package app.podium.sources.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitiesTest {

    @Test
    fun `absent capability is unavailable`() {
        val caps = SourceCapabilities.available(Capability.SEARCH)
        assertEquals(CapabilityStatus.UNAVAILABLE, caps[Capability.DOWNLOADS].status)
        assertFalse(caps.isUsable(Capability.DOWNLOADS))
    }

    @Test
    fun `restrictions only narrow, never widen`() {
        val declared = SourceCapabilities.available(Capability.SEARCH, Capability.LIBRARY, Capability.DIRECT_STREAM)
        val account = SourceCapabilities.of(
            Capability.LIBRARY to CapabilityState(CapabilityStatus.REQUIRES_SIGN_IN, "Sign in to see your library"),
            Capability.DOWNLOADS to CapabilityState.Available,
        )
        val effective = declared.restrictedBy(account)
        assertTrue(effective.isUsable(Capability.SEARCH))
        assertEquals(CapabilityStatus.REQUIRES_SIGN_IN, effective[Capability.LIBRARY].status)
        // A probe saying "available" can't add what the provider never declared.
        assertEquals(CapabilityStatus.UNAVAILABLE, effective[Capability.DOWNLOADS].status)
    }

    @Test
    fun `policy can disable offered capabilities but not invent missing ones`() {
        val caps = SourceCapabilities.available(Capability.SEARCH, Capability.DIRECT_STREAM)
            .disable(setOf(Capability.DIRECT_STREAM, Capability.DOWNLOADS), "Turned off in this build")
        assertEquals(CapabilityStatus.DISABLED, caps[Capability.DIRECT_STREAM].status)
        assertEquals(CapabilityStatus.UNAVAILABLE, caps[Capability.DOWNLOADS].status)
    }

    @Test
    fun `actionable statuses are distinguished from unavailable ones`() {
        assertTrue(CapabilityStatus.REQUIRES_PERMISSION.isActionable)
        assertTrue(CapabilityStatus.REQUIRES_PROVIDER_APP.isActionable)
        assertFalse(CapabilityStatus.UNAVAILABLE.isActionable)
        assertTrue(CapabilityStatus.DEGRADED.isUsable)
    }
}
