package app.podium

import android.content.Context
import app.podium.sources.youtubemusic.StreamWalkMemory

/**
 * The online app that last served a song's stream, kept across restarts (D-98), so the first song
 * after a start doesn't wait on an app that has stopped serving. One short name, nothing about the
 * listener; read on the walk's own thread, never at start-up.
 */
internal class PrefsStreamWalkMemory(context: Context) : StreamWalkMemory {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun lastServed(): String? = prefs.getString(KEY, null)

    override fun served(identity: String) {
        if (prefs.getString(KEY, null) != identity) prefs.edit().putString(KEY, identity).apply()
    }

    private companion object {
        const val FILE = "podium-streams"
        const val KEY = "last-served-app"
    }
}
