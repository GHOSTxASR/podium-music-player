package app.podium.update

import app.podium.feature.settings.SoftwareUpdateSettings
import kotlinx.coroutines.flow.StateFlow

/** Settings ▸ Software update (D-99), on [UpdateCenter]. Test builds never check by themselves. */
internal class SoftwareUpdate(private val updates: UpdateCenter, checksByItself: Boolean) : SoftwareUpdateSettings {
    override val version: String get() = updates.version.toString()
    override val automatic: StateFlow<Boolean>? = if (checksByItself) updates.automatic else null

    override fun setAutomatic(on: Boolean) = updates.setAutomatic(on)

    override fun checkNow() = updates.checkNow()
}
