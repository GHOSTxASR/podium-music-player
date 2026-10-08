plugins {
    alias(libs.plugins.podium.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.kotlinx.serialization.json)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(testFixtures(project(":sources:api")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

