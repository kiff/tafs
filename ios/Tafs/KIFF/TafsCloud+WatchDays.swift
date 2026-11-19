import Foundation

extension TafsCloud {
    /// W11, "From your watch" (the owner-approved Watch Coach mockup; tafs cloud's design on kiff-cloud's coordination
    /// thread, 19 Nov 13:28): the days the person asked from their watch, newest first, each with how many requests
    /// (`GET /v1/watch/days`). Only the person's own: a watch day is personal, the account's owner included. Read only.
    struct WatchDay: Decodable, Equatable, Identifiable, Hashable {
        /// `YYYY-MM-DD`, in the account's time zone.
        var date: String
        var count: Int
        var id: String { date }
        init(date: String, count: Int) { self.date = date; self.count = count }
    }

    /// The days, and which is today in the account's time zone (`today`), so a person on another zone than the account's
    /// sees "Today" on the right day.
    struct WatchDayList: Equatable {
        var days: [WatchDay] = []
        var today: String?
    }

    /// One request said from a watch that day, and where it went (`GET /v1/watch/days/{date}`), oldest first.
    struct WatchTurn: Decodable, Equatable, Identifiable, Hashable {
        struct Taf: Decodable, Equatable, Hashable {
            var id: String
            var name: String
        }
        var id: String
        /// What the person said, as the watch sent it: the first message of the request's own conversation.
        var said: String
        /// When, RFC 3339, as tafs cloud wrote it.
        var saidAt: String
        /// The taf that took it, once one did.
        var taf: Taf?
        /// `accepted`, `working`, `done`, `no_taf` or `failed`.
        var state: String
        /// What came of it, checked by tafs cloud; none while it's at work.
        var line: String?
        /// The state in tafs cloud's words, in the request's language.
        var words: String
        /// The request this one replies to, shown under it.
        var replyTo: String?

        enum CodingKeys: String, CodingKey {
            case id, said, taf, state, line, words
            case saidAt = "said_at", replyTo = "reply_to"
        }

        init(id: String, said: String, saidAt: String, taf: Taf? = nil, state: String, line: String? = nil, words: String,
             replyTo: String? = nil) {
            self.id = id; self.said = said; self.saidAt = saidAt; self.taf = taf; self.state = state; self.line = line
            self.words = words; self.replyTo = replyTo
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            said = try c.decode(String.self, forKey: .said)
            saidAt = (try? c.decode(String.self, forKey: .saidAt)) ?? ""
            taf = try? c.decode(Taf.self, forKey: .taf)
            state = (try? c.decode(String.self, forKey: .state)) ?? ""
            line = (try? c.decode(String.self, forKey: .line)).flatMap { $0.isEmpty ? nil : $0 }
            words = (try? c.decode(String.self, forKey: .words)) ?? ""
            replyTo = try? c.decode(String.self, forKey: .replyTo)
        }

        /// What came of it, as the watch shows it: tafs cloud's line when there is one, else its words for the state.
        var outcome: String { line ?? words }
    }

    /// The person's watch days, newest first. A switch that's off (404 `watch_off`) is no days, never an error.
    func watchDays() async throws -> WatchDayList {
        do {
            let data = try await sendData("GET", "/v1/watch/days", body: Optional<[String: String]>.none)
            return Self.watchDays(decoding: data)
        } catch let e as CloudError where e.status == 404 {
            return WatchDayList()
        }
    }

    /// One day's turns, oldest first.
    func watchDay(_ date: String) async throws -> [WatchTurn] {
        let data = try await sendData("GET", "/v1/watch/days/\(escaped(date))", body: Optional<[String: String]>.none)
        return Self.watchTurns(decoding: data)
    }

    /// The days from tafs cloud's answer, and its today; a day this app can't read is left out, never the list.
    static func watchDays(decoding data: Data) -> WatchDayList {
        struct Response: Decodable { let days: [LenientItem<WatchDay>]?; let today: String? }
        let read = try? JSONDecoder().decode(Response.self, from: data)
        return WatchDayList(days: (read?.days ?? []).compactMap(\.value), today: read?.today)
    }

    /// The turns from tafs cloud's answer, oldest first as it sends them; one this app can't read is left out.
    static func watchTurns(decoding data: Data) -> [WatchTurn] {
        struct Response: Decodable { let turns: [LenientItem<WatchTurn>]? }
        return ((try? JSONDecoder().decode(Response.self, from: data))?.turns ?? []).compactMap(\.value)
    }
}
