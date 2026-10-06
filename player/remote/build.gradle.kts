plugins { alias(libs.plugins.podium.android.library) }

android {
    namespace = "app.podium.player.remote"
}

dependencies {
    api(project(":player:api"))
    implementation(libs.androidx.core.ktx)
}
