plugins { alias(libs.plugins.podium.jvm.library) }

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":sources:api"))
    testImplementation(testFixtures(project(":sources:api")))
}
