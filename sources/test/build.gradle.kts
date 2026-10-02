plugins { alias(libs.plugins.podium.android.library) }

android {
    namespace = "app.podium.sources.test"
}

dependencies {
    api(project(":sources:api"))
}
