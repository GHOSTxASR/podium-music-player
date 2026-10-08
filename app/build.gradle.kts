import java.util.Properties

plugins {
    alias(libs.plugins.podium.android.application)
    alias(libs.plugins.podium.android.compose)
}

// Release signing (D-69): the key and its passwords live outside git, in keystore.properties
// (ignored) next to this project, or in PODIUM_KEYSTORE_* environment variables. Without either,
// the release APK is built unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val releaseStore = secret("storeFile", "PODIUM_KEYSTORE_FILE")

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
    lint {
        // MainActivity and WebSignInActivity are ComponentActivity, whose result APIs are correct;
        // the check is about FragmentActivity before fragment 1.3, which arrives only transitively.
        disable += "InvalidFragmentVersionForActivityResult"
    }
    // The cutout model is memory-mapped straight from the APK.
    androidResources {
        noCompress += "tflite"
    }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = secret("storePassword", "PODIUM_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "PODIUM_KEY_ALIAS")
                keyPassword = secret("keyPassword", "PODIUM_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // This is the build that lives on the phone (D-65). A debuggable app is never compiled
            // ahead of time and loses its JIT-compiled code whenever its process ends, so every
            // fresh open started cold and stuttered until it warmed up. Not debuggable, ART compiles
            // it (the libraries' baseline profiles through profileinstaller, then the phone's own
            // profile) and the first transition is as smooth as the hundredth. Debug commands, the
            // test tones and the package (app.podium.debug, the listener's data) are unchanged.
            // For a debugger: ./gradlew installDebug -Ppodium.debuggable=true
            isDebuggable = providers.gradleProperty("podium.debuggable").orNull == "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":core:database"))
    implementation(project(":core:lyrics"))
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
    // Sticker cutout on the phone (D-55): LiteRT runs the bundled magic-touch model.
    implementation(libs.litert)

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
