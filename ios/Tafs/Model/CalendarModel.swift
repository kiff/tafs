import Foundation

/// The calendar's three views (kiff/tafs#534). Week is the one it opens on.
enum CalendarMode: String, CaseIterable, Codable, Sendable {
    case day, week, month
    var title: String {
        switch self {
        case .day: return Words.Calendar.Mode.day
        case .week: return Words.Calendar.Mode.week
        case .month: return Words.Calendar.Mode.month
        }
    }
}

/// What the calendar shows: its view and the selected date (YYYY-MM-DD in the account's zone).
/// Kept on the model, so a rotation or a new layout keeps it.
struct CalendarState: Codable, Equatable, Sendable {
    var mode: CalendarMode = .week
    var selected: String
}

/// Wide (a month navigator, the calendar beside it, an agenda under it) or compact (a week strip,
/// the selected day's events under it).
enum CalendarLayout: Equatable, Sendable {
    case wide, compact

    /// Wide only when there's room for the navigator beside the calendar and the screen lies on
    /// its side with the height of a tablet: an iPad in landscape, a wide tablet. An iPad in
    /// portrait and a phone, either way up, are compact.
    static func of(width: Double, height: Double) -> CalendarLayout {
        width >= 700 && width > height && height >= 600 ? .wide : .compact
    }
}

