package app.podium

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.podium.core.model.SourceId
import app.podium.sources.api.CredentialStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens small secrets. The app's is backed by the Android Keystore; tests use a fake. */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key generated in, and never leaving, the Android Keystore (security.md).
 * Output is IV (12 bytes) followed by ciphertext and tag.
 */
class KeystoreCipher(private val alias: String = "podium-credentials") : SecretCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Sources' secrets (D-37): sealed per source and kept in a private preferences file that only ever
 * holds ciphertext. Cloud backup is off; a device-to-device transfer may still copy the file, but the
 * key never leaves this phone's Keystore, so the copy can't be opened. A value that can't be opened
 * — the key was reset, the data damaged, a copy from another phone — reads as "no credentials", so
 * the source asks to sign in again.
 */
class KeystoreCredentialStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreCipher(),
) : CredentialStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(source: SourceId): Map<String, String>? {
        val sealed = prefs.getString(source.value, null) ?: return null
        return runCatching {
            val json = JSONObject(cipher.open(Base64.decode(sealed, Base64.NO_WRAP)).decodeToString())
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrNull()
    }

    @Synchronized
    override fun write(source: SourceId, values: Map<String, String>) {
        val plain = JSONObject(values).toString().toByteArray()
        prefs.edit(commit = true) { putString(source.value, Base64.encodeToString(cipher.seal(plain), Base64.NO_WRAP)) }
    }

    @Synchronized
    override fun delete(source: SourceId) {
        prefs.edit(commit = true) { remove(source.value) }
    }

    companion object {
        const val FILE = "credentials"
    }
}
