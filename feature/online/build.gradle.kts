plugins {
    alias(libs.plugins.podium.android.library)
    alias(libs.plugins.podium.android.compose)
}

android {
    namespace = "app.podium.feature.online"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":player:api"))
    api(project(":sources:api"))
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Paper-layout checks for the online screens (Robolectric native graphics).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(testFixtures(project(":sources:api")))
    debugImplementation(libs.compose.ui.test.manifest)
}
