package app.podium

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.SourceId
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
    fun `retired servers' secrets are removed, the online service's are kept`() {
        val store = KeystoreCredentialStore(context, FakeCipher)
        store.write(SourceId("opensubsonic-1a2b3c4d"), mapOf("password" to "old-server-secret"))
        store.write(SourceId("ytmusic"), mapOf("session" to "kept"))
        context.getSharedPreferences("source-profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles", "[{\"id\":\"opensubsonic-1a2b3c4d\",\"kind\":\"opensubsonic\",\"name\":\"Home\",\"settings\":{}}]")
            .commit()

        RetiredSources.cleanUp(context, store)

        assertNull(store.read(SourceId("opensubsonic-1a2b3c4d")))
        assertEquals(mapOf("session" to "kept"), store.read(SourceId("ytmusic")))
        assertTrue(context.getSharedPreferences("source-profiles", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse("old-server-secret" in storedText())
        // Nothing to do the second time.
        RetiredSources.cleanUp(context, store)
        assertEquals(mapOf("session" to "kept"), store.read(SourceId("ytmusic")))
    }
}
