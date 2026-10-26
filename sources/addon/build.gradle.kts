plugins {
    alias(libs.plugins.podium.jvm.library)
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":sources:api")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
