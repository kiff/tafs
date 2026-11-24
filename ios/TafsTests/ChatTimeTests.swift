import XCTest
@testable import Tafs

/// When each chat message was sent (row 44, C), as Android's `ChatTimeTest`: 12/24 h, Today, Yesterday, an older
/// date with and without its year, and where a header goes.
final class ChatTimeTests: XCTestCase {
    private var paris: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Paris")!
        return c
    }

    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int) -> Date {
        paris.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }

    func testTheTimeFollowsTheDevicesClock() {
        let at = date(2026, 11, 20, 14, 5)
        let en = Locale(identifier: "en")
        XCTAssertEqual(ChatTime.time(at, zone: paris.timeZone, language: en, twentyFour: true), "14:05")
        XCTAssertTrue(ChatTime.time(at, zone: paris.timeZone, language: en, twentyFour: false).hasPrefix("2:05"))
        XCTAssertEqual(ChatTime.time(at, zone: paris.timeZone, language: Locale(identifier: "de"), twentyFour: true), "14:05")
    }

    func testTodayYesterdayThenTheDateWithItsYearOnlyWhenOther() {
        let now = date(2026, 11, 20, 23, 0)
        XCTAssertEqual(ChatTime.day(date(2026, 11, 20, 0, 5), now: now, calendar: paris), Words.Calendar.today)
        XCTAssertEqual(ChatTime.day(date(2026, 11, 19, 23, 55), now: now, calendar: paris), Words.Chat.Day.yesterday)
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(ChatTime.day(date(2026, 11, 18, 9, 0), now: now, calendar: paris, language: en), "Wed, Nov 18")
        XCTAssertEqual(ChatTime.day(date(2025, 11, 18, 9, 0), now: now, calendar: paris, language: en), "Tue, Nov 18, 2025")
        XCTAssertFalse(ChatTime.day(date(2026, 11, 18, 9, 0), now: now, calendar: paris, language: Locale(identifier: "de")).isEmpty)
    }

    /// Five messages over three days: three headers, at the first message of each day, in the account's zone.
    func testAHeaderWhereEachDayStarts() {
        let dates = [date(2026, 11, 18, 9, 0), date(2026, 11, 18, 23, 59), date(2026, 11, 19, 0, 1),
                     date(2026, 11, 19, 8, 0), date(2026, 11, 20, 7, 0)]
        let starts = dates.indices.filter { ChatTime.startsDay(dates, at: $0, calendar: paris) }
        XCTAssertEqual(starts, [0, 2, 4])
    }

    /// The wrist reads the account's zone, as the phone and Wear OS do, not its own. A message at
    /// 23:30 in Paris is a new day there while it's still the same day in New York, and both its time and its day
    /// header follow the calendar given.
    func testTimesAndDaysFollowTheAccountsZoneNotTheDevices() {
        var newYork = Calendar(identifier: .gregorian)
        newYork.timeZone = TimeZone(identifier: "America/New_York")!
        let late = date(2026, 11, 19, 23, 30), early = date(2026, 11, 20, 0, 30)
        XCTAssertEqual(ChatTime.time(late, zone: paris.timeZone, language: Locale(identifier: "en"), twentyFour: true), "23:30")
        XCTAssertEqual(ChatTime.time(late, zone: newYork.timeZone, language: Locale(identifier: "en"), twentyFour: true), "17:30")
        XCTAssertTrue(ChatTime.startsDay([late, early], at: 1, calendar: paris), "past midnight in Paris")
        XCTAssertFalse(ChatTime.startsDay([late, early], at: 1, calendar: newYork), "the same evening in New York")
        let reply = WatchReply(.ok, chat: [], zone: "Europe/Paris")
        let read = try! JSONDecoder().decode(WatchReply.self, from: JSONEncoder().encode(reply))
        XCTAssertEqual(read.zone, "Europe/Paris", "the phone sends the account's zone with a taf's chat")
    }

    /// An ended request carries when its answer came (tafs cloud's `updated`), and the
    /// answer the watch gets keeps it, so the wrist shows it under the answer as Wear OS does.
    func testAnAnswerCarriesWhenItCame() throws {
        let json = #"{"id":"a1","state":"done","words":"Done","updated":"2026-11-20T13:05:00.123Z"}"#
        let asked = try JSONDecoder().decode(TafsCloud.WatchAsk.self, from: Data(json.utf8))
        XCTAssertEqual(asked.updated, TafsCloud.WatchAsk.instant("2026-11-20T13:05:00.123Z"))
        XCTAssertNotNil(asked.updated)
        let reply = KeptWatchReading.reply(asked)
        XCTAssertEqual(reply.coach?.at, asked.updated)
        XCTAssertNil(TafsCloud.WatchAsk.instant("not a time"))
    }
}
