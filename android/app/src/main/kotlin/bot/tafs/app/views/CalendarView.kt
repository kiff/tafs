package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.CalendarEvent
import bot.tafs.app.model.CalendarLayout
import bot.tafs.app.model.CalendarMath
import bot.tafs.app.model.CalendarMode
import bot.tafs.app.model.CalendarState
import bot.tafs.app.model.zone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val HOUR_HEIGHT = 44.dp

/**
 * The tafs' scheduled work still to come (kiff/tafs#534), as iOS's `CalendarView`: Day, Week and Month, Week first. Every
 * occurrence is tafs cloud's (`GET /v1/calendar`), placed by the day and minute it gives in the account's zone; the app computes
 * none. Read-only: a tap opens the taf. Wide (a tablet on its side): a month navigator at the upper left, the calendar beside it,
 * the agenda under them. Compact (a tablet upright, a phone): a scrolling week strip, the selected day's work under it. What it
 * shows is kept in the app's state, so a rotation keeps it.
 */
@Composable
fun CalendarView(
  state: AppState,
  onShow: (CalendarState) -> Unit,
  load: suspend (CalendarState) -> Unit,
  tafIdForCloud: (String) -> String?,
  onOpenTaf: (String) -> Unit,
  onBack: (() -> Unit)? = null,
) {
  val today = CalendarMath.today(zone)
  val shown = state.calendarState ?: CalendarState(selected = today)
  // The read for the selected date's range, or null while it's being read: never another month's, never "nothing" before the first.
  val read = CalendarMath.current(state.calendar, shown.selected)
  val events = read?.events ?: emptyList()
  val range = CalendarMath.range(shown.selected)
  var retries by remember { mutableStateOf(0) }
  LaunchedEffect(range.first, retries) { load(shown) }
  val ctx = Ctx(shown, events, today, read != null, read?.words.orEmpty(), onShow, { cloud -> tafIdForCloud(cloud)?.let(onOpenTaf) }, tafIdForCloud)

  BoxWithConstraints(Modifier.fillMaxSize().background(Theme.background).statusBarsPadding().testTag("calendar")) {
    val layout = CalendarLayout.of(maxWidth.value.toInt(), maxHeight.value.toInt())
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (layout == CalendarLayout.Wide) 24.dp else 16.dp).widthIn(max = 1180.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (onBack != null) {
        Row(Modifier.clickable(onClick = onBack).semantics { contentDescription = "Back" }, verticalAlignment = Alignment.CenterVertically) {
          Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.amber, modifier = Modifier.size(20.dp))
          Text("Tafs", style = scaled(16, color = Theme.amber))
        }
      }
      Text("Calendar", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
      val error = state.calendarError
      when {
        error != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(error, style = scaled(13, color = Theme.no), modifier = Modifier.weight(1f, fill = false))
          OutlineButton("Try again", onClick = { retries += 1 }, modifier = Modifier.testTag("calendar.retry"))
        }
        read == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("calendar.loading")) {
          androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Theme.dim, strokeWidth = 2.dp)
          Text("Reading the calendar…", style = scaled(13, color = Theme.dim))
        }
        // tafs cloud's words: the account is halted, or some work was left out of this read.
        read.halted || read.more ->
          Text(read.words, style = scaled(13, FontWeight.SemiBold, if (read.halted) Theme.no else Theme.soft), modifier = Modifier.testTag("calendar.words"))
      }
      if (layout == CalendarLayout.Wide) Wide(ctx) else Compact(ctx)
    }
  }
}

/** What every part of the calendar reads. */
private class Ctx(
  val state: CalendarState,
  val events: List<CalendarEvent>,
  val today: String,
  /** The read answers this range: only then is an empty day "nothing scheduled". */
  val loaded: Boolean,
  /** tafs cloud's words for the range, for the agenda when nothing is to come. */
  val words: String,
  val show: (CalendarState) -> Unit,
  val open: (String) -> Unit,
  val known: (String) -> String?,
) {
  fun select(day: String) = show(state.copy(selected = day))
}

