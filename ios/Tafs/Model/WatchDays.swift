import Foundation

/// W11, "From your watch" on the iPhone and iPad (docs/watch-app.md, "W11"): on for everyone since tafs cloud's watch-day
/// routes went live (tafs#1463); its app switch went with row 35. A tafs cloud whose own switch is off answers 404, and the
/// section isn't drawn.

/// tafs cloud's watch-day reads (`GET /v1/watch/days`, `GET /v1/watch/days/{date}`): the person's own, read only.
@MainActor
protocol WatchDaysReading {
    func watchDays() async throws -> TafsCloud.WatchDayList
    func watchDay(_ date: String) async throws -> [TafsCloud.WatchTurn]
    /// Read more: a request's whole answer, read on the tap only; nil when tafs cloud has none to give.
    func watchAskWhole(_ id: String) async throws -> String?
}

extension WatchDaysReading {
    /// A reader from before Read more: nothing.
    func watchAskWhole(_ id: String) async throws -> String? { nil }
}

extension TafsCloud: WatchDaysReading {
    func watchAskWhole(_ id: String) async throws -> String? { try await watchAsk(id, full: true).text }
}

/// The watch's conversations in Coach, one a day: which days there are, and one day's turns. Nothing is kept on the
/// phone: each is read when shown, and an answer for a day asked before the last is never drawn.
@MainActor
final class WatchDaysModel: ObservableObject {
    enum DayState: Equatable {
        case reading
        case loaded([TafsCloud.WatchTurn])
        case failed(String)
    }

    /// The days, newest first; none when tafs cloud has none (or doesn't serve them, or its switch is off).
    @Published private(set) var days: [TafsCloud.WatchDay] = []
    /// Today in the account's time zone, as tafs cloud says it; nil from one that doesn't, and then the phone's day.
    @Published private(set) var today: String?
    @Published private(set) var day: DayState = .reading
    /// Read more on a turn (the owner's call 8, as on the watch): its whole answer, read on the tap and held only while the day
    /// is open.
    enum Whole: Equatable {
        case reading
        case read(String)
        /// Not read: Read more stays, to tap again.
        case failed
    }
    @Published private(set) var whole: [String: Whole] = [:]
    private let reading: WatchDaysReading
    private var asked = 0
    /// The account's time zone (the phone's when it has none): the days are the account's, so a turn's time is too, as
    /// Android shows it.
    let zone: TimeZone

    init(reading: WatchDaysReading, zone: TimeZone = .current) {
        self.reading = reading
        self.zone = zone
    }

    /// The days. A read that fails shows none: the section is simply not there.
    func load() async {
        let read = (try? await reading.watchDays()) ?? TafsCloud.WatchDayList()
        days = read.days
        today = read.today
    }

    /// One day's turns, oldest first.
    func open(_ date: String) async {
        asked += 1
        let this = asked
        day = .reading
        do {
            let turns = try await reading.watchDay(date)
            guard this == asked else { return }
            day = .loaded(turns)
        } catch {
            guard this == asked else { return }
            // The conversation couldn't be loaded, with the error's own words.
            day = .failed(Words.Coach.conversationNotLoaded(why: error.localizedDescription))
        }
    }

    /// The day left the screen: nothing of it is kept.
    func close() {
        asked += 1
        day = .reading
        whole = [:]
    }

    /// Read more: the turn's whole answer, read once on the tap.
    func readMore(_ id: String) async {
        switch whole[id] {
        case .reading?, .read?: return
        default: break
        }
        whole[id] = .reading
        let text = (try? await reading.watchAskWhole(id)) ?? nil
        guard whole[id] == .reading else { return }
        if let text, !text.isEmpty { whole[id] = .read(text) } else { whole[id] = .failed }
    }

    /// A day's title: Today for the account's today when tafs cloud gives it (else the phone's), else its date in the
    /// person's language.
    func title(_ date: String) -> String { Self.title(date, today: today, zone: zone) }

    static func title(_ date: String, today: String? = nil, now: Date = Date(), zone: TimeZone = .current,
                      locale: Locale = .current) -> String {
        let parse = DateFormatter()
        parse.calendar = Calendar(identifier: .gregorian)
        parse.locale = Locale(identifier: "en_US_POSIX")
        parse.timeZone = zone
        parse.dateFormat = "yyyy-MM-dd"
        guard let day = parse.date(from: date) else { return date }
        if date == (today ?? parse.string(from: now)) { return Words.Calendar.today }
        let shown = DateFormatter()
        shown.locale = locale
        shown.timeZone = zone
        shown.setLocalizedDateFormatFromTemplate("EEEEdMMMM")
        return shown.string(from: day)
    }
}
