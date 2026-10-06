plugins {
    alias(libs.plugins.podium.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Lyrics (LYRICS_ARCHITECTURE.md): provider-independent models, LRC parsing, matching, the
// repository and its providers. Pure Kotlin; the app supplies the cache directory and consent.
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":sources:api")) // the title normaliser: versions must agree
    implementation(libs.kotlinx.serialization.json)
}
