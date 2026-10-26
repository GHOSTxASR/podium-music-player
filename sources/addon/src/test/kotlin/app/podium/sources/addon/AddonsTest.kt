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
        assertEquals(expected, Addons.address("https://addon.example.com"), "the address as a source gives it")
        assertEquals(expected, Addons.address("https://addon.example.com/manifest.json"))
        assertEquals(expected, Addons.address("https://addon.example.com/"))
        assertEquals(expected, Addons.address("  addon.example.com "))
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com/u/abc123"), Addons.address("https://addon.example.com/u/abc123/manifest.json"))
    }

    @Test
    fun `an address ending in any json file is its folder, and its query is kept (D-98)`() {
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com/u/abc"), Addons.address("https://addon.example.com/u/abc/addon.json"))
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com"), Addons.address("https://addon.example.com/Manifest.JSON/"))
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com?token=s3cret"), Addons.address("https://addon.example.com/manifest.json?token=s3cret"))
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.example.com/u/abc?token=a%2Bb&x=1"), Addons.address("https://addon.example.com/u/abc/?token=a%2Bb&x=1#frag"))
        assertEquals(NetworkPolicy.Verdict.LocalCleartext("http://192.168.1.20:7000?k=1"), Addons.address("http://192.168.1.20:7000/config.json?k=1"))
        // A host that happens to end in .json is a host, not a file.
        assertEquals(NetworkPolicy.Verdict.Secure("https://addon.json"), Addons.address("https://addon.json"))
        assertEquals("addon.example.com", Addons.host("https://addon.example.com?token=s3cret"), "the token is never shown")
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

    @Test
    fun `a kept manifest reads back the same`() {
        val m = AddonProtocol.manifest(Fixtures.MANIFEST.toByteArray())!!
        assertEquals(m, Addons.readManifest(Addons.writeManifest(m)))
        val mixed = AddonManifest(
            id = "x", name = "X", version = null, description = "About",
            resources = setOf("stream"),
            settings = listOf(AddonSetting("hires", "Hi-res", AddonSetting.Type.TOGGLE, "true"), AddonSetting("region", "Region", AddonSetting.Type.TEXT)),
            allowsDownloads = true,
        )
        assertEquals(mixed, Addons.readManifest(Addons.writeManifest(mixed)))
    }
}
