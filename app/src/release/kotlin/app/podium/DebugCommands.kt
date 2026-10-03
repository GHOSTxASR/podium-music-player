package app.podium

import android.content.Intent

/** Release builds take no debug commands. */
@Suppress("UNUSED_PARAMETER")
internal fun handleDebugIntent(intent: Intent?, graph: AppGraph) = Unit
