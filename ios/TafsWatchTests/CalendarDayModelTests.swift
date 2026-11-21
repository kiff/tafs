import XCTest
@testable import TafsWatch

/// v2's calendar day (docs/watch-app.md, "Step (vi)"): today first, then the day before or after the one shown; Today only
/// for the day the phone answered as today; a late answer never drawn; forgotten on Close.
@MainActor
final class CalendarDayModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// The phone: each calendar day by the day asked ("" for today, which it answers as 2026-11-19).
    private final class Phone: PhoneAsking {
        var asked: [WatchRequest] = []
        var reply: ((String?) -> WatchReply?)
        init(_ reply: @escaping (String?) -> WatchReply?) { self.reply = reply }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return reply(request.day)
        }
    }

    /// A phone that holds its reply until told.
    private final class HeldPhone: PhoneAsking {
        var held: CheckedContinuation<WatchReply?, Never>?
        func ask(_ request: WatchRequest) async -> WatchReply? {
            await withCheckedContinuation { held = $0 }
        }
        func answer(_ reply: WatchReply?) {
            held?.resume(returning: reply)
            held = nil
        }
    }

    nonisolated private static func day(_ day: String, halted: Bool = false) -> WatchCalendarDay {
        WatchCalendarDay(day: day, words: "2 runs", events: [.init(time: "09:00", taf: "Rae", words: "Refunds", count: 3)],
                         halted: halted)
    }

    private func phone() -> Phone {
        Phone { day in WatchReply(.ok, calendar: Self.day(day ?? "2026-11-19")) }
    }

    func testTodayFirstThenTheDaysAround() async {
        let phone = phone()
        let model = CalendarDayModel(phone: phone, clock: { self.now })
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.calendar, made: now)])
        XCTAssertEqual(model.shown?.day, "2026-11-19")
        XCTAssertEqual(model.title(), Words.Calendar.today)
        await model.previous()
        XCTAssertEqual(phone.asked.last?.day, "2026-11-18")
        XCTAssertNotEqual(model.title(locale: Locale(identifier: "en")), Words.Calendar.today, "another day: its date")
        await model.next()
        await model.next()
        XCTAssertEqual(phone.asked.map(\.day), [nil, "2026-11-18", "2026-11-19", "2026-11-20"])
        await model.previous()
        XCTAssertEqual(model.title(), Words.Calendar.today, "back on the phone's today")
    }

    func testDaysCrossMonthsAndYears() {
        XCTAssertEqual(CalendarDayModel.day("2026-03-01", plus: -1), "2026-02-28")
        XCTAssertEqual(CalendarDayModel.day("2028-02-28", plus: 1), "2028-02-29")
        XCTAssertEqual(CalendarDayModel.day("2026-12-31", plus: 1), "2027-01-01")
        XCTAssertNil(CalendarDayModel.day("tomorrow", plus: 1))
    }

    /// An answer for a day asked before the last, or after Close, is never drawn.
    func testALateAnswerIsNeverDrawn() async {
        let phone = HeldPhone()
        let model = CalendarDayModel(phone: phone, clock: { self.now })
        let reading = Task { await model.load() }
        while phone.held == nil { await Task.yield() }
        model.close()
        phone.answer(WatchReply(.ok, calendar: Self.day("2026-11-19")))
        await reading.value
        XCTAssertEqual(model.state, .reading)
        XCTAssertNil(model.shown)
    }

    func testEachWayThePhoneCantAnswer() async {
        let cases: [(WatchReply?, CalendarDayModel.State)] = [
            (nil, .unreachable(.notReachable)),
            (WatchReply(.signedOut), .signedOut),
            (WatchReply(.notYet), .updatePhone),
            (WatchReply(.failed, line: "tafs couldn't read it."), .failed("tafs couldn't read it.")),
            (WatchReply(.ok), .failed(Words.Alert.tafsCouldNotReachKiff)),
        ]
        for (reply, state) in cases {
            let model = CalendarDayModel(phone: Phone { _ in reply }, clock: { self.now })
            await model.load()
            XCTAssertEqual(model.state, state)
        }
    }

    /// Close forgets the day and which day was today.
    func testCloseForgets() async {
        let model = CalendarDayModel(phone: phone(), clock: { self.now })
        await model.load()
        model.close()
        XCTAssertNil(model.shown)
        XCTAssertNil(model.today)
        XCTAssertEqual(model.title(), Words.Watch.calendarToday)
    }
}
