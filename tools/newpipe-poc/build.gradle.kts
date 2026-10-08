plugins {
    alias(libs.plugins.podium.android.application)
}

android {
    namespace = "app.podium.poc"
    defaultConfig {
        applicationId = "app.podium.poc"
        versionCode = 1
        versionName = "1.0.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