@Composable
private fun Wide(c: Ctx) {
  Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Navigator(c, Modifier.width(280.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Header(c, compact = false)
      when (c.state.mode) {
        CalendarMode.Day -> TimeGrid(c, listOf(c.state.selected))
        CalendarMode.Week -> TimeGrid(c, CalendarMath.week(c.state.selected))
        CalendarMode.Month -> MonthCells(c, large = true)
      }
    }
  }
  Agenda(c)
}

@Composable
private fun Compact(c: Ctx) {
  Header(c, compact = true)
  when (c.state.mode) {
    CalendarMode.Day -> { WeekStrip(c); TimeGrid(c, listOf(c.state.selected)) }
    CalendarMode.Week -> { WeekStrip(c); DayList(c, c.state.selected) }
    CalendarMode.Month -> { MonthCells(c, large = false); DayList(c, c.state.selected) }
  }
}

/** Today, previous and next, the title, a date to pick, and Day / Week / Month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(c: Ctx, compact: Boolean) {
  var picking by rememberSaveable { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlineButton("Today", onClick = { c.select(c.today) }, enabled = c.state.selected != c.today, modifier = Modifier.testTag("calendar.today"))
      Chevron(R.drawable.ic_chevron_left, "Previous ${c.state.mode.title.lowercase()}") { c.show(CalendarMath.step(c.state, -1)) }
      Chevron(R.drawable.ic_chevron_right, "Next ${c.state.mode.title.lowercase()}") { c.show(CalendarMath.step(c.state, 1)) }
      Text(
        CalendarMath.title(c.state), style = scaled(if (compact) 16 else 20, FontWeight.Bold, Theme.ink, display = true),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("calendar.title"),
      )
      Box(
        Modifier.size(40.dp).clip(CircleShape).background(Theme.raised).clickable { picking = true }.semantics { contentDescription = "Choose a date" },
        contentAlignment = Alignment.Center,
      ) { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(20.dp)) }
    }
    Box(Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp)).testTag("calendar.mode")) {
      Segmented(CalendarMode.entries, c.state.mode, { it.title }) { c.show(c.state.copy(mode = it)) }
    }
  }
  if (picking) {
    // The picker counts in UTC midnights: the selected date as one, and back, so the day it shows is the day it holds.
    val initial = runCatching { LocalDate.parse(c.state.selected).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
      onDismissRequest = { picking = false },
      confirmButton = {
        TextButton(onClick = {
          picker.selectedDateMillis?.let { c.select(CalendarMath.today(ZoneOffset.UTC, Instant.ofEpochMilli(it))) }
          picking = false
        }) { Text("Choose", style = scaled(15, FontWeight.SemiBold, Theme.amberText)) }
      },
      dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", style = scaled(15, color = Theme.soft)) } },
    ) { DatePicker(state = picker) }
  }
}

@Composable
private fun Chevron(icon: Int, label: String, onClick: () -> Unit) {
  Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
    Icon(painterResource(icon), contentDescription = null, tint = Theme.amberText, modifier = Modifier.size(20.dp))
  }
}

/** The month navigator, at the upper left of the wide layout. */
@Composable
private fun Navigator(c: Ctx, modifier: Modifier) {
  Column(
    modifier.clip(RoundedCornerShape(14.dp)).background(Theme.panel).padding(14.dp).testTag("calendar.navigator"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(CalendarMath.title(CalendarState(CalendarMode.Month, c.state.selected)), style = scaled(15, FontWeight.SemiBold), modifier = Modifier.weight(1f))
      Chevron(R.drawable.ic_chevron_left, "Previous month") { c.select(CalendarMath.plusMonths(c.state.selected, -1)) }
      Chevron(R.drawable.ic_chevron_right, "Next month") { c.select(CalendarMath.plusMonths(c.state.selected, 1)) }
    }
    MonthCells(c, large = false)
  }
}

