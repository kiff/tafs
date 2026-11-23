// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.jvm) apply false
}

// Robolectric's Android jar comes through Gradle, never Robolectric's own download at test time: that one went to Maven Central
// on every CI run, and its rate limit (429) failed every Robolectric test at once. Gradle fetches it once into its cache (which
// CI's setup-gradle keeps between runs), copies it next to each app's build, and the tests read it offline from there.
val robolectricAndroidAll = libs.robolectric.android.all.get().toString()
subprojects {
  plugins.withId("com.android.application") {
    val androidAll = configurations.create("robolectricAndroidAll") { isTransitive = false }
    dependencies.add(androidAll.name, robolectricAndroidAll)
    val dir = layout.buildDirectory.dir("robolectric-jars")
    val jars = tasks.register<Copy>("robolectricJars") {
      from(androidAll)
      into(dir)
    }
    tasks.withType<Test>().configureEach {
      dependsOn(jars)
      systemProperty("robolectric.offline", "true")
      systemProperty("robolectric.dependency.dir", dir.get().asFile.absolutePath)
    }
  }
}
