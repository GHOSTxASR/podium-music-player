plugins {
    alias(libs.plugins.podium.android.library)
    alias(libs.plugins.podium.android.compose)
}

android {
    namespace = "app.podium.feature.settings"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":sources:api"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}
