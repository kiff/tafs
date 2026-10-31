plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "bot.tafs.app"
  compileSdk { version = release(37) { minorApiLevel = 0 } }
  defaultConfig {
    // Play's package for the app. The namespace and the Kotlin packages stay bot.tafs.app.
    applicationId = "dev.kiff.tafs"
    minSdk = 30
    targetSdk = 37
    versionCode = 14
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // The upload key, from outside this public repository: TAFS_UPLOAD_KEYSTORE (a .jks path) and its
  // passwords in TAFS_UPLOAD_STORE_PASSWORD and TAFS_UPLOAD_KEY_PASSWORD, as android/release.sh sets
  // them from Secrets Manager. Without them a release build is left unsigned.
  val uploadKeystore = System.getenv("TAFS_UPLOAD_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
  signingConfigs {
    if (uploadKeystore != null) {
      create("upload") {
        storeFile = uploadKeystore
        storePassword = System.getenv("TAFS_UPLOAD_STORE_PASSWORD")
        keyAlias = System.getenv("TAFS_UPLOAD_KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("TAFS_UPLOAD_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
      // R8 keeps only the code the app reaches: most of Bouncy Castle goes. app/proguard-rules.pro keeps what's
      // looked up by name. A release build's receipt check on a phone is the test for these rules.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // The libraries' native code goes to Play with its symbol tables, so a native crash's stack trace reads by function
      // name in Play's crash view. The bundle carries them for Play; the app on the phone doesn't.
      ndk { debugSymbolLevel = "SYMBOL_TABLE" }
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

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

// Firebase's settings for this app come from the project's google-services.json, which isn't kept in this public repository:
// without it nothing is configured and the app runs with alerts off.
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.fragment.ktx)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.biometric)
  // The vendor's sign-in page, in a Chrome Custom Tab.
  implementation(libs.androidx.browser)
  // Buying Solo and task packs through Google Play (docs/accounts.md), off until KIFF records Play purchases.
  implementation(libs.billing.ktx)
  implementation(libs.bouncycastle)

  // Alerts: Firebase Cloud Messaging carries tafs cloud's push to this phone. The app builds and runs without it:
  // alerts are on only in a build that has the project's google-services.json (see push/Alerts.kt).
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
