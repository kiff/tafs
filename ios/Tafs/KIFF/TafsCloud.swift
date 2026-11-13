import Foundation

/// tafs cloud: the calls that start, follow, chat with and remove tafs, which run on
/// kiff/agents; the phone never holds a taf's key.
/// It keeps no accounts: every call carries the person's KIFF session, and tafs cloud
/// asks KIFF who that is.
@MainActor
final class TafsCloud {
    struct CloudError: Error, Equatable, LocalizedError {
        let status: Int
        let message: String
        /// The runs that keep a switch from beginning, when tafs cloud names them (kiff/tafs#206).
        var blocking: [BlockingRun] = []
        /// What tafs cloud says is wrong, written for the owner (a run kiff/agents refused, a
        /// switch a run blocks), when it says it.
        var words: String? = nil
        /// tafs cloud's code for the refusal, when it gives one ("held_full").
        var code: String? = nil
        /// For a refusal because of a halt: whether the whole `account` is halted or the `taf`
        /// on its own, as tafs cloud says. Nil for any other refusal, and for a value this app
        /// doesn't know.
        var halted: String? = nil
        /// The AI-data consent's screen, when the refusal carries it: a turn refused for want of
        /// it (`ai_consent_required`), or an older screen's yes refused for newer words.
        var consent: AIConsent? = nil
        /// tafs cloud's words first, on every path that shows the error (kiff/tafs#397); the
        /// app's own line for the status only without them.
        var errorDescription: String? { words ?? message }
    }

    /// tafs cloud's refusal: its words, and the runs in the way when it names them (kiff/tafs#206).
    static func refusal(_ status: Int, _ data: Data) -> CloudError {
        struct Body: Decodable { let error: String?; let blocking: [BlockingRun]?; let words: String?; let code: String?; let halted: String? }
        struct Consent: Decodable { let consent: AIConsent? }
        let body = try? JSONDecoder().decode(Body.self, from: data)
        // The consent's words, read on their own, so one that doesn't read leaves the rest of the refusal:
        // with a refused turn, and with an older screen's refused yes (409), which carries the new words.
        let consent = (try? JSONDecoder().decode(Consent.self, from: data))?.consent
        let error = CloudError(status: status, message: body?.error ?? Words.Error.cloudAnswered(status: "\(status)"), blocking: body?.blocking ?? [], words: body?.words,
                               code: body?.code, halted: body?.halted.flatMap { ["account", "taf"].contains($0) ? $0 : nil }, consent: consent)
        // Every route that would start a model turn can refuse this way: said once here, so the
        // consent screen shows wherever the person tried, with nothing wired per call.
        if body?.code == aiConsentRequired, let consent, consent.drawable {
            NotificationCenter.default.post(name: .aiConsentRequired, object: consent)
        }
        return error
    }

    /// tafs cloud's code for a model turn refused for want of the AI-data consent.
    nonisolated static let aiConsentRequired = "ai_consent_required"

    /// A run in the way of an install (kiff/tafs#206): one at work, or one waiting on the owner.
    struct BlockingRun: Decodable, Equatable {
        let run: String
        let session: String
        let status: String
        let trigger: String?
    }

    /// A taf as tafs cloud runs it.
    struct CloudTaf: Decodable, Equatable, Identifiable {
        let id: String
        let name: String
        let job: String
        /// The KIFF agent it acts as: the holder of its Cards.
        let kiffAgent: String?
        let status: String
        let nextRun: Date?
        let lastRun: Date?
        /// It works from its chat (kiff/tafs#138): its tools can't find its work, so no
        /// schedule runs it, and there's nothing to run now or pause.
        var unscheduled: Bool?
        /// Where its results go that tafs cloud delivers: `door_reply`, `app`, `owner_email`.
        var results: [JSONValue]?
        var timeZoneWords: String? = nil
        var scheduleTimeZone: String? = nil
        var scheduleTimeZoneWords: String? = nil
        /// The next run in the person's language, with its weekday when it isn't today ("Next run
        /// Mon 08:00"): drawn as is, never a bare time that reads as tomorrow.
        var nextRunWords: String? = nil

        enum CodingKeys: String, CodingKey {
            case id, name, job, status, unscheduled, results
            case kiffAgent = "kiff_agent", nextRun = "next_run", lastRun = "last_run", nextRunWords = "next_run_words"
            case timeZoneWords = "time_zone_words", scheduleTimeZone = "schedule_time_zone"
            case scheduleTimeZoneWords = "schedule_time_zone_words"
        }

        var worksFromChat: Bool { unscheduled == true }

        /// Its results alert the account's phones: it answered someone, never its words.
        var alertsPhones: Bool { results?.contains { TafsCloud.Knob.Setting.place(of: $0) == "app" } ?? false }

        var paused: Bool { status == "paused" }
    }

    struct Run: Decodable, Equatable {
        let session: String?
        let at: Date
        let error: String?
    }

    struct Report: Decodable, Equatable {
        let status: String
        let text: String
    }

    let baseURL: URL
    /// tafs-cloud-stream: the one route whose answer streams (#60).
    let streamURL: URL
    var token: String?
    let session: URLSession

    /// Keeps the server's representation validator; 304 leaves cached pages intact.
    func representation(_ path: String, etag: String? = nil, headers: [String: String] = [:]) async throws -> (Data?, String?) {
        guard let url = URL(string: path, relativeTo: baseURL), let token else { throw CloudError(status: 401, message: Words.Error.signInFirst) }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await session.data(for: request)
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        let tag = http?.value(forHTTPHeaderField: "ETag") ?? etag
        if status == 304 { return (nil, tag) }
        guard (200..<300).contains(status) else {
            DriveDiagnostics.shared.failed(method: "GET", path: path, status: status)
            throw Self.refusal(status, data)
        }
        return (data, tag)
    }

    static let defaultURL = URL(string: "https://cloud.tafs.kiff.dev")!
    /// Straight to the function, in eu-west-1: a run streams for minutes, longer than a CDN waits between bytes.
    static let defaultStreamURL = URL(string: "https://ghjfunc5ppretjo22bl5izlnya0zrocm.lambda-url.eu-west-1.on.aws")!

    init(baseURL: URL = TafsCloud.defaultURL, streamURL: URL? = nil, token: String? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        // A test's cloud streams from its own host.
        self.streamURL = streamURL ?? (baseURL == TafsCloud.defaultURL ? TafsCloud.defaultStreamURL : baseURL)
        self.token = token
        self.session = session
    }

    func tafs() async throws -> [CloudTaf] {
        try await listing().tafs
    }

    /// A taf whose removal began and didn't finish: archived, so no longer listed as a taf.
    struct PendingRemoval: Decodable, Equatable, Identifiable {
        let id: String
        let name: String
    }

    /// One holder of Cards on the home list, as tafs cloud makes it from its records and
    /// KIFF's Cards and held calls (#91): the app shows it and combines nothing itself.
    struct HomeTaf: Decodable, Equatable {
        /// An amount and what KIFF says it means ("EUR", 2); no unit for a bare number.
        struct Amount: Decodable, Equatable {
            let minor: Int64
            let unit: String?
            let scale: Int
        }

        /// A window in the owner's words, as tafs cloud sends them: shown as they are.
        struct WindowWords: Decodable, Equatable, Sendable {
            let each: String
            let now: String
        }

        struct Rule: Decodable, Equatable {
            let action: String
            let label: String
            let outside: String
            let perAction: Amount?
            let perWindow: Amount?
            let countPerWindow: Int?
            let window: String
            var windowWords: WindowWords?
            /// Of perWindow, the extra room KIFF says is active, the Card's own limit, and when
            /// the room ends, as tafs cloud passes them on from KIFF (the 755 retest).
            var extraRoom: Amount?
            var baseLimit: Amount?
            var roomWindowEnd: Date?

            enum CodingKeys: String, CodingKey {
                case action, label, outside, window
                case windowWords = "window_words"
                case perAction = "per_action", perWindow = "per_window", countPerWindow = "count_per_window"
                case extraRoom = "extra_room", baseLimit = "base_limit", roomWindowEnd = "room_window_end"
            }
        }

        /// A Card's total for its window and what it has used; no use when KIFF couldn't read it.
        /// Sendable said outright: a generic type isn't taken as Sendable on its own.
        struct Total<Value: Decodable & Equatable & Sendable>: Decodable, Equatable, Sendable {
            let limit: Value
            let used: Value?
            let window: String
            var windowWords: WindowWords?
            /// Of limit, the extra room KIFF says is active, the Card's own limit, and when the
            /// room ends (the 755 retest).
            var extraRoom: Value?
            var baseLimit: Value?
            var roomWindowEnd: Date?
            /// What of its use is from calls the tool refused, which count until KIFF can
            /// confirm nothing moved, in KIFF's words; none without any.
            var pendingText: String?
            /// Why its amount has no currency, in KIFF's words, when a tool the Card grants
            /// isn't connected any more ("Its currency comes from …, which isn't connected.");
            /// and those tools. None otherwise.
            var unitWords: String?
            var unitMissing: UnitMissing?

            struct UnitMissing: Decodable, Equatable, Sendable {
                var tools: [String] = []
                var connected = false
            }

            enum CodingKeys: String, CodingKey {
                case limit, used, window
                case windowWords = "window_words"
                case extraRoom = "extra_room", baseLimit = "base_limit", roomWindowEnd = "room_window_end"
                case pendingText = "pending_text"
                case unitWords = "unit_words", unitMissing = "unit_missing"
            }
        }

        /// One KIFF Card on its own (kiff/tafs#211).
        struct CardView: Decodable, Equatable {
            let id: String
            let title: String
            /// As KIFF names it; empty for every environment.
            let environment: String
            /// Production, or every environment: what the taf does for real.
            let live: Bool
            let active: Bool
            let expires: Date?
            let rules: [Rule]
            let total: Total<Amount>?
            let calls: Total<Int>?
            /// How this is counted: each tool's lines in KIFF's words, as the owner approved
            /// them; nil when KIFF has none.
            var counted: [Counted]? = nil
            /// Its totals for each item apart, in tafs cloud's words; nil without any.
            var eachItem: [String]? = nil

            enum CodingKeys: String, CodingKey {
                case id, title, environment, live, active, expires, rules, total, calls, counted
                case eachItem = "each_item"
            }
        }

        /// One tool of a Card, and how KIFF counts it.
        struct Counted: Decodable, Equatable {
            let tool: String
            let title: String
            let lines: [String]
        }

        struct CardSummary: Decodable, Equatable {
            /// The live Cards: what revoking stops.
            let ids: [String]
            let active: Bool
            let expires: Date?
            let tools: [String]
            let rules: [Rule]
            let used: [String: Amount]
            let usageUnknown: [String]
            /// Each Card on its own, the live ones first; nil from an older tafs cloud.
            var cards: [CardView]? = nil

            enum CodingKeys: String, CodingKey {
                case ids, active, expires, tools, rules, used, cards, usageUnknown = "usage_unknown"
            }
        }

        /// tafs cloud's id; nil for an agent tafs doesn't run.
        let id: String?
        let name: String
        let job: String?
        /// It works from its chat, with no schedule (kiff/tafs#138, items 43 and 55).
        var unscheduled: Bool? = nil
        /// Its job in words (kiff/tafs#167); absent for an agent tafs doesn't run.
        var jobSummary: String? = nil
        let kiffAgent: String
        let status: String?
        let nextRun: Date?
        let lastRun: Date?
        let color: Int
        /// working, paused, waiting, idle (an agent tafs doesn't run) or stopped (no active Card).
        let state: String
        /// Its calls held for the owner.
        let waiting: Int
        let card: CardSummary
        /// Its open trial (kiff/tafs#173), its own entry in the menu.
        var trial: OpenTrial? = nil
        /// Its doors, for its avatar's rim; absent when it has none.
        var doors: [DoorLight]? = nil
        var avatarRim: AvatarRim? = nil
        var timeZoneWords: String? = nil
        var scheduleTimeZone: String? = nil
        var scheduleTimeZoneWords: String? = nil
        /// Where its results go that tafs cloud delivers (`door_reply`, `app`, `owner_email`), live:
        /// what Results go to's switches show.
        var results: [JSONValue]? = nil
        /// Its health, with tafs cloud's lines and fixes; nil when it couldn't be read in time.
        var health: Health? = nil
        /// Where its work stands, as the list sends it (`work: {status, line}`): an idle taf that
        /// isn't healthy says its first reason here, where it would say its next run.
        var work: TafWork.Summary? = nil
        /// tafs cloud's words for its next run, with the day when it isn't today ("Next run Mon 08:00").
        var nextRunWords: String? = nil

        enum CodingKeys: String, CodingKey {
            case id, name, job, status, color, state, waiting, card, unscheduled, trial, doors, results, health, work
            case nextRunWords = "next_run_words"
            case avatarRim = "avatar_rim"
            case timeZoneWords = "time_zone_words", scheduleTimeZone = "schedule_time_zone"
            case scheduleTimeZoneWords = "schedule_time_zone_words"
            case kiffAgent = "kiff_agent", nextRun = "next_run", lastRun = "last_run", jobSummary = "job_summary"
        }

        /// tafs cloud's record of it, for its chat, runs and schedule; nil for another agent.
        var cloudTaf: CloudTaf? {
            // With unscheduled, or the taf page offers Run now and Pause a taf that works from
            // its chat can't take (kiff/tafs#206 section 2).
            id.map { CloudTaf(id: $0, name: name, job: job ?? "", kiffAgent: kiffAgent, status: status ?? "active", nextRun: nextRun, lastRun: lastRun,
                              unscheduled: unscheduled, results: results, timeZoneWords: timeZoneWords, scheduleTimeZone: scheduleTimeZone, scheduleTimeZoneWords: scheduleTimeZoneWords,
                              nextRunWords: nextRunWords) }
        }
    }

    /// The home list: the account's tafs, its other agents with Cards (listed apart), and
    /// the removals still to finish.
    struct Home: Decodable, Equatable {
        let tafs: [HomeTaf]
        let others: [HomeTaf]
        let removing: [PendingRemoval]
        /// The doors tafs cloud offers here (docs/doors.md); absent when none, and the app shows no Doors.
        var doorsOffered: [String]? = nil
        var cardUnitExceptions: [CardUnitException]? = nil
        /// The steps tafs handed the person, oldest first (docs/api.md › Your step); absent when none.
        var yourSteps: [YourStep]? = nil
        /// Everything that waits for the person, counted by tafs cloud with its words; absent
        /// when KIFF's "Did this happen?" can't be read, and the app counts what it has.
        var needsYou: NeedsYou? = nil

