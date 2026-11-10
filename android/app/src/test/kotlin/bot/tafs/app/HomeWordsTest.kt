package bot.tafs.app

import bot.tafs.app.model.HomeWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** An empty home list: setting up a taf is Coach's, so only someone who can use Coach is told to. */
class HomeWordsTest {
  @Test fun theEmptyLineFollowsWhetherThePersonCanUseCoach() {
    assertEquals("Connect an app to set up your first taf. Pull down to refresh.", HomeWords.empty(mayUseCoach = true))
    assertEquals("Tafs shared with you show here. Pull down to refresh.", HomeWords.empty(mayUseCoach = false))
  }
}
