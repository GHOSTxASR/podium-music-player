plugins {
    alias(libs.plugins.podium.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "app.podium.core.database"
}

// Exported schemas are committed: every schema change is reviewed and migration-tested (ADR-004).
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":player:api"))
    implementation(libs.room3.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.room3.compiler)

    testImplementation(libs.room3.testing)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(project(":sources:api")))
    // Unit tests run on the development machine: the bundled driver's desktop build carries its natives.
    testImplementation(libs.sqlite.bundled.jvm)
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
