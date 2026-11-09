import Foundation

/// A taf's dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`, the knob API): the purpose in
/// the face, and When, What and Where around it. Every label, line and words is
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

        /// One stop: a trigger on When, a recipe step on What, or a place on Where.
        struct Item: Decodable, Equatable, Identifiable, Sendable {
            let id: String
            /// The server's kind: run_now, door, schedule, app_event, app_check, follow_up or step.
            var kind: String?
            /// Where: door_reply, app, owner_email or any connected app action.
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
            var grant: String?
            var step: Int?
            var action: String?
            var ok: String?
            var look: String?
            var followUp: String?
            struct Approval: Decodable, Equatable, Sendable {
                let state: String
                let grant: String
                let event: String
                let config: [String: JSONValue]
                let subscription: String?
            }
            var approval: Approval?
            struct Size: Decodable, Equatable, Sendable {
                struct Choice: Decodable, Equatable, Sendable {
                    var label: String
                    var setting: Setting
                }
                var label: String
                var index: Int
                var choices: [Choice]
                func choice(moving direction: Int) -> Choice? {
                    guard choices.indices.contains(index), direction == -1 || direction == 1,
                          choices.indices.contains(index + direction) else { return nil }
                    return choices[index + direction]
                }
            }
            var size: Size?
            var ringLabel: String { step.map { "\($0) · \(label)" } ?? label }

            enum CodingKeys: String, CodingKey { case id, kind, place, label, words, on, state, tap, expires, door, trigger, grant, step, action, ok, look, size, approval, followUp = "follow_up" }
            init(id: String, kind: String? = nil, place: String? = nil, label: String, words: String, on: Bool = true,
                 state: String? = nil, tap: String? = nil, expires: String? = nil, door: String? = nil, grant: String? = nil, step: Int? = nil, ok: String? = nil, look: String? = nil) {
                self.id = id; self.kind = kind; self.place = place; self.label = label; self.words = words
                self.on = on; self.state = state; self.tap = tap; self.expires = expires; self.door = door; self.grant = grant; self.step = step; self.ok = ok; self.look = look
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
                grant = try c.decodeIfPresent(String.self, forKey: .grant)
                step = try c.decodeIfPresent(Int.self, forKey: .step)
                action = try c.decodeIfPresent(String.self, forKey: .action)
                ok = try c.decodeIfPresent(String.self, forKey: .ok)
                look = try c.decodeIfPresent(String.self, forKey: .look)
                followUp = try c.decodeIfPresent(String.self, forKey: .followUp)
                approval = try c.decodeIfPresent(Approval.self, forKey: .approval)
                size = try c.decodeIfPresent(Size.self, forKey: .size)
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
            struct Group: Decodable, Equatable, Identifiable {
                var grant: String?
                var name: String
                var wakes: [String]
                var what: [String]
                var results: [String]
                var id: String { grant ?? "taf" }
                var rings: [(Ring, [String])] {
                    [(Ring.wakes, wakes), (.what, what), (.results, results)].filter { !$0.1.isEmpty }
                }
            }
            var groups: [Group] = []
            let version: Int
            var words: [String] = []
            var wakes: [String] = []
            var when: [String] = []
            var what: [String] = []
            var results: [String] = []
            enum CodingKeys: String, CodingKey { case version, words, wakes, when, what, results, groups }
            init(version: Int, words: [String] = [], wakes: [String] = [], when: [String] = [], what: [String] = [], results: [String] = []) {
                self.version = version; self.words = words; self.wakes = wakes; self.when = when; self.what = what; self.results = results
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                version = try c.decode(Int.self, forKey: .version)
                groups = try c.decodeIfPresent([Group].self, forKey: .groups) ?? []
                words = try c.decodeIfPresent([String].self, forKey: .words) ?? []
                wakes = try c.decodeIfPresent([String].self, forKey: .wakes) ?? []
                when = try c.decodeIfPresent([String].self, forKey: .when) ?? []
                what = try c.decodeIfPresent([String].self, forKey: .what) ?? []
                results = try c.decodeIfPresent([String].self, forKey: .results) ?? []
            }
            /// Each ring's lines, inside to out, the empty ones left out.
            var rings: [(Ring, [String])] {
                [(Ring.wakes, wakes), (.what, what), (.results, results)].filter { !$0.1.isEmpty }
            }
        }

        struct Connection: Decodable, Equatable, Identifiable {
            var grant: String
            var name: String
            var wakes: Int
            var results: Int
            var icon: String?
            var id: String { grant }
        }
        struct RingName: Decodable, Equatable {
            var key: String
            var name: String
        }

        struct Pending: Decodable, Equatable {
            var version: Int
            var changes: Int
        }
        /// Older servers did not send the flag; preserve their enabled remote until they do.
        var canPause = true
        var pending: Pending?
        var version: Int?
        var shown: Int?
        var why: String?
        var what: [Item] = []
        var connections: [Connection] = []
        var rings: [RingName] = []
        var does = Does()
        var wakes: [Item] = []
        var when: [Item] = []
        var results: [Item] = []
        var rim = Rim()
        var draft: Draft?
        /// The live version's dial as `PUT …/knob` takes it: a change copies it, changes one
        /// thing and sends it whole.
        var setting: Setting?

        enum CodingKeys: String, CodingKey { case canPause = "can_pause", pending, version, shown, why, does, wakes, when, what, results, rim, draft, setting, connections, rings }
        init(version: Int? = nil, does: Does = Does(), wakes: [Item] = [], when: [Item] = [], results: [Item] = [], rim: Rim = Rim(), draft: Draft? = nil) {
            self.version = version; self.does = does; self.wakes = wakes; self.when = when; self.results = results; self.rim = rim; self.draft = draft
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            canPause = try c.decodeIfPresent(Bool.self, forKey: .canPause) ?? true
            version = try c.decodeIfPresent(Int.self, forKey: .version)
            pending = try c.decodeIfPresent(Pending.self, forKey: .pending)
            shown = try c.decodeIfPresent(Int.self, forKey: .shown)
            why = try c.decodeIfPresent(String.self, forKey: .why)
            what = try c.decodeIfPresent([Item].self, forKey: .what) ?? []
            connections = try c.decodeIfPresent([Connection].self, forKey: .connections) ?? []
            rings = try c.decodeIfPresent([RingName].self, forKey: .rings) ?? []
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
            /// A place by name ("app"), or with what it needs (`{place: "notion", page}`):
            /// sent back as tafs cloud wrote it.
            var results: [JSONValue]
            var what: [JSONValue]?
            var why: String?

            enum CodingKeys: String, CodingKey { case wakes, when, what, why, results, busy }
            struct When: Codable, Equatable { var busy: String }
            init(wakes: [JSONValue], busy: String, results: [JSONValue]) { self.wakes = wakes; self.busy = busy; self.results = results }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                wakes = try c.decodeIfPresent([JSONValue].self, forKey: .wakes) ?? []
                busy = try c.decodeIfPresent(String.self, forKey: .busy)
                    ?? c.decodeIfPresent(When.self, forKey: .when)?.busy ?? "skip"
                results = try c.decodeIfPresent([JSONValue].self, forKey: .results) ?? []
                what = try c.decodeIfPresent([JSONValue].self, forKey: .what)
                why = try c.decodeIfPresent(String.self, forKey: .why)
            }

            /// Whether results go to `place`, named alone or with what it needs.
            func sends(to place: String) -> Bool { results.contains { Self.place(of: $0) == place } }

            static func place(of v: JSONValue) -> String? {
                switch v {
                case .string(let s): return s
                case .object(let o): if case .string(let s)? = o["place"] { return s } else { return nil }
                default: return nil
                }
            }
            /// As `PUT` takes it: `when: {busy}`.
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: CodingKeys.self)
                try c.encode(wakes, forKey: .wakes)
                try c.encode(When(busy: busy), forKey: .when)
                try c.encode(results, forKey: .results)
                try c.encodeIfPresent(what, forKey: .what)
                try c.encodeIfPresent(why, forKey: .why)
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
            case wakes, what, results
            /// The dial's own words, the same on iPhone, iPad and Android.
            var title: String {
                switch self {
                case .wakes: return "When"
                case .what: return "What"
                case .results: return "Where"
                }
            }
        }

        /// Choices must be based on the newest draft known to this phone. A failed read
        /// after a successful PUT must not allow an older choice to replace that draft.
        func acceptsChoices(waitingFor changedVersion: Int? = nil) -> Bool {
            let pending = max(self.pending?.version ?? draft?.version ?? 0, changedVersion ?? 0)
            return pending == 0 || pending <= (version ?? 0) || shown == pending
        }

        var orderedRings: [Ring] {
            guard !rings.isEmpty else { return Ring.allCases }
            var seen = Set<Ring>()
            return rings.compactMap { name in
                let ring: Ring?
                switch name.key { case "wakes": ring = .wakes; case "what": ring = .what; case "results": ring = .results; default: ring = nil }
                guard let ring, seen.insert(ring).inserted else { return nil }
                return ring
            }
        }

        func title(_ ring: Ring) -> String {
            let key = ring == .wakes ? "wakes" : ring == .what ? "what" : "results"
            return rings.first { $0.key == key }?.name ?? ring.title
        }

        func items(_ ring: Ring) -> [Item] {
            switch ring {
            case .wakes: return wakes
            case .what: return what
            case .results: return results
            }
        }
    }

    /// A taf's dial.
    func knob(_ id: String, version: Int? = nil) async throws -> Knob {
        try await send("GET", "/v1/tafs/\(escaped(id))/knob" + (version.map { "?version=\($0)" } ?? ""))
    }

    /// Record only the receipt planque returned for this live version's event. The server
    /// decides whether it still matches, and returns the resulting dial.
    func recordKnobSubscription(_ id: String, trigger: String, subscription: String, version: Int) async throws -> Knob {
        struct Body: Encodable { let trigger: String; let subscription: String; let version: Int }
        return try await send("POST", "/v1/tafs/\(escaped(id))/knob/subscriptions",
                              body: Body(trigger: trigger, subscription: subscription, version: version))
    }

    /// Changes the taf's dial: its next version, made live only by the version switch.
    func putKnob(_ id: String, _ setting: Knob.Setting) async throws -> Knob.Change {
        try await send("PUT", "/v1/tafs/\(escaped(id))/knob", body: setting)
    }

    /// Where its results go that tafs cloud delivers itself (`PUT …/results`): one or more of
    /// `door_reply`, `app` and `owner_email`. Live at once, no new version.
    func putResults(_ id: String, places: [String]) async throws -> CloudTaf {
        struct Body: Encodable { let places: [String] }
        struct Response: Decodable { let taf: CloudTaf }
        let r: Response = try await send("PUT", "/v1/tafs/\(escaped(id))/results", body: Body(places: places))
        return r.taf
    }

    func removeFollowUp(_ id: String, followUp: String) async throws {
        struct Removed: Decodable { var removed: String }
        let _: Removed = try await send("DELETE", "/v1/tafs/\(escaped(id))/follow-ups/\(escaped(followUp))")
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
        /// A live taf whose Card in force still uses a removed taf's connection, by tafs cloud's name.
        struct UsedBy: Decodable, Equatable {
            var taf = ""
            var name = ""
            enum CodingKeys: String, CodingKey { case taf, name }
            init(taf: String = "", name: String = "") { self.taf = taf; self.name = name }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                taf = try c.decodeIfPresent(String.self, forKey: .taf) ?? ""
                name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
            }
        }
        /// A removed taf's live connection, with the live tafs whose Cards still use it: Clear keeps those.
        struct RemovedGrant: Decodable, Equatable {
            let grant: String
            var usedBy: [UsedBy] = []
            enum CodingKeys: String, CodingKey { case grant, usedBy = "used_by" }
            init(grant: String, usedBy: [UsedBy] = []) { self.grant = grant; self.usedBy = usedBy }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                grant = try c.decode(String.self, forKey: .grant)
                usedBy = try c.decodeIfPresent([UsedBy].self, forKey: .usedBy) ?? []
            }
        }
        /// A taf removed since, as tafs cloud names it from KIFF's archived agents.
        struct RemovedTaf: Decodable, Equatable {
            let agent: String
            var removedAt: String?
            /// Its live connections. Only with this phone's signed grants read, and only when tafs
            /// cloud could read planque and KIFF's Cards: nil means no Clear.
            var grants: [RemovedGrant]?
            enum CodingKeys: String, CodingKey { case agent, removedAt = "removed_at", grants }
        }
        var apps: [App] = []
        /// The tafs removed since, newest first: a grant whose `request.agent` is one is from a
        /// removed taf. Only for someone who manages Cards; empty otherwise, or from a tafs
        /// cloud without it.
        var removedTafs: [RemovedTaf] = []
        /// Coach can draft a vendor that isn't listed.
        var byKey = false
        /// The managed provider this page lists apps of, and its next page's cursor: none on
        /// the last page.
        var provider: String?
        var nextCursor: String?
        enum CodingKeys: String, CodingKey { case apps, removedTafs = "removed_tafs", byKey = "by_key", provider, nextCursor = "next_cursor" }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            apps = try c.decodeIfPresent([App].self, forKey: .apps) ?? []
            removedTafs = try c.decodeIfPresent([RemovedTaf].self, forKey: .removedTafs) ?? []
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
    /// provider and cursor the last one gave. With `proof`, this phone's signed grants read,
    /// tafs cloud also lists each removed taf's connections with the live tafs whose Cards
    /// still use them (`removed_tafs[].grants`).
    func connectApps(_ q: String? = nil, provider: String? = nil, cursor: String? = nil, proof: String? = nil) async throws -> ConnectApps {
        var items: [URLQueryItem] = []
        if let q = q?.trimmingCharacters(in: .whitespaces), !q.isEmpty { items.append(URLQueryItem(name: "q", value: q)) }
        if let provider { items.append(URLQueryItem(name: "provider", value: provider)) }
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        var c = URLComponents()
        c.queryItems = items.isEmpty ? nil : items
        // A cursor may hold + or /: encoded as a value, never as a separator.
        let query = (c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")).map { "?" + $0 } ?? ""
        return try await send("GET", "/v1/connect/apps" + query, headers: proof.map { ["Planque-Device-Signature": $0] } ?? [:])
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
