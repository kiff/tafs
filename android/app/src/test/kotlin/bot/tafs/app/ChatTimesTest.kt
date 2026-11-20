package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Res
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import bot.tafs.app.model.chatDay
import bot.tafs.app.model.messageTime
import bot.tafs.app.model.zone
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DAY_HEADER
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.MESSAGE_TIME
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafChatView
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * When each message was sent (row 44, C; docs/chat.md): a header where a day starts (Today, Yesterday, then the date) and the
 * time under each message, in the device's 12- or 24-hour setting and the app's language. Coach's chat is the same view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatTimesTest {
  @get:Rule val compose = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")
  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()

  @Test fun eachDayHasItsHeaderAndEachMessageItsTime() {
    RuntimeEnvironment.setQualifiers("en-w411dp-h2400dp-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val today = LocalDate.now(zone)
    val older = today.minusDays(5)
    val messages = listOf(
      ChatMessage(at(older, 9, 30), ChatMessage.From.owner, "Anything late?", "s1"),
      ChatMessage(at(older, 9, 31), ChatMessage.From.taf, "Nothing late.", "s1"),
      ChatMessage(at(today.minusDays(1), 18, 2), ChatMessage.From.owner, "And now?", "s2"),
      ChatMessage(at(today, 8, 5), ChatMessage.From.owner, "Refund order 5098", "s3"),
      ChatMessage(at(today, 8, 6), ChatMessage.From.taf, "Refunded €12.", "s3"),
    )
    val state = AppState(
      mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
      access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)), chats = mapOf("refunds-1" to messages),
    )
    compose.setContent { TafsTheme { TafChatView("refunds-1", state, actions) } }

    // Three days, three headers, in order: the date five days ago in English's order, then Yesterday, then Today.
    val headers = compose.onAllNodesWithTag(DAY_HEADER).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
    assertEquals(listOf(chatDay(older), Words.Chat.Day.yesterday, Words.Calendar.today), headers)
    // A time under each of the five messages, as the device counts hours.
    val is24 = android.text.format.DateFormat.is24HourFormat(ApplicationProvider.getApplicationContext())
    assertEquals(5, compose.onAllNodesWithTag(MESSAGE_TIME).fetchSemanticsNodes().size)
    for (m in messages) compose.onNodeWithText(messageTime(m.at, is24)).assertExists()
  }
}
