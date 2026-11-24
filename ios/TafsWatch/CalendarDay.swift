import SwiftUI

/// v2's calendar day (docs/watch-app.md, "Step (vi)"), as Wear OS's: the phone's `calendar` read, today in the account's zone
/// first, then the day before or after the one shown. In memory only, and forgotten on Close.
@MainActor
final class CalendarDayModel: ObservableObject {
    enum State: Equatable {
        case reading
        case loaded(WatchCalendarDay)
        case unreachable(PhoneFailure)
        case signedOut
        /// A phone from before the read: "Update tafs on your phone".
        case updatePhone
        case failed(String)
    }

    @Published private(set) var state: State = .reading
    /// The day the phone answered as today: the title says Today only for it.
    private(set) var today: String?
    private let phone: PhoneAsking
    private let clock: () -> Date
    /// Counts the reads asked: an answer for one asked before the last, or before Close, is never drawn.
    private var asked = 0

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init) {
        self.phone = phone
        self.clock = clock
    }

    /// The day shown, once read.
    var shown: WatchCalendarDay? {
        if case .loaded(let day) = state { return day }
        return nil
    }

    /// Reads a day, `YYYY-MM-DD`, or with none today in the account's zone.
    func load(_ day: String? = nil) async {
        asked += 1
        let this = asked
        state = .reading
        let reached = await phone.reach(WatchRequest(.calendar, made: clock(), day: day))
        guard this == asked else { return }
        let reply: WatchReply
        switch reached {
        case .reply(let answered): reply = answered
        case .failed(let why): state = .unreachable(why); return
        }
        switch reply.outcome {
        case .ok:
            guard let read = reply.calendar else { state = .failed(reply.line ?? Words.Watch.phoneCouldNotReachCloud); return }
            if day == nil { today = read.day }
            state = .loaded(read)
        case .signedOut:
            state = .signedOut
        case .failed:
            state = .failed(reply.line ?? Words.Watch.phoneCouldNotReachCloud)
        case .notYet:
            state = .updatePhone
        case .refused, .stale, .answered:
            state = .unreachable(.notReachable)
        }
    }

    /// Previous day: the day before the one shown.
    func previous() async {
        guard let day = shown?.day, let before = Self.day(day, plus: -1) else { return }
        await load(before)
    }

    /// Next day: the day after the one shown.
    func next() async {
        guard let day = shown?.day, let after = Self.day(day, plus: 1) else { return }
        await load(after)
    }

    /// Close: forgotten, and an answer still on its way is never drawn.
    func close() {
        asked += 1
        state = .reading
        today = nil
    }

    /// The page's title: Today for the day the phone answered as today, else the date in the watch's language.
    func title(locale: Locale = .current) -> String {
        guard let day = shown?.day else { return Words.Watch.calendarToday }
        if day == today { return Words.Calendar.today }
        guard let date = Self.days.date(from: day) else { return day }
        return date.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, locale: locale, timeZone: Self.utc))
    }

    private static let utc = TimeZone(identifier: "UTC")!

    /// `YYYY-MM-DD`, read and written as a day, with no time zone of its own.
    private static let days: ISO8601DateFormatter = {
        let days = ISO8601DateFormatter()
        days.formatOptions = [.withFullDate]
        days.timeZone = utc
        return days
    }()

    /// The day `plus` days from `day`, both `YYYY-MM-DD`.
    static func day(_ day: String, plus: Int) -> String? {
        guard WatchRequest.isDay(day), let date = days.date(from: day) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(byAdding: .day, value: plus, to: date).map { days.string(from: $0) }
    }
}

extension TafsModel {
    /// The calendar day, read through the same phone as Your tafs.
    func calendarDay() -> CalendarDayModel {
        CalendarDayModel(phone: phone, clock: clock)
    }
}

/// Today's calendar, from the foot of Your tafs: tafs cloud's line for the day (in red when the account is halted), each event
/// with its time, its taf, its words and "×3" when it happens more than once; Previous day and Next day.
struct CalendarDayScreen: View {
    @StateObject private var model: CalendarDayModel

    init(model: @autoclosure @escaping () -> CalendarDayModel) {
        _model = StateObject(wrappedValue: model())
    }

    var body: some View {
        content
            .navigationTitle(model.title())
            .task { if model.shown == nil { await model.load() } }
            .onDisappear { model.close() }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .reading:
            List {
                ProgressView()
                Text(Words.Calendar.reading).font(.footnote).foregroundStyle(.secondary)
            }
        case .loaded(let day):
            List {
                Text(day.words)
                    .font(.footnote)
                    .foregroundStyle(day.halted ? WatchLook.danger : Color.primary)
                ForEach(Array(day.events.enumerated()), id: \.offset) { _, event in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.time).font(.headline)
                        Text(event.taf).font(.footnote.weight(.semibold))
                        Text(event.words).font(.footnote)
                        if event.count > 1 {
                            Text(Words.Calendar.times(count: event.count)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                Button(Words.Calendar.previousDay) { Task { await model.previous() } }
                Button(Words.Calendar.nextDay) { Task { await model.next() } }
            }
        case .unreachable(let why):
            WatchNotice(line: why.line) { await model.load() }
        case .signedOut:
            WatchNotice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .updatePhone:
            WatchNotice(line: Words.Watch.updatePhone) { await model.load() }
        case .failed(let why):
            WatchNotice(line: why) { await model.load() }
        }
    }
}
