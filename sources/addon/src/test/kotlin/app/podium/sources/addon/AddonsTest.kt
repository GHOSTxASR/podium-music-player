package app.podium.sources.addon

import app.podium.sources.api.NetworkPolicy
import app.podium.sources.api.SetupProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AddonsTest {

    @Test
    fun `the address is read with or without the manifest`() {
        val expected = NetworkPolicy.Verdict.Secure("https://addon.example.com")
        assertEquals(expected, Addons.address("https://addon.example.com/manifest.json"))
        assertEquals(expected, Addons.address("https://addon.example.com/"))
        assertEquals(expected, Addons.address("  addon.example.com "))
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com/u/abc123"), Addons.address("https://addon.example.com/u/abc123/manifest.json"))
    }

    @Test
    fun `unencrypted only on the listener's own network`() {
        assertEquals(NetworkPolicy.Verdict.LocalCleartext("http://192.168.1.20:7000"), Addons.address("http://192.168.1.20:7000/manifest.json"))
        assertEquals(NetworkPolicy.Verdict.Refused(SetupProblem.INSECURE_ADDRESS), Addons.address("http://addon.example.com"))
        assertEquals(NetworkPolicy.Verdict.Refused(SetupProblem.MISSING_FIELD), Addons.address("   "))
    }

    @Test
    fun `the source id is stable and doesn't reveal the address`() {
        val id = Addons.sourceIdFor("https://addon.example.com/u/secret-token")
        assertEquals(id, Addons.sourceIdFor("https://addon.example.com/u/secret-token"))
        assertNotEquals(id, Addons.sourceIdFor("https://addon.example.com/u/other"))
        assertTrue(id.value.startsWith("custom-") && "secret" !in id.value && "example" !in id.value)
    }

    @Test
    fun `the listener sees the host alone`() {
        assertEquals("addon.example.com", Addons.host("https://addon.example.com/u/secret-token"))
    }
}
