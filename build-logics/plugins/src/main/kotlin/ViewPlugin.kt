
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class ViewPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

      pluginManager.apply {
        apply(libs.findPlugin("android.library").get().get().pluginId)
        apply(libs.findPlugin("kotlin.android").get().get().pluginId)
        apply("plugin.hilt")
        apply(libs.findPlugin("nav.safeargs").get().get().pluginId)
      }

      extensions.configure<LibraryExtension> {
        configureKotlinAndroid(this)
      }

      dependencies {
        "implementation"(libs.findBundle("runtime").get())
        "implementation"(libs.findBundle("appcompat").get())
        "kapt"(libs.findLibrary("androidx.lifecycle.compilerkapt").get())
        "implementation"(libs.findBundle("viewmodel").get())
        "implementation"(libs.findBundle("fragment").get())
        "implementation"(libs.findBundle("activity").get())
        "implementation"(libs.findBundle("navigation").get())
        "implementation"(libs.findLibrary("material").get())
      }

    }
  }
}
