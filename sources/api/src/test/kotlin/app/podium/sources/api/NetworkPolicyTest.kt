package app.podium.sources.api

import app.podium.sources.api.NetworkPolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** D-09: https anywhere; http only on the listener's own network, and only after they confirm. */
class NetworkPolicyTest {

    @Test
    fun `https is fine anywhere and is normalised`() {
        assertEquals(Verdict.Secure("https://music.example.com"), NetworkPolicy.check("https://Music.Example.com/"))
        assertEquals(Verdict.Secure("https://music.example.com:4533/navidrome"), NetworkPolicy.check(" https://music.example.com:4533/navidrome/ "))
        assertEquals(Verdict.Secure("https://music.example.com"), NetworkPolicy.check("music.example.com"))
    }

    @Test
    fun `http on the listener's own network is allowed with consent`() {
        listOf("http://192.168.1.20:4533", "http://10.0.0.5", "http://172.20.1.1", "http://127.0.0.1:4533", "http://localhost:4533", "http://nas.local", "http://music.lan", "http://box.home.arpa", "http://[fd00::1]:4533", "http://169.254.3.4")
            .forEach { assertIs<Verdict.LocalCleartext>(NetworkPolicy.check(it), it) }
    }

    @Test
    fun `http anywhere else is refused`() {
        listOf("http://music.example.com", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.1.1", "http://11.0.0.1")
            .forEach { assertEquals(SetupProblem.INSECURE_ADDRESS, assertIs<Verdict.Refused>(NetworkPolicy.check(it), it).problem) }
    }

    @Test
    fun `every request is held to the policy, not only the address typed in`() {
        listOf(
            "https://music.example.com/rest/stream?id=1&t=abc&s=def",
            "http://192.168.1.20:4533/rest/stream?id=1",
            "http://user@nas.local/x",
            "HTTP://[fd00::1]:4533/rest/ping",
            "file:///storage/emulated/0/Music/My Song.mp3",
            "content://media/external/audio/media/12",
        ).forEach { assertTrue(NetworkPolicy.permits(it), it) }
        listOf(
            "http://cdn.example.com/a.mp3",
            "http://8.8.8.8/rest/stream?id=1",
            "http://nas.local.example.com/x",
            "http://192.168.1.20@evil.example.com/x",
            "http:///nohost",
        ).forEach { assertFalse(NetworkPolicy.permits(it), it) }
    }

    @Test
    fun `anything that isn't a plain server address is refused`() {
        listOf("ftp://music.example.com", "https://user:pass@music.example.com", "https://music.example.com?x=1", "https://", "::::")
            .forEach { assertIs<Verdict.Refused>(NetworkPolicy.check(it), it) }
        assertEquals(SetupProblem.MISSING_FIELD, (NetworkPolicy.check("  ") as Verdict.Refused).problem)
    }
}