        enum CodingKeys: String, CodingKey {
            case tafs, others, removing, doorsOffered = "doors_offered"
            case cardUnitExceptions = "card_unit_exceptions"
            case yourSteps = "your_steps"
            case needsYou = "needs_you"
        }
    }

    struct NeedsYou: Decodable, Equatable {
        let count: Int
        let words: String
    }

    func home() async throws -> Home {
        try await send("GET", "/v1/home")
    }

    /// What happened in tafs itself (kiff/tafs#236, items 15 and 19): a halt or resume, a
    /// knock at a door and what the owner did with it, a door's removal. Newest first.
    struct AccountEvent: Decodable, Equatable {
        let at: Date
        let kind: String
        /// The taf's id in tafs cloud; nil for all of the account's tafs.
        var taf: String? = nil
        var name: String? = nil
        var app: String? = nil
        var from: String? = nil
    }

    func events() async throws -> [AccountEvent] {
        struct Response: Decodable { let events: [AccountEvent] }
        let r: Response = try await send("GET", "/v1/events")
        return r.events
    }

    /// The account's balance now, in tafs cloud's words (docs/accounts.md › The balance read):
    /// for Plan & usage, Settings' Plan card and Home. The app draws the words as they are,
    /// with a bar at `meter_pct`, and offers Raise the limit and buying only where tafs cloud
    /// says so. Lenient: a field it can't read is left out, never the read.
    struct Balance: Decodable, Equatable {
        /// Raise the limit, as offered: its words, and the limit now, which the raise sends back.
        struct Offer: Decodable, Equatable {
            let words: String
            var fromCents: Int? = nil
            enum CodingKeys: String, CodingKey { case words, fromCents = "from_cents" }
        }
        /// What the store may sell this person now: Solo, on an account with no plan billed,
        /// and packs of credits while Solo runs.
        struct Buy: Decodable, Equatable {
            /// The First month pack, which an account buys once: set only while it's offered
            /// (while Solo runs). `ready` false is a tafs cloud from before, where it couldn't
            /// be bought yet: the app shows no such pack.
            struct FirstMonth: Decodable, Equatable {
                var ready = false
                init(ready: Bool = false) { self.ready = ready }
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    ready = ((try? c.decodeIfPresent(Bool.self, forKey: .ready)) ?? nil) ?? false
                }
                enum CodingKeys: String, CodingKey { case ready }
            }
            var solo = false
            var packs = false
            var firstMonth: FirstMonth? = nil
            /// What Coach's help includes (`coach`): tafs cloud's sentence for under the Solo
            /// offer, as kiff.dev says it. Nil from a tafs cloud that sends none, or a blank one.
            var coach: String? = nil
            init(solo: Bool = false, packs: Bool = false, firstMonth: FirstMonth? = nil, coach: String? = nil) {
                self.solo = solo; self.packs = packs; self.firstMonth = firstMonth; self.coach = coach
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                solo = Self.offered(c, .solo)
                packs = Self.offered(c, .packs)
                firstMonth = (try? c.decodeIfPresent(FirstMonth.self, forKey: .firstMonth)) ?? nil
                coach = ((try? c.decodeIfPresent(String.self, forKey: .coach)) ?? nil)
                    .flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
            }
            /// The First month pack can be bought now.
            var firstMonthReady: Bool { firstMonth?.ready == true }
            /// `true`, or anything set that isn't `false` (a list of products), offers it.
            private static func offered(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> Bool {
                if let b = try? c.decodeIfPresent(Bool.self, forKey: k) { return b }
                guard c.contains(k), (try? c.decodeNil(forKey: k)) == false else { return false }
                return true
            }
            enum CodingKeys: String, CodingKey { case solo, packs, firstMonth = "first_month", coach }
        }
        struct Line: Decodable, Equatable {
            /// `ok`, `warning` (from 80%) or `waiting`; a state the app doesn't know reads `ok`.
            var state = "ok"
            /// While waiting: `credits_used_up`, `limit_reached` or `no_plan`.
            var why: String? = nil
            /// How much is used, 0 to 100, for a bar; nil where nothing bounds it.
            var meterPct: Int? = nil
            let words: String
            var detail: String? = nil
            var warning: String? = nil
            /// Set only for someone who may raise the spend limit.
            var raise: Offer? = nil
            var buy: Buy? = nil
            /// Whether Plan & usage shows the Credits panel: false only with no plan and no run
            /// lately. A tafs cloud from before the flag doesn't say, and the panel shows.
            var creditsPanel = true
            enum CodingKeys: String, CodingKey {
                case state, why, meterPct = "meter_pct", words, detail, warning, raise, buy, creditsPanel = "credits_panel"
            }

            init(state: String = "ok", why: String? = nil, meterPct: Int? = nil, words: String, detail: String? = nil,
                 warning: String? = nil, raise: Offer? = nil, buy: Buy? = nil, creditsPanel: Bool = true) {
                self.state = state; self.why = why; self.meterPct = meterPct; self.words = words; self.detail = detail
                self.warning = warning; self.raise = raise; self.buy = buy; self.creditsPanel = creditsPanel
            }

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                words = try c.decode(String.self, forKey: .words)
                state = (try? c.decodeIfPresent(String.self, forKey: .state)) ?? "ok"
                why = (try? c.decodeIfPresent(String.self, forKey: .why)) ?? nil
                meterPct = ((try? c.decodeIfPresent(Int.self, forKey: .meterPct)) ?? nil).map { min(max($0, 0), 100) }
                detail = Self.text(c, .detail)
                warning = Self.text(c, .warning)
                raise = (try? c.decodeIfPresent(Offer.self, forKey: .raise)) ?? nil
                buy = (try? c.decodeIfPresent(Buy.self, forKey: .buy)) ?? nil
                creditsPanel = ((try? c.decodeIfPresent(Bool.self, forKey: .creditsPanel)) ?? nil) ?? true
            }

            private static func text(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> String? {
                guard let t = (try? c.decodeIfPresent(String.self, forKey: k)) ?? nil, !t.isEmpty else { return nil }
                return t
            }

            /// Waiting: no new run starts until the balance changes.
            var waiting: Bool { state == "waiting" }
        }
        let balance: Line
        /// Settings' Plan card, in `planTone`'s colour.
        var planLine: String? = nil
        /// `ok`, `warning` or `waiting`.
        var planTone = "ok"
        /// Home's one line about the plan; nil when the plan doesn't need the person.
        var homeLine: String? = nil
        enum CodingKeys: String, CodingKey { case balance, planLine = "plan_line", planTone = "plan_tone", homeLine = "home_line" }

        init(balance: Line, planLine: String? = nil, planTone: String = "ok", homeLine: String? = nil) {
            self.balance = balance; self.planLine = planLine; self.planTone = planTone; self.homeLine = homeLine
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            balance = try c.decode(Line.self, forKey: .balance)
            planLine = ((try? c.decodeIfPresent(String.self, forKey: .planLine)) ?? nil).flatMap { $0.isEmpty ? nil : $0 }
            planTone = ((try? c.decodeIfPresent(String.self, forKey: .planTone)) ?? nil) ?? "ok"
            homeLine = ((try? c.decodeIfPresent(String.self, forKey: .homeLine)) ?? nil).flatMap { $0.isEmpty ? nil : $0 }
        }
    }

    /// The balance now; nil from a tafs cloud from before it (404), which shows none.
    func balance() async throws -> Balance? {
        do {
            return try await send("GET", "/v1/usage/balance")
        } catch let e as CloudError where e.status == 404 { return nil }
    }

