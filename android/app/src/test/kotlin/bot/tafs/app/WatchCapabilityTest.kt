package bot.tafs.app

import bot.tafs.watchlink.WatchLink
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The watch finds this phone by one name (docs/watch-wear.md): the watch asks Google Play services for
 * [WatchLink.PHONE_CAPABILITY], and the phone declares it in `res/values/wear.xml`. No code reads the declaration, so a
 * name changed on one side only would still build, and the watch would read the phone as out of range. These tie the
 * declaration, the release build's keep, and the bundle check to the watch's own constant.
 */
class WatchCapabilityTest {
  private fun xml(path: String): Element =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

  @Test fun thePhoneDeclaresExactlyWhatTheWatchLooksFor() {
    val arrays = xml("src/main/res/values/wear.xml").getElementsByTagName("string-array")
    val declared = (0 until arrays.length).map { arrays.item(it) as Element }
      .single { it.getAttribute("name") == "android_wear_capabilities" }
      .getElementsByTagName("item").let { items -> (0 until items.length).map { items.item(it).textContent.trim() } }
    assertEquals(listOf(WatchLink.PHONE_CAPABILITY), declared)
  }

  @Test fun theReleaseBuildKeepsTheDeclaration() {
    val keep = xml("src/main/res/raw/keep.xml").getAttributeNS("http://schemas.android.com/tools", "keep")
    assertTrue(keep, keep.split(',').map { it.trim() }.contains("@array/android_wear_capabilities"))
  }

  @Test fun theBundleCheckLooksForTheSameName() {
    val check = File("../play/check_watch_link.sh").readText()
    val needed = Regex("""for needed in ([^;]+);""").find(check)!!.groupValues[1].trim().split(Regex("\\s+"))
    assertEquals(listOf("android_wear_capabilities", WatchLink.PHONE_CAPABILITY), needed)
  }
}
