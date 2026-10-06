plugins {
    alias(libs.plugins.podium.android.application)
    alias(libs.plugins.podium.android.compose)
}

android {
    namespace = "app.podium"
    defaultConfig {
        // Provisional: the product name is pending trademark clearance (docs/repository-audit.md §5).
        applicationId = "app.podium"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":core:database"))
    implementation(project(":sources:api"))
    implementation(project(":sources:local"))
    implementation(project(":player:api"))
    implementation(project(":player:service"))
    implementation(project(":player:remote"))
    implementation(project(":feature:library"))
    implementation(project(":feature:online"))
    implementation(project(":sources:youtubemusic"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:settings"))
    // Deterministic generated test tones: debug builds only, never shipped.
    debugImplementation(project(":sources:test"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // The library repository over a real (in-memory) database, on the development machine.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room3.runtime)
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(testFixtures(project(":sources:api")))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":sources:test"))
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
