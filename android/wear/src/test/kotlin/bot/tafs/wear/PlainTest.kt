package bot.tafs.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/** No raw markdown on the watch (the Galaxy Watch 5 re-test showed `**Versailles**` as written). */
class PlainTest {
  @Test fun boldAndItalicsKeepTheirWords() {
    assertEquals("The first result is Versailles.", Plain.of("The first result is **Versailles**."))
    assertEquals("a French commune", Plain.of("a __French__ *commune*"))
    assertEquals("an emphasis here", Plain.of("an _emphasis_ here"))
  }

  @Test fun wordsWithUnderscoresOrStarsInsideThemStayAsTheyAre() {
    assertEquals("taf_task and 2*3*4", Plain.of("taf_task and 2*3*4"))
    assertEquals("snake_case_name", Plain.of("snake_case_name"))
  }

  @Test fun linksKeepTheirWordsAndCodeItsText() {
    assertEquals("See Versailles on Wikipedia, run tafs_state.", Plain.of("See [Versailles on Wikipedia](https://fr.wikipedia.org/wiki/Versailles), run `tafs_state`."))
    assertEquals("A map", Plain.of("![A map](https://example.org/map.png)"))
  }

  @Test fun headingsListsQuotesAndFencesBecomePlainLines() {
    val md = "## Versailles\n\n- a commune\n* in the Yvelines\n> quoted\n```\ncode\n```"
    assertEquals("Versailles\n\n• a commune\n• in the Yvelines\nquoted\n\ncode", Plain.of(md))
  }

  @Test fun plainTextIsUnchanged() {
    val text = "Searching \"Versailles\" on Wikipedia went straight to the article. 2 × 3 = 6, isn't it?"
    assertEquals(text, Plain.of(text))
  }
}
