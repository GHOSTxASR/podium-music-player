package app.podium.sources.subsonic

import app.podium.sources.api.ConnectResult
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubsonicSourceFactoryTest {
    private val server = FakeSubsonicServer()
    private val factory = SubsonicSourceFactory(server)

    private fun values(address: String, password: String = server.password, consent: Boolean = false) = buildMap {
        put(SubsonicMusicSource.KEY_ADDRESS, address)
        put(SubsonicMusicSource.KEY_USERNAME, server.user)
        put(SubsonicMusicSource.KEY_PASSWORD, password)
        if (consent) put(SetupForm.ALLOW_CLEARTEXT, "true")
    }

    @Test
    fun `the form asks for an address, a user name and a secret password`() {
        assertEquals(listOf("address", "username", "password"), factory.form.fields.map { it.key })
        assertEquals(app.podium.sources.api.SetupField.Kind.SECRET, factory.form.fields.last().kind)
    }

    @Test
    fun `a server that accepts the credentials is connected, and the password is a secret, not a setting`() = runTest {
        val connected = assertIs<ConnectResult.Connected>(factory.connect(values("https://music.example.com/")))
        assertEquals("Navidrome (music.example.com)", connected.displayName)
        assertEquals(mapOf("address" to "https://music.example.com", "username" to server.user), connected.settings)
        assertEquals(mapOf("password" to server.password), connected.secrets)
        assertTrue(server.methods.single() == "ping")
    }

    @Test
    fun `an address without a scheme is taken as https`() = runTest {
        val connected = assertIs<ConnectResult.Connected>(factory.connect(values("music.example.com:4533")))
        assertEquals("https://music.example.com:4533", connected.settings["address"])
    }

    @Test
    fun `wrong credentials are refused`() = runTest {
        assertEquals(SetupProblem.WRONG_CREDENTIALS, assertIs<ConnectResult.Refused>(factory.connect(values("https://music.example.com", password = "nope"))).problem)
    }

    @Test
    fun `an unreachable server and an address with no music API are explained`() = runTest {
        server.unreachable = true
        assertEquals(SetupProblem.UNREACHABLE, assertIs<ConnectResult.Refused>(factory.connect(values("https://music.example.com"))).problem)
        server.unreachable = false
        server.httpStatus = 404
        assertEquals(SetupProblem.NOT_SUPPORTED, assertIs<ConnectResult.Refused>(factory.connect(values("https://example.com"))).problem)
    }

    @Test
    fun `an unencrypted public address is refused outright`() = runTest {
        assertEquals(SetupProblem.INSECURE_ADDRESS, assertIs<ConnectResult.Refused>(factory.connect(values("http://music.example.com", consent = true))).problem)
        assertTrue(server.requests.isEmpty(), "never even contacted")
    }

    @Test
    fun `an unencrypted address on the listener's network needs their consent first`() = runTest {
        assertEquals(SetupProblem.NEEDS_CLEARTEXT_CONSENT, assertIs<ConnectResult.Refused>(factory.connect(values("http://192.168.1.20:4533"))).problem)
        assertTrue(server.requests.isEmpty())
        val connected = assertIs<ConnectResult.Connected>(factory.connect(values("http://192.168.1.20:4533", consent = true)))
        assertEquals("http://192.168.1.20:4533", connected.settings["address"])
    }

    @Test
    fun `missing fields are refused before anything is sent`() = runTest {
        assertEquals(SetupProblem.MISSING_FIELD, assertIs<ConnectResult.Refused>(factory.connect(values("https://music.example.com", password = ""))).problem)
        assertEquals(SetupProblem.MISSING_FIELD, assertIs<ConnectResult.Refused>(factory.connect(values(""))).problem)
        assertTrue(server.requests.isEmpty())
    }
}
