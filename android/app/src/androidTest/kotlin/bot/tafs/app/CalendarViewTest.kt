package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.CalendarRead
import bot.tafs.app.model.CalendarMath
import bot.tafs.app.model.CalendarState
import bot.tafs.app.views.CalendarView
import org.junit.Rule
import org.junit.Test

/**
 * The calendar never says "Nothing scheduled" when it doesn't know (kiff/tafs#537, dev-android): a failed read shows its error
 * with Try again, and a read of another month shows it's reading.
 */
class CalendarViewTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val shown = CalendarState(selected = "2026-11-05")

  private fun show(state: AppState) = rule.setShown {
    TafsTheme { CalendarView(state.copy(calendarState = shown), onShow = {}, load = {}, tafIdForCloud = { null }, onOpenTaf = {}) }
  }

  @Test fun aFailedReadShowsItsErrorAndTryAgainNeverNothingScheduled() {
    show(AppState(mode = Mode.kiff, ready = true, calendarError = "The calendar couldn't be read. Offline."))
    rule.onNodeWithText("The calendar couldn't be read. Offline.").assertIsDisplayed()
    rule.onNodeWithText("Try again").assertIsDisplayed()
    rule.onNodeWithText("Nothing scheduled on this day.").assertDoesNotExist()
  }

  @Test fun anotherMonthsReadShowsReadingNeverNothingScheduled() {
    show(AppState(mode = Mode.kiff, ready = true, calendar = CalendarRead(from = "2026-08-31", to = "2026-10-11", words = "Nothing scheduled in these days")))
    rule.onNodeWithText("Reading the calendar…").assertIsDisplayed()
    rule.onNodeWithText("Nothing scheduled on this day.").assertDoesNotExist()
  }

  @Test fun thisMonthsEmptyReadSaysNothingScheduled() {
    val (from, to) = CalendarMath.range(shown.selected)
    show(AppState(mode = Mode.kiff, ready = true, calendar = CalendarRead(from = from, to = to, words = "Nothing scheduled in these days")))
    rule.onNodeWithText("Nothing scheduled on this day.").assertIsDisplayed()
  }
}
