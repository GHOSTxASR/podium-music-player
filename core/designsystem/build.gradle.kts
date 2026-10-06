plugins {
    alias(libs.plugins.podium.android.library)
    alias(libs.plugins.podium.android.compose)
}

android {
    namespace = "app.podium.core.designsystem"
}

dependencies {
    api(project(":core:interaction"))
    implementation(libs.backdrop)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
