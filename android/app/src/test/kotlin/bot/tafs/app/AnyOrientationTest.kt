package bot.tafs.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tafs runs in any orientation (docs/android-app.md, "Any screen"): on phones held upright and on devices that are only ever
 * landscape (a Chromebook, a desktop, a tablet docked sideways). Read from the merged manifest, libraries' activities included,
 * since Play reads an activity locked to portrait as needing a portrait screen (Android 128's pre-launch check, 20 Nov: ML Kit's
 * scanner delegate).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AnyOrientationTest {
  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private val locked = setOf(
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LOCKED,
    ActivityInfo.SCREEN_ORIENTATION_NOSENSOR,
  )

  @Test fun noActivityIsLockedToAnOrientationLibrariesIncluded() {
    val activities = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
    assertTrue("the merged manifest's activities were read", activities.any { it.name.endsWith(".MainActivity") })
    assertTrue("ML Kit's scanner delegate is in it", activities.any { it.name.endsWith("GmsBarcodeScanningDelegateActivity") })
    assertEquals("activities locked to one orientation", emptyList<String>(), activities.filter { it.screenOrientation in locked }.map { it.name })
  }

  @Test fun noScreenOrientationIsARequiredFeature() {
    val features = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
    val screen = features.filter { it.name?.startsWith("android.hardware.screen.") == true }
    assertEquals("both screen orientations are named, neither required", setOf("android.hardware.screen.portrait", "android.hardware.screen.landscape"), screen.mapNotNull { it.name }.toSet())
    assertEquals(emptyList<String>(), screen.filter { it.flags and FeatureInfo.FLAG_REQUIRED != 0 }.map { it.name })
  }
}
