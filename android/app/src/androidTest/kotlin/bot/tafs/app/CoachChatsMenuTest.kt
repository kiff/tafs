package bot.tafs.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachChatActions
import bot.tafs.app.views.PreviousConversations
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Coach's Previous conversations: a long press asks about the chat pressed, by its title, so nobody has to guess which one it
 * acts on. No menu that could open over another row.
 */
class CoachChatsMenuTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val now = Instant.parse("2026-11-13T17:41:00Z")
  private val first = TafsCloud.Chat("s1", now, now, "Set up an ongoing LinkedIn assistant")
  private val second = TafsCloud.Chat("s2", now.minusSeconds(10_000), now.minusSeconds(10_000), "Refunds under 50 euros")

  private fun named() = rule.onNodeWithTag("archive.chat", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()

  @Test fun aLongPressAsksToArchiveThatChatByName() {
    val archived = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        PreviousConversations(
          AppState(mode = Mode.kiff, ready = true, coachChats = listOf(first, second)),
          CoachChatActions(archive = { archived += it; true }),
          onClose = {},
        )
      }
    }
    rule.onNodeWithText(second.title).performTouchInput { longClick() }
    rule.waitForIdle()
    rule.onNodeWithText(Words.Coach.Chats.archiveTitle).assertExists()
    assertEquals(second.title, named())
    rule.onNodeWithText(Words.Coach.Chats.archive).performClick()
    rule.waitForIdle()
    assertEquals(listOf("s2"), archived)
  }

  @Test fun anArchivedChatIsPutBackByName() {
    val back = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        PreviousConversations(
          AppState(mode = Mode.kiff, ready = true, coachArchived = listOf(first)),
          CoachChatActions(unarchive = { back += it; true }),
          onClose = {},
        )
      }
    }
    rule.onNodeWithText(Words.Coach.Chats.archived).performClick()
    rule.waitForIdle()
    rule.onNodeWithText(first.title).performTouchInput { longClick() }
    rule.waitForIdle()
    assertEquals(first.title, named())
    rule.onNodeWithText(Words.Coach.Chats.putBack).performClick()
    rule.waitForIdle()
    assertEquals(listOf("s1"), back)
  }
}
