package app.podium

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.ConfiguredSources
import app.podium.sources.api.ConnectResult
import app.podium.sources.api.CredentialStore
import app.podium.sources.api.InMemorySourcePreferencesStore
import app.podium.sources.api.MusicSource
import app.podium.sources.api.SetupField
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceFactory
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceProfile
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.SourceSettings
import app.podium.feature.settings.OnlineSourceSettings
import app.podium.sources.testing.FakeOnlineMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric has no Android Keystore: a reversible stand-in proves only ciphertext is stored. */
private object FakeCipher : app.podium.SecretCipher {
    override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.first() == 7.toByte())
        return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

@RunWith(AndroidJUnit4::class)
class SourceCredentialsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val id = SourceId("opensubsonic-1")

    private fun storedText() = context.getSharedPreferences(KeystoreCredentialStore.FILE, Context.MODE_PRIVATE).all.values.joinToString()

    @Test
    fun `credentials round-trip, are never stored readable, and are removed completely`() {
        val store = KeystoreCredentialStore(context, FakeCipher)
        store.write(id, mapOf("password" to "hunter2-test"))
        assertEquals(mapOf("password" to "hunter2-test"), KeystoreCredentialStore(context, FakeCipher).read(id), "survives a new process")
        assertFalse("hunter2-test" in storedText(), "only ciphertext at rest")
        store.delete(id)
        assertNull(store.read(id))
        assertEquals("", storedText())
    }

    @Test
    fun `a value that can't be opened reads as signed out`() {
        context.getSharedPreferences(KeystoreCredentialStore.FILE, Context.MODE_PRIVATE).edit().putString(id.value, "not-ciphertext").commit()
        assertNull(KeystoreCredentialStore(context, FakeCipher).read(id))
    }

    @Test
    fun `profiles keep names and settings, never secrets, across restarts`() {
        val profiles = SharedPrefsSourceProfiles(context)
        val profile = SourceProfile(id, "opensubsonic", "Navidrome (home.lan)", mapOf("address" to "https://home.lan", "username" to "listener"))
        profiles.save(listOf(profile))
        assertEquals(listOf(profile), SharedPrefsSourceProfiles(context).load())
    }

    /** A signing-in source whose server accepts the password "right". */
    private class SigningSource(profile: SourceProfile, private val credentials: CredentialStore) : MusicSource by FakeOnlineMusicSource(profile.id.value) {
        override val descriptor = SourceDescriptor(profile.id, profile.displayName, "Fake", Basis.USER_SERVER)
        private val _state = MutableStateFlow<AuthState>(if (credentials.read(profile.id) != null) AuthState.SignedIn("listener") else AuthState.SignedOut)
        override val auth = object : app.podium.sources.api.AuthFacet {
            override val state: StateFlow<AuthState> = _state
            override val signInFields = listOf(SetupField("password", "Password", kind = SetupField.Kind.SECRET))
            override suspend fun signIn(values: Map<String, String>): SignInResult =
                if (values["password"] == "right") {
                    credentials.write(profile.id, values)
                    _state.value = AuthState.SignedIn("listener")
                    SignInResult.SignedIn
                } else SignInResult.Refused(SetupProblem.WRONG_CREDENTIALS)
            override suspend fun signOut() {
                credentials.delete(profile.id)
                _state.value = AuthState.SignedOut
            }
        }
    }

    @Test
    fun `settings add a server, sign it out and in again, and remove it`() = runBlocking {
        withTimeout(10_000) {
            val credentials = KeystoreCredentialStore(context, FakeCipher)
            val registry = SourceRegistry(SourceHealthMonitor(ManualClock()))
            val health = SourceHealthMonitor(ManualClock())
            val prefs = SourceSettings(registry, InMemorySourcePreferencesStore())
            val factory = object : SourceFactory {
                override val form = SetupForm("server", "Add a music server", listOf(SetupField("password", "Password", kind = SetupField.Kind.SECRET)))
                override suspend fun connect(values: Map<String, String>) =
                    if (values["password"] == "right") ConnectResult.Connected("Home server", emptyMap(), values) else ConnectResult.Refused(SetupProblem.WRONG_CREDENTIALS)
                override fun create(profile: SourceProfile, credentials: CredentialStore): MusicSource = SigningSource(profile, credentials)
            }
            val configured = ConfiguredSources(registry, prefs, SharedPrefsSourceProfiles(context), credentials, listOf(factory)) { SourceId("server-1") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val settings = RegistryOnlineSourceSettings(registry, prefs, configured, health, scope)

            val form = settings.addable.single()
            assertEquals(SetupProblem.WRONG_CREDENTIALS, settings.submit(form, mapOf("password" to "wrong")))
            assertNull(settings.submit(form, mapOf("password" to "right")))
            val row = settings.sources.first { it.isNotEmpty() }.single()
            assertEquals("Home server", row.name)
            assertEquals(true, row.signedIn)
            assertTrue(row.removable)

            settings.signOut("server-1")
            assertEquals(false, settings.sources.first { it.single().signedIn == false }.single().signedIn)
            assertNull(credentials.read(SourceId("server-1")), "signing out leaves no secret behind")

            val signIn = settings.form(OnlineSourceSettings.signInKey("server-1"))!!
            assertEquals("Sign in", signIn.submitLabel)
            assertNull(settings.submit(signIn, mapOf("password" to "right")))
            assertEquals(true, settings.sources.first { it.single().signedIn == true }.single().signedIn)

            settings.remove("server-1")
            assertTrue(settings.sources.first { it.isEmpty() }.isEmpty())
            assertNull(credentials.read(SourceId("server-1")))
            assertTrue(SharedPrefsSourceProfiles(context).load().isEmpty())
        }
    }
}
