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
        var errorDescription: String? { message }
    }

    /// tafs cloud's refusal: its words, and the runs in the way when it names them (kiff/tafs#206).
    static func refusal(_ status: Int, _ data: Data) -> CloudError {
        struct Body: Decodable { let error: String?; let blocking: [BlockingRun]? }
        let body = try? JSONDecoder().decode(Body.self, from: data)
        return CloudError(status: status, message: body?.error ?? "tafs cloud answered \(status)", blocking: body?.blocking ?? [])
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

    struct Started: Decodable, Equatable {
        let taf: CloudTaf
        /// Its first run's chat; none for a taf that works from its chat (kiff/tafs#138).
        let session: String?
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

    static let defaultURL = URL(string: "https://qvjurway3eczrk7pqorfuy7z5y0vuztq.lambda-url.us-east-1.on.aws")!
    static let defaultStreamURL = URL(string: "https://y3mbk66eybe3mmu5loval6asle0kjygr.lambda-url.us-east-1.on.aws")!

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

        struct Rule: Decodable, Equatable {
            let action: String
            let label: String
            let outside: String
            let perAction: Amount?
            let perWindow: Amount?
            let countPerWindow: Int?
            let window: String

            enum CodingKeys: String, CodingKey {
                case action, label, outside, window
                case perAction = "per_action", perWindow = "per_window", countPerWindow = "count_per_window"
            }
        }

        /// A Card's total for its window and what it has used; no use when KIFF couldn't read it.
        /// Sendable said outright: a generic type isn't taken as Sendable on its own.
        struct Total<Value: Decodable & Equatable & Sendable>: Decodable, Equatable, Sendable {
            let limit: Value
            let used: Value?
            let window: String
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
        /// Its doors, for its avatar's rim; absent when it has none.
        var doors: [DoorLight]? = nil

        enum CodingKeys: String, CodingKey {
            case id, name, job, status, color, state, waiting, card, unscheduled, doors
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
        /// held back meanwhile (kiff/tafs#138, item 48). An owner's message keeps its words in
        /// its id: two sent at once stay two.
        var id: String {
            from == .taf ? "\(session)|\(at.timeIntervalSince1970)|taf|\(kind.rawValue)"
                : "\(session)|\(at.timeIntervalSince1970)|\(from.rawValue)|\(text.hashValue)"
        }
        let at: Date
        let from: From
        let kind: Kind
        let text: String
        let session: String
        /// It came through one of the taf's doors, or answers one that did.
        var door: DoorMark? = nil

        init(at: Date, from: From, kind: Kind = .message, text: String, session: String, door: DoorMark? = nil) {
            self.at = at; self.from = from; self.kind = kind; self.text = text; self.session = session; self.door = door
        }

        enum CodingKeys: String, CodingKey { case at, from, kind, text, session, door }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            at = try c.decode(Date.self, forKey: .at)
            from = try c.decode(From.self, forKey: .from)
            kind = (try? c.decode(Kind.self, forKey: .kind)) ?? .message
            text = try c.decode(String.self, forKey: .text)
            session = try c.decode(String.self, forKey: .session)
            // A mark the app can't read leaves the message as a plain one.
            door = try? c.decodeIfPresent(DoorMark.self, forKey: .door)
        }
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
        struct Working: Decodable { let session: String; let run: String? }
        /// One the app can't read is left out, never the thread with it.
        struct Lenient: Decodable {
            let steering: Steering?
            init(from decoder: Decoder) throws { steering = try? Steering(from: decoder) }
        }
        struct Response: Decodable { let messages: [ChatMessage]; let working: Working?; let steering: Lenient? }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/conversation")
        return Thread(messages: r.messages, working: r.working?.session, workingRun: r.working?.run, steering: r.steering?.steering)
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
        enum CodingKeys: String, CodingKey { case session, newSession = "new_session" }
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
        enum CodingKeys: String, CodingKey { case text, session, newChat = "new_chat" }

        init(_ text: String, _ target: ChatTarget) {
            self.text = text
            session = target.session
            newChat = target.newChat ? true : nil
        }
    }

    /// Opens a new conversation with the account's Setup on this taf (kiff/tafs#107): tafs
    /// cloud puts the taf's job, Card and latest calls in front of the owner's words.
    func improve(_ id: String, text: String) async throws -> Sent {
        try await send("POST", "/v1/tafs/\(escaped(id))/improve", body: ["text": text])
    }

    /// Sends the owner's message: into the taf's current run, or a new one.
    func sendMessage(_ id: String, text: String, to target: ChatTarget = .live) async throws -> Sent {
        try await send("POST", "/v1/tafs/\(escaped(id))/messages", body: MessageBody(text, target))
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

    /// What a streamed message's run does, as tafs cloud relays it (#60).
    enum StreamEvent: Equatable {
        /// The chat the message went into, first, and the run that answers it.
        case session(String, newSession: Bool, run: String? = nil)
        /// More of the answer, as it is written.
        case text(String)
        /// A tool the agent calls, by name, with what it's doing in a few words when tafs
        /// cloud says ("Checking your Stripe connection…"): never what it was given.
        case activity(String, step: String? = nil)
        /// The agent showed a component: the conversation has it.
        case component
        /// The run stopped: its status, and why.
        case end(status: String, stop: String)
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
    func streamMessage(_ id: String, text: String, to target: ChatTarget = .live) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        guard let url = URL(string: "/v1/tafs/\(escaped(id))/messages/stream", relativeTo: streamURL) else {
            throw CloudError(status: 0, message: "Bad path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // A run can think for minutes between two words.
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(MessageBody(text, target))
        guard let token else { throw CloudError(status: 401, message: "Sign in with KIFF first.") }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

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
        case "session": return (o["session"] as? String).map { .session($0, newSession: o["new_session"] as? Bool ?? false, run: o["run"] as? String) }
        case "taken": return .taken
        case "text": return (o["text"] as? String).map(StreamEvent.text)
        case "activity": return (o["tool"] as? String).map { .activity($0, step: o["step"] as? String) }
        case "component": return .component
        case "end": return .end(status: o["status"] as? String ?? "", stop: o["stop"] as? String ?? "")
        case "error": return .broken
        case "held": return .held(exception: o["exception"] as? String ?? "", invocation: o["invocation"] as? String ?? "")
        default: return nil
        }
    }

    // MARK: Setup (kiff/agents#91)

    /// A component as the conversation lists it. One the app doesn't know is skipped.
    struct Shown: Decodable, Equatable {
        let at: Date
        let session: String
        let run: String
        let component: SetupComponent
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

    /// Starts a taf Setup made (kiff/agents#91): kiff/agents claims its key from the draft
    /// Setup submitted, so it never reaches the phone or tafs cloud. Runs it once now, unless
    /// Setup said its tools can't find its work (kiff/tafs#138): then it works from its chat.
    func startFromSetup(name: String, job: String, draftID: String, unscheduled: Bool = false,
                        timezone: String = TimeZone.current.identifier) async throws -> Started {
        struct Body: Encodable {
            let name: String, job: String, draftID: String, timezone: String
            let fromSetup = true
            let unscheduled: Bool?
            enum CodingKeys: String, CodingKey { case name, job, timezone, unscheduled, draftID = "draft_id", fromSetup = "from_setup" }
        }
        return try await send("POST", "/v1/tafs", body: Body(name: name, job: job, draftID: draftID, timezone: timezone,
                                                              unscheduled: unscheduled ? true : nil))
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
        let created: Date
        let live: Bool
        /// The KIFF draft whose confirmation brings this version's Card revision, while it
        /// isn't confirmed; nil: the taf's current Cards.
        let draft: String?
        var id: Int { n }
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

    /// Stores the taf's next version as Setup drafted it (kiff/tafs#107), once per result
    /// (`from`): nothing goes live. Returns its number. `agent` is the result's: tafs cloud
    /// refuses an improvement of another taf's agent.
    func createVersion(_ id: String, note: String, notes: String?, draft: String?, from: String, agent: String? = nil) async throws -> Int {
        struct Body: Encodable { let note: String; let notes: String?; let draft: String?; let from: String; let agent: String? }
        struct Response: Decodable { let version: Int }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions", body: Body(note: note, notes: notes, draft: draft, from: from, agent: agent))
        return r.version
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

    /// A trial's run: what it says, and whether it has stopped.
    struct TrialRun: Decodable, Equatable {
        let id: String
        let status: String
        let output: String?
        let error: String?
        var finished: Bool { ["COMPLETED", "ERROR", "CANCELLED"].contains(status) }
    }

    /// One call a trial made, a read or a write: what KIFF would decide. It was not sent.
    struct TrialWrite: Decodable, Equatable {
        let tool: String
        let title: String?
        /// allowed, held or refused.
        let decision: String
        let reason: String?
    }

    struct Trial: Decodable, Equatable {
        let run: TrialRun
        let writes: [TrialWrite]
    }

    /// Starts a trial of version n on `input` (the job's own words when empty): a run whose
    /// writes KIFF decides without sending. Returns at once; `trial` follows it.
    func startTrial(_ id: String, version n: Int, input: String = "") async throws -> TrialRun {
        struct Response: Decodable { let run: TrialRun }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions/\(n)/trial", body: ["input": input])
        return r.run
    }

    /// A trial so far: its run, and what KIFF decided for each write.
    func trial(_ id: String, run: String) async throws -> Trial {
        try await send("GET", "/v1/tafs/\(escaped(id))/trials/\(escaped(run))")
    }

    /// Makes version n live. tafs cloud refuses (409) while its Card revision isn't confirmed.
    func makeLive(_ id: String, version n: Int) async throws {
        struct Response: Decodable { let live: Int }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/versions/\(n)/live")
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
    /// the KIFF target's signing secret, which checks KIFF's deliveries.
    func registerPush(deviceToken: String, environment: String, target: KIFFClient.AlertTarget? = nil) async throws -> PushStatus {
        struct Target: Encodable {
            let id: String, signingSecret: String
            enum CodingKeys: String, CodingKey { case id, signingSecret = "signing_secret" }
        }
        struct Body: Encodable {
            let deviceToken: String, environment: String, alertTarget: Target?
            enum CodingKeys: String, CodingKey { case environment, deviceToken = "device_token", alertTarget = "alert_target" }
        }
        return try await send("POST", "/v1/push", body: Body(
            deviceToken: deviceToken, environment: environment,
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

    func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await send(method, path, body: Optional<[String: String]>.none)
    }

    func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        guard let url = URL(string: path, relativeTo: baseURL) else { throw CloudError(status: 0, message: "Bad path") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 70
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
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
