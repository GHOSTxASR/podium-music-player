package app.podium

import android.app.Application
import app.podium.player.service.PlaybackDependencies

class PodiumApplication : Application(), PlaybackDependencies.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override val playbackDependencies: PlaybackDependencies
        get() = graph.playbackDependencies
}
