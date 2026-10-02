import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android library. AGP 9 has built-in Kotlin, so the kotlin-android plugin is not applied. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = Sdk.COMPILE
            defaultConfig {
                minSdk = Sdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = PODIUM_JAVA
                targetCompatibility = PODIUM_JAVA
            }
            testOptions {
                unitTests {
                    isIncludeAndroidResources = true
                }
            }
        }
        configureKotlinCompilation()
        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlin-test"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("androidx-test-core"))
            add("testImplementation", libs.lib("androidx-test-ext-junit"))
        }
    }
}
