import Foundation

/// W11, "From your watch" on the iPhone and iPad (docs/watch-app.md, "W11"): off until tafs cloud's watch-day routes are
/// live and the owner says yes.
enum WatchDaysFeature {
    static let on = false
}

/// tafs cloud's watch-day reads (`GET /v1/watch/days`, `GET /v1/watch/days/{date}`): the person's own, read only.
@MainActor
protocol WatchDaysReading {
    func watchDays() async throws -> TafsCloud.WatchDayList
    func watchDay(_ date: String) async throws -> [TafsCloud.WatchTurn]
}

extension TafsCloud: WatchDaysReading {}

/// The watch's conversations in Coach, one a day: which days there are, and one day's turns. Nothing is kept on the
/// phone: each is read when shown, and an answer for a day asked before the last is never drawn.
@MainActor
final class WatchDaysModel: ObservableObject {
    enum DayState: Equatable {
        case reading
        case loaded([TafsCloud.WatchTurn])
        case failed(String)
    }

    /// The days, newest first; none while the switch is off, or when tafs cloud has none (or doesn't serve them yet).
    @Published private(set) var days: [TafsCloud.WatchDay] = []
    /// Today in the account's time zone, as tafs cloud says it; nil from one that doesn't, and then the phone's day.
    @Published private(set) var today: String?
    @Published private(set) var day: DayState = .reading
    private let reading: WatchDaysReading
    private let on: Bool
    private var asked = 0

    init(reading: WatchDaysReading, on: Bool = WatchDaysFeature.on) {
        self.reading = reading
        self.on = on
    }

    /// The days. A read that fails shows none: the section is simply not there, as with the switch off.
    func load() async {
        guard on else { days = []; today = nil; return }
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
            // The conversation couldn't be loaded, with the error's own words (dev-android's read).
            day = .failed(Words.Coach.conversationNotLoaded(why: error.localizedDescription))
        }
    }

    /// The day left the screen: nothing of it is kept.
    func close() {
        asked += 1
        day = .reading
    }

    /// A day's title: Today for the account's today when tafs cloud gives it (else the phone's), else its date in the
    /// person's language.
    func title(_ date: String) -> String { Self.title(date, today: today) }

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