/// The calendar's dates, all as YYYY-MM-DD in the account's zone, counted on the Gregorian
/// calendar alone: no zone is converted here (tafs cloud gives each event its day and minute),
/// and Android counts them the same way. Weeks start on Monday.
enum CalendarMath {
    private static let utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.firstWeekday = 2
        return c
    }()

    static let monthNames = [Words.Calendar.MonthName.january, Words.Calendar.MonthName.february, Words.Calendar.MonthName.march, Words.Calendar.MonthName.april, Words.Calendar.MonthName.may, Words.Calendar.MonthName.june, Words.Calendar.MonthName.july, Words.Calendar.MonthName.august, Words.Calendar.MonthName.september, Words.Calendar.MonthName.october, Words.Calendar.MonthName.november, Words.Calendar.MonthName.december]
    /// A month named on its own, for the month view's title: in Polish the nominative ("Listopad 2026"),
    /// where `monthNames` is the form that follows a day ("10 listopada").
    static let monthTitles = [Words.Calendar.MonthTitle.january, Words.Calendar.MonthTitle.february, Words.Calendar.MonthTitle.march, Words.Calendar.MonthTitle.april, Words.Calendar.MonthTitle.may, Words.Calendar.MonthTitle.june, Words.Calendar.MonthTitle.july, Words.Calendar.MonthTitle.august, Words.Calendar.MonthTitle.september, Words.Calendar.MonthTitle.october, Words.Calendar.MonthTitle.november, Words.Calendar.MonthTitle.december]
    /// Monday first.
    static let weekdayNames = [Words.Calendar.Weekday.mon, Words.Calendar.Weekday.tue, Words.Calendar.Weekday.wed, Words.Calendar.Weekday.thu, Words.Calendar.Weekday.fri, Words.Calendar.Weekday.sat, Words.Calendar.Weekday.sun]

    private static func parts(_ day: String) -> (y: Int, m: Int, d: Int)? {
        let p = day.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3, (1...12).contains(p[1]), (1...31).contains(p[2]) else { return nil }
        return (p[0], p[1], p[2])
    }

    private static func date(_ day: String) -> Date? {
        guard let p = parts(day) else { return nil }
        return utc.date(from: DateComponents(year: p.y, month: p.m, day: p.d))
    }

    private static func string(_ date: Date) -> String {
        let c = utc.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    /// Today in a zone.
    static func today(in zone: TimeZone, now: Date = Date()) -> String {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        let p = c.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", p.year!, p.month!, p.day!)
    }

    static func plusDays(_ day: String, _ n: Int) -> String {
        guard let d = date(day), let out = utc.date(byAdding: .day, value: n, to: d) else { return day }
        return string(out)
    }

    /// The same day of the month `n` months on, on the month's last day when it has fewer.
    static func plusMonths(_ day: String, _ n: Int) -> String {
        guard let d = date(day), let out = utc.date(byAdding: .month, value: n, to: d) else { return day }
        return string(out)
    }

    /// 0 for Monday to 6 for Sunday.
    static func weekdayIndex(_ day: String) -> Int {
        guard let d = date(day) else { return 0 }
        return (utc.component(.weekday, from: d) + 5) % 7
    }

    /// The Monday of the day's week.
    static func weekStart(_ day: String) -> String { plusDays(day, -weekdayIndex(day)) }

    static func week(_ day: String) -> [String] {
        let start = weekStart(day)
        return (0..<7).map { plusDays(start, $0) }
    }

    static func monthStart(_ day: String) -> String {
        guard let p = parts(day) else { return day }
        return String(format: "%04d-%02d-01", p.y, p.m)
    }

    /// Six weeks from the Monday on or before the month's first day: every week with a day of the month.
    static func monthGrid(_ day: String) -> [String] {
        let start = weekStart(monthStart(day))
        return (0..<42).map { plusDays(start, $0) }
    }

    static func sameMonth(_ a: String, _ b: String) -> Bool { a.prefix(7) == b.prefix(7) }

    static func dayNumber(_ day: String) -> Int { parts(day)?.d ?? 0 }

    /// The dates one read covers: the selected month's grid, which holds its day and its week too, so
    /// one read draws every view and the navigator's marks.
    static func range(_ selected: String) -> (from: String, to: String) {
        let grid = monthGrid(selected)
        return (grid.first!, grid.last!)
    }

    /// The read, when it answers the selected date's range; nil while that range is still being read, so a
    /// view never says "Nothing scheduled" from another month's read or before the first.
    static func current(_ read: TafsCloud.CalendarRead?, selected: String) -> TafsCloud.CalendarRead? {
        let r = range(selected)
        guard let read, read.from == r.from, read.to == r.to else { return nil }
        return read
    }

    /// The dates a view shows.
    static func shown(_ state: CalendarState) -> [String] {
        switch state.mode {
        case .day: return [state.selected]
        case .week: return week(state.selected)
        case .month: return monthGrid(state.selected).filter { sameMonth($0, state.selected) }
        }
    }

    /// The days the compact layout's strip draws, whatever its width: in Week, exactly the seven
    /// its heading names; in Day, the month's weeks, which scroll. Month draws no strip.
    static func stripDays(_ state: CalendarState) -> [String] {
        state.mode == .week ? week(state.selected) : monthGrid(state.selected)
    }

    /// The previous or next day, week or month.
    static func step(_ state: CalendarState, by n: Int) -> CalendarState {
        var s = state
        switch state.mode {
        case .day: s.selected = plusDays(state.selected, n)
        case .week: s.selected = plusDays(state.selected, 7 * n)
        case .month: s.selected = plusMonths(state.selected, n)
        }
        return s
    }

    /// The view's title, in the order the app's language writes a date: "Thu 5 November 2026",
    /// "2 – 8 November 2026", "November 2026" (German "Do., 5. November 2026", Spanish "noviembre de 2026").
    static func title(_ state: CalendarState) -> String {
        guard let p = parts(state.selected) else { return state.selected }
        switch state.mode {
        case .day:
            return Words.Calendar.Title.day(weekday: weekdayNames[weekdayIndex(state.selected)], day: "\(p.d)", month: monthNames[p.m - 1], year: "\(p.y)")
        case .week:
            let days = week(state.selected)
            guard let a = parts(days[0]), let b = parts(days[6]) else { return state.selected }
            if a.y != b.y {
                return Words.Calendar.Title.weekAcrossYears(fromDay: "\(a.d)", fromMonth: monthNames[a.m - 1], fromYear: "\(a.y)",
                                                            toDay: "\(b.d)", toMonth: monthNames[b.m - 1], toYear: "\(b.y)")
            }
            if a.m != b.m {
                return Words.Calendar.Title.weekAcrossMonths(fromDay: "\(a.d)", fromMonth: monthNames[a.m - 1], toDay: "\(b.d)", toMonth: monthNames[b.m - 1], year: "\(b.y)")
            }
            return Words.Calendar.Title.week(fromDay: "\(a.d)", toDay: "\(b.d)", month: monthNames[b.m - 1], year: "\(b.y)")
        case .month:
            return Words.Calendar.Title.month(month: monthTitles[p.m - 1], year: "\(p.y)")
        }
    }

    /// A day as a heading in the agenda: "Thu 5 November".
    static func dayHeading(_ day: String) -> String {
        guard let p = parts(day) else { return day }
        return Words.Calendar.dayHeading(weekday: weekdayNames[weekdayIndex(day)], day: "\(p.d)", month: monthNames[p.m - 1])
    }

    /// The events of a day, in time order.
    static func events(_ events: [TafsCloud.CalendarEvent], on day: String) -> [TafsCloud.CalendarEvent] {
        events.filter { $0.day == day }.sorted { ($0.minute, $0.name, $0.at) < ($1.minute, $1.name, $1.at) }
    }

    /// One day of the agenda: its date and its events in time order.
    struct AgendaDay: Identifiable, Equatable {
        var day: String
        var events: [TafsCloud.CalendarEvent]
        var id: String { day }
    }

    /// The agenda: the events on the given dates, by day, each day's in time order. Days with none are left out.
    static func agenda(_ events: [TafsCloud.CalendarEvent], days: [String]) -> [AgendaDay] {
        days.compactMap { day in
            let on = self.events(events, on: day)
            return on.isEmpty ? nil : AgendaDay(day: day, events: on)
        }
    }

    /// One hour's slot of a day: each taf with work in it once, with how many times, in order of its first.
    struct Slot: Equatable, Sendable {
        var taf: String
        var name: String
        var color: Int
        var count: Int
        var first: String
    }

    /// A day's work by hour (0 to 23), for the Day and Week views' time slots.
    static func slots(_ events: [TafsCloud.CalendarEvent], on day: String) -> [Int: [Slot]] {
        var out: [Int: [Slot]] = [:]
        for e in self.events(events, on: day) {
            let hour = min(max(e.minute / 60, 0), 23)
            var row = out[hour] ?? []
            if let i = row.firstIndex(where: { $0.taf == e.taf }) { row[i].count += e.count } else {
                row.append(Slot(taf: e.taf, name: e.name, color: e.color, count: e.count, first: e.time))
            }
            out[hour] = row
        }
        return out
    }

    /// The first hour with work among the dates, to scroll a time view to; 8 when there's none.
    static func firstHour(_ events: [TafsCloud.CalendarEvent], days: [String]) -> Int {
        let set = Set(days)
        return events.filter { set.contains($0.day) }.map { min(max($0.minute / 60, 0), 23) }.min() ?? 8
    }

    /// How many firings a day has, a folded event counting all it stands for.
    static func count(_ events: [TafsCloud.CalendarEvent], on day: String) -> Int {
        events.filter { $0.day == day }.reduce(0) { $0 + $1.count }
    }

    /// The colours of the tafs with work on a day, at most three, for the day's marks.
    static func marks(_ events: [TafsCloud.CalendarEvent], on day: String) -> [Int] {
        var seen: [String] = [], colors: [Int] = []
        for e in self.events(events, on: day) where !seen.contains(e.taf) {
            seen.append(e.taf); colors.append(e.color)
            if colors.count == 3 { break }
        }
        return colors
    }
}
