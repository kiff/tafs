// The Wear OS watch (docs/watch-wear.md): a companion to the phone app, on Wear OS 3 or later. It holds no
// keys and no session; it asks the phone over the Wearable Data Layer, in watchlink's messages, and draws what comes back.
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "bot.tafs.wear"
  // The phone's upload key, from outside this public repository (TAFS_UPLOAD_KEYSTORE and its passwords, as the phone's
  // release takes them): the Data Layer joins a watch app and a phone app only when Play signs both with the same key, and
  // Play's app signing re-signs each upload with the listing's one key. Without them a release build is left unsigned.
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
  compileSdk { version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    // The phone's package: the Data Layer only joins a watch app and a phone app with the same package and signing key.
    applicationId = "dev.kiff.tafs"
    // Wear OS 3 or later.
    minSdk = 30
    // Wear OS 6's level, the newest a watch runs.
    targetSdk = 36
    // Its own band under the phone's package (docs/watch-wear.md, Versions): Play wants every versionCode on the listing unique
    // across the phone's and the watch's builds, and the phone counts up by one from 1, so the watch counts up by one from
    // 400,000,001 and the two never meet.
    versionCode = 400_000_015
    // "-test": an internal build for a test on a real watch, with the timing readout (below). A build for production has a
    // plain name, so it never shows it.
    versionName = "0.4.10"
  }

  buildTypes {
    debug {
      // The timing line on a result (listen, receipt, result), for the owner's test of the internal build.
      buildConfigField("boolean", "TIMING", "true")
      // v2 stays off in debug builds and in the unit tests: they pass it where they need it (docs/watch-wear.md, Version 2).
      buildConfigField("boolean", "WATCH_V2", "false")
      // The app's own recording (Play and Pause) stays off: the system's recognizer hears the watch's microphone.
      buildConfigField("boolean", "KEEP_AUDIO", "false")
      // Coach's conversation kept on the watch across opens (docs/watch-wear.md, "What the watch keeps"): on in debug builds,
      // whose unit tests pass a store where they need one.
      buildConfigField("boolean", "KEEP_THREADS", "true")
    }
    release {
      // Only an internal build asks for it: a test version ("-test"), a timing version ("-timing": the timings and the link's
      // measurements, LinkTrace, with no recording kept), TAFS_WATCH_TIMING=1 or -PwatchTiming. A production build never shows it.
      val name = defaultConfig.versionName.orEmpty()
      val timing = name.endsWith("-test") || name.endsWith("-timing") || System.getenv("TAFS_WATCH_TIMING") == "1" || project.hasProperty("watchTiming")
      buildConfigField("boolean", "TIMING", timing.toString())
      // Wear v2 (docs/watch-wear.md, Version 2): on in every release build since 0.4.0, the version the owner tested on a
      // Galaxy Watch 5 and sends to Play's review.
      buildConfigField("boolean", "WATCH_V2", "true")
      // The app's own recording, for Play and Pause on the review: only in a test version ("-test") or with TAFS_WATCH_AUDIO=1,
      // until a watch's probe line shows the recognizer takes the app's audio. Off, the review is the words alone and the watch
      // keeps no recording (docs/watch-wear.md, "What the watch keeps").
      val audio = defaultConfig.versionName.orEmpty().endsWith("-test") || System.getenv("TAFS_WATCH_AUDIO") == "1"
      buildConfigField("boolean", "KEEP_AUDIO", audio.toString())
      // The chats kept on the watch across opens (docs/watch-wear.md, "What the watch keeps"): on in every release build from
      // 0.4.8, with the owner's yes and the Privacy page's line for watch threads (as the Apple Watch's kept threads).
      buildConfigField("boolean", "KEEP_THREADS", "true")
      if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // The store screenshots render the real screen with its resources under Robolectric (WearStoreScreenshots).
  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
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
  implementation(libs.androidx.wear.tiles)
  implementation(libs.androidx.wear.complications.data.source)
  implementation(libs.androidx.concurrent.futures)
  implementation(libs.play.services.wearable)
  // Play services brings Fragment 1.1, older than the speech input's registerForActivityResult needs (1.3); the release
  // build's lint refuses it, as the phone's would without the same line.
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.wear.remote.interactions)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.androidx.lifecycle.runtime.compose)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(composeBom)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The store screenshots are drawn only when asked (`-PwearScreenshots`), into android/play/wear-screenshots; the test runs
// either way, so a screen that no longer draws fails CI.
tasks.withType<Test>().configureEach {
  systemProperty("roborazzi.test.record", if (project.hasProperty("wearScreenshots")) "true" else "false")
  systemProperty("tafs.repo", rootProject.projectDir.parentFile.absolutePath)
  // The round-fit check (WearV2Screenshots): every screen at three round sizes and two font sizes, into this folder.
  systemProperty("tafs.roundFit", (project.findProperty("wearRoundFit") as String?) ?: "")
}
