package bot.tafs.app.model

import bot.tafs.app.kiff.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The calendar's three views (kiff/tafs#534). Week is the one it opens on. */
enum class CalendarMode {
  Day, Week, Month;

  val title: String get() = when (this) { Day -> Words.Calendar.Mode.day; Week -> Words.Calendar.Mode.week; Month -> Words.Calendar.Mode.month }
}

/** What the calendar shows: its view and the selected date (YYYY-MM-DD in the account's zone). Kept in the app's state, so a rotation keeps it. */
data class CalendarState(val mode: CalendarMode = CalendarMode.Week, val selected: String)

/** Wide (a month navigator, the calendar beside it, an agenda under it) or compact (a week strip, the selected day's events under it). */
enum class CalendarLayout {
  Wide, Compact;

  companion object {
    /**
     * Wide only when there's room for the navigator beside the calendar and the screen lies on its side with the height of a
     * tablet: a tablet in landscape. A tablet upright and a phone, either way up, are compact. As iOS's `CalendarLayout.of`.
     */
    fun of(widthDp: Int, heightDp: Int): CalendarLayout = if (widthDp >= 700 && widthDp > heightDp && heightDp >= 600) Wide else Compact
  }
}

/**
 * The calendar's dates, all as YYYY-MM-DD in the account's zone, counted on the ISO calendar alone: no zone is converted here
 * (tafs cloud gives each event its day and minute), and iOS counts them the same way (`CalendarMath`). Weeks start on Monday.
 */
object CalendarMath {
  val monthNames: List<String>
    get() = Words.Calendar.MonthName.let {
      listOf(it.january, it.february, it.march, it.april, it.may, it.june, it.july, it.august, it.september, it.october, it.november, it.december)
    }

  /**
   * A month on its own, as the month view's title names it: the form a language uses with no day ("listopad 2026" in Polish,
   * where a date says "5 listopada").
   */
  val monthTitles: List<String>
    get() = Words.Calendar.MonthTitle.let {
      listOf(it.january, it.february, it.march, it.april, it.may, it.june, it.july, it.august, it.september, it.october, it.november, it.december)
    }

  /** Monday first. */
  val weekdayNames: List<String>
    get() = Words.Calendar.Weekday.let { listOf(it.mon, it.tue, it.wed, it.thu, it.fri, it.sat, it.sun) }

