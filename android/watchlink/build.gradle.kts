// The messages the Wear OS watch and the phone exchange over the Wearable Data Layer (docs/watch-wear.md): plain Kotlin, so
// both apps build on the same rules and their tests run on the JVM, with no phone or watch.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}
