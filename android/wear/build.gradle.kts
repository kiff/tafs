// The Wear OS watch (docs/watch-wear.md): a companion to the phone app, on Samsung Galaxy Watch and Pixel Watch. It holds no
// keys and no session; it asks the phone over the Wearable Data Layer, in watchlink's messages, and draws what comes back.
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "bot.tafs.wear"
  compileSdk { version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    // The phone's package: the Data Layer only joins a watch app and a phone app with the same package and signing key.
    applicationId = "dev.kiff.tafs"
    // Wear OS 3 and later: Galaxy Watch4 onwards, and every Pixel Watch.
    minSdk = 30
    // Wear OS 6's level, the newest a watch runs.
    targetSdk = 36
    // Not uploaded yet: its number on Play's listing beside the phone's is decided before its first upload.
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":watchlink"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.androidx.wear.compose.foundation)
  implementation(libs.play.services.wearable)
  implementation(libs.androidx.wear.remote.interactions)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.androidx.lifecycle.runtime.compose)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
