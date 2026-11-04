package bot.tafs.app

import bot.tafs.app.views.versionWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** Account › Version names the build, so a check on a phone says which one it was. */
class AccountVersionTest {
  @Test fun theBuildNumberComesFirstThenTheVersion() {
    assertEquals("25 (1.0)", versionWords(25, "1.0"))
    assertEquals("25 (–)", versionWords(25, null))
  }
}
