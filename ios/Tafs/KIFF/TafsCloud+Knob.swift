import Foundation

/// A taf's dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`, the knob API): the job in
/// the face, and Wakes it, When and Results go around it. Every label, line and words is
/// tafs cloud's, in the account's language and zone; the app draws them and composes none.
extension TafsCloud {
    struct Knob: Decodable, Equatable {
        struct Does: Decodable, Equatable {
            var label = ""
            var line = ""
        }

        /// One stop: a trigger on Wakes it, a rule on When, a place on Results go.
        struct Item: Decodable, Equatable, Identifiable {
            let id: String
            /// Wakes it: run_now, door, schedule, vendor_event, app_check, follow_up,
            /// before_event. When: arrivals, schedule, missed, busy.
            var kind: String?
            /// Results go: door_reply, app, owner_email, slack, notion, gmail, teams.
            var place: String?
            var label: String
            /// What this stop means for this taf, in one sentence, under the dial.
            var words: String
            /// Whether it's on; a stop that doesn't say is on.
            var on: Bool
            /// The face's big word when it's chosen ("Open"), when tafs cloud sends one.
            var state: String?
            /// What tapping the face does ("Tap to close"); none, the face does nothing.
            var tap: String?
            /// A follow-up's end.
            var expires: String?
            /// A door's own id: the face opens or closes it through the doors API.
            var door: String?

            enum CodingKeys: String, CodingKey { case id, kind, place, label, words, on, state, tap, expires, door }
            init(id: String, kind: String? = nil, place: String? = nil, label: String, words: String, on: Bool = true,
                 state: String? = nil, tap: String? = nil, expires: String? = nil, door: String? = nil) {
                self.id = id; self.kind = kind; self.place = place; self.label = label; self.words = words
                self.on = on; self.state = state; self.tap = tap; self.expires = expires; self.door = door
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                kind = try c.decodeIfPresent(String.self, forKey: .kind)
                place = try c.decodeIfPresent(String.self, forKey: .place)
                label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
                words = try c.decodeIfPresent(String.self, forKey: .words) ?? ""
                on = try c.decodeIfPresent(Bool.self, forKey: .on) ?? true
                state = try c.decodeIfPresent(String.self, forKey: .state)
                tap = try c.decodeIfPresent(String.self, forKey: .tap)
                expires = try c.decodeIfPresent(String.self, forKey: .expires)
                door = try c.decodeIfPresent(String.self, forKey: .door)
            }
        }

        /// Today's work in the account's zone, and tafs cloud's line for it.
        struct Rim: Decodable, Equatable {
            var done = 0
            var working = 0
            var retrying = 0
            var needsYou = 0
            var line: String?
            enum CodingKeys: String, CodingKey { case done, working, retrying, needsYou = "needs_you", line }
            init(done: Int = 0, working: Int = 0, retrying: Int = 0, needsYou: Int = 0, line: String? = nil) {
                self.done = done; self.working = working; self.retrying = retrying; self.needsYou = needsYou; self.line = line
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                done = try c.decodeIfPresent(Int.self, forKey: .done) ?? 0
                working = try c.decodeIfPresent(Int.self, forKey: .working) ?? 0
                retrying = try c.decodeIfPresent(Int.self, forKey: .retrying) ?? 0
                needsYou = try c.decodeIfPresent(Int.self, forKey: .needsYou) ?? 0
                line = try c.decodeIfPresent(String.self, forKey: .line)
            }
            var total: Int { done + working + retrying + needsYou }
        }

        /// A change waiting for the owner's confirmation.
        struct Draft: Decodable, Equatable {
            let version: Int
            var words: [String] = []
        }

        var version: Int?
        var does = Does()
        var wakes: [Item] = []
        var when: [Item] = []
        var results: [Item] = []
        var rim = Rim()
        var draft: Draft?

        enum CodingKeys: String, CodingKey { case version, does, wakes, when, results, rim, draft }
        init(version: Int? = nil, does: Does = Does(), wakes: [Item] = [], when: [Item] = [], results: [Item] = [], rim: Rim = Rim(), draft: Draft? = nil) {
            self.version = version; self.does = does; self.wakes = wakes; self.when = when; self.results = results; self.rim = rim; self.draft = draft
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            version = try c.decodeIfPresent(Int.self, forKey: .version)
            does = try c.decodeIfPresent(Does.self, forKey: .does) ?? Does()
            wakes = try c.decodeIfPresent([Item].self, forKey: .wakes) ?? []
            when = try c.decodeIfPresent([Item].self, forKey: .when) ?? []
            results = try c.decodeIfPresent([Item].self, forKey: .results) ?? []
            rim = try c.decodeIfPresent(Rim.self, forKey: .rim) ?? Rim()
            draft = try c.decodeIfPresent(Draft.self, forKey: .draft)
        }

        /// The rings, inside to out.
        enum Ring: Int, CaseIterable {
            case wakes, when, results
            /// The dial's own words, the same on iPhone, iPad and Android.
            var title: String {
                switch self {
                case .wakes: return "Wakes it"
                case .when: return "When"
                case .results: return "Results go"
                }
            }
        }

        func items(_ ring: Ring) -> [Item] {
            switch ring {
            case .wakes: return wakes
            case .when: return when
            case .results: return results
            }
        }
    }

    /// A taf's dial.
    func knob(_ id: String) async throws -> Knob {
        try await send("GET", "/v1/tafs/\(escaped(id))/knob")
    }

    /// Connect apps (`GET /v1/connect/apps`): the catalog first, then KIFF's own apps for
    /// Google, Microsoft and Slack.
    struct ConnectApps: Decodable, Equatable {
        struct App: Decodable, Equatable, Identifiable {
            let entry: String
            var vendor = ""
            var title = ""
            var icon: String?
            /// oauth or key.
            var how = "oauth"
            /// connected, available or needs_reconnect.
            var state = "available"
            var grant: String?
            var id: String { entry }
            var connected: Bool { state == "connected" }
            var needsReconnect: Bool { state == "needs_reconnect" }

            enum CodingKeys: String, CodingKey { case entry, vendor, title, icon, how, state, grant }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                entry = try c.decode(String.self, forKey: .entry)
                vendor = try c.decodeIfPresent(String.self, forKey: .vendor) ?? ""
                title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
                icon = try c.decodeIfPresent(String.self, forKey: .icon)
                how = try c.decodeIfPresent(String.self, forKey: .how) ?? "oauth"
                state = try c.decodeIfPresent(String.self, forKey: .state) ?? "available"
                grant = try c.decodeIfPresent(String.self, forKey: .grant)
            }
        }
        var apps: [App] = []
        /// Coach can draft a vendor that isn't listed.
        var byKey = false
        enum CodingKeys: String, CodingKey { case apps, byKey = "by_key" }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            apps = try c.decodeIfPresent([App].self, forKey: .apps) ?? []
            byKey = try c.decodeIfPresent(Bool.self, forKey: .byKey) ?? false
        }
    }

    /// Connect apps, filtered by `q` when given.
    func connectApps(_ q: String? = nil) async throws -> ConnectApps {
        let query = q.map { $0.trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : "?q=\(escaped($0))" } ?? ""
        return try await send("GET", "/v1/connect/apps" + query)
    }
}
