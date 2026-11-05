package bot.tafs.app

import bot.tafs.app.kiff.CalendarEvent
import bot.tafs.app.kiff.CalendarRead
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.CalendarLayout
import bot.tafs.app.model.CalendarMath
import bot.tafs.app.model.CalendarMode
import bot.tafs.app.model.CalendarState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar (kiff/tafs#534), as iOS's `CalendarTests`: tafs cloud gives every occurrence with its day and minute in the
 * account's zone; the app counts dates, groups and places, and converts no zone.
 */
class CalendarTest {
  private fun event(taf: String, day: String, time: String, color: Int = 0, kind: String = "schedule"): CalendarEvent {
    val (h, m) = time.split(":").map { it.toInt() }
    return CalendarEvent(taf = taf, name = taf.replaceFirstChar { it.uppercase() }, color = color, kind = kind, at = "${day}T$time:00Z", day = day, time = time, minute = h * 60 + m, words = "Every day at $time")
  }

  @Test fun decodesTheReadAndLeavesOutAnEventItCantRead() {
    val read = KiffClient.json.decodeFromString(CalendarRead.serializer(), """
      {"time_zone":"Europe/Paris","from":"2026-10-26","to":"2026-12-06","words":"2 to come","days":["2026-11-06"],
       "events":[
        {"taf":"t1","name":"Rae","color":2,"kind":"schedule","at":"2026-11-06T08:00:00Z","day":"2026-11-06","time":"09:00","minute":540,"words":"Every day at 09:00"},
        {"taf":"t1","name":"Rae","kind":"follow_up","at":"2026-11-06T12:00:00Z","day":"2026-11-06","time":"13:00","minute":780},
        {"taf":"t2","name":"broken"}]}
    """.trimIndent())
    assertEquals("Europe/Paris", read.timeZone)
    assertEquals("an event missing its day or time is left out, never the read", 2, read.events.size)
    assertEquals(540, read.events[0].minute)
    assertEquals("follow_up", read.events[1].kind)
    assertEquals(0, read.events[1].color)
    assertEquals(listOf("2026-11-06"), read.days)
    assertEquals("2 to come", read.words)
  }

  @Test fun todayIsTheAccountsDateNotTheDevices() {
    val late = Instant.parse("2026-11-05T23:30:00Z")
    assertEquals("2026-11-06", CalendarMath.today(ZoneId.of("Europe/Paris"), late))
    assertEquals("2026-11-05", CalendarMath.today(ZoneId.of("UTC"), late))
    assertEquals("2026-11-05", CalendarMath.today(ZoneId.of("America/Los_Angeles"), late))
  }

  @Test fun weeksStartOnMondayAndTheMonthGridHoldsEveryWeekOfTheMonth() {
    assertEquals("2026-11-02", CalendarMath.weekStart("2026-11-05"))
    assertEquals("Sunday ends the week", "2026-11-02", CalendarMath.weekStart("2026-11-08"))
    assertEquals(listOf("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08"), CalendarMath.week("2026-11-05"))
    val grid = CalendarMath.monthGrid("2026-11-17")
    assertEquals(42, grid.size)
    assertEquals("2026-10-26", grid.first())
    assertEquals("2026-12-06", grid.last())
    assertTrue(grid.contains("2026-11-30"))
    val (from, to) = CalendarMath.range("2026-11-30")
    assertEquals("2026-10-26", from)
    assertEquals("2026-12-06", to)
    assertTrue(CalendarMath.week("2026-11-30").all { it in from..to })
  }

  @Test fun previousAndNextMoveByTheViewsPeriod() {
    assertEquals("2027-01-01", CalendarMath.step(CalendarState(CalendarMode.Day, "2026-12-31"), 1).selected)
    assertEquals("2026-10-29", CalendarMath.step(CalendarState(CalendarMode.Week, "2026-11-05"), -1).selected)
    val month = CalendarMath.step(CalendarState(CalendarMode.Month, "2026-01-31"), 1)
    assertEquals("the month's last day when the next has fewer", "2026-02-28", month.selected)
    assertEquals(CalendarMode.Month, month.mode)
    assertEquals("2028-02-29", CalendarMath.plusMonths("2028-01-31", 1))
  }

  @Test fun titlesAcrossMonthsAndYearsMatchIOS() {
    assertEquals("Thu 5 November 2026", CalendarMath.title(CalendarState(CalendarMode.Day, "2026-11-05")))
    assertEquals("2 – 8 November 2026", CalendarMath.title(CalendarState(CalendarMode.Week, "2026-11-05")))
    assertEquals("30 November – 6 December 2026", CalendarMath.title(CalendarState(CalendarMode.Week, "2026-12-01")))
    assertEquals("28 December 2026 – 3 January 2027", CalendarMath.title(CalendarState(CalendarMode.Week, "2026-12-31")))
    assertEquals("November 2026", CalendarMath.title(CalendarState(CalendarMode.Month, "2026-11-05")))
  }

  @Test fun theViewOpensOnWeek() {
    assertEquals(CalendarMode.Week, CalendarState(selected = "2026-11-05").mode)
  }

  @Test fun wideOnlyOnItsSideWithRoomCompactOtherwise() {
    assertEquals("a tablet in landscape", CalendarLayout.Wide, CalendarLayout.of(1180, 820))
    assertEquals("beside the list, still on its side", CalendarLayout.Wide, CalendarLayout.of(860, 780))
    assertEquals("a tablet upright", CalendarLayout.Compact, CalendarLayout.of(820, 1180))
    assertEquals("a phone", CalendarLayout.Compact, CalendarLayout.of(393, 852))
    assertEquals("a phone on its side", CalendarLayout.Compact, CalendarLayout.of(852, 393))
    assertEquals("too narrow for the navigator beside it", CalendarLayout.Compact, CalendarLayout.of(600, 400))
  }

  @Test fun theDaysEventsTheAgendaAndTheShownDates() {
    val events = listOf(event("bo", "2026-11-06", "09:00"), event("rae", "2026-11-06", "09:00", color = 2), event("rae", "2026-11-05", "18:30"), event("rae", "2026-11-20", "08:00"))
    assertEquals("same minute: by name", listOf("bo", "rae"), CalendarMath.events(events, "2026-11-06").map { it.taf })
    assertTrue(CalendarMath.events(events, "2026-11-07").isEmpty())
    val week = CalendarMath.agenda(events, CalendarMath.shown(CalendarState(CalendarMode.Week, "2026-11-05")))
    assertEquals("days with nothing are left out, and another week's work too", listOf("2026-11-05", "2026-11-06"), week.map { it.first })
    val month = CalendarMath.shown(CalendarState(CalendarMode.Month, "2026-11-05"))
    assertEquals("2026-11-01", month.first())
    assertEquals("2026-11-30", month.last())
    assertEquals(3, CalendarMath.agenda(events, month).size)
  }

  @Test fun timeSlotsGroupATafsWorkInAnHourAndMarksShowAtMostThreeTafs() {
    val every = (0 until 4).map { event("rae", "2026-11-06", "09:%02d".format(it * 15)) }
    val slots = CalendarMath.slots(every + event("bo", "2026-11-06", "09:30", color = 1), "2026-11-06")
    assertEquals(listOf(CalendarMath.Slot("rae", "Rae", 0, 4, "09:00"), CalendarMath.Slot("bo", "Bo", 1, 1, "09:30")), slots[9])
    assertNull(slots[10])
    assertEquals(9, CalendarMath.firstHour(every, listOf("2026-11-06")))
    assertEquals("an empty day opens on the morning", 8, CalendarMath.firstHour(emptyList(), listOf("2026-11-06")))
    val four = listOf("a", "b", "c", "d").mapIndexed { i, t -> event(t, "2026-11-06", "10:00", color = i) }
    assertEquals(listOf(0, 1, 2), CalendarMath.marks(four, "2026-11-06"))
    assertEquals("an empty day has no mark", emptyList<Int>(), CalendarMath.marks(four, "2026-11-07"))
  }

  @Test fun aFoldedDayCountsEveryFiringAndAHaltedReadSaysSo() {
    val halted = KiffClient.json.decodeFromString(CalendarRead.serializer(), """{"time_zone":"UTC","halted":true,"more":false,"words":"Halted: nothing runs until it's resumed","events":[],"days":[]}""")
    assertTrue(halted.halted)
    assertEquals("Halted: nothing runs until it's resumed", halted.words)
    val folded = CalendarEvent(taf = "rae", name = "Rae", at = "2026-11-06T08:00:00Z", day = "2026-11-06", time = "09:00", minute = 540, words = "Every 15 minutes: 36 times from 09:00 to 17:45", rawCount = 36)
    val one = event("bo", "2026-11-06", "09:30", color = 1)
    assertEquals(37, CalendarMath.count(listOf(folded, one), "2026-11-06"))
    assertEquals(36, CalendarMath.slots(listOf(folded), "2026-11-06")[9]?.first()?.count)
    val old = KiffClient.json.decodeFromString(CalendarEvent.serializer(), """{"taf":"t","at":"x","day":"2026-11-06","time":"09:00","minute":540}""")
    assertEquals("an event without a count stands for one firing", 1, old.count)
  }

  @Test fun aReadCountsOnlyForTheRangeItAnswered() {
    val november = CalendarRead(from = "2026-10-26", to = "2026-12-06", words = "Nothing scheduled in these days")
    assertEquals(november, CalendarMath.current(november, "2026-11-20"))
    assertNull("another month's read never says this one is empty", CalendarMath.current(november, "2026-12-10"))
    assertNull("before the first answer: reading, not empty", CalendarMath.current(null, "2026-11-20"))
  }

  @Test fun aDSTDayIsPlacedByTheMinuteTafsCloudGivesNotByTheInstant() {
    // 25 October 2026 in Paris: 09:00 is 08:00Z, an hour later in UTC than the day before.
    val before = CalendarEvent(taf = "rae", name = "Rae", at = "2026-10-24T07:00:00Z", day = "2026-10-24", time = "09:00", minute = 540)
    val after = CalendarEvent(taf = "rae", name = "Rae", at = "2026-10-25T08:00:00Z", day = "2026-10-25", time = "09:00", minute = 540)
    assertNotNull(CalendarMath.slots(listOf(before, after), "2026-10-24")[9])
    assertNotNull("the same slot on both days", CalendarMath.slots(listOf(before, after), "2026-10-25")[9])
  }
}
