package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.signing.promptDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The fingerprint prompt says why before it asks (the owner's ask): Coach's own prompts carry the why-line, nothing else does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PromptDescriptionTest {
  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun coachsPromptsSayWhy() {
    for (title in listOf(Words.Coach.Prompt.start, Words.Coach.Prompt.giveCard, Words.Coach.Prompt.connectTools)) {
      assertEquals(title, "Coach can act only within what you approve.", promptDescription(title))
    }
  }

  @Test fun anyOtherApprovalKeepsItsTitleAlone() {
    assertNull(promptDescription(Words.Approval.Prompt.approveOnKiff))
    assertNull(promptDescription("Approve a refund of €40"))
  }
}
