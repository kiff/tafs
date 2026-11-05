import XCTest
@testable import Tafs

/// The calendar (kiff/tafs#534): tafs cloud gives every occurrence with its day and minute in the
/// account's zone; the app counts dates, groups and places, and converts no zone.
final class CalendarTests: XCTestCase {
    private func event(_ taf: String, _ day: String, _ time: String, color: Int = 0, kind: String = "schedule") -> TafsCloud.CalendarEvent {
        let p = time.split(separator: ":").compactMap { Int($0) }
        return TafsCloud.CalendarEvent(taf: taf, name: taf.capitalized, color: color, kind: kind, at: "\(day)T\(time):00Z", day: day, time: time, minute: p[0] * 60 + p[1], words: "Every day at \(time)")
    }

    func testDecodesTheReadAndLeavesOutAnEventItCantRead() throws {
        let json = #"""
        {"time_zone":"Europe/Paris","from":"2026-10-26","to":"2026-12-06","words":"2 to come","days":["2026-11-06"],
         "events":[
          {"taf":"t1","name":"Rae","color":2,"kind":"schedule","at":"2026-11-06T08:00:00Z","day":"2026-11-06","time":"09:00","minute":540,"words":"Every day at 09:00"},
          {"taf":"t1","name":"Rae","kind":"follow_up","at":"2026-11-06T12:00:00Z","day":"2026-11-06","time":"13:00","minute":780},
          {"taf":"t2","name":"broken"}]}
        """#
        let read = try KIFFClient.decoder.decode(TafsCloud.CalendarRead.self, from: Data(json.utf8))
        XCTAssertEqual(read.timeZone, "Europe/Paris")
        XCTAssertEqual(read.events.count, 2, "an event missing its day or time is left out, never the read")
        XCTAssertEqual(read.events[0].minute, 540)
        XCTAssertEqual(read.events[1].kind, "follow_up")
        XCTAssertEqual(read.events[1].color, 0)
        XCTAssertEqual(read.days, ["2026-11-06"])
        XCTAssertEqual(read.words, "2 to come")
    }

    func testTodayIsTheAccountsDateNotTheDevices() {
        let late = ISO8601DateFormatter().date(from: "2026-11-05T23:30:00Z")!
        XCTAssertEqual(CalendarMath.today(in: TimeZone(identifier: "Europe/Paris")!, now: late), "2026-11-06")
        XCTAssertEqual(CalendarMath.today(in: TimeZone(identifier: "UTC")!, now: late), "2026-11-05")
        XCTAssertEqual(CalendarMath.today(in: TimeZone(identifier: "America/Los_Angeles")!, now: late), "2026-11-05")
    }

    func testWeeksStartOnMondayAndTheMonthGridHoldsEveryWeekOfTheMonth() {
        XCTAssertEqual(CalendarMath.weekStart("2026-11-05"), "2026-11-02")
        XCTAssertEqual(CalendarMath.weekStart("2026-11-08"), "2026-11-02", "Sunday ends the week")
        XCTAssertEqual(CalendarMath.week("2026-11-05"), ["2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08"])
        let grid = CalendarMath.monthGrid("2026-11-17")
        XCTAssertEqual(grid.count, 42)
        XCTAssertEqual(grid.first, "2026-10-26")
        XCTAssertEqual(grid.last, "2026-12-06")
        XCTAssertTrue(grid.contains("2026-11-30"))
        // One read covers the month's grid: its day and week are always inside.
        let r = CalendarMath.range("2026-11-30")
        XCTAssertEqual(r.from, "2026-10-26")
        XCTAssertEqual(r.to, "2026-12-06")
        XCTAssertTrue(CalendarMath.week("2026-11-30").allSatisfy { $0 >= r.from && $0 <= r.to })
    }

    func testPreviousAndNextMoveByTheViewsPeriodAndTodayComesBack() {
        let day = CalendarState(mode: .day, selected: "2026-12-31")
        XCTAssertEqual(CalendarMath.step(day, by: 1).selected, "2027-01-01")
        let week = CalendarState(mode: .week, selected: "2026-11-05")
        XCTAssertEqual(CalendarMath.step(week, by: -1).selected, "2026-10-29")
        let month = CalendarState(mode: .month, selected: "2026-01-31")
        XCTAssertEqual(CalendarMath.step(month, by: 1).selected, "2026-02-28", "the month's last day when the next has fewer")
        XCTAssertEqual(CalendarMath.step(month, by: 1).mode, .month)
        XCTAssertEqual(CalendarMath.plusMonths("2028-01-31", 1), "2028-02-29")
    }

    func testTitlesAcrossMonthsAndYears() {
        XCTAssertEqual(CalendarMath.title(CalendarState(mode: .day, selected: "2026-11-05")), "Thu 5 November 2026")
        XCTAssertEqual(CalendarMath.title(CalendarState(mode: .week, selected: "2026-11-05")), "2 – 8 November 2026")
        XCTAssertEqual(CalendarMath.title(CalendarState(mode: .week, selected: "2026-12-01")), "30 November – 6 December 2026")
        XCTAssertEqual(CalendarMath.title(CalendarState(mode: .week, selected: "2026-12-31")), "28 December 2026 – 3 January 2027")
        XCTAssertEqual(CalendarMath.title(CalendarState(mode: .month, selected: "2026-11-05")), "November 2026")
    }

    func testTheViewOpensOnWeekAndItsStateSurvivesBeingKept() throws {
        let s = CalendarState(selected: "2026-11-05")
        XCTAssertEqual(s.mode, .week)
        let kept = try JSONDecoder().decode(CalendarState.self, from: JSONEncoder().encode(CalendarState(mode: .month, selected: "2026-11-20")))
        XCTAssertEqual(kept, CalendarState(mode: .month, selected: "2026-11-20"))
    }

    func testWideOnlyOnItsSideWithRoomCompactOtherwise() {
        XCTAssertEqual(CalendarLayout.of(width: 1180, height: 820), .wide, "an iPad in landscape")
        XCTAssertEqual(CalendarLayout.of(width: 860, height: 780), .wide, "beside the sidebar, still on its side")
        XCTAssertEqual(CalendarLayout.of(width: 820, height: 1180), .compact, "an iPad upright")
        XCTAssertEqual(CalendarLayout.of(width: 393, height: 852), .compact, "a phone")
        XCTAssertEqual(CalendarLayout.of(width: 852, height: 393), .compact, "a phone on its side")
        XCTAssertEqual(CalendarLayout.of(width: 600, height: 400), .compact, "too narrow for the navigator beside it")
    }

    func testTheDaysEventsTheAgendaAndTheShownDates() {
        let events = [event("bo", "2026-11-06", "09:00"), event("rae", "2026-11-06", "09:00", color: 2), event("rae", "2026-11-05", "18:30"), event("rae", "2026-11-20", "08:00")]
        XCTAssertEqual(CalendarMath.events(events, on: "2026-11-06").map(\.taf), ["bo", "rae"], "same minute: by name")
        XCTAssertTrue(CalendarMath.events(events, on: "2026-11-07").isEmpty)
        let week = CalendarMath.agenda(events, days: CalendarMath.shown(CalendarState(mode: .week, selected: "2026-11-05")))
        XCTAssertEqual(week.map(\.day), ["2026-11-05", "2026-11-06"], "days with nothing are left out, and another week's work too")
        let month = CalendarMath.shown(CalendarState(mode: .month, selected: "2026-11-05"))
        XCTAssertEqual(month.first, "2026-11-01")
        XCTAssertEqual(month.last, "2026-11-30")
        XCTAssertEqual(CalendarMath.agenda(events, days: month).count, 3)
    }

    func testTimeSlotsGroupATafsWorkInAnHourAndMarksShowAtMostThreeTafs() {
        let every = (0..<4).map { event("rae", "2026-11-06", String(format: "09:%02d", $0 * 15)) }
        let slots = CalendarMath.slots(every + [event("bo", "2026-11-06", "09:30", color: 1)], on: "2026-11-06")
        XCTAssertEqual(slots[9], [CalendarMath.Slot(taf: "rae", name: "Rae", color: 0, count: 4, first: "09:00"),
                                  CalendarMath.Slot(taf: "bo", name: "Bo", color: 1, count: 1, first: "09:30")])
        XCTAssertNil(slots[10])
        XCTAssertEqual(CalendarMath.firstHour(every, days: ["2026-11-06"]), 9)
        XCTAssertEqual(CalendarMath.firstHour([], days: ["2026-11-06"]), 8, "an empty day opens on the morning")
        let four = ["a", "b", "c", "d"].enumerated().map { event($1, "2026-11-06", "10:00", color: $0) }
        XCTAssertEqual(CalendarMath.marks(four, on: "2026-11-06"), [0, 1, 2])
        XCTAssertEqual(CalendarMath.marks(four, on: "2026-11-07"), [], "an empty day has no mark")
    }

    func testADSTDayIsPlacedByTheMinuteTafsCloudGivesNotByTheInstant() {
        // 25 October 2026 in Paris: 09:00 is 08:00Z, an hour later in UTC than the day before.
        let before = TafsCloud.CalendarEvent(taf: "rae", name: "Rae", at: "2026-10-24T07:00:00Z", day: "2026-10-24", time: "09:00", minute: 540)
        let after = TafsCloud.CalendarEvent(taf: "rae", name: "Rae", at: "2026-10-25T08:00:00Z", day: "2026-10-25", time: "09:00", minute: 540)
        XCTAssertNotNil(CalendarMath.slots([before, after], on: "2026-10-24")[9])
        XCTAssertNotNil(CalendarMath.slots([before, after], on: "2026-10-25")[9], "the same slot on both days")
    }
}