  private fun date(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

  /** Today in a zone. */
  fun today(zone: ZoneId, now: Instant = Instant.now()): String = now.atZone(zone).toLocalDate().toString()

  fun plusDays(day: String, n: Int): String = date(day)?.plusDays(n.toLong())?.toString() ?: day

  /** The same day of the month [n] months on, on the month's last day when it has fewer. */
  fun plusMonths(day: String, n: Int): String = date(day)?.plusMonths(n.toLong())?.toString() ?: day

  /** 0 for Monday to 6 for Sunday. */
  fun weekdayIndex(day: String): Int = (date(day)?.dayOfWeek?.value ?: 1) - 1

  /** The Monday of the day's week. */
  fun weekStart(day: String): String = plusDays(day, -weekdayIndex(day))

  fun week(day: String): List<String> = weekStart(day).let { start -> (0 until 7).map { plusDays(start, it) } }

  fun monthStart(day: String): String = date(day)?.withDayOfMonth(1)?.toString() ?: day

  /** Six weeks from the Monday on or before the month's first day: every week with a day of the month. */
  fun monthGrid(day: String): List<String> = weekStart(monthStart(day)).let { start -> (0 until 42).map { plusDays(start, it) } }

  fun sameMonth(a: String, b: String): Boolean = a.take(7) == b.take(7)

  fun dayNumber(day: String): Int = date(day)?.dayOfMonth ?: 0

  /** The dates one read covers: the selected month's grid, which holds its day and its week too. */
  fun range(selected: String): Pair<String, String> = monthGrid(selected).let { it.first() to it.last() }

  /**
   * The read, when it answers the selected date's range; null while that range is still being read, so a view never says
   * "Nothing scheduled" from another month's read or before the first. As iOS's `CalendarMath.current`.
   */
  fun current(read: bot.tafs.app.kiff.CalendarRead?, selected: String): bot.tafs.app.kiff.CalendarRead? {
    val (from, to) = range(selected)
    return read?.takeIf { it.from == from && it.to == to }
  }

  /** The dates a view shows. */
  fun shown(state: CalendarState): List<String> = when (state.mode) {
    CalendarMode.Day -> listOf(state.selected)
    CalendarMode.Week -> week(state.selected)
    CalendarMode.Month -> monthGrid(state.selected).filter { sameMonth(it, state.selected) }
  }

  /** The previous or next day, week or month. */
  fun step(state: CalendarState, by: Int): CalendarState = when (state.mode) {
    CalendarMode.Day -> state.copy(selected = plusDays(state.selected, by))
    CalendarMode.Week -> state.copy(selected = plusDays(state.selected, 7 * by))
    CalendarMode.Month -> state.copy(selected = plusMonths(state.selected, by))
  }

  /** The view's title: "Thu 5 November 2026", "2 – 8 November 2026", "November 2026". */
  fun title(state: CalendarState): String {
    val d = date(state.selected) ?: return state.selected
    val months = monthNames
    return when (state.mode) {
      CalendarMode.Day -> Words.Calendar.Title.day(weekdayNames[weekdayIndex(state.selected)], d.dayOfMonth.toString(), months[d.monthValue - 1], d.year.toString())
      CalendarMode.Week -> {
        val days = week(state.selected)
        val a = date(days[0]) ?: return state.selected
        val b = date(days[6]) ?: return state.selected
        when {
          a.year != b.year -> Words.Calendar.Title.weekAcrossYears(
            a.dayOfMonth.toString(), months[a.monthValue - 1], a.year.toString(), b.dayOfMonth.toString(), months[b.monthValue - 1], b.year.toString(),
          )
          a.monthValue != b.monthValue -> Words.Calendar.Title.weekAcrossMonths(
            a.dayOfMonth.toString(), months[a.monthValue - 1], b.dayOfMonth.toString(), months[b.monthValue - 1], b.year.toString(),
          )
          else -> Words.Calendar.Title.week(a.dayOfMonth.toString(), b.dayOfMonth.toString(), months[b.monthValue - 1], b.year.toString())
        }
      }
      CalendarMode.Month -> Words.Calendar.Title.month(monthTitles[d.monthValue - 1], d.year.toString())
    }
  }

  /** A day as a heading in the agenda: "Thu 5 November". */
  fun dayHeading(day: String): String {
    val d = date(day) ?: return day
    return Words.Calendar.dayHeading(weekdayNames[weekdayIndex(day)], d.dayOfMonth.toString(), monthNames[d.monthValue - 1])
  }

  /** The events of a day, in time order. */
  fun events(events: List<CalendarEvent>, day: String): List<CalendarEvent> =
    events.filter { it.day == day }.sortedWith(compareBy<CalendarEvent>({ it.minute }, { it.name }, { it.at }))

  /** The agenda: the events on the given dates, by day, each day's in time order. Days with none are left out. */
  fun agenda(events: List<CalendarEvent>, days: List<String>): List<Pair<String, List<CalendarEvent>>> =
    days.mapNotNull { day -> events(events, day).takeIf { it.isNotEmpty() }?.let { day to it } }

  /** One hour's slot of a day: each taf with work in it once, with how many times, in order of its first. */
  data class Slot(val taf: String, val name: String, val color: Int, val count: Int, val first: String)

  /** A day's work by hour (0 to 23), for the Day and Week views' time slots. */
  fun slots(events: List<CalendarEvent>, day: String): Map<Int, List<Slot>> {
    val out = linkedMapOf<Int, MutableList<Slot>>()
    for (e in events(events, day)) {
      val hour = (e.minute / 60).coerceIn(0, 23)
      val row = out.getOrPut(hour) { mutableListOf() }
      val i = row.indexOfFirst { it.taf == e.taf }
      if (i >= 0) row[i] = row[i].copy(count = row[i].count + e.count) else row.add(Slot(e.taf, e.name, e.color, e.count, e.time))
    }
    return out
  }

  /** The first hour with work among the dates, to scroll a time view to; 8 when there's none. */
  fun firstHour(events: List<CalendarEvent>, days: List<String>): Int =
    events.filter { it.day in days }.minOfOrNull { (it.minute / 60).coerceIn(0, 23) } ?: 8

  /** How many firings a day has, a folded event counting all it stands for. */
  fun count(events: List<CalendarEvent>, day: String): Int = events.filter { it.day == day }.sumOf { it.count }

  /** The colours of the tafs with work on a day, at most three, for the day's marks. */
  fun marks(events: List<CalendarEvent>, day: String): List<Int> =
    events(events, day).distinctBy { it.taf }.take(3).map { it.color }
}