    /// The credits the account's tafs used in a period, read only (docs/accounts.md): in all,
    /// by taf, and what wasn't counted and why. Every line is tafs cloud's `words`, shown as
    /// they are: the app derives nothing but the order.
    struct Credits: Decodable, Equatable {
        struct Period: Decodable, Equatable {
            let start: Date
            let end: Date
            var current = true
            init(start: Date, end: Date, current: Bool = true) { self.start = start; self.end = end; self.current = current }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                start = try c.decode(Date.self, forKey: .start)
                end = try c.decode(Date.self, forKey: .end)
                current = (try? c.decodeIfPresent(Bool.self, forKey: .current)) ?? true
            }
            enum CodingKeys: String, CodingKey { case start, end, current }
        }
        /// One taf's part of the period. Only tafs that used credits are listed, most first; one
        /// row with no `taf` is the tafs removed since, which doesn't open.
        struct Taf: Decodable, Equatable, Identifiable {
            var taf: String?
            let credits: Int
            let runs: Int
            let words: String
            var id: String { taf ?? "removed" }
        }
        /// Coach's runs past its fair use: their own row.
        struct Coach: Decodable, Equatable { let credits: Int; let runs: Int; let words: String }
        struct NotCounted: Decodable, Equatable {
            struct Line: Decodable, Equatable { let why: String; let runs: Int; let words: String }
            var runs = 0
            var words = ""
            var lines: [Line] = []
            init(runs: Int = 0, words: String = "", lines: [Line] = []) { self.runs = runs; self.words = words; self.lines = lines }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                runs = (try? c.decodeIfPresent(Int.self, forKey: .runs)) ?? 0
                words = (try? c.decodeIfPresent(String.self, forKey: .words)) ?? ""
                lines = ((try? c.decodeIfPresent([LenientItem<Line>].self, forKey: .lines)) ?? []).compactMap(\.value)
            }
            enum CodingKeys: String, CodingKey { case runs, words, lines }
        }
        let period: Period?
        let credits: Int
        var runs = 0
        let words: String
        var tafs: [Taf] = []
        /// Nil while Coach is within its fair use.
        var coach: Coach?
        var notCounted: NotCounted?
        var note: String?
        enum CodingKeys: String, CodingKey { case period, credits, runs, words, tafs, coach, notCounted = "not_counted", note }

        init(period: Period?, credits: Int, runs: Int = 0, words: String, tafs: [Taf] = [], coach: Coach? = nil, notCounted: NotCounted? = nil, note: String? = nil) {
            self.period = period; self.credits = credits; self.runs = runs; self.words = words; self.tafs = tafs; self.coach = coach
            self.notCounted = notCounted; self.note = note
        }
        /// A line the app can't read is left out, not the read.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            period = try? c.decodeIfPresent(Period.self, forKey: .period)
            credits = try c.decode(Int.self, forKey: .credits)
            runs = (try? c.decodeIfPresent(Int.self, forKey: .runs)) ?? 0
            words = try c.decode(String.self, forKey: .words)
            tafs = ((try? c.decodeIfPresent([LenientItem<Taf>].self, forKey: .tafs)) ?? []).compactMap(\.value)
            coach = try? c.decodeIfPresent(Coach.self, forKey: .coach)
            notCounted = try? c.decodeIfPresent(NotCounted.self, forKey: .notCounted)
            note = (try? c.decodeIfPresent(String.self, forKey: .note)).flatMap { $0?.isEmpty == false ? $0 : nil }
        }
    }

    /// One taf's credits in a period, with its runs (`items`), newest first, 50 a page; `next`
    /// reads further back.
    struct TafCredits: Decodable, Equatable {
        struct Run: Decodable, Equatable, Identifiable {
            let run: String
            /// When the run ended.
            let at: Date
            let credits: Int
            let counted: Bool
            /// Why it wasn't counted (`trial`, `failed`, `coach`); nil on a counted run.
            var why: String?
            let words: String
            /// Whether its row opens the run's request (`GET /v1/tafs/{id}/requests/{run}`):
            /// while kiff/agents keeps the run, never for a trial.
            var opens = false
            var id: String { run }
            init(run: String, at: Date, credits: Int, counted: Bool, why: String? = nil, words: String, opens: Bool = false) {
                self.run = run; self.at = at; self.credits = credits; self.counted = counted; self.why = why; self.words = words; self.opens = opens
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                run = try c.decode(String.self, forKey: .run)
                at = try c.decode(Date.self, forKey: .at)
                credits = try c.decode(Int.self, forKey: .credits)
                counted = try c.decode(Bool.self, forKey: .counted)
                why = try? c.decodeIfPresent(String.self, forKey: .why)
                words = try c.decode(String.self, forKey: .words)
                opens = (try? c.decodeIfPresent(Bool.self, forKey: .opens)) ?? false
            }
            enum CodingKeys: String, CodingKey { case run, at, credits, counted, why, words, opens }
        }
        let credits: Int
        var runs = 0
        let words: String
        var items: [Run] = []
        var next: String?

        init(credits: Int, runs: Int = 0, words: String, items: [Run] = [], next: String? = nil) {
            self.credits = credits; self.runs = runs; self.words = words; self.items = items; self.next = next
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            credits = try c.decode(Int.self, forKey: .credits)
            runs = (try? c.decodeIfPresent(Int.self, forKey: .runs)) ?? 0
            words = try c.decode(String.self, forKey: .words)
            items = ((try? c.decodeIfPresent([LenientItem<Run>].self, forKey: .items)) ?? []).compactMap(\.value)
            next = (try? c.decodeIfPresent(String.self, forKey: .next)).flatMap { $0?.isEmpty == false ? $0 : nil }
        }
        enum CodingKeys: String, CodingKey { case credits, runs, words, items, next }
    }

    /// Decodes one element of a list, leaving out one it can't read.
    struct LenientItem<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    /// The period's credits; nil from a tafs cloud without the read (404), which shows nothing.
    func credits(previous: Bool = false) async throws -> Credits? {
        do {
            return try await send("GET", "/v1/usage/credits" + (previous ? "?period=previous" : ""))
        } catch let e as CloudError where e.status == 404 { return nil }
    }

    /// One taf's credits and runs in the period; nil from a tafs cloud without the read.
    func credits(taf: String, previous: Bool = false, after: String? = nil) async throws -> TafCredits? {
        var query: [String] = []
        if previous { query.append("period=previous") }
        if let after, let escaped = after.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+"))) {
            query.append("after=\(escaped)")
        }
        let id = taf.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? taf
        do {
            return try await send("GET", "/v1/usage/credits/tafs/\(id)" + (query.isEmpty ? "" : "?" + query.joined(separator: "&")))
        } catch let e as CloudError where e.status == 404 { return nil }
    }

    /// The account's tafs, and the removals still to finish.
    func listing() async throws -> (tafs: [CloudTaf], removing: [PendingRemoval]) {
        struct Response: Decodable { let tafs: [CloudTaf]; let removing: [PendingRemoval]? }
        let r: Response = try await send("GET", "/v1/tafs")
        return (r.tafs, r.removing ?? [])
    }

    /// What removing a taf did: each step, "schedule", "history", "memory", "key" and "name",
    /// is in `removed` or `failed`. A second remove finishes what the first left.
    struct Removal: Decodable, Equatable {
        let done: Bool
        let removed: [String]
        let failed: [String]
    }

    /// Removes the taf for good: stops it, deletes its runs, chats and memory and its key,
    /// and frees its name. Its Cards are KIFF's: the phone revokes them first.
    func remove(_ id: String, signature: String? = nil) async throws -> Removal {
        try await send("DELETE", tafPath(id), headers: signature.map { ["KIFF-Device-Signature": $0] } ?? [:])
    }

    /// What removing a taf takes with it, for the confirmation before Face ID
    /// (`GET /v1/tafs/{id}/removal`, which ends nothing): tafs cloud's words, ready to draw
    /// ("Qonto goes with it. Stripe stays: Chargebacks uses it."), or none when it could read nothing.
    struct RemovalPreview: Decodable, Equatable {
        var words: String?
        private enum CodingKeys: String, CodingKey { case words }
        init(words: String? = nil) { self.words = words }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            let said = ((try? c.decodeIfPresent(String.self, forKey: .words)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines)
            words = said?.isEmpty == false ? said : nil
        }
    }

    func removalPreview(_ id: String) async throws -> RemovalPreview {
        try await send("GET", tafPath(id) + "/removal")
    }

    /// A taf's path, as a request to it is sent and as the approval key signs it.
    func tafPath(_ id: String) -> String { "/v1/tafs/\(escaped(id))" }

    /// What Run now did: the run's session, or none when the taf's check started no run, with
    /// tafs cloud's words for why (nothing new since the last run), in the person's language.
    struct RunNow: Equatable {
        let session: String?
        let words: String?
    }

    /// Runs the taf now, by firing its schedule. A taf that checks first and finds nothing new
    /// starts no run, which is no failure: the answer has no session, and tafs cloud's words
    /// say why (docs/api.md). An older tafs cloud's bare `null` carries none.
    func runNow(_ id: String) async throws -> RunNow {
        struct Response: Decodable { let session: String?; let words: String? }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/run", body: [String: String]())
        // The words are tafs cloud's, as they are; the app writes none of its own.
        let words = r.session == nil ? r.words.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } : nil
        return RunNow(session: r.session, words: words)
    }

    func setPaused(_ id: String, _ paused: Bool) async throws -> CloudTaf {
        struct Response: Decodable { let taf: CloudTaf }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/\(paused ? "pause" : "resume")", body: [String: String]())
        return r.taf
    }

    // MARK: Halt

    /// A halt of one taf or of the whole account, as tafs cloud reads
    /// it from kiff/agents and KIFF: halted only once all three hold it.
    struct Halt: Decodable, Equatable {
        enum State: String, Decodable { case running, halting, halted }
        let state: State
        let haltedAt: Date?
        /// Who holds it: kiff/agents (no run starts), KIFF's gateway (it refuses the calls),
        /// planque (it sends nothing to the vendor).
        let runtime: Bool
        let kiff: Bool
        let planque: Bool
        /// Halted because the whole account is, not on its own.
        let account: Bool
        /// How many runs this halt stopped.
        let stopped: Int?

        enum CodingKeys: String, CodingKey {
            case state, runtime, kiff, planque, account, stopped
            case haltedAt = "halted_at"
        }

        static let running = Halt(state: .running, haltedAt: nil, runtime: false, kiff: false, planque: false, account: false, stopped: nil)
    }

    private struct HaltResponse: Decodable { let halt: Halt }

    private func haltPath(_ id: String?) -> String {
        id.map { "/v1/tafs/\(escaped($0))/halt" } ?? "/v1/halt"
    }

    /// Halts the taf, or with none every taf of the account, in kiff/agents and KIFF at once.
    func halt(_ id: String?) async throws -> Halt {
        let r: HaltResponse = try await send("POST", haltPath(id), body: [String: String]())
        return r.halt
    }

    /// What deleting the account in tafs cloud did (kiff/tafs#185): done, or the step that
    /// failed, which asking again goes on from.
    struct AccountDeletion: Decodable {
        let done: Bool
        let failed: String?
    }

    /// Erases every taf, and what tafs cloud and kiff/agents keep for the account
    /// (kiff/tafs#185). Only someone who may delete the account can.
    func deleteAccount() async throws -> AccountDeletion {
        try await send("POST", "/v1/account/delete", body: [String: String]())
    }

    func halted(_ id: String?) async throws -> Halt {
        let r: HaltResponse = try await send("GET", haltPath(id))
        return r.halt
    }

    /// Lets kiff/agents run it again, once KIFF has resumed it: the phone resumes it in KIFF
    /// first, signed with Face ID (`KIFFClient.resume`), and tafs cloud checks that.
    func releaseHalt(_ id: String?) async throws -> Halt {
        let r: HaltResponse = try await send("DELETE", haltPath(id))
        return r.halt
    }

    func runs(_ id: String) async throws -> [Run] {
        struct Response: Decodable { let runs: [Run] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/runs")
        return r.runs
    }

    func report(_ id: String, session: String) async throws -> Report {
        struct Response: Decodable { let report: Report }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/runs/\(escaped(session))")
        return r.report
    }

    // MARK: Chat

    /// One line of a taf's thread: the owner's messages and the taf's replies and reports.
    struct ChatMessage: Decodable, Equatable, Identifiable {
        enum From: String, Decodable { case owner, taf }
        enum Kind: String, Decodable { case kickoff, message }
        /// A taf's (or Setup's) message is the same message while its text grows: tafs cloud
        /// writes a turn's words as one message, so a reload can bring more of it. Keyed by its
        /// words, it would show as a new message and be revealed again from nothing, its cards
        /// held back meanwhile (kiff/tafs#138, item 48). An owner's message is tafs cloud's id
        /// for it where there is one, so two with the same words at the same moment stay two;
        /// else (a steer, an older tafs cloud) its words are in its id.
        var id: String {
            if from == .taf { return "\(session)|\(at.timeIntervalSince1970)|taf|\(kind.rawValue)" }
            if let serverID { return "\(session)|owner|\(serverID)" }
            return "\(session)|\(at.timeIntervalSince1970)|\(from.rawValue)|\(text.hashValue)|\(attachments.map(\.id).joined(separator: ","))"
        }
        let at: Date
        let from: From
        let kind: Kind
        let text: String
        let session: String
        /// It came through one of the taf's doors, or answers one that did.
        var door: DoorMark? = nil
        /// What the taf did before writing it (kiff/tafs#285): how long it worked, and the
        /// tools it called. Nil for a message written without calling any.
        var work: Work? = nil
        /// The files the person attached to their message (kiff/tafs#209).
        var attachments: [AttachmentRef] = []
        /// tafs cloud's id for it: from the thread, or, on one being sent, the id its send
        /// answered (a run's first owner message is `<run>.in`). None from an older tafs cloud,
        /// and on a message that steers a run at work.
        var serverID: String? = nil
        /// The card this owner's message answered, by id: they wrote while it waited, and
        /// their words were its answer.
        var answers: String? = nil
        /// On a job run's report: where its result went, in tafs cloud's words ("Sent to Google
        /// Sheets · Refunds 2026"). Drawn as written, above the taf's words. None where tafs
        /// cloud sends none, or a blank one.
        var delivered: String? = nil
        /// On a run's last message: what the run did, counted from the record, in tafs cloud's
        /// words ("From the record: 5 done · 1 refused"). Drawn as written, under the taf's
        /// words. The phone counts nothing: none where tafs cloud sends none, or a blank one.
        var counted: String? = nil
        /// A connection its work asked for that the connection server refused, not connected
        /// since (docs/chat.md): tafs cloud's lines, drawn under the taf's words, never folded.
        var refused: Refused? = nil

        init(at: Date, from: From, kind: Kind = .message, text: String, session: String, door: DoorMark? = nil, work: Work? = nil,
             attachments: [AttachmentRef] = [], serverID: String? = nil, answers: String? = nil, delivered: String? = nil, counted: String? = nil,
             refused: Refused? = nil) {
            self.at = at; self.from = from; self.kind = kind; self.text = text; self.session = session; self.door = door; self.work = work
            self.attachments = attachments; self.serverID = serverID; self.answers = answers
            self.delivered = Self.line(delivered); self.counted = Self.line(counted)
            self.refused = refused.flatMap { $0.lines.isEmpty ? nil : $0 }
        }

        /// A refused connection's lines, each tafs cloud's words: the step's ("Coach couldn't
        /// connect Shippo yet"), the server's reason ("Why: …"), and what to do next.
        struct Refused: Decodable, Equatable {
            var words: String
            var why: String? = nil
            var next: String
            init(words: String, why: String? = nil, next: String) {
                self.words = words; self.why = ChatMessage.line(why); self.next = next
            }
            enum CodingKeys: String, CodingKey { case words, why, next }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                words = (try? c.decodeIfPresent(String.self, forKey: .words)) ?? ""
                why = ChatMessage.line(try? c.decodeIfPresent(String.self, forKey: .why))
                next = (try? c.decodeIfPresent(String.self, forKey: .next)) ?? ""
            }
            /// Its lines, in order, the blank ones left out.
            var lines: [String] { [words, why ?? "", next].compactMap { ChatMessage.line($0) } }
        }

        /// The refused connection the chat draws under a taf's words: none on an owner's message
        /// or a run's kickoff.
        var refusedLines: Refused? { from == .taf && kind != .kickoff ? refused : nil }

        /// One of tafs cloud's lines as the app draws it: as written, without the space around it;
        /// none for a blank one.
        static func line(_ words: String?) -> String? {
            guard let trimmed = words?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
            return trimmed
        }

        /// tafs cloud's lines around a taf's words, as the chat draws them: where the run's result
        /// went above, what the run did under. An owner's message and a run's kickoff have none.
        var runLines: (above: String?, under: String?) {
            guard from == .taf, kind != .kickoff else { return (nil, nil) }
            return (delivered, counted)
        }

        enum CodingKeys: String, CodingKey { case at, from, kind, text, session, door, work, attachments, answers, delivered, counted, refused, serverID = "id" }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            at = try c.decode(Date.self, forKey: .at)
            from = try c.decode(From.self, forKey: .from)
            kind = (try? c.decode(Kind.self, forKey: .kind)) ?? .message
            text = try c.decode(String.self, forKey: .text)
            session = try c.decode(String.self, forKey: .session)
            // A mark the app can't read leaves the message as a plain one.
            door = try? c.decodeIfPresent(DoorMark.self, forKey: .door)
            work = try? c.decodeIfPresent(Work.self, forKey: .work)
            attachments = (try? c.decodeIfPresent([AttachmentRef].self, forKey: .attachments)) ?? []
            serverID = try? c.decodeIfPresent(String.self, forKey: .serverID)
            answers = try? c.decodeIfPresent(String.self, forKey: .answers)
            // A line the app can't read as words leaves the message without it.
            delivered = Self.line(try? c.decodeIfPresent(String.self, forKey: .delivered))
            counted = Self.line(try? c.decodeIfPresent(String.self, forKey: .counted))
            refused = (try? c.decodeIfPresent(Refused.self, forKey: .refused)).flatMap { $0.lines.isEmpty ? nil : $0 }
        }
    }

    // MARK: Attachments (kiff/tafs#209)

    /// A file the person attached to a message, as the thread names it: never its bytes.
    struct AttachmentRef: Codable, Equatable, Hashable, Identifiable {
        let id: String
        /// How the model reads it: "image", "pdf", "text", "word" or "excel".
        let kind: String
        let mediaType: String
        let name: String
        let size: Int
        enum CodingKeys: String, CodingKey { case id, kind, name, size, mediaType = "media_type" }
    }

    /// Where a file's bytes go: a PUT to `url` with `headers`, sent as given, straight to
    /// storage, never through tafs cloud. It works for a few minutes.
    struct Upload: Decodable, Equatable {
        let url: URL
        let method: String
        let headers: [String: String]
    }

    /// Declares a file the person will attach to a message in their chat with the taf (or
    /// Coach): tafs cloud answers where to upload it. A file over its limit is refused here,
    /// before any upload, and the refusal says which limit.
    func attach(_ id: String, mediaType: String, name: String, size: Int, sha256: String) async throws -> (AttachmentRef, Upload) {
        struct Body: Encodable {
            let mediaType: String, name: String, size: Int, sha256: String
            enum CodingKeys: String, CodingKey { case name, size, sha256, mediaType = "media_type" }
        }
        struct Response: Decodable { let attachment: AttachmentRef; let upload: Upload }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/attachments", body: Body(mediaType: mediaType, name: name, size: size, sha256: sha256))
        return (r.attachment, r.upload)
    }

    /// Uploads a file's bytes where `attach` said: no KIFF session goes with them.
    func upload(_ data: Data, to upload: Upload) async throws {
        var request = URLRequest(url: upload.url)
        request.httpMethod = upload.method
        request.timeoutInterval = 120
        for (name, value) in upload.headers { request.setValue(value, forHTTPHeaderField: name) }
        let (_, response) = try await session.upload(for: request, from: data)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw CloudError(status: status, message: Words.Error.fileNotUploaded)
        }
    }

    /// A file the person attached, downloaded: only their own; another's is a 404.
    func attachment(_ id: String, file: String) async throws -> Data {
        struct Response: Decodable { let url: URL }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/attachments/\(escaped(file))")
        let (data, response) = try await session.data(from: r.url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw CloudError(status: status, message: Words.Chat.File.couldNotOpen) }
        return data
    }

    /// How long a taf worked on a message, and the tools it called, in order, by name.
    struct Work: Decodable, Equatable {
        struct Step: Decodable, Equatable {
            let tool: String
            var error = false
            /// Refused back unmade, an act sent beside a read: not a failure.
            var notMade = false
            init(tool: String, error: Bool = false, notMade: Bool = false) { self.tool = tool; self.error = error; self.notMade = notMade }
            enum CodingKeys: String, CodingKey { case tool, error, notMade = "not_made" }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                tool = try c.decode(String.self, forKey: .tool)
                error = (try? c.decodeIfPresent(Bool.self, forKey: .error)) ?? false
                notMade = (try? c.decodeIfPresent(Bool.self, forKey: .notMade)) ?? false
            }
        }
        let ms: Int
        let steps: [Step]
    }

    /// A door's message or its answer (docs/doors.md): which door, who wrote and the
    /// email's subject; on the answer, whether it went back.
    struct DoorMark: Decodable, Equatable {
        let app: String
        var from: String? = nil
        var subject: String? = nil
        /// "sent", "not_sent" or "pending"; nil on the message itself.
        var reply: String? = nil
        var why: String? = nil
    }

    /// The taf's thread, oldest first.
    /// A taf's thread, oldest first, and the session it's at work in now, if any.
    struct Thread: Equatable {
        let messages: [ChatMessage]
        let working: String?
        /// The run at work in `working`.
        var workingRun: String? = nil
        /// What the owner wrote to the latest run that it hasn't read, as kiff/agents says.
        var steering: Steering? = nil
        /// The latest run stopped without finishing (`cost_cap`, `turn_cap`, `max_token` or
        /// `refused`), with no words for it: the app says why.
        var capped: Capped? = nil
        /// The taf's `ask_coach` cards, the settings it hands to Coach (kiff/tafs#476, gap 15):
        /// the only cards of a taf's own thread the app draws.
        var askCoach: [AskCoachCard] = []
    }

    /// A taf's card handing a setting to Coach (kiff/tafs#476, gap 15): the owner's request in
    /// the taf's words, which Ask Coach writes into Coach's box on that taf.
    struct AskCoachCard: Equatable, Identifiable {
        let id: String
        let at: Date
        let session: String
        let text: String

        /// One card of a thread's `components`: an `ask_coach` one with words, else nil. One the
        /// app can't read is nil too, never the thread with it.
        struct Lenient: Decodable {
            let card: AskCoachCard?
            init(from decoder: Decoder) throws {
                struct Component: Decodable { let type: String; let id: String; let text: String? }
                struct Shown: Decodable { let at: Date; let session: String?; let component: Component }
                guard let shown = try? Shown(from: decoder), shown.component.type == "ask_coach",
                      let text = shown.component.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { card = nil; return }
                card = AskCoachCard(id: shown.component.id, at: shown.at, session: shown.session ?? "", text: text)
            }
        }
    }

    /// A run that stopped at its cost or turn cap, as tafs cloud reports it.
    struct Capped: Decodable, Equatable {
        let run: String
        let stop: String
    }

    /// What the owner wrote to a run that it hasn't read (kiff/agents#102): while the run
    /// goes, it reads them at its next step; once it's over, they were never read.
    struct Steering: Decodable, Equatable {
        struct Message: Decodable, Equatable {
            let text: String
            let at: Date
        }
        /// What a stopped run never read, with why, in the words the app shows as they are.
        struct NotSent: Decodable, Equatable {
            let text: String
            let at: Date
            let why: String
            var message: Message { Message(text: text, at: at) }
        }
        let run: String
        let status: String
        let untaken: [Message]
        /// What waits in this chat to go once the taf is done (docs/chat.md, `hold`).
        var held: Message?
        /// What a stopped run never read, and what was written for it by the stop.
        var notSent: [NotSent] = []

        init(run: String, status: String, untaken: [Message], held: Message? = nil, notSent: [NotSent] = []) {
            self.run = run; self.status = status; self.untaken = untaken; self.held = held; self.notSent = notSent
        }

        enum CodingKeys: String, CodingKey { case run, status, untaken, held, notSent = "not_sent" }
        private struct LenientNotSent: Decodable {
            let notSent: NotSent?
            init(from decoder: Decoder) throws { notSent = try? NotSent(from: decoder) }
        }
        /// A message the app can't read (its time) is left out, not the others.
        private struct LenientMessage: Decodable {
            let message: Message?
            init(from decoder: Decoder) throws { message = try? Message(from: decoder) }
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            run = try c.decode(String.self, forKey: .run)
            status = try c.decode(String.self, forKey: .status)
            untaken = try c.decode([LenientMessage].self, forKey: .untaken).compactMap(\.message)
            held = try? c.decodeIfPresent(Message.self, forKey: .held)
            notSent = (try? c.decodeIfPresent([LenientNotSent].self, forKey: .notSent))?.compactMap(\.notSent) ?? []
        }

        /// The run is over: what it didn't read, it never will.
        var over: Bool { ["COMPLETED", "ERROR", "CANCELLED"].contains(status) }
    }

    func conversation(_ id: String) async throws -> Thread {
        try await thread("/v1/tafs/\(escaped(id))/conversation")
    }

    /// A thread as tafs cloud reads it: the taf's, or a trial's (kiff/tafs#173).
    private func thread(_ path: String) async throws -> Thread {
        struct Working: Decodable { let session: String; let run: String? }
        /// One the app can't read is left out, never the thread with it.
        struct Lenient: Decodable {
            let steering: Steering?
            init(from decoder: Decoder) throws { steering = try? Steering(from: decoder) }
        }
        /// A cap the app can't read is left out too.
        struct LenientCap: Decodable {
            let capped: Capped?
            init(from decoder: Decoder) throws { capped = try? Capped(from: decoder) }
        }
        struct Response: Decodable {
            let messages: [ChatMessage]; let working: Working?; let steering: Lenient?; let capped: LenientCap?
            /// Where the latest run stopped without finishing, any stop tafs cloud names (`turn_cap`,
            /// `cost_cap`, `max_token`, `refused`); `capped` carries the two caps alone, for older apps.
            var stopped: LenientCap? = nil
            var components: [AskCoachCard.Lenient]? = nil
        }
        let r: Response = try await send("GET", path)
        return Thread(messages: r.messages, working: r.working?.session, workingRun: r.working?.run, steering: r.steering?.steering,
                      capped: r.stopped?.capped ?? r.capped?.capped, askCoach: (r.components ?? []).compactMap(\.card))
    }

    /// What tafs cloud did with a message written to the run at work.
    enum Steered: Equatable {
        /// The run took it: it reads it at its next step (a paused run, when it carries on).
        case taken(status: String)
        /// The run didn't take it, and tafs cloud kept it (docs/chat.md, `hold`): `sent`, the
        /// chat's run was over and it went at once as the person's next message; `held`, what
        /// waits in that chat now, to go once the taf is done. Both can come at once.
        case kept(sent: SentRun?, held: Steering.Message?)
    }

    struct SentRun: Decodable, Equatable { let run: String; let session: String }

    /// Writes to the run at work (kiff/tafs#64), once, with `hold`: what the run doesn't
    /// take, tafs cloud holds and sends once the taf is done, so the app never resends it.
    /// More than tafs cloud holds throws 409 `held_full`, with words to show as they are.
    func steer(_ id: String, run: String, text: String) async throws -> Steered {
        struct Body: Encodable { let run: String; let text: String; let hold = true }
        struct Response: Decodable { let status: String?; let sent: SentRun?; let held: Steering.Message? }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/steer", body: Body(run: run, text: text))
        if let status = r.status { return .taken(status: status) }
        return .kept(sent: r.sent, held: r.held)
    }

    /// The owner just answered one of the taf's held calls in KIFF (kiff/tafs#206): the
    /// run that paused on it goes on now, not when KIFF's alert reaches it. tafs cloud
    /// asks KIFF for the answer itself, so this only asks sooner. Returns the run's
    /// status, or "waiting" while KIFF still holds one of its calls.
    @discardableResult
    func continueHeld(_ id: String, exception: String) async throws -> String {
        struct Response: Decodable { let status: String }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/holds/\(escaped(exception))/continue")
        return r.status
    }

    struct Sent: Decodable, Equatable {
        let session: String
        let newSession: Bool
        /// The message the send made, as the thread will name it: the app matches its send to
        /// the thread by its id, never by its words. None from an older tafs cloud.
        var message: SentMessage? = nil
        enum CodingKeys: String, CodingKey { case session, message, newSession = "new_session" }
    }

    struct SentMessage: Decodable, Equatable {
        let id: String
    }

    /// Which chat a message goes into (kiff/tafs#73): the live one, one the owner
    /// reopened, or a new one.
    struct ChatTarget: Equatable {
        var session: String?
        var newChat = false
        static let live = ChatTarget()
        static let new = ChatTarget(newChat: true)
    }

    /// A message as tafs cloud takes it: the live chat unless it names one or asks for a new one.
    private struct MessageBody: Encodable {
        let text: String
        let session: String?
        let newChat: Bool?
        /// The files attached to it, by the ids their uploads gave (kiff/tafs#209).
        let attachments: [String]?
        enum CodingKeys: String, CodingKey { case text, session, attachments, newChat = "new_chat" }

        init(_ text: String, _ target: ChatTarget, files: [AttachmentRef] = []) {
            self.text = text
            session = target.session
            newChat = target.newChat ? true : nil
            attachments = files.isEmpty ? nil : files.map(\.id)
        }
    }

    /// Opens a new conversation with the account's Setup on this taf (kiff/tafs#107): tafs
    /// cloud puts the taf's job, Card and latest calls in front of the owner's words.
    func improve(_ id: String, text: String) async throws -> Sent {
        try await send("POST", "/v1/tafs/\(escaped(id))/improve", body: ["text": text])
    }

    /// A card's answer, as tafs cloud takes it: the run that waited on it goes on.
    struct CardAnswered: Decodable, Equatable {
        let session: String
        let run: String
        let status: String
    }

    /// A card's answer as data: a string (a confirm's option value, an approval's or a
    /// sign-in's outcome), or a questions card's answer to each question.
    enum CardAnswerValue: Encodable, Equatable {
        case text(String)
        case questions([String: QuestionAnswer])

        struct QuestionAnswer: Codable, Equatable {
            var value: String?
            var values: [String]?
            var own: String?
        }

        func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            switch self {
            case .text(let s): try c.encode(s)
            case .questions(let q): try c.encode(q)
            }
        }
    }

    /// The owner's answer to a card of theirs that waits for it, as data
    /// (`POST /v1/tafs/{id}/components/{component}/answer`): kiff/agents checks it against the
    /// card, and the run goes on with it. A 409 says the card doesn't wait any more.
    func answerCard(_ id: String, component: String, answer: CardAnswerValue, session: String?) async throws -> CardAnswered {
        struct Body: Encodable { let answer: CardAnswerValue; let session: String? }
        return try await send("POST", "/v1/tafs/\(escaped(id))/components/\(escaped(component))/answer",
                              body: Body(answer: answer, session: session))
    }

    /// tafs cloud's decision on an approval card (kiff/tafs#413). `outcome` is `answered` (Coach
    /// heard `answer` from tafs cloud; `note` is the card's line), `read_again` (the card is out of
    /// date: `note` says so; nothing was told) or `sign` (`ask`: the one message to sign and the
    /// call it goes into).
    struct ApprovalDecision: Decodable, Equatable {
        /// One message for the device's key, and the call it goes into: planque's approve body,
        /// or KIFF's draft to confirm.
        struct Ask: Decodable, Equatable {
            /// planque's approve, its body as tafs cloud built it: the phone sends it as it is.
            struct Planque: Decodable, Equatable { let grant: String; let body: PlanqueClient.ApproveBody }
            /// KIFF's draft to confirm, at exactly this version.
            struct KIFF: Decodable, Equatable { let draft: String; let version: String }
            let id: String
            /// `connect` or `cards`.
            let step: String
            /// The exact bytes the device's key signs, under Face ID.
            let message: String
            /// What the prompt says.
            let reason: String
            let planque: Planque?
            let kiff: KIFF?
        }
        let outcome: String
        let answer: String?
        let note: String?
        let ask: Ask?
        let session: String?
        let run: String?
    }

    /// Approve tapped on an approval card: what the card showed, so tafs cloud compares it with
    /// what planque and KIFF hold now, and KIFF's words for the connection as this phone read them.
    struct ApprovalAskBody: Encodable, Equatable {
        struct Words: Encodable, Equatable {
            let profileSHA256: String
            let wordsSHA256: String
            enum CodingKeys: String, CodingKey { case profileSHA256 = "profile_sha256", wordsSHA256 = "words_sha256" }
        }
        let grant: String
        var shownDigest: String? = nil
        var draft: String? = nil
        var shownDraft: String? = nil
        var words: Words? = nil
        var session: String? = nil
        /// The steps this phone can sign beyond connect and cards (`key_and_cards`): tafs cloud
        /// never answers an app a step it didn't say it can act on.
        var can: [String]? = nil
        enum CodingKeys: String, CodingKey { case grant, shownDigest = "shown_digest", draft, shownDraft = "shown_draft", words, session, can }
    }

    /// What came of the call made with the signature tafs cloud asked for: never an answer, only
    /// what happened, in tafs cloud's own terms.
    struct ApprovalReportBody: Encodable, Equatable {
        let result: String
        var status: String? = nil
        var why: String? = nil
        var session: String? = nil
    }

    /// Approve tapped (kiff/tafs#413): tafs cloud reads planque's grant as this phone (the signed
    /// read `proof`, forwarded) and KIFF's draft, and decides. See `ApprovalDecision`.
    func approval(_ id: String, component: String, body: ApprovalAskBody, proof: String?) async throws -> ApprovalDecision {
        try await send("POST", "/v1/tafs/\(escaped(id))/components/\(escaped(component))/approval", body: body,
                       headers: proof.map { ["Planque-Device-Signature": $0] } ?? [:])
    }

    /// What came of the call, signed as a setup call to KIFF (`kiff.request.v1` over this request,
    /// in `KIFF-Device-Signature`): KIFF tells tafs cloud this phone signed it, and planque or KIFF
    /// what holds. tafs cloud answers Coach, and says what it answered.
    func approvalReport(_ id: String, component: String, ask: String, body: ApprovalReportBody, proof: String?, tenant: String,
                        signedBy sign: (Data) throws -> String) async throws -> ApprovalDecision {
        let path = "/v1/tafs/\(escaped(id))/components/\(escaped(component))/approval/\(escaped(ask))"
        let payload = try JSONEncoder().encode(body)
        let signedAt = SigningTime.next()
        let signature = try sign(KIFFClient.requestMessage(tenant: tenant, method: "POST", uri: path, body: payload, signedAt: signedAt))
        var headers = ["KIFF-Device-Signature": "t=\(signedAt),v1=\(signature)"]
        if let proof { headers["Planque-Device-Signature"] = proof }
        return try KIFFClient.decoder.decode(ApprovalDecision.self, from: try await sendData("POST", path, body: KIFFClient.RawJSON(data: payload), headers: headers))
    }

    /// Sends the owner's message: into the taf's current run, or a new one.
    func sendMessage(_ id: String, text: String, to target: ChatTarget = .live, files: [AttachmentRef] = []) async throws -> Sent {
        try await send("POST", "/v1/tafs/\(escaped(id))/messages", body: MessageBody(text, target, files: files))
    }

    /// Why a person reports an answer Coach or a taf wrote (kiff/tafs#421).
    enum ReportReason: String, CaseIterable, Encodable, Identifiable {
        case offensive, harmful, wrong, other
        var id: String { rawValue }
        var title: String {
            switch self {
            case .offensive: return Words.Report.Reason.offensive
            case .harmful: return Words.Report.Reason.harmful
            case .wrong: return Words.Report.Reason.wrong
            case .other: return Words.Report.Reason.other
            }
        }
    }

    /// A report tafs cloud keeps; `new` is false when the person had reported this answer already.
    struct AnswerReport: Decodable, Equatable {
        let id: String
        let at: String
        let new: Bool
    }

    /// Reports one of the taf's answers (or Coach's, by Coach's taf id) by its id, with the
    /// chat or trial it's in. tafs cloud keeps the answer as kiff/agents holds it and a person
    /// at KIFF reads it (kiff/tafs#421, Google Play's rule for content an AI generates).
    func reportAnswer(_ id: String, message: String, reason: ReportReason, note: String?, session: String?) async throws -> AnswerReport {
        struct Body: Encodable { let reason: ReportReason; let note: String?; let session: String? }
        struct Response: Decodable { let report: AnswerReport }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/messages/\(escaped(message))/report",
                                         body: Body(reason: reason, note: note, session: session))
        return r.report
    }

    /// The person's own chats, trials and notes in tafs, for their rights of access and
    /// portability (kiff/tafs#424): `json`, the answer without `readable`, and `text`, the same
    /// as text. Kept as tafs cloud wrote them; never another person's.
    func exportChats() async throws -> (json: Data, text: String) {
        let data = try await sendData("GET", "/v1/me/chats/export", body: Optional<[String: String]>.none)
        return try Self.splitExport(data)
    }

    /// Splits tafs cloud's export into its two files.
    static func splitExport(_ data: Data) throws -> (json: Data, text: String) {
        guard var body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CloudError(status: 0, message: Words.Chat.exportNotWhatTafsCloud)
        }
        let text = body.removeValue(forKey: "readable") as? String ?? ""
        let json = try JSONSerialization.data(withJSONObject: body, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        return (json, text)
    }

    /// One of a taf's chats, to reopen (kiff/tafs#73): named by its first words.
    struct Chat: Decodable, Equatable, Identifiable {
        let session: String
        let started: Date
        let updated: Date
        let title: String
        var id: String { session }
    }

    /// The taf's chats, newest first.
    func chats(_ id: String) async throws -> [Chat] {
        struct Response: Decodable { let chats: [Chat] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/chats")
        return r.chats
    }

    /// Archives one of the taf's chats (kiff/tafs#82): tafs cloud leaves it out of `chats`
    /// and the thread from then on. It isn't deleted.
    func archiveChat(_ id: String, session: String) async throws {
        struct Response: Decodable { let archived: Bool }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/chats/\(escaped(session))/archive")
    }

    /// The chats the owner archived, to find and reopen one (kiff/tafs#97).
    func archivedChats(_ id: String) async throws -> [Chat] {
        struct Response: Decodable { let chats: [Chat] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/chats/archived")
        return r.chats
    }

    /// Puts an archived chat back in the list (kiff/tafs#97).
    func unarchiveChat(_ id: String, session: String) async throws {
        struct Response: Decodable { let archived: Bool }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/chats/\(escaped(session))/unarchive")
    }

    /// A call a run made that acted and went through (kiff/agents#249).
    struct DoneCall: Equatable {
        let tool: String
        /// What it acted on (a payment's id), when KIFF named it.
        let resource: String?
        /// KIFF's key for the call, its Record line's: where the amount it moved is read.
        var invocation: String? = nil
    }

    /// What a streamed message's run does, as tafs cloud relays it (#60).
    enum StreamEvent: Equatable {
        /// The chat the message went into, first, and the run that answers it.
        /// `message`: tafs cloud's id for the owner's message this stream answers (as
        /// `POST …/messages` gives it); none on a steer, or from an older tafs cloud.
        case session(String, newSession: Bool, run: String? = nil, message: String? = nil)
        /// More of the answer, as it is written.
        case text(String)
        /// A tool the agent calls, by name, with what it's doing in a few words when tafs
        /// cloud says ("Checking your Stripe connection…"): never what it was given. `call`
        /// is the call's id, which its `step` names when it ends (kiff/tafs#285).
        case activity(String, step: String? = nil, call: String? = nil)
        /// How a call ended: "done", "error" or "not_made", refused back unmade beside a read
        /// (kiff/tafs#285). A held call is `held`.
        case step(call: String, status: String)
        /// More of the summary of what the taf is thinking (kiff/tafs#285). Kept, never drawn:
        /// the live line (`state`) says what it's doing.
        case thinking(String)
        /// The one line to show while it works (docs/chat.md, "The live line"): `thinking`,
        /// `working` with the step's words or Coach's own progress note, `waiting_for_you` or
        /// `done`. tafs cloud paces it, so the app draws it as it comes.
        case state(String, words: String? = nil)
        /// A run picked back up wrote a message: the conversation has it (kiff/tafs#285).
        case said
        /// A run picked back up went on after a pause, a held call the owner answered: what it
        /// did before is the conversation's, with its own work (the 859 test).
        case part
        /// The agent showed a component: the conversation has it.
        case component
        /// The run stopped: its status, and why. A failed run also says what went through
        /// before it stopped (kiff/agents#249): each call that acted, by tool and what it
        /// acted on. `code` and `words` are tafs cloud's, on a run that failed for a reason it
        /// names (`ai_unavailable`): said as they are.
        case end(status: String, stop: String, done: [DoneCall] = [], code: String? = nil, words: String? = nil)
        /// The stream broke on tafs cloud's side; the conversation has the rest.
        case broken
        /// The run read a message the owner wrote to it while it went (kiff/tafs#64).
        case taken
        /// KIFF holds the call just made for the owner, at this point of the answer
        /// (kiff/tafs#206): KIFF's id for the owner's decision, and for the call.
        case held(exception: String, invocation: String)
    }

    /// Sends the owner's message with its run streamed: returns once tafs cloud took it,
    /// with the run's events to follow. A refusal throws, as `sendMessage` does.
    func streamMessage(_ id: String, text: String, to target: ChatTarget = .live, files: [AttachmentRef] = []) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        guard let url = URL(string: "/v1/tafs/\(escaped(id))/messages/stream", relativeTo: streamURL) else {
            throw CloudError(status: 0, message: Words.Error.badPath)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // A run can think for minutes between two words.
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(MessageBody(text, target, files: files))
        guard let token else { throw CloudError(status: 401, message: Words.Error.signInFirst) }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        return try await events(request)
    }

    /// Picks a run of the person's back up (kiff/tafs#285): its steps so far, then the rest
    /// as it goes, until it stops. Only their own run; another's is a 404.
    func resumeRun(_ id: String, run: String, after: Int = 0) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        guard let url = URL(string: "/v1/tafs/\(escaped(id))/runs/\(escaped(run))/stream?after=\(max(0, after))", relativeTo: streamURL) else {
            throw CloudError(status: 0, message: Words.Error.badPath)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        guard let token else { throw CloudError(status: 401, message: Words.Error.signInFirst) }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try await events(request)
    }

    /// A stream's events, once tafs cloud answered 200; a refusal throws.
    private func events(_ request: URLRequest) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            var data = Data()
            for try await b in bytes { data.append(b) }
            throw Self.refusal(status, data)
        }
        return AsyncThrowingStream { continuation in
            let reading = Task {
                do {
                    var name = "message"
                    for try await line in bytes.lines {
                        if line.hasPrefix("event:") {
                            name = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                        } else if line.hasPrefix("data:"), let e = Self.streamEvent(name, data: Data(line.dropFirst(5).utf8)) {
                            continuation.yield(e)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in reading.cancel() }
        }
    }

    /// One event of tafs cloud's stream, from its name and its line of JSON; nil when unknown.
    nonisolated static func streamEvent(_ name: String, data: Data) -> StreamEvent? {
        let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        switch name {
        case "session":
            return (o["session"] as? String).map {
                .session($0, newSession: o["new_session"] as? Bool ?? false, run: o["run"] as? String,
                         message: (o["message"] as? [String: Any])?["id"] as? String)
            }
        case "taken": return .taken
        case "text": return (o["text"] as? String).map(StreamEvent.text)
        case "activity": return (o["tool"] as? String).map { .activity($0, step: o["step"] as? String, call: o["call_id"] as? String) }
        case "step": return (o["call_id"] as? String).map { .step(call: $0, status: o["status"] as? String ?? "done") }
        case "thinking": return (o["text"] as? String).map(StreamEvent.thinking)
        case "state":
            return (o["state"] as? String).map { .state($0, words: (o["words"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }) }
        case "said": return .said
        case "part": return .part
        case "component": return .component
        case "end":
            let done = (o["done"] as? [[String: Any]] ?? []).compactMap { c in
                (c["tool"] as? String).map { DoneCall(tool: $0, resource: c["resource"] as? String, invocation: c["invocation"] as? String) }
            }
            let said = { (key: String) in (o[key] as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } }
            return .end(status: o["status"] as? String ?? "", stop: o["stop"] as? String ?? "", done: done, code: said("code"), words: said("words"))
        case "error": return .broken
        case "held": return .held(exception: o["exception"] as? String ?? "", invocation: o["invocation"] as? String ?? "")
        default: return nil
        }
    }

    // MARK: Setup (kiff/agents#91)

    /// A component as the conversation lists it, with its answer once kiff/agents recorded one.
    /// One the app doesn't know is skipped.
    struct Shown: Decodable, Equatable {
        let at: Date
        let session: String
        let run: String
        let component: SetupComponent
        var answer: CardAnswer?
        /// tafs cloud's key for the card, unique in the thread (kiff/tafs#386); none from a
        /// tafs cloud that doesn't send one.
        var key: String? = nil

        enum CodingKeys: String, CodingKey { case at, session, run, component, answer, key }

        init(at: Date, session: String, run: String, component: SetupComponent, answer: CardAnswer? = nil, key: String? = nil) {
            self.at = at
            self.session = session
            self.run = run
            self.component = component
            self.answer = answer
            self.key = key
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            at = try c.decode(Date.self, forKey: .at)
            session = try c.decode(String.self, forKey: .session)
            run = try c.decode(String.self, forKey: .run)
            component = try c.decode(SetupComponent.self, forKey: .component)
            // An answer the app can't read leaves the card unanswered, never the card out.
            answer = try? c.decodeIfPresent(CardAnswer.self, forKey: .answer)
            key = try? c.decodeIfPresent(String.self, forKey: .key)
        }
    }

    /// A card's answer as kiff/agents recorded it: `by` is "tafs cloud" when tafs cloud answered a
    /// result itself, with its `outcome`: the taf it set up, the version it made, or why it didn't.
    struct CardAnswer: Decodable, Equatable {
        var state = "answered"
        var words = ""
        var by: String?
        var outcome: Outcome?
        /// When it was answered, as tafs cloud writes it; nil from a tafs cloud that doesn't say.
        var at: Date?

        struct Outcome: Decodable, Equatable {
            var tafID: String?
            var version: Int?
            var failed: String?
            enum CodingKeys: String, CodingKey { case version, failed, tafID = "taf_id" }
        }

        enum CodingKeys: String, CodingKey { case state, words, by, outcome, at }

        init(state: String = "answered", words: String = "", by: String? = nil, outcome: Outcome? = nil, at: Date? = nil) {
            self.state = state
            self.words = words
            self.by = by
            self.outcome = outcome
            self.at = at
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            state = try c.decodeIfPresent(String.self, forKey: .state) ?? "answered"
            words = try c.decodeIfPresent(String.self, forKey: .words) ?? ""
            by = try c.decodeIfPresent(String.self, forKey: .by)
            outcome = try? c.decodeIfPresent(Outcome.self, forKey: .outcome)
            at = (try? c.decodeIfPresent(String.self, forKey: .at)).flatMap { $0 }.flatMap(Self.date)
        }

        private static func date(_ raw: String) -> Date? {
            let precise = ISO8601DateFormatter()
            precise.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return precise.date(from: raw) ?? ISO8601DateFormatter().date(from: raw)
        }
    }

    private struct LenientShown: Decodable {
        let shown: Shown?
        init(from decoder: Decoder) throws { shown = try? Shown(from: decoder) }
    }

    /// The recipes a new Coach conversation opens to (`GET /v1/recipes`, kiff/tafs#476), by
    /// category, as tafs cloud sends them: a recipe is a job and the words to say to Coach. The
    /// app draws them and keeps no list of its own.
    struct Recipes: Decodable, Equatable {
        struct Category: Decodable, Equatable {
            let id: String
            let title: String
            /// The category's colour, tafs cloud's token for light and dark, for its chip and its
            /// cards' edge; nil from a tafs cloud from before colours, or one the app can't read.
            var color: Tint? = nil
            private enum CodingKeys: String, CodingKey { case id, title, color }
            init(id: String, title: String, color: Tint? = nil) { self.id = id; self.title = title; self.color = color }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                title = try c.decode(String.self, forKey: .title)
                color = (try? c.decodeIfPresent(Tint.self, forKey: .color)) ?? nil
            }
        }
        /// A colour as tafs cloud sends it, `#RRGGBB` for light and for dark. Either one not
        /// readable, there's no colour: the app never guesses one.
        struct Tint: Decodable, Equatable {
            let light: UInt32
            let dark: UInt32
            init(light: UInt32, dark: UInt32) { self.light = light; self.dark = dark }
            private enum CodingKeys: String, CodingKey { case light, dark }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                guard let l = Self.hex(try c.decode(String.self, forKey: .light)),
                      let d = Self.hex(try c.decode(String.self, forKey: .dark)) else {
                    throw DecodingError.dataCorruptedError(forKey: .light, in: c, debugDescription: "not #RRGGBB")
                }
                light = l; dark = d
            }
            /// `#RRGGBB`, and only that.
            static func hex(_ s: String) -> UInt32? {
                guard s.count == 7, s.first == "#" else { return nil }
                let digits = s.dropFirst()
                guard digits.allSatisfy(\.isHexDigit) else { return nil }
                return UInt32(digits, radix: 16)
            }
        }
        /// One of the apps a recipe's taf uses, as tafs cloud names it, with its logo's path on
        /// tafs cloud (`/v1/logos/{key}`) when there is one.
        struct App: Decodable, Equatable {
            let name: String
            var logo: String? = nil
            private enum CodingKeys: String, CodingKey { case name, logo }
            init(name: String, logo: String? = nil) { self.name = name; self.logo = logo }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                name = try c.decode(String.self, forKey: .name)
                logo = (try? c.decodeIfPresent(String.self, forKey: .logo)) ?? nil
            }
        }
        /// The Card a recipe's taf would have, as its file says it: what it does on its own,
        /// asks first, and never does.
        struct Card: Decodable, Equatable {
            var allows: [String] = []
            var holds: [String] = []
            var never: [String] = []
            private enum CodingKeys: String, CodingKey { case allows, holds, never }
            init(allows: [String] = [], holds: [String] = [], never: [String] = []) {
                self.allows = allows; self.holds = holds; self.never = never
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                allows = (try? c.decodeIfPresent([String].self, forKey: .allows)) ?? []
                holds = (try? c.decodeIfPresent([String].self, forKey: .holds)) ?? []
                never = (try? c.decodeIfPresent([String].self, forKey: .never)) ?? []
            }
        }
        /// One recipe: its title and line, and `say`, what a tap puts in Coach's message box for
        /// the person to edit and send. On Home its `headline` (the problem it ends) leads, then
        /// `line`; its sheet adds `today` (what that costs today) and its `card`.
        struct Recipe: Decodable, Equatable, Identifiable {
            let id: String
            let title: String
            var category: String = ""
            var line: String = ""
            var say: String = ""
            var headline: String = ""
            var today: String = ""
            var card = Card()
            /// The apps its taf uses, one per connection, in tafs cloud's order.
            var apps: [App] = []
            /// Every app it needs is connected on the account already.
            var connected = false
            /// A taf on the account was built from it.
            var running = false
            /// tafs cloud's line for a job whose proof run passed ("Proved on a test help desk."),
            /// drawn as sent; empty for a job with none.
            var proved = ""
            /// What the job reported after that run, as tafs cloud sends it; empty with none.
            var sample = ""
            private enum CodingKeys: String, CodingKey { case id, title, category, line, say, headline, today, card, apps, connected, running, proved, sample }
            init(id: String, title: String, category: String = "", line: String = "", say: String = "",
                 headline: String = "", today: String = "", card: Card = Card(), apps: [App] = [],
                 connected: Bool = false, running: Bool = false, proved: String = "", sample: String = "") {
                self.id = id; self.title = title; self.category = category; self.line = line; self.say = say
                self.headline = headline; self.today = today; self.card = card
                self.apps = apps; self.connected = connected; self.running = running
                self.proved = proved; self.sample = sample
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                title = try c.decode(String.self, forKey: .title)
                category = try c.decodeIfPresent(String.self, forKey: .category) ?? ""
                line = try c.decodeIfPresent(String.self, forKey: .line) ?? ""
                say = try c.decodeIfPresent(String.self, forKey: .say) ?? ""
                headline = (try? c.decodeIfPresent(String.self, forKey: .headline)) ?? ""
                today = (try? c.decodeIfPresent(String.self, forKey: .today)) ?? ""
                card = (try? c.decodeIfPresent(Card.self, forKey: .card)) ?? Card()
                apps = ((try? c.decodeIfPresent([LenientItem<App>].self, forKey: .apps)) ?? nil)?.compactMap(\.value) ?? []
                connected = ((try? c.decodeIfPresent(Bool.self, forKey: .connected)) ?? nil) ?? false
                running = ((try? c.decodeIfPresent(Bool.self, forKey: .running)) ?? nil) ?? false
                // Anything but words (a flag, a list) is no line: the sheet draws only what reads.
                proved = (((try? c.decodeIfPresent(String.self, forKey: .proved)) ?? nil) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                sample = (((try? c.decodeIfPresent(String.self, forKey: .sample)) ?? nil) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            }
            /// What leads the recipe on Home and on its sheet: the problem it ends; its title
            /// from a tafs cloud from before headlines.
            var lead: String { headline.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? title : headline }
        }
        /// Home with no taf: `shown`, the recipes are listed under `words`; not shown, `words`
        /// is Home's empty line. `ownWords` ("Say it in your own words") leads Home's list for
        /// someone who manages tafs; nil when tafs cloud sent none.
        struct Home: Decodable, Equatable {
            /// "Say it in your own words" and its line, in the person's language.
            struct OwnWords: Decodable, Equatable {
                var title = ""
                var line = ""
                init(title: String = "", line: String = "") { self.title = title; self.line = line }
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    title = ((try? c.decodeIfPresent(String.self, forKey: .title)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    line = ((try? c.decodeIfPresent(String.self, forKey: .line)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                }
                private enum CodingKeys: String, CodingKey { case title, line }
            }
            var shown = false
            var words = ""
            var ownWords: OwnWords?
            private enum CodingKeys: String, CodingKey { case shown, words, ownWords = "own_words" }
            init(shown: Bool = false, words: String = "", ownWords: OwnWords? = nil) {
                self.shown = shown; self.words = words; self.ownWords = ownWords
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                shown = (try? c.decodeIfPresent(Bool.self, forKey: .shown)) ?? false
                words = (try? c.decodeIfPresent(String.self, forKey: .words)) ?? ""
                // A row with no title isn't one.
                ownWords = (try? c.decodeIfPresent(OwnWords.self, forKey: .ownWords)).flatMap { $0?.title.isEmpty == false ? $0 : nil }
            }
        }
        /// The recipe sheet's words, in the person's language: its three zones and its button.
        struct Labels: Decodable, Equatable {
            var allows = ""
            var holds = ""
            var never = ""
            var hand = ""
            private enum CodingKeys: String, CodingKey { case allows, holds, never, hand }
            init(allows: String = "", holds: String = "", never: String = "", hand: String = "") {
                self.allows = allows; self.holds = holds; self.never = never; self.hand = hand
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                allows = (try? c.decodeIfPresent(String.self, forKey: .allows)) ?? ""
                holds = (try? c.decodeIfPresent(String.self, forKey: .holds)) ?? ""
                never = (try? c.decodeIfPresent(String.self, forKey: .never)) ?? ""
                hand = (try? c.decodeIfPresent(String.self, forKey: .hand)) ?? ""
            }
        }
        /// One zone of a recipe's Card on its sheet: its title and its lines.
        struct Zone: Equatable, Identifiable {
            let id: String
            let title: String
            let lines: [String]
        }
        var categories: [Category] = []
        var recipes: [Recipe] = []
        /// Whether tafs cloud shows them now: only while the account has no live taf and its
        /// suggestions are on; else none come.
        var shown = true
        var suggestions: Suggestions?
        /// What Home shows with no taf; nil from a tafs cloud from before it.
        var home: Home?
        /// The recipe sheet's words; nil from a tafs cloud from before them.
        var labels: Labels?
        private enum CodingKeys: String, CodingKey { case categories, recipes, shown, suggestions, home, labels }
        init(categories: [Category] = [], recipes: [Recipe] = [], shown: Bool = true, suggestions: Suggestions? = nil,
             home: Home? = nil, labels: Labels? = nil) {
            self.categories = categories; self.recipes = recipes; self.shown = shown; self.suggestions = suggestions
            self.home = home; self.labels = labels
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            categories = try c.decodeIfPresent([Category].self, forKey: .categories) ?? []
            recipes = try c.decodeIfPresent([Recipe].self, forKey: .recipes) ?? []
            shown = try c.decodeIfPresent(Bool.self, forKey: .shown) ?? true
            suggestions = try? c.decodeIfPresent(Suggestions.self, forKey: .suggestions)
            home = try? c.decodeIfPresent(Home.self, forKey: .home)
            labels = try? c.decodeIfPresent(Labels.self, forKey: .labels)
        }
        /// Whether Home with no taf lists the recipes: tafs cloud says so (`home.shown`), and
        /// the sheet's words came with them.
        var homeListsRecipes: Bool { home?.shown == true && labels != nil && !byCategory.isEmpty }
        /// "Say it in your own words", leading Home's list; nil when tafs cloud sent none.
        var homeOwnWords: Home.OwnWords? { home?.ownWords }
        /// tafs cloud's line for Home with no taf; nil when it sent none.
        var homeWords: String? {
            guard let w = home?.words.trimmingCharacters(in: .whitespacesAndNewlines), !w.isEmpty else { return nil }
            return w
        }
        /// The categories Home lists, narrowed to `picked` when one is: nothing picked is all of
        /// them, and a category no longer listed narrows nothing.
        func groups(picked: String?) -> [(category: Category, recipes: [Recipe])] {
            let all = byCategory
            guard let picked, all.contains(where: { $0.category.id == picked }) else { return all }
            return all.filter { $0.category.id == picked }
        }
        /// The zones a recipe's sheet draws, in order (does on its own, asks first, never does),
        /// titled by `labels`: a zone its Card leaves empty isn't one.
        func zones(of recipe: Recipe) -> [Zone] {
            let l = labels ?? Labels()
            return [Zone(id: "allows", title: l.allows, lines: recipe.card.allows),
                    Zone(id: "holds", title: l.holds, lines: recipe.card.holds),
                    Zone(id: "never", title: l.never, lines: recipe.card.never)]
                .filter { !$0.lines.isEmpty }
        }
        /// The words of the sheet's button that hands the recipe to Coach; nil when none came.
        var handWords: String? {
            guard let w = labels?.hand.trimmingCharacters(in: .whitespacesAndNewlines), !w.isEmpty else { return nil }
            return w
        }
        /// Each category in tafs cloud's order, with its recipes; a category with none isn't shown.
        var byCategory: [(category: Category, recipes: [Recipe])] {
            categories.map { c in (c, recipes.filter { $0.category == c.id }) }.filter { !$0.recipes.isEmpty }
        }
    }

    /// The account's "Show suggestions" setting, kept in tafs cloud: whether it's on, and tafs
    /// cloud's words for it.
    struct Suggestions: Decodable, Equatable {
        var show = true
        var words = ""
        private enum CodingKeys: String, CodingKey { case show, words }
        init(show: Bool = true, words: String = "") { self.show = show; self.words = words }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            show = try c.decodeIfPresent(Bool.self, forKey: .show) ?? true
            words = try c.decodeIfPresent(String.self, forKey: .words) ?? ""
        }
    }

    func recipes() async throws -> Recipes {
        try await send("GET", "/v1/recipes")
    }

    /// Every ready-made job, reachable at any time (`GET /v1/recipes/gallery`): the full
    /// gallery behind + New, and the short strip under the tafs list. tafs cloud decides what
    /// each shows and in what order; the app draws it.
    struct Gallery: Decodable, Equatable {
        /// The strip under the tafs list: shown or not, its title, and the jobs, in order.
        struct Strip: Decodable, Equatable {
            var shown = false
            var title = ""
            var ids: [String] = []
            private enum CodingKeys: String, CodingKey { case shown, title, ids }
            init(shown: Bool = false, title: String = "", ids: [String] = []) { self.shown = shown; self.title = title; self.ids = ids }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                shown = ((try? c.decodeIfPresent(Bool.self, forKey: .shown)) ?? nil) ?? false
                title = ((try? c.decodeIfPresent(String.self, forKey: .title)) ?? nil) ?? ""
                ids = ((try? c.decodeIfPresent([String].self, forKey: .ids)) ?? nil) ?? []
            }
        }
        /// The gallery's own words: its title, and the row that opens Coach with nothing in it.
        struct Words: Decodable, Equatable {
            var title = ""
            /// The heading over every job ("All jobs").
            var all = ""
            var ownWords = ""
            var ownWordsLine = ""
            /// Browsing, with nothing created: Home's row and the gallery's title ("Explore jobs").
            var explore = ""
            /// The row under the strip ("See all jobs").
            var seeAll = ""
            /// A browsed job's only action ("Try it with Coach").
            var tryIt = ""
            /// The label over a job's sample ("What it reported after its test run").
            var sample = ""
            private enum CodingKeys: String, CodingKey {
                case title, all, ownWords = "own_words", ownWordsLine = "own_words_line"
                case explore, seeAll = "see_all", tryIt = "try", sample
            }
            init(title: String = "", all: String = "", ownWords: String = "", ownWordsLine: String = "",
                 explore: String = "", seeAll: String = "", tryIt: String = "", sample: String = "") {
                self.title = title; self.all = all; self.ownWords = ownWords; self.ownWordsLine = ownWordsLine
                self.explore = explore; self.seeAll = seeAll; self.tryIt = tryIt; self.sample = sample
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                title = ((try? c.decodeIfPresent(String.self, forKey: .title)) ?? nil) ?? ""
                all = ((try? c.decodeIfPresent(String.self, forKey: .all)) ?? nil) ?? ""
                ownWords = ((try? c.decodeIfPresent(String.self, forKey: .ownWords)) ?? nil) ?? ""
                ownWordsLine = ((try? c.decodeIfPresent(String.self, forKey: .ownWordsLine)) ?? nil) ?? ""
                explore = ((try? c.decodeIfPresent(String.self, forKey: .explore)) ?? nil) ?? ""
                seeAll = ((try? c.decodeIfPresent(String.self, forKey: .seeAll)) ?? nil) ?? ""
                tryIt = ((try? c.decodeIfPresent(String.self, forKey: .tryIt)) ?? nil) ?? ""
                sample = ((try? c.decodeIfPresent(String.self, forKey: .sample)) ?? nil) ?? ""
            }
        }
        var categories: [Recipes.Category] = []
        var recipes: [Recipes.Recipe] = []
        var strip = Strip()
        var words = Words()
        /// The job sheet's words; nil from a tafs cloud that sends none with the gallery.
        var labels: Recipes.Labels?
        private enum CodingKeys: String, CodingKey { case categories, recipes, strip, words, labels }
        init(categories: [Recipes.Category] = [], recipes: [Recipes.Recipe] = [], strip: Strip = Strip(),
             words: Words = Words(), labels: Recipes.Labels? = nil) {
            self.categories = categories; self.recipes = recipes; self.strip = strip; self.words = words; self.labels = labels
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            categories = ((try? c.decodeIfPresent([LenientItem<Recipes.Category>].self, forKey: .categories)) ?? nil)?.compactMap(\.value) ?? []
            recipes = ((try? c.decodeIfPresent([LenientItem<Recipes.Recipe>].self, forKey: .recipes)) ?? nil)?.compactMap(\.value) ?? []
            strip = ((try? c.decodeIfPresent(Strip.self, forKey: .strip)) ?? nil) ?? Strip()
            words = ((try? c.decodeIfPresent(Words.self, forKey: .words)) ?? nil) ?? Words()
            labels = (try? c.decodeIfPresent(Recipes.Labels.self, forKey: .labels)) ?? nil
        }
        /// The gallery as recipes, so it draws as Home's list does, its sheet with `labels`
        /// (the gallery's, else `fallback`'s, from `/v1/recipes`).
        func asRecipes(labels fallback: Recipes.Labels? = nil) -> Recipes {
            Recipes(categories: categories, recipes: recipes, labels: labels ?? fallback)
        }
        /// The words of the row under the strip that opens every job: tafs cloud's "See all
        /// jobs" (`words.see_all`), else its heading for them ("All jobs") from a tafs cloud from
        /// before it. Nil when it sends neither, or the strip already shows every job.
        var seeAll: String? {
            guard recipes.count > stripRecipes.count else { return nil }
            return Self.said(words.seeAll) ?? Self.said(words.all)
        }
        /// The words of Home's row that opens the jobs to browse, with nothing created
        /// (`words.explore`). Nil when tafs cloud sends none or lists no job: then no row, and
        /// no words of the app's own.
        var explore: String? { recipes.isEmpty ? nil : Self.said(words.explore) }
        /// The gallery's title: browsing, tafs cloud's "Explore jobs" where it sends it; else,
        /// and from + New, its title.
        func title(browse: Bool) -> String { (browse ? Self.said(words.explore) : nil) ?? words.title }
        /// The words of a job's one button, which hands it to Coach: browsing, "Try it with
        /// Coach" (`words.try`); else, and from a tafs cloud from before it, the sheet's own
        /// (`labels.hand`). Nil when none came: no button.
        func handWords(browse: Bool, labels: Recipes.Labels?) -> String? {
            (browse ? Self.said(words.tryIt) : nil) ?? Self.said(labels?.hand ?? "")
        }
        /// The label over a job's sample; nil when tafs cloud sends none.
        var sampleLabel: String? { Self.said(words.sample) }
        private static func said(_ words: String) -> String? {
            let w = words.trimmingCharacters(in: .whitespacesAndNewlines)
            return w.isEmpty ? nil : w
        }
        /// The strip's jobs, in tafs cloud's order: an id the gallery doesn't list is skipped.
        /// None when tafs cloud doesn't show it.
        var stripRecipes: [Recipes.Recipe] {
            guard strip.shown else { return [] }
            return strip.ids.compactMap { id in recipes.first { $0.id == id } }
        }
        /// The category a recipe is in, for its colour.
        func category(of recipe: Recipes.Recipe) -> Recipes.Category? { categories.first { $0.id == recipe.category } }
    }

    func recipesGallery() async throws -> Gallery {
        try await send("GET", "/v1/recipes/gallery")
    }

    /// Where an app's logo loads from: a path tafs cloud sent, `/v1/logos/` and the 32 lowercase
    /// hex of the key tafs cloud mints, as Android checks it, on tafs cloud itself. Anything else,
    /// an absolute URL or another path, loads nothing: the phone only ever asks tafs cloud, so no
    /// one else sees its address.
    func logoURL(_ path: String?) -> URL? {
        let prefix = "/v1/logos/"
        guard let path, path.hasPrefix(prefix) else { return nil }
        let key = path.utf8.dropFirst(prefix.utf8.count)
        guard key.count == 32, key.allSatisfy({ (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }) else { return nil }
        return URL(string: path, relativeTo: baseURL)?.absoluteURL
    }

    /// The account's "Show suggestions" setting, with tafs cloud's words for it.
    func suggestions() async throws -> Suggestions {
        struct Response: Decodable { let suggestions: Suggestions }
        let r: Response = try await send("GET", "/v1/me/suggestions")
        return r.suggestions
    }

    /// Turns the account's suggestions off for good, or on again: for the people who manage its
    /// tafs (403 otherwise).
    func setSuggestions(_ show: Bool) async throws -> Suggestions {
        struct Body: Encodable { let show: Bool }
        struct Response: Decodable { let suggestions: Suggestions }
        let r: Response = try await send("PUT", "/v1/me/suggestions", body: Body(show: show))
        return r.suggestions
    }

    /// The account's Setup, or nil before it is started.
    func setup() async throws -> CloudTaf? { try await setupRead().setup }

    /// The account's Coach and its tools as KIFF has them, from the one read (docs/api.md).
    func setupRead() async throws -> (setup: CloudTaf?, tools: CoachTools?, start: CoachStart?) {
        struct Response: Decodable { let setup: CloudTaf? }
        let data = try await sendData("GET", "/v1/setup", body: Optional<[String: String]>.none)
        let r = try KIFFClient.decoder.decode(Response.self, from: data)
        return (r.setup, CoachTools.read(data), r.setup == nil ? CoachStart.read(data) : nil)
    }

    /// Coach's first start under one fingerprint (`GET /v1/setup`'s `start`, docs/api.md): for
    /// an account with no Coach yet, the requests the phone signs once, as tafs cloud built them.
    /// The tools batch for the gateway (`connect`) and Coach's agent draft with its Cards (`draft`)
    /// under one `kiff.requests.v1`; or, with every tool already connected, the draft alone
    /// (`kiff.request.v1`). The phone builds neither, and sends each body as these bytes.
    struct CoachStart: Equatable {
        struct Request: Equatable {
            let id: String
            let method: String
            let path: String
            /// For KIFF's gateway, not its API.
            let gateway: Bool
            let body: Data
            var lines: [String] = []
            /// KIFF's own words for the Cards the draft would issue, from its preview (the draft only).
            var cardTitle: String? = nil
            var cards: [CoachTools.CardDraft.Words] = []
        }
        var state = "unknown"
        var scheme = ""
        var agentID = ""
        var requests: [Request] = []
        var step: CoachTools.Step? = nil
        var taken: String? = nil

        var connect: Request? { requests.first { $0.id == "connect" } }
        var draft: Request? { requests.first { $0.id == "draft" } }
        /// The pair, signed once: both requests and the scheme that names two.
        var together: Bool { scheme == RequestsTogether.scheme && connect != nil && draft != nil }
        /// Something the phone can sign and send now: the pair, or the draft alone.
        var startable: Bool {
            guard state == "to_give", let draft, draft.method == "POST", draft.path == "/v1/me/agent-drafts", !draft.gateway else { return false }
            if let connect { return together && connect.method == "POST" && connect.path == "/v1/connections/batch" && connect.gateway }
            return scheme == "kiff.request.v1"
        }

        /// What the tools step draws for it: its words, the batch's lines, and what's to give.
        var tools: CoachTools {
            var t = CoachTools()
            t.state = state
            t.taken = taken
            if let connect { t.connect = .init(body: connect.body, lines: connect.lines) }
            // Coach's Cards in KIFF's words, drawn beside what's signed: the draft's own body.
            if let draft { t.card = .init(body: draft.body, cardTitle: draft.cardTitle, cards: draft.cards) }
            t.step = step
            return t
        }

        /// Read from `GET /v1/setup`'s answer; nil from a tafs cloud that sends no `start`.
        static func read(_ response: Data) -> CoachStart? {
            guard let raw = RawJSON.value(of: "start", in: response),
                  let fields = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else { return nil }
            var s = CoachStart()
            s.state = fields["state"] as? String ?? "unknown"
            s.scheme = fields["scheme"] as? String ?? ""
            s.agentID = fields["agent_id"] as? String ?? ""
            s.taken = fields["taken"] as? String
            if let list = RawJSON.value(of: "requests", in: raw), let items = RawJSON.elements(of: list) {
                s.requests = items.compactMap { item in
                    guard let f = (try? JSONSerialization.jsonObject(with: item)) as? [String: Any],
                          let id = f["id"] as? String, let method = f["method"] as? String, let path = f["path"] as? String,
                          let body = RawJSON.value(of: "body", in: item) else { return nil }
                    // KIFF's words for the Cards, as tafs cloud reads them for the tools step's `card`.
                    let cards: [CoachTools.CardDraft.Words] = (f["cards"] as? [Any] ?? []).compactMap { item in
                        guard let c = item as? [String: Any] else { return nil }
                        let title = (c["title"] as? String) ?? (c["card_title"] as? String) ?? (c["label"] as? String)
                        let lines = (c["lines"] as? [Any])?.compactMap { $0 as? String } ?? []
                        if title == nil && lines.isEmpty { return nil }
                        return CoachTools.CardDraft.Words(title: title ?? "", lines: lines)
                    }
                    return Request(id: id, method: method, path: path, gateway: f["gateway"] as? Bool ?? false, body: body,
                                   lines: (f["lines"] as? [Any])?.compactMap { $0 as? String } ?? [],
                                   cardTitle: f["card_title"] as? String, cards: cards)
                }
            }
            if let step = RawJSON.value(of: "coach_tools_step", in: raw) { s.step = try? JSONDecoder().decode(CoachTools.Step.self, from: step) }
            return s
        }
    }

    /// Coach's tools as tafs cloud reads them from KIFF (`GET /v1/setup`'s `tools`,
    /// docs/coach.md): `given`, `to_give`, `taken` or `unknown`. With `to_give`, what the phone
    /// signs and sends exactly as it came: the batch of connections, then Coach's Cards' draft;
    /// and the words of the step that asks for them. The phone builds none of it.
    struct CoachTools: Equatable {
        /// The batch for KIFF's `POST /v1/connections/batch`, its bytes as tafs cloud wrote
        /// them, and a line per tool.
        struct Connect: Equatable {
            let body: Data
            var lines: [String] = []
        }
        /// Coach's Cards' draft for `POST /v1/me/agent-drafts`, its bytes as tafs cloud wrote
        /// them, with KIFF's own words for each Card.
        struct CardDraft: Equatable {
            struct Words: Equatable {
                var title = ""
                var lines: [String] = []
            }
            let body: Data
            var cardTitle: String? = nil
            var cards: [Words] = []
        }
        /// The step's words, every one tafs cloud's, in the person's language.
        struct Step: Decodable, Equatable {
            struct Tool: Decodable, Equatable {
                var name = ""
                var does = ""
                var why = ""
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    name = Step.text(c, .name); does = Step.text(c, .does); why = Step.text(c, .why)
                }
                enum CodingKeys: String, CodingKey { case name, does, why }
            }
            struct Group: Decodable, Equatable {
                var name = ""
                var line = ""
                var tools: [Tool] = []
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    name = Step.text(c, .name); line = Step.text(c, .line)
                    // A tool with no name the app can read is left out.
                    tools = ((try? c.decodeIfPresent([LenientItem<Tool>].self, forKey: .tools)) ?? nil)?.compactMap(\.value).filter { !$0.name.isEmpty } ?? []
                }
                enum CodingKeys: String, CodingKey { case name, line, tools }
            }
            var title = ""
            var intro = ""
            var groups: [Group] = []
            var details = ""
            var ask = ""
            var give = ""
            var notNow = ""
            var row = ""
            enum CodingKeys: String, CodingKey { case title, intro, groups, details, ask, give, notNow = "not_now", row }

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                title = Self.text(c, .title); intro = Self.text(c, .intro); details = Self.text(c, .details)
                ask = Self.text(c, .ask); give = Self.text(c, .give); notNow = Self.text(c, .notNow); row = Self.text(c, .row)
                groups = ((try? c.decodeIfPresent([LenientItem<Group>].self, forKey: .groups)) ?? nil)?.compactMap(\.value) ?? []
            }

            /// A line as written, or none where it's missing or isn't text.
            fileprivate static func text<K: CodingKey>(_ c: KeyedDecodingContainer<K>, _ k: K) -> String {
                ((try? c.decodeIfPresent(String.self, forKey: k)) ?? nil) ?? ""
            }
        }
        var state = "unknown"
        /// With `taken`: the name a vendor's tool holds.
        var taken: String? = nil
        var connect: Connect? = nil
        var card: CardDraft? = nil
        var step: Step? = nil

        var toGive: Bool { state == "to_give" }
        var given: Bool { state == "given" }

        /// Read from `GET /v1/setup`'s answer; nil from a tafs cloud that sends no `tools`.
        /// The bodies are kept as their own bytes: they're signed and sent as they came.
        static func read(_ response: Data) -> CoachTools? {
            guard let raw = RawJSON.value(of: "tools", in: response),
                  let fields = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else { return nil }
            var tools = CoachTools()
            tools.state = fields["state"] as? String ?? "unknown"
            tools.taken = fields["taken"] as? String
            if let connect = RawJSON.value(of: "connect", in: raw), let body = RawJSON.value(of: "body", in: connect) {
                let lines = ((fields["connect"] as? [String: Any])?["lines"] as? [Any])?.compactMap { $0 as? String } ?? []
                tools.connect = Connect(body: body, lines: lines)
            }
            if let card = RawJSON.value(of: "card", in: raw), let body = RawJSON.value(of: "body", in: card) {
                let said = fields["card"] as? [String: Any]
                let cards: [CardDraft.Words] = (said?["cards"] as? [Any] ?? []).compactMap { item in
                    guard let c = item as? [String: Any] else { return nil }
                    let title = (c["title"] as? String) ?? (c["card_title"] as? String) ?? (c["label"] as? String)
                    let lines = (c["lines"] as? [Any])?.compactMap { $0 as? String } ?? []
                    // A Card the app can't read is left out.
                    if title == nil && lines.isEmpty { return nil }
                    return CardDraft.Words(title: title ?? "", lines: lines)
                }
                tools.card = CardDraft(body: body, cardTitle: said?["card_title"] as? String, cards: cards)
            }
            if let step = RawJSON.value(of: "coach_tools_step", in: raw) {
                tools.step = try? JSONDecoder().decode(Step.self, from: step)
            }
            return tools
        }
    }

    /// Starts the account's Setup from a confirmed no-tools KIFF draft.
    /// tafs cloud claims its key with the claim secret, as for a taf.
    func startSetup(draftID: String, claimSecret: String) async throws -> CloudTaf {
        struct Body: Encodable {
            let draftID: String, claimSecret: String
            enum CodingKeys: String, CodingKey { case draftID = "draft_id", claimSecret = "claim_secret" }
        }
        struct Response: Decodable { let setup: CloudTaf }
        let r: Response = try await send("POST", "/v1/setup", body: Body(draftID: draftID, claimSecret: claimSecret))
        return r.setup
    }

    /// Setup's thread: its messages and the components it showed. With `session`, that
    /// one chat only (kiff/tafs#73).
    func setupThread(_ id: String, session: String? = nil) async throws -> SetupThread {
        struct Working: Decodable { let session: String }
        /// One the app can't read is left out, never the thread with it.
        struct LenientLine: Decodable {
            let line: CoachLine?
            init(from decoder: Decoder) throws { line = try? CoachLine(from: decoder) }
        }
        let path = "/v1/tafs/\(escaped(id))/conversation" + (session.map { "/\(escaped($0))" } ?? "")
        return try Self.setupThread(decoding: try await sendData("GET", path, body: Optional<[String: String]>.none))
    }

    /// Setup's thread from tafs cloud's answer: its messages, its cards, and the drives that ended in it
    /// (`items` of type `drive`, docs/drive.md). Anything the app can't read is left out, never the thread.
    static func setupThread(decoding data: Data) throws -> SetupThread {
        struct Working: Decodable { let session: String; let run: String? }
        /// Steering the app can't read is left out, never the thread with it.
        struct LenientSteering: Decodable {
            let steering: Steering?
            init(from decoder: Decoder) throws { steering = try? Steering(from: decoder) }
        }
        /// One the app can't read is left out, never the thread with it.
        struct LenientLine: Decodable {
            let line: CoachLine?
            init(from decoder: Decoder) throws { line = try? CoachLine(from: decoder) }
        }
        /// Only a drive's end, of the thread's items: the rest the app reads from messages and components.
        struct LenientDrive: Decodable {
            let drive: DriveRecap?
            enum CodingKeys: String, CodingKey { case type, drive }
            init(from decoder: Decoder) throws {
                let c = try? decoder.container(keyedBy: CodingKeys.self)
                drive = (try? c?.decode(String.self, forKey: .type)) == "drive" ? (try? c?.decode(DriveRecap.self, forKey: .drive)) : nil
            }
        }
        struct Response: Decodable {
            let messages: [ChatMessage]; let components: [LenientShown]?; let working: Working?; let coachLines: [LenientLine]?
            let items: [LenientDrive]?; let steering: LenientSteering?
            enum CodingKeys: String, CodingKey { case messages, components, working, items, steering, coachLines = "coach_lines" }
        }
        let r = try KIFFClient.decoder.decode(Response.self, from: data)
        return SetupThread(messages: r.messages, components: SetupThread.fold((r.components ?? []).compactMap(\.shown)),
                           working: r.working?.session, coachLines: (r.coachLines ?? []).compactMap(\.line),
                           drives: (r.items ?? []).compactMap(\.drive), workingRun: r.working?.run, steering: r.steering?.steering)
    }

    /// How a task Coach handed a taf went (kiff/tafs#206), shown in Coach's chat until
    /// Coach reads it on its next turn.
    struct CoachLine: Decodable, Equatable, Identifiable {
        let at: Date
        let taf: String
        let said: String
        /// Kept while the owner was away (kiff/tafs#210): shown in "While you were away".
        var away: Bool?
        var id: String { "\(at.timeIntervalSince1970)|\(taf)|\(said)" }
    }

    /// Stops what the agent is doing in `session`: its latest run is cancelled.
    func stop(_ id: String, session: String) async throws {
        struct Response: Decodable { let stopped: Bool }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/stop", body: ["session": session])
    }

    // MARK: Memory

    /// One thing a taf remembers: a note it keeps between its runs and chats.
    struct Note: Decodable, Equatable, Identifiable {
        let id: String
        let path: String
        let content: String
        let updatedAt: Date

        enum CodingKeys: String, CodingKey { case id, path, content, updatedAt = "updated_at" }

        /// The note's name, from its path: "/customers/acme.md" reads "customers · acme".
        var title: String {
            path.split(separator: "/").map { $0.replacingOccurrences(of: ".md", with: "").replacingOccurrences(of: "-", with: " ") }
                .joined(separator: " · ")
        }
    }

    /// What one of the taf's calls through KIFF acted on (kiff/tafs#167): KIFF's key for
    /// the call and the value of the argument its Card scopes on, kept 30 days.
    struct CallRef: Decodable, Equatable {
        let invocation: String
        let value: String
    }

    /// What the taf's calls through KIFF acted on, as its runs kept them.
    func calls(_ id: String) async throws -> [CallRef] {
        struct Response: Decodable { let calls: [CallRef] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/calls")
        return r.calls
    }

    /// What the taf remembers, newest first.
    func memory(_ id: String, all: Bool = false) async throws -> [Note] {
        struct Response: Decodable { let notes: [Note] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/memory" + (all ? "?all=true" : ""))
        return r.notes
    }

    /// Forgets one note: the taf won't see it on its next run or chat.
    func forget(_ id: String, note: String) async throws {
        struct Response: Decodable { let forgotten: String }
        let _: Response = try await send("DELETE", "/v1/tafs/\(escaped(id))/memory/\(escaped(note))")
    }

    // MARK: Versions

    /// One version of a taf (kiff/tafs#107), kept like an app build: any one can be live,
    /// forward or back.
    struct Version: Decodable, Equatable, Identifiable {
        let n: Int
        /// What changed, in plain words.
        let note: String?
        /// Who made it: the owner's subject, or "setup".
        let by: String?
        /// Who made it, in tafs cloud's words ("By Coach mode", "By you"); none for one
        /// kiff/agents wrote itself (the first, or an Upgrade's).
        var byWords: String? = nil
        let created: Date
        let live: Bool
        /// The KIFF draft whose confirmation brings this version's Card revision, while it
        /// isn't confirmed; nil: the taf's current Cards.
        let draft: String?
        /// Its trials that ended, oldest first (kiff/tafs#173).
        var trials: [TrialSummary]? = nil
        var id: Int { n }
        enum CodingKeys: String, CodingKey { case n, note, by, created, live, draft, trials, byWords = "by_words" }
    }

    /// A switch to version n under way (kiff/tafs#107): nothing of the taf runs until it
    /// ends, with that version live, or is dropped.
    struct Switching: Decodable, Equatable {
        let n: Int
        /// The KIFF draft whose confirmation it waits for.
        let draft: String?
        let at: Date
    }

    /// The taf's versions, oldest first, the live one marked, and a switch under way.
    struct VersionState: Decodable, Equatable {
        let versions: [Version]
        let switching: Switching?
    }

    /// The taf's versions and a switch under way, which tafs cloud settles first: ended
    /// once KIFF has its Card, dropped once its draft never will be.
    func versionState(_ id: String) async throws -> VersionState {
        try await send("GET", "/v1/tafs/\(escaped(id))/versions")
    }

    /// The taf's versions, oldest first, the live one marked.
    func versions(_ id: String) async throws -> [Version] {
        try await versionState(id).versions
    }

    /// Begins the switch to version n: from then on nothing of the taf runs until it ends.
    /// Refused (409) while a run of the taf is going or waiting on the owner.
    /// tafs cloud prepares the Card draft it waits for (kiff/tafs#122): the version's own,
    /// drafted again (`redraft`, or once it expired), a restore of the Cards it ran under
    /// when going back, or none.
    func beginSwitch(_ id: String, version n: Int, redraft: Bool = false, stopBlocking: Bool = false) async throws -> Switching {
        struct Body: Encodable {
            let redraft: Bool
            let stopBlocking: Bool
            enum CodingKeys: String, CodingKey { case redraft, stopBlocking = "stop_blocking" }
        }
        struct Response: Decodable { let switching: Switching }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions/\(n)/switch", body: Body(redraft: redraft, stopBlocking: stopBlocking))
        return r.switching
    }

    /// Drops the switch under way while its Card isn't confirmed; once it is, tafs cloud
    /// ends it instead. True when one was dropped.
    @discardableResult
    func cancelSwitch(_ id: String) async throws -> Bool {
        struct Response: Decodable { let cancelled: Bool }
        let r: Response = try await send("DELETE", "/v1/tafs/\(escaped(id))/switching")
        return r.cancelled
    }

    /// Makes version n live. tafs cloud refuses (409) while its Card revision isn't confirmed.
    func makeLive(_ id: String, version n: Int) async throws {
        struct Response: Decodable { let live: Int }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions/\(n)/live")
    }

    // MARK: Trying a version (kiff/tafs#173)

    /// The taf's open trial: the owner chats with version n under its own trial Card,
    /// while the live version keeps its schedule, Card and budget. One per taf.
    struct OpenTrial: Decodable, Equatable {
        let id: String
        let n: Int
        /// The KIFF draft trying as its Card.
        let draft: String
        /// Its chat, opened with the trial.
        let session: String?
        let started: Date
        /// When the owner last wrote in it: unused for 7 days, it ends.
        let used: Date
        /// Its guidance, as tafs cloud words it (kiff/tafs#247): a badge ("Trial"), a title
        /// ("Trying version 2"), what the version changes, the task to try it with, and
        /// when it ends unused. Absent from an older tafs cloud.
        var label: String? = nil
        var title: String? = nil
        var changed: String? = nil
        var `try`: String? = nil
        var ends: Date? = nil
    }

    /// What a version keeps of a trial once it ended: when, how, and KIFF's counts.
    struct TrialSummary: Decodable, Equatable {
        let started: Date
        let ended: Date
        /// installed, back, idle or replaced.
        let why: String
        let calls: Int
        let held: Int
        let refused: Int
    }

    /// The KIFF draft to confirm as version n's trial Card, signed by the phone.
    func trialDraft(_ id: String, version n: Int) async throws -> String {
        struct Response: Decodable { let draft: String }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions/\(n)/try")
        return r.draft
    }

    /// Opens the trial of version n, once KIFF has `draft` trying. 409 before that.
    func openTrial(_ id: String, version n: Int, draft: String) async throws -> OpenTrial {
        struct Body: Encodable { let n: Int; let draft: String }
        struct Response: Decodable { let trial: OpenTrial }
        let r: Response = try await send("PUT", "/v1/tafs/\(escaped(id))/trial", body: Body(n: n, draft: draft))
        return r.trial
    }

    /// The taf's open trial; nil when it has none.
    func openTrial(_ id: String) async throws -> OpenTrial? {
        struct Response: Decodable { let trial: OpenTrial? }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/trial")
        return r.trial
    }

    /// One message to the trial: a turn of the version being tried, in its own chat.
    /// Returns its session and run.
    func trialMessage(_ id: String, text: String) async throws -> (session: String, run: String) {
        struct Response: Decodable { let session: String; let run: String }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/trial/messages", body: ["text": text])
        return (r.session, r.run)
    }

    /// The trial's chat, read as a taf's thread is.
    func trialConversation(_ id: String) async throws -> Thread {
        try await thread("/v1/tafs/\(escaped(id))/trial/conversation")
    }

    /// Install: once the phone confirmed the trial's draft live in KIFF, that version goes
    /// live and the trial ends. 409 while KIFF hasn't confirmed it.
    func installTrial(_ id: String) async throws -> TrialSummary {
        struct Response: Decodable { let live: Int; let ended: TrialSummary }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/trial/install")
        return r.ended
    }

    /// Go back: KIFF ends the trial Cards, the trial's chat is deleted, and the live version
    /// stays as it was.
    func endTrial(_ id: String) async throws -> TrialSummary {
        struct Response: Decodable { let ended: TrialSummary }
        let r: Response = try await send("DELETE", "/v1/tafs/\(escaped(id))/trial")
        return r.ended
    }

    // MARK: Alerts

    /// Where KIFF posts the account's held calls, for tafs cloud to push to its phones.
    var alertsURL: URL { URL(string: "/v1/kiff/alerts", relativeTo: baseURL)!.absoluteURL }

    /// Coach's tools (kiff/tafs#206): the MCP server KIFF's gateway calls, and the audience
    /// its permits name.
    var mcpURL: URL { URL(string: "/v1/mcp", relativeTo: baseURL)!.absoluteURL }

    /// What tafs cloud says after a phone registers: whether this person's phones are alerted
    /// (they may answer held calls), and whether tafs cloud holds a KIFF secret for the account.
    struct PushStatus: Decodable, Equatable {
        let answers: Bool
        let alerts: Bool
    }

    /// This phone gets the account's alerts at `deviceToken`. With `target`, also hands over
    /// the KIFF target's signing secret, which checks KIFF's deliveries. `sound` false: this
    /// phone's alerts arrive quietly, with no sound or banner.
    func registerPush(deviceToken: String, environment: String, sound: Bool = true, target: KIFFClient.AlertTarget? = nil, kinds: [String: Bool]? = nil) async throws -> PushStatus {
        struct Target: Encodable {
            let id: String, signingSecret: String
            enum CodingKeys: String, CodingKey { case id, signingSecret = "signing_secret" }
        }
        struct Body: Encodable {
            let deviceToken: String, environment: String, sound: Bool, alertTarget: Target?
            let kinds: [String: Bool]?
            enum CodingKeys: String, CodingKey { case environment, sound, kinds, deviceToken = "device_token", alertTarget = "alert_target" }
        }
        return try await send("POST", "/v1/push", body: Body(
            deviceToken: deviceToken, environment: environment, sound: sound,
            alertTarget: target.map { Target(id: $0.id, signingSecret: $0.signingSecret) }, kinds: kinds))
    }

    /// Whether tafs cloud holds KIFF's alert secret for the account (kiff/tafs#206).
    func alertsKept() async throws -> Bool {
        struct Response: Decodable { let alerts: Bool }
        let r: Response = try await send("GET", "/v1/alerts")
        return r.alerts
    }

    /// Hands over KIFF's alert target for the account, with no phone's alerts (kiff/tafs#206):
    /// a paused run then hears the owner's answer whether or not a phone turned alerts on.
    func keepAlerts(_ target: KIFFClient.AlertTarget) async throws {
        struct Target: Encodable {
            let id: String, signingSecret: String
            enum CodingKeys: String, CodingKey { case id, signingSecret = "signing_secret" }
        }
        struct Body: Encodable {
            let alertTarget: Target
            enum CodingKeys: String, CodingKey { case alertTarget = "alert_target" }
        }
        struct Response: Decodable { let alerts: Bool }
        let _: Response = try await send("POST", "/v1/alerts", body: Body(alertTarget: Target(id: target.id, signingSecret: target.signingSecret)))
    }

    /// This phone stops getting the account's alerts.
    func unregisterPush(deviceToken: String) async throws {
        struct Response: Decodable { let removed: Bool }
        let _: Response = try await send("DELETE", "/v1/push/\(escaped(deviceToken))")
    }

    // MARK: Transport

    func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    func send<T: Decodable>(_ method: String, _ path: String, headers: [String: String] = [:]) async throws -> T {
        try await send(method, path, body: Optional<[String: String]>.none, headers: headers)
    }

    func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, headers: [String: String] = [:]) async throws -> T {
        try KIFFClient.decoder.decode(T.self, from: await sendData(method, path, body: body, headers: headers))
    }

    /// The answer's body as tafs cloud sent it, for one kept as it is (the person's export).
    func sendData<B: Encodable>(_ method: String, _ path: String, body: B?, headers: [String: String] = [:]) async throws -> Data {
        guard let url = URL(string: path, relativeTo: baseURL) else { throw CloudError(status: 0, message: Words.Error.badPath) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 70
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            // Bytes already encoded are sent as they are: a signed request's signature covers exactly them.
            request.httpBody = try (body as? KIFFClient.RawJSON)?.data ?? JSONEncoder().encode(body)
        }
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        guard let token else { throw CloudError(status: 401, message: Words.Error.signInFirst) }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // By its route and status only, for a drive's diagnostics (docs/drive.md).
            DriveDiagnostics.shared.failed(method: method, path: path, status: status)
            throw Self.refusal(status, data)
        }
        return data
    }
}