@Composable
private fun MonthCells(c: Ctx, large: Boolean) {
  Column(Modifier.testTag("calendar.month"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      CalendarMath.weekdayNames.forEach { Text(it, style = scaled(11, FontWeight.Bold, Theme.dim), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
    CalendarMath.monthGrid(c.state.selected).chunked(7).forEach { week ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { week.forEach { day -> DayCell(c, day, large, Modifier.weight(1f)) } }
    }
  }
}

@Composable
private fun DayCell(c: Ctx, day: String, large: Boolean, modifier: Modifier) {
  val inMonth = CalendarMath.sameMonth(day, c.state.selected)
  val marks = CalendarMath.marks(c.events, day)
  val count = CalendarMath.count(c.events, day)
  val selected = day == c.state.selected
  Column(
    modifier
      .heightIn(min = if (large) 64.dp else 38.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) Theme.amber.copy(alpha = 0.18f) else Color.Transparent)
      .border(1.5.dp, if (day == c.today) Theme.amber else Color.Transparent, RoundedCornerShape(8.dp))
      .clickable { c.select(day) }
      .semantics(mergeDescendants = true) {
        contentDescription = CalendarMath.dayHeading(day) + if (count > 0) ", $count scheduled" else ""
        this.selected = selected
      }
      .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text("${CalendarMath.dayNumber(day)}", style = scaled(if (large) 15 else 13, if (selected) FontWeight.Bold else FontWeight.Normal, if (inMonth) Theme.ink else Theme.dim.copy(alpha = 0.6f)))
    Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { marks.forEach { Dot(Theme.tafColor(it), 5.dp) } }
    if (large && count > 0) Text("$count", style = scaled(11, color = Theme.soft))
  }
}

/** The scrolling week strip at the top of the compact layout. */
@Composable
private fun WeekStrip(c: Ctx) {
  val days = CalendarMath.monthGrid(c.state.selected)
  val list = rememberLazyListState()
  LaunchedEffect(c.state.selected) { list.animateScrollToItem(days.indexOf(CalendarMath.weekStart(c.state.selected)).coerceAtLeast(0)) }
  LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("calendar.strip")) {
    items(days, key = { it }) { day ->
      val selected = day == c.state.selected
      Column(
        Modifier
          .width(46.dp).height(66.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(if (selected) Theme.amber else Theme.panel)
          .border(1.5.dp, if (day == c.today && !selected) Theme.amber else Color.Transparent, RoundedCornerShape(12.dp))
          .clickable { c.select(day) }
          .semantics(mergeDescendants = true) { contentDescription = CalendarMath.dayHeading(day); this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
      ) {
        Text(CalendarMath.weekdayNames[CalendarMath.weekdayIndex(day)], style = scaled(11, FontWeight.Bold, if (selected) Theme.amberInk else Theme.dim))
        Text("${CalendarMath.dayNumber(day)}", style = scaled(17, FontWeight.SemiBold, if (selected) Theme.amberInk else Theme.ink))
        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          CalendarMath.marks(c.events, day).forEach { Dot(if (selected) Theme.amberInk else Theme.tafColor(it), 5.dp) }
        }
      }
    }
  }
}

/** Day and Week: each hour's slot, with each taf's work in it. */
@Composable
private fun TimeGrid(c: Ctx, days: List<String>) {
  val byDay = remember(c.events, days) { days.associateWith { CalendarMath.slots(c.events, it) } }
  val first = CalendarMath.firstHour(c.events, days)
  val scroll = rememberScrollState()
  val hourPx = with(androidx.compose.ui.platform.LocalDensity.current) { HOUR_HEIGHT.roundToPx() }
  LaunchedEffect(first, days) { scroll.animateScrollTo(first * hourPx) }
  Column(Modifier.testTag("calendar.slots")) {
    if (days.size > 1) {
      Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(48.dp))
        days.forEach { day ->
          Column(
            Modifier.weight(1f).clickable { c.show(CalendarState(CalendarMode.Day, day)) }.semantics(mergeDescendants = true) { contentDescription = CalendarMath.dayHeading(day) },
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(CalendarMath.weekdayNames[CalendarMath.weekdayIndex(day)], style = scaled(11, FontWeight.Bold, Theme.dim))
            Text("${CalendarMath.dayNumber(day)}", style = scaled(15, if (day == c.today) FontWeight.Bold else FontWeight.Normal, if (day == c.today) Theme.amberText else Theme.ink))
          }
        }
      }
    }
    Column(Modifier.height(520.dp).verticalScroll(scroll)) {
      (0 until 24).forEach { hour ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
        Row(Modifier.heightIn(min = HOUR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("%02d:00".format(hour), style = scaled(11, color = Theme.dim, mono = true), modifier = Modifier.width(48.dp).padding(top = 3.dp))
          days.forEach { day ->
            Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              byDay[day]?.get(hour)?.forEach { SlotChip(c, it, compact = days.size > 1) }
            }
          }
        }
      }
    }
    if (c.loaded && days.all { CalendarMath.events(c.events, it).isEmpty() }) Empty(if (days.size == 1) "Nothing scheduled on this day." else "Nothing scheduled this week.")
  }
}

