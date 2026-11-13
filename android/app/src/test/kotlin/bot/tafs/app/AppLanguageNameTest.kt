package bot.tafs.app

import bot.tafs.app.model.AppLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account's Language: the app's language named in itself, and the phone's own setting to change it. Android 13+ offers tafs in
 * its per-app language setting only when the manifest declares the generated `locales_config`.
 */
class AppLanguageNameTest {
  @Test fun eachLanguageIsNamedInItself() {
    assertEquals("English", AppLanguage.name("en"))
    assertEquals("Français", AppLanguage.name("fr"))
    assertEquals("Deutsch", AppLanguage.name("de"))
    assertEquals("Polski", AppLanguage.name("pl"))
    assertEquals("English", AppLanguage.name())
  }

  @Test fun thisPhonesNameKeepsEachLanguagesWordOrder() {
    // What KIFF's device list calls this phone: "tafs on Android phone", never "tafs sur Android appareil" (the owner's iPad, 13 Nov).
    assertEquals("tafs on Android phone", bot.tafs.app.model.Words.Settings.Devices.thisDeviceName(device = bot.tafs.app.model.Words.Settings.Devices.androidDevice(device = "phone")))
    val fr = File("src/main/res/values-fr/strings.xml").readText()
    assertTrue(fr.contains("""<string name="settings_devices_android_device">%1${'$'}s Android</string>"""))
  }

  @Test fun theManifestDeclaresTheGeneratedLocalesSoAndroidOffersTheEight() {
    val manifest = File("src/main/AndroidManifest.xml").readText()
    assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
    val locales = File("src/main/res/xml/locales_config.xml").readText()
    listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl").forEach { assertTrue(it, locales.contains("android:name=\"$it\"")) }
  }
}
