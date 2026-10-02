package app.podium

import android.content.Context
import app.podium.sources.api.MusicSource

/** Release builds ship only real sources. */
internal fun buildVariantSources(context: Context): List<MusicSource> = emptyList()
