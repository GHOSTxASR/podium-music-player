plugins { alias(libs.plugins.podium.android.library) }

android {
    namespace = "app.podium.sources.local"
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.androidx.core.ktx)
}