@Composable
private fun SlotChip(c: Ctx, slot: CalendarMath.Slot, compact: Boolean) {
  val opens = c.known(slot.taf) != null
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Theme.tafColor(slot.color).copy(alpha = 0.16f))
      .then(if (opens) Modifier.clickable { c.open(slot.taf) } else Modifier)
      .semantics(mergeDescendants = true) { contentDescription = "${slot.name}, ${if (slot.count > 1) "${slot.count} times from" else "at"} ${slot.first}" }
      .padding(horizontal = 6.dp, vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Dot(Theme.tafColor(slot.color), 7.dp)
    Text(if (compact) slot.name else "${slot.first} · ${slot.name}", style = scaled(12, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    if (slot.count > 1) Text("×${slot.count}", style = scaled(11, color = Theme.soft))
  }
}

/** The selected day's work, under the compact layout's strip or grid. */
@Composable
private fun DayList(c: Ctx, day: String) {
  val on = CalendarMath.events(c.events, day)
  Column(Modifier.testTag("calendar.day"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Eyebrow(CalendarMath.dayHeading(day))
    if (c.loaded && on.isEmpty()) Empty("Nothing scheduled on this day.")
    on.forEach { EventRow(c, it) }
  }
}

/** The wide layout's agenda: the shown dates' work, by day. */
@Composable
private fun Agenda(c: Ctx) {
  val groups = CalendarMath.agenda(c.events, CalendarMath.shown(c.state))
  Column(Modifier.testTag("calendar.agenda"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Eyebrow("Agenda")
    // tafs cloud's own words when nothing is to come, so both apps say the same.
    if (c.loaded && groups.isEmpty()) Empty(c.words.ifEmpty { "Nothing scheduled in these days." })
    groups.forEach { (day, events) ->
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(CalendarMath.dayHeading(day), style = scaled(14, FontWeight.SemiBold, if (day == c.today) Theme.amberText else Theme.ink))
        events.forEach { EventRow(c, it) }
      }
    }
  }
}

@Composable
private fun EventRow(c: Ctx, e: CalendarEvent) {
  val opens = c.known(e.taf) != null
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Theme.panel)
      .then(if (opens) Modifier.clickable { c.open(e.taf) } else Modifier)
      .semantics(mergeDescendants = true) {}
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(e.time, style = scaled(13, color = Theme.soft, mono = true), modifier = Modifier.width(46.dp))
    Dot(Theme.tafColor(e.color), 9.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(e.name, style = scaled(14, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(e.words, style = scaled(12, color = Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun Empty(words: String) {
  Text(words, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(vertical = 6.dp))
}
