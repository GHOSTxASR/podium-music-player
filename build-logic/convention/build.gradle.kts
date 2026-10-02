plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "podium.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "podium.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "podium.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "podium.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
