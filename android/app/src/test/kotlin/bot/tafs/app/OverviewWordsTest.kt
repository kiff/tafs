package bot.tafs.app

import bot.tafs.app.model.OverviewWords
import bot.tafs.app.model.RunReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** Overview's lines on a tablet (as iOS's): a taf's last result, from its last run's report. */
class OverviewWordsTest {
  @Test fun theLastResultIsTheReportsFirstLineWithoutItsMarkdown() {
    assertEquals("Refunded 2 payments", OverviewWords.lastResult(RunReport(text = "\n## **Refunded 2 payments**\nAna and Bo.")))
  }

  @Test fun withoutAReportItSaysWhyOrThatThereIsNoneYet() {
    assertEquals("Its runs couldn't be loaded: down", OverviewWords.lastResult(RunReport(error = "Its runs couldn't be loaded: down")))
    assertEquals("Nothing yet", OverviewWords.lastResult(null))
  }
}
