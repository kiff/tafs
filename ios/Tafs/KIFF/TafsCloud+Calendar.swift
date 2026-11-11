import Foundation

extension TafsCloud {
    /// The tafs' scheduled work still to come on a range of dates (`GET /v1/calendar`, kiff/tafs#534).
    /// tafs cloud computes every occurrence in the account's zone and gives each its day, time and
    /// minute there, so the app places it without converting a zone.
    struct CalendarRead: Decodable, Equatable {
        var timeZone = "UTC"
        var from = ""
        var to = ""
        var events: [CalendarEvent] = []
        /// The dates with an event, so the app marks them.
        var days: [String] = []
        /// tafs cloud's line for the range: "3 to come", "Nothing scheduled in these days", "Halted: nothing runs until it's resumed".
        var words = ""
        /// Some events were left out: more than one read answers.
        var more = false
        /// The account is halted: nothing runs, so nothing is to come.
        var halted = false
        enum CodingKeys: String, CodingKey { case timeZone = "time_zone", from, to, events, days, words, more, halted }
        init(timeZone: String = "UTC", from: String = "", to: String = "", events: [CalendarEvent] = [], days: [String] = [], words: String = "", more: Bool = false, halted: Bool = false) {
            self.timeZone = timeZone; self.from = from; self.to = to; self.events = events; self.days = days; self.words = words; self.more = more; self.halted = halted
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            timeZone = (try? c.decode(String.self, forKey: .timeZone)) ?? "UTC"
            from = (try? c.decode(String.self, forKey: .from)) ?? ""
            to = (try? c.decode(String.self, forKey: .to)) ?? ""
            // An event this app can't read is left out, never the read.
            events = ((try? c.decode([LenientItem<CalendarEvent>].self, forKey: .events)) ?? []).compactMap(\.value)
            days = (try? c.decode([String].self, forKey: .days)) ?? []
            words = (try? c.decode(String.self, forKey: .words)) ?? ""
            more = (try? c.decode(Bool.self, forKey: .more)) ?? false
            halted = (try? c.decode(Bool.self, forKey: .halted)) ?? false
        }
    }

    /// One occurrence: a live schedule's firing, a follow-up the taf set itself, or a run's next try.
    struct CalendarEvent: Decodable, Equatable, Identifiable, Hashable {
        /// The taf it belongs to (tafs cloud's id), its name and its colour.
        var taf: String
        var name: String
        var color: Int
        /// `schedule`, `follow_up` or `retry`.
        var kind: String
        /// The instant, RFC 3339 in UTC, as tafs cloud wrote it.
        var at: String
        /// Its date, time of day and minutes since midnight, in the account's zone.
        var day: String
        var time: String
        var minute: Int
        /// What it is, in tafs cloud's words.
        var words: String
        /// How many firings it stands for: tafs cloud folds a day of a dense schedule into one event.
        var count: Int
        var id: String { "\(taf)|\(kind)|\(at)" }
        enum CodingKeys: String, CodingKey { case taf, name, color, kind, at, day, time, minute, words, count }
        init(taf: String, name: String, color: Int = 0, kind: String = "schedule", at: String, day: String, time: String, minute: Int, words: String = "", count: Int = 1) {
            self.taf = taf; self.name = name; self.color = color; self.kind = kind; self.at = at; self.day = day; self.time = time; self.minute = minute; self.words = words; self.count = count
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            taf = try c.decode(String.self, forKey: .taf)
            at = try c.decode(String.self, forKey: .at)
            day = try c.decode(String.self, forKey: .day)
            time = try c.decode(String.self, forKey: .time)
            minute = try c.decode(Int.self, forKey: .minute)
            name = (try? c.decode(String.self, forKey: .name)) ?? ""
            color = (try? c.decode(Int.self, forKey: .color)) ?? 0
            kind = (try? c.decode(String.self, forKey: .kind)) ?? "schedule"
            words = (try? c.decode(String.self, forKey: .words)) ?? ""
            count = max(1, (try? c.decode(Int.self, forKey: .count)) ?? 1)
        }
    }

    /// The scheduled work to come on the dates `from` to `to` (YYYY-MM-DD, in the account's zone).
    func calendar(from: String, to: String) async throws -> CalendarRead {
        try await send("GET", "/v1/calendar?from=\(from)&to=\(to)")
    }
}

/// One element of a list, decoded on its own: nil when it can't be, so one bad item never fails the list.
struct LenientItem<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

extension LenientItem: Equatable where T: Equatable {}
extension LenientItem: Sendable where T: Sendable {}
