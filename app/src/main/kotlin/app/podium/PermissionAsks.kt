package app.podium

import android.content.Context

/**
 * What Podium remembers about asking for a runtime permission (music access): whether it has
 * asked on its own yet, and whether Android has stopped showing the question (the listener said
 * no twice, or "don't ask again"). Then a press on "Allow music access" opens Podium's page in
 * the system settings instead of doing nothing.
 */
class PermissionAsks(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("permission_asks", Context.MODE_PRIVATE)

    /** Podium asks by itself once, the first time it's switched on without access. */
    fun shouldAskFirstTime(permission: String, granted: Boolean): Boolean =
        !granted && !prefs.getBoolean(KEY_ASKED + permission, false)

    fun onAsked(permission: String) {
        prefs.edit().putBoolean(KEY_ASKED + permission, true).apply()
    }

    /**
     * The answer to a request. Refused with no way to ask again ([canAskAgain] is Android's
     * `shouldShowRequestPermissionRationale` after the answer): only the system settings can change it.
     */
    fun onAnswer(permission: String, granted: Boolean, canAskAgain: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCKED + permission, !granted && !canAskAgain).apply()
    }

    /** Whether asking would show nothing, so the system settings page is the way to allow it. */
    fun isBlocked(permission: String, granted: Boolean): Boolean =
        !granted && prefs.getBoolean(KEY_BLOCKED + permission, false)

    /** Access arrived (from the dialog or the system settings): forget any refusal. */
    fun onGranted(permission: String) {
        if (prefs.getBoolean(KEY_BLOCKED + permission, false)) prefs.edit().remove(KEY_BLOCKED + permission).apply()
    }

    private companion object {
        const val KEY_ASKED = "asked:"
        const val KEY_BLOCKED = "blocked:"
    }
}
