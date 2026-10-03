plugins {
    alias(libs.plugins.podium.android.library)
    alias(libs.plugins.podium.android.compose)
}

android {
    namespace = "app.podium.feature.nowplaying"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":player:api"))
    implementation(project(":sources:api"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}

dependencies {
    // Layout screenshots at several device sizes (Robolectric native graphics).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
