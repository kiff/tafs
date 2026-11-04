package bot.tafs.app

import bot.tafs.app.views.versionWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** Account › Version names the build, so a check on a phone says which one it was. */
class AccountVersionTest {
  @Test fun theVersionComesFirstThenTheBuildNumber() {
    assertEquals("1.0 (26)", versionWords(26, "1.0"))
    assertEquals("– (26)", versionWords(26, null))
  }
}
