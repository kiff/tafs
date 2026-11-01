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
        /// tafs cloud's words first, on every path that shows the error (kiff/tafs#397); the
        /// app's own line for the status only without them.
        var errorDescription: String? { words ?? message }
    }

    /// tafs cloud's refusal: its words, and the runs in the way when it names them (kiff/tafs#206).
    static func refusal(_ status: Int, _ data: Data) -> CloudError {
        struct Body: Decodable { let error: String?; let blocking: [BlockingRun]?; let words: String? }
        let body = try? JSONDecoder().decode(Body.self, from: data)
        return CloudError(status: status, message: body?.error ?? "tafs cloud answered \(status)", blocking: body?.blocking ?? [], words: body?.words)
    }

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

        enum CodingKeys: String, CodingKey {
            case id, name, job, status, unscheduled
            case kiffAgent = "kiff_agent", nextRun = "next_run", lastRun = "last_run"
        }

        var worksFromChat: Bool { unscheduled == true }

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
    private let session: URLSession

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

            enum CodingKeys: String, CodingKey {
                case limit, used, window
                case windowWords = "window_words"
                case extraRoom = "extra_room", baseLimit = "base_limit", roomWindowEnd = "room_window_end"
                case pendingText = "pending_text"
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

        enum CodingKeys: String, CodingKey {
            case id, name, job, status, color, state, waiting, card, unscheduled, trial, doors
            case kiffAgent = "kiff_agent", nextRun = "next_run", lastRun = "last_run", jobSummary = "job_summary"
        }

        /// tafs cloud's record of it, for its chat, runs and schedule; nil for another agent.
        var cloudTaf: CloudTaf? {
            // With unscheduled, or the taf page offers Run now and Pause a taf that works from
            // its chat can't take (kiff/tafs#206 section 2).
            id.map { CloudTaf(id: $0, name: name, job: job ?? "", kiffAgent: kiffAgent, status: status ?? "active", nextRun: nextRun, lastRun: lastRun,
                              unscheduled: unscheduled) }
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

        enum CodingKeys: String, CodingKey { case tafs, others, removing, doorsOffered = "doors_offered" }
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

    /// The period's AI use, from chats and Setup (kiff/tafs#193): a share of the plan's fair
    /// use, where the tafs ask next (100%, then 200% after a go on…), and whether they're
    /// asking now. Nil shares: no bound on this account.
    struct AIUse: Decodable, Equatable {
        let usedPct: Int?
        let asksAtPct: Int?
        let paused: Bool
        enum CodingKeys: String, CodingKey { case usedPct = "used_pct", asksAtPct = "asks_at_pct", paused }
    }

    func aiUse() async throws -> AIUse {
        try await send("GET", "/v1/usage/ai")
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
    func remove(_ id: String) async throws -> Removal {
        try await send("DELETE", "/v1/tafs/\(escaped(id))")
    }

    /// Runs the taf now. Returns the run's session.
    func runNow(_ id: String) async throws -> String {
        struct Response: Decodable { let session: String }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/run", body: [String: String]())
        return r.session
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

        init(at: Date, from: From, kind: Kind = .message, text: String, session: String, door: DoorMark? = nil, work: Work? = nil,
             attachments: [AttachmentRef] = [], serverID: String? = nil, answers: String? = nil) {
            self.at = at; self.from = from; self.kind = kind; self.text = text; self.session = session; self.door = door; self.work = work
            self.attachments = attachments; self.serverID = serverID; self.answers = answers
        }

        enum CodingKeys: String, CodingKey { case at, from, kind, text, session, door, work, attachments, answers, serverID = "id" }
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
            throw CloudError(status: status, message: "The file didn't upload. Try attaching it again.")
        }
    }

    /// A file the person attached, downloaded: only their own; another's is a 404.
    func attachment(_ id: String, file: String) async throws -> Data {
        struct Response: Decodable { let url: URL }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/attachments/\(escaped(file))")
        let (data, response) = try await session.data(from: r.url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw CloudError(status: status, message: "The file couldn't be opened.") }
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
        /// The latest run stopped at a cap of its own (`cost_cap` or `turn_cap`), with no
        /// words for it: the app says why.
        var capped: Capped? = nil
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
        let run: String
        let status: String
        let untaken: [Message]

        init(run: String, status: String, untaken: [Message]) {
            self.run = run; self.status = status; self.untaken = untaken
        }

        enum CodingKeys: String, CodingKey { case run, status, untaken }
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
        struct Response: Decodable { let messages: [ChatMessage]; let working: Working?; let steering: Lenient?; let capped: LenientCap? }
        let r: Response = try await send("GET", path)
        return Thread(messages: r.messages, working: r.working?.session, workingRun: r.working?.run, steering: r.steering?.steering,
                      capped: r.capped?.capped)
    }

    /// Writes to the run at work (kiff/tafs#64): it reads the message at its next step.
    /// A run that has ended throws 409, and the message goes as a new one. Returns the
    /// run's status: a paused run reads it when it carries on.
    func steer(_ id: String, run: String, text: String) async throws -> String {
        struct Response: Decodable { let run: String; let status: String }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/steer", body: ["run": run, "text": text])
        return r.status
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
            case .offensive: return "Offensive"
            case .harmful: return "Harmful"
            case .wrong: return "Wrong"
            case .other: return "Other"
            }
        }
    }

    /// A report tafs cloud keeps; `new` is false when the person had reported this answer already.
    struct Report: Decodable, Equatable {
        let id: String
        let at: String
        let new: Bool
    }

    /// Reports one of the taf's answers (or Coach's, by Coach's taf id) by its id, with the
    /// chat or trial it's in. tafs cloud keeps the answer as kiff/agents holds it and a person
    /// at KIFF reads it (kiff/tafs#421, Google Play's rule for content an AI generates).
    func reportAnswer(_ id: String, message: String, reason: ReportReason, note: String?, session: String?) async throws -> Report {
        struct Body: Encodable { let reason: ReportReason; let note: String?; let session: String? }
        struct Response: Decodable { let report: Report }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/messages/\(escaped(message))/report",
                                         body: Body(reason: reason, note: note, session: session))
        return r.report
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
        /// More of the summary of what the taf is thinking (kiff/tafs#285).
        case thinking(String)
        /// A run picked back up wrote a message: the conversation has it (kiff/tafs#285).
        case said
        /// A run picked back up went on after a pause, a held call the owner answered: what it
        /// did before is the conversation's, with its own work (the 859 test).
        case part
        /// The agent showed a component: the conversation has it.
        case component
        /// The run stopped: its status, and why. A failed run also says what went through
        /// before it stopped (kiff/agents#249): each call that acted, by tool and what it
        /// acted on.
        case end(status: String, stop: String, done: [DoneCall] = [])
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
            throw CloudError(status: 0, message: "Bad path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // A run can think for minutes between two words.
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(MessageBody(text, target, files: files))
        guard let token else { throw CloudError(status: 401, message: "Sign in with KIFF first.") }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        return try await events(request)
    }

    /// Picks a run of the person's back up (kiff/tafs#285): its steps so far, then the rest
    /// as it goes, until it stops. Only their own run; another's is a 404.
    func resumeRun(_ id: String, run: String, after: Int = 0) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        guard let url = URL(string: "/v1/tafs/\(escaped(id))/runs/\(escaped(run))/stream?after=\(max(0, after))", relativeTo: streamURL) else {
            throw CloudError(status: 0, message: "Bad path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        guard let token else { throw CloudError(status: 401, message: "Sign in with KIFF first.") }
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
        case "said": return .said
        case "part": return .part
        case "component": return .component
        case "end":
            let done = (o["done"] as? [[String: Any]] ?? []).compactMap { c in
                (c["tool"] as? String).map { DoneCall(tool: $0, resource: c["resource"] as? String, invocation: c["invocation"] as? String) }
            }
            return .end(status: o["status"] as? String ?? "", stop: o["stop"] as? String ?? "", done: done)
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

        struct Outcome: Decodable, Equatable {
            var tafID: String?
            var version: Int?
            var failed: String?
            enum CodingKeys: String, CodingKey { case version, failed, tafID = "taf_id" }
        }

        enum CodingKeys: String, CodingKey { case state, words, by, outcome }

        init(state: String = "answered", words: String = "", by: String? = nil, outcome: Outcome? = nil) {
            self.state = state
            self.words = words
            self.by = by
            self.outcome = outcome
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            state = try c.decodeIfPresent(String.self, forKey: .state) ?? "answered"
            words = try c.decodeIfPresent(String.self, forKey: .words) ?? ""
            by = try c.decodeIfPresent(String.self, forKey: .by)
            outcome = try? c.decodeIfPresent(Outcome.self, forKey: .outcome)
        }
    }

    private struct LenientShown: Decodable {
        let shown: Shown?
        init(from decoder: Decoder) throws { shown = try? Shown(from: decoder) }
    }

    /// The account's Setup, or nil before it is started.
    func setup() async throws -> CloudTaf? {
        struct Response: Decodable { let setup: CloudTaf? }
        let r: Response = try await send("GET", "/v1/setup")
        return r.setup
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
        struct Response: Decodable {
            let messages: [ChatMessage]; let components: [LenientShown]?; let working: Working?; let coachLines: [LenientLine]?
            enum CodingKeys: String, CodingKey { case messages, components, working, coachLines = "coach_lines" }
        }
        let path = "/v1/tafs/\(escaped(id))/conversation" + (session.map { "/\(escaped($0))" } ?? "")
        let r: Response = try await send("GET", path)
        return SetupThread(messages: r.messages, components: SetupThread.fold((r.components ?? []).compactMap(\.shown)),
                           working: r.working?.session, coachLines: (r.coachLines ?? []).compactMap(\.line))
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
    func memory(_ id: String) async throws -> [Note] {
        struct Response: Decodable { let notes: [Note] }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/memory")
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
    func registerPush(deviceToken: String, environment: String, sound: Bool = true, target: KIFFClient.AlertTarget? = nil) async throws -> PushStatus {
        struct Target: Encodable {
            let id: String, signingSecret: String
            enum CodingKeys: String, CodingKey { case id, signingSecret = "signing_secret" }
        }
        struct Body: Encodable {
            let deviceToken: String, environment: String, sound: Bool, alertTarget: Target?
            enum CodingKeys: String, CodingKey { case environment, sound, deviceToken = "device_token", alertTarget = "alert_target" }
        }
        return try await send("POST", "/v1/push", body: Body(
            deviceToken: deviceToken, environment: environment, sound: sound,
            alertTarget: target.map { Target(id: $0.id, signingSecret: $0.signingSecret) }))
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
        guard let url = URL(string: path, relativeTo: baseURL) else { throw CloudError(status: 0, message: "Bad path") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 70
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        guard let token else { throw CloudError(status: 401, message: "Sign in with KIFF first.") }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw Self.refusal(status, data)
        }
        return try KIFFClient.decoder.decode(T.self, from: data)
    }
}
