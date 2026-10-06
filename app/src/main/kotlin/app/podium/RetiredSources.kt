package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.model.SourceId
import app.podium.sources.api.CredentialStore
import org.json.JSONArray

/**
 * The online sources Podium had before YouTube Music (D-38) and what they left on the phone.
 *
 * Music servers the listener added (O11, D-37) kept a profile in `source-profiles` and their sealed
 * password in the credential store. Those sources no longer exist, so their secrets go: a sealed
 * password nobody can use is still a password at rest. Their listens and likes stay in the database,
 * untouched and simply not shown (the cleanup of rows waits for a later migration, §12.3).
 */
internal object RetiredSources {
    private const val PROFILES_FILE = "source-profiles"
    private const val PROFILES_KEY = "profiles"

    fun cleanUp(context: Context, credentials: CredentialStore) {
        val prefs = context.getSharedPreferences(PROFILES_FILE, Context.MODE_PRIVATE)
        val text = prefs.getString(PROFILES_KEY, null) ?: return
        runCatching {
            val array = JSONArray(text)
            (0 until array.length()).forEach { i -> credentials.delete(SourceId(array.getJSONObject(i).getString("id"))) }
        }
        prefs.edit(commit = true) { clear() }
    }
}
