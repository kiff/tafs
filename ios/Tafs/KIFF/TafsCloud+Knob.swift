import Foundation

/// A taf's dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`, the knob API): the job in
/// the face, and Wakes it, When and Results go around it. Every label, line and words is
/// tafs cloud's, in the account's language and zone; the app draws them and composes none.
extension TafsCloud {
    struct Knob: Decodable, Equatable {
        struct Does: Decodable, Equatable {
            var label = ""
            var line = ""
            enum CodingKeys: String, CodingKey { case label, line }
            init(label: String = "", line: String = "") { self.label = label; self.line = line }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
                line = try c.decodeIfPresent(String.self, forKey: .line) ?? ""
            }
        }

        /// One stop: a trigger on Wakes it, a rule on When, a place on Results go.
        struct Item: Decodable, Equatable, Identifiable, Sendable {
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
            /// A trigger's form, exactly as `PUT …/knob` takes it; none on Run now, nor on a
            /// schedule the dial can't set.
            var trigger: JSONValue?

            enum CodingKeys: String, CodingKey { case id, kind, place, label, words, on, state, tap, expires, door, trigger }
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
                trigger = try c.decodeIfPresent(JSONValue.self, forKey: .trigger)
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
        /// The newest dial version made after the live one, in tafs cloud's words: by ring,
        /// for Coach's dial card; `words` all together, from an older tafs cloud.
        struct Draft: Decodable, Equatable {
            let version: Int
            var words: [String] = []
            var wakes: [String] = []
            var when: [String] = []
            var results: [String] = []
            enum CodingKeys: String, CodingKey { case version, words, wakes, when, results }
            init(version: Int, words: [String] = [], wakes: [String] = [], when: [String] = [], results: [String] = []) {
                self.version = version; self.words = words; self.wakes = wakes; self.when = when; self.results = results
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                version = try c.decode(Int.self, forKey: .version)
                words = try c.decodeIfPresent([String].self, forKey: .words) ?? []
                wakes = try c.decodeIfPresent([String].self, forKey: .wakes) ?? []
                when = try c.decodeIfPresent([String].self, forKey: .when) ?? []
                results = try c.decodeIfPresent([String].self, forKey: .results) ?? []
            }
            /// Each ring's lines, inside to out, the empty ones left out.
            var rings: [(Ring, [String])] {
                [(Ring.wakes, wakes), (.when, when), (.results, results)].filter { !$0.1.isEmpty }
            }
        }

        var version: Int?
        var does = Does()
        var wakes: [Item] = []
        var when: [Item] = []
        var results: [Item] = []
        var rim = Rim()
        var draft: Draft?
        /// The live version's dial as `PUT …/knob` takes it: a change copies it, changes one
        /// thing and sends it whole.
        var setting: Setting?

        enum CodingKeys: String, CodingKey { case version, does, wakes, when, results, rim, draft, setting }
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
            setting = try c.decodeIfPresent(Setting.self, forKey: .setting)
        }

        /// What `PUT …/knob` takes: the triggers as tafs cloud wrote them, what a busy taf
        /// does, and where results go.
        struct Setting: Codable, Equatable, Sendable {
            var wakes: [JSONValue]
            var busy: String
            var results: [String]

            enum CodingKeys: String, CodingKey { case wakes, when, results, busy }
            struct When: Codable, Equatable { var busy: String }
            init(wakes: [JSONValue], busy: String, results: [String]) { self.wakes = wakes; self.busy = busy; self.results = results }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                wakes = try c.decodeIfPresent([JSONValue].self, forKey: .wakes) ?? []
                busy = try c.decodeIfPresent(String.self, forKey: .busy) ?? "skip"
                results = try c.decodeIfPresent([String].self, forKey: .results) ?? []
            }
            /// As `PUT` takes it: `when: {busy}`.
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                try c.encode(wakes, forKey: .wakes)
                try c.encode(When(busy: busy), forKey: .when)
                try c.encode(results, forKey: .results)
            }
        }

        /// What a change made: the taf's next version, and what it changes in words. Nothing
        /// runs from it until the version switch makes it live.
        struct Change: Decodable, Equatable {
            let version: Int
            var words: [String] = []
            enum CodingKeys: String, CodingKey { case version, words }
            init(version: Int, words: [String] = []) { self.version = version; self.words = words }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                version = try c.decode(Int.self, forKey: .version)
                words = try c.decodeIfPresent([String].self, forKey: .words) ?? []
            }
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

    /// Changes the taf's dial: its next version, made live only by the version switch.
    func putKnob(_ id: String, _ setting: Knob.Setting) async throws -> Knob.Change {
        try await send("PUT", "/v1/tafs/\(escaped(id))/knob", body: setting)
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
        /// The managed provider this page lists apps of, and its next page's cursor: none on
        /// the last page.
        var provider: String?
        var nextCursor: String?
        enum CodingKeys: String, CodingKey { case apps, byKey = "by_key", provider, nextCursor = "next_cursor" }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            apps = try c.decodeIfPresent([App].self, forKey: .apps) ?? []
            byKey = try c.decodeIfPresent(Bool.self, forKey: .byKey) ?? false
            provider = try c.decodeIfPresent(String.self, forKey: .provider)
            nextCursor = try c.decodeIfPresent(String.self, forKey: .nextCursor)
        }
    }

    /// What starting a connection answers: the grant to approve, and whether it's approved
    /// with an API key the owner gives on the phone. The approval comes first, then the sign-in.
    struct ConnectStart: Decodable, Equatable {
        let grant: String
        var needsKey = false
        enum CodingKeys: String, CodingKey { case grant, needsKey = "needs_key" }
        init(grant: String, needsKey: Bool = false) { self.grant = grant; self.needsKey = needsKey }
        private struct GrantRef: Decodable { let id: String }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            // planque's grant as tafs cloud passes it on, or only its id.
            if let id = try? c.decode(String.self, forKey: .grant) { grant = id } else {
                grant = try c.decode(GrantRef.self, forKey: .grant).id
            }
            needsKey = try c.decodeIfPresent(Bool.self, forKey: .needsKey) ?? false
        }
    }

    /// Starts connecting `entry` from the list for one taf (its tafs cloud id): planque makes
    /// the grant for that taf's KIFF agent, with its profile.
    func startConnect(_ entry: String, taf: String) async throws -> ConnectStart {
        try await send("POST", "/v1/connect/apps/\(escaped(entry))", body: ["taf": taf])
    }

    /// One page of Connect apps, filtered by `q` when given; the next page with the
    /// provider and cursor the last one gave.
    func connectApps(_ q: String? = nil, provider: String? = nil, cursor: String? = nil) async throws -> ConnectApps {
        var items: [URLQueryItem] = []
        if let q = q?.trimmingCharacters(in: .whitespaces), !q.isEmpty { items.append(URLQueryItem(name: "q", value: q)) }
        if let provider { items.append(URLQueryItem(name: "provider", value: provider)) }
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        var c = URLComponents()
        c.queryItems = items.isEmpty ? nil : items
        // A cursor may hold + or /: encoded as a value, never as a separator.
        let query = (c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")).map { "?" + $0 } ?? ""
        return try await send("GET", "/v1/connect/apps" + query)
    }
}

/// Any JSON value, kept as it came: what tafs cloud writes and takes back unchanged.
enum JSONValue: Codable, Equatable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .object(let o): try c.encode(o)
        case .array(let a): try c.encode(a)
        case .null: try c.encodeNil()
        }
    }
}
