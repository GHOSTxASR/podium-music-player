import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = Sdk.COMPILE
            defaultConfig {
                minSdk = Sdk.MIN
                targetSdk = Sdk.TARGET
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
    }
}
