plugins {
    alias(libs.plugins.podium.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":sources:api")))
    testImplementation(project(":player:api"))
}
