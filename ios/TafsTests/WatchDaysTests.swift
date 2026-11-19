import XCTest
@testable import Tafs

/// W11, "From your watch" (docs/watch-app.md, "W11"), against tafs cloud's design: the person's watch days, newest first,
/// and one day's turns, oldest first. Read only, nothing kept on the phone, and nothing at all while the switch is off.
@MainActor
final class WatchDaysTests: XCTestCase {
    private let daysJSON = #"{"days":[{"date":"2026-11-19","count":3},{"date":"2026-11-18","count":8},{"count":1}]}"#
    private let dayJSON = #"""
    {"date":"2026-11-19","turns":[
     {"id":"wa_1","said":"Move my call to six","said_at":"2026-11-19T08:40:00Z","taf":{"id":"taf-cal","name":"Calendar"},
      "state":"done","line":"Moved your call to 18:00.","words":"Done"},
     {"id":"wa_2","said":"And tell Ana","said_at":"2026-11-19T08:42:10.5Z","state":"working","line":"","words":"Working",
      "reply_to":"wa_1"},
     {"said":"no id: left out"}]}
    """#

    /// The reads, held or answered as told.
    private final class Reading: WatchDaysReading {
        var days: [TafsCloud.WatchDay] = []
        var turns: [TafsCloud.WatchTurn] = []
        var fails = false
        var dayReads: [String] = []
        var held: CheckedContinuation<[TafsCloud.WatchTurn], Error>?
        var holds = false
        func watchDays() async throws -> [TafsCloud.WatchDay] {
            if fails { throw TafsCloud.CloudError(status: 500, message: "down") }
            return days
        }
        func watchDay(_ date: String) async throws -> [TafsCloud.WatchTurn] {
            dayReads.append(date)
            if holds { return try await withCheckedThrowingContinuation { held = $0 } }
            if fails { throw TafsCloud.CloudError(status: 500, message: "down") }
            return turns
        }
    }

    func testTheDaysAndTurnsAreTheDesignsJSON() {
        XCTAssertEqual(TafsCloud.watchDays(decoding: Data(daysJSON.utf8)),
                       [.init(date: "2026-11-19", count: 3), .init(date: "2026-11-18", count: 8)], "one without a date is left out")
        let turns = TafsCloud.watchTurns(decoding: Data(dayJSON.utf8))
        XCTAssertEqual(turns.map(\.id), ["wa_1", "wa_2"], "oldest first, as sent; one without an id is left out")
        XCTAssertEqual(turns[0].taf, .init(id: "taf-cal", name: "Calendar"))
        XCTAssertEqual(turns[0].outcome, "Moved your call to 18:00.", "the taf's line when there is one")
        XCTAssertEqual(turns[1].outcome, "Working", "else tafs cloud's words for the state; an empty line is none")
        XCTAssertEqual(turns[1].replyTo, "wa_1")
        XCTAssertNotNil(WatchTurnRow.time(turns[0].saidAt))
        XCTAssertNotNil(WatchTurnRow.time(turns[1].saidAt), "with fractional seconds too")
        XCTAssertNil(WatchTurnRow.time(""))
    }

    func testWithTheSwitchOffNothingIsRead() async {
        let reading = Reading()
        reading.days = [.init(date: "2026-11-19", count: 1)]
        let model = WatchDaysModel(reading: reading, on: false)
        await model.load()
        XCTAssertTrue(model.days.isEmpty)
    }

    /// A read that fails shows no section, as with the switch off: never an error in Previous conversations.
    func testTheDaysOrNoneWhenTheReadFails() async {
        let reading = Reading()
        reading.days = [.init(date: "2026-11-19", count: 3), .init(date: "2026-11-18", count: 8)]
        let model = WatchDaysModel(reading: reading, on: true)
        await model.load()
        XCTAssertEqual(model.days.map(\.date), ["2026-11-19", "2026-11-18"], "newest first, as tafs cloud sends them")
        reading.fails = true
        await model.load()
        XCTAssertTrue(model.days.isEmpty)
    }

    func testADayOpensWithItsTurnsOrSaysWhy() async {
        let reading = Reading()
        reading.turns = TafsCloud.watchTurns(decoding: Data(dayJSON.utf8))
        let model = WatchDaysModel(reading: reading, on: true)
        await model.open("2026-11-19")
        XCTAssertEqual(model.day, .loaded(reading.turns))
        XCTAssertEqual(reading.dayReads, ["2026-11-19"])
        reading.fails = true
        await model.open("2026-11-18")
        XCTAssertEqual(model.day, .failed(Words.Alert.tafsCouldNotReachKiff))
        model.close()
        XCTAssertEqual(model.day, .reading, "nothing of a day is kept once it's left")
    }

    /// An answer for a day asked before the last, or after the day was left, is never drawn.
    func testALateDayIsNeverDrawn() async {
        let reading = Reading()
        reading.holds = true
        let model = WatchDaysModel(reading: reading, on: true)
        let opening = Task { await model.open("2026-11-18") }
        while reading.held == nil { await Task.yield() }
        model.close()
        reading.held?.resume(returning: [.init(id: "wa_9", said: "Late", saidAt: "", state: "done", words: "Done")])
        await opening.value
        XCTAssertEqual(model.day, .reading)
    }

    func testADaysTitleIsTodayElseItsDate() {
        let zone = TimeZone(identifier: "Europe/Paris")!
        let now = ISO8601DateFormatter().date(from: "2026-11-19T12:00:00Z")!
        XCTAssertEqual(WatchDaysModel.title("2026-11-19", now: now, zone: zone), Words.Calendar.today)
        let yesterday = WatchDaysModel.title("2026-11-18", now: now, zone: zone, locale: Locale(identifier: "en_GB"))
        XCTAssertTrue(yesterday.contains("18") && yesterday.contains("November"), yesterday)
        XCTAssertEqual(WatchDaysModel.title("not a day", now: now, zone: zone), "not a day")
    }

    /// tafs cloud without the routes, or with the watch switch off (404 `watch_off`), is no days, never an error.
    func testNoRoutesIsNoDays() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.responses["GET /v1/watch/days"] = (404, #"{"error":"not found","code":"watch_off"}"#)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "dev_token", session: URLSession(configuration: config))
        let days = try await cloud.watchDays()
        XCTAssertTrue(days.isEmpty)
        StubProtocol.responses["GET /v1/watch/days"] = (200, daysJSON)
        let read = try await cloud.watchDays()
        XCTAssertEqual(read.count, 2)
    }
}
