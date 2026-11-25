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
    versionCode = 146
    versionName = "1.49.3"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // The committed app map (app/map/android.json) is the UI tests' asset: AppMapWalkTest's walk must match it.
  sourceSets.getByName("androidTest").assets.directories.add("../../app/map")

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
    debug {
      // K5, row 39 (docs/onboarding.md): the onboarding and the sign-in inside tafs, on in every build since the owner's yes.
      buildConfigField("boolean", "K5", "true")
    }
    release {
      // On in every build for Play, as in debug: the onboarding and the sign-in inside tafs.
      buildConfigField("boolean", "K5", "true")
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
  // The store screenshots render the real screens with their resources under Robolectric (PhoneStoreScreenshots).
  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
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
  // The Wear OS watch's requests (docs/watch-wear.md): the messages both apps share, and the Data Layer they arrive on.
  implementation(project(":watchlink"))
  implementation(libs.play.services.wearable)
  // An expedited job keeps an ask's answer for the watch, outside Firebase's few seconds (docs/watch-wear.md).
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.play.services)
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
  // The phone's store screenshots, the real screens drawn on the JVM (PhoneStoreScreenshots): test-only.
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.androidx.compose.ui.test.junit4)

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
  // Play's flexible in-app update: a new version on the track is offered in one tap, then a restart.
  implementation(libs.play.app.update.ktx)
  // Pairing a computer: Google's code scanner reads its QR in its own screen, so the app asks for no camera permission.
  implementation(libs.play.services.code.scanner)
  // Signing in with your other phone: this phone draws its own pairing QR. The encoder alone, pure Java.
  implementation(libs.zxing.core)
  // A kiff.dev passkey made on the phone, through Android's Credential Manager; Play services carries it on Android 11 to 13.
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

// Unit tests run in UTC, as CI does, so a developer's own time zone never changes what they read; a test that needs a phone in
// another zone sets it itself.
tasks.withType<Test>().configureEach {
  systemProperty("user.timezone", "UTC")
  // The store screenshots are drawn only when asked (`-PphoneScreenshots`), into android/play/phone-screenshots, and the tablet's
  // against the iPad's (`-PtabletScreenshots`), into docs/screenshots/android/tablet; the tests run either way, so a screen that
  // no longer draws fails CI.
  systemProperty("roborazzi.test.record", if (project.hasProperty("phoneScreenshots") || project.hasProperty("tabletScreenshots") || project.hasProperty("landscapeScreenshots") || project.hasProperty("rtlScreenshots")) "true" else "false")
  systemProperty("tafs.repo", rootProject.projectDir.parentFile.absolutePath)
}
