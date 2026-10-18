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
        var errorDescription: String? { message }
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

        enum CodingKeys: String, CodingKey {
            case id, name, job, status
            case kiffAgent = "kiff_agent", nextRun = "next_run", lastRun = "last_run"
        }

        var paused: Bool { status == "paused" }
    }

    struct Started: Decodable, Equatable {
        let taf: CloudTaf
        let session: String
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

    /// The account's tafs, and the removals still to finish.
    func listing() async throws -> (tafs: [CloudTaf], removing: [PendingRemoval]) {
        struct Response: Decodable { let tafs: [CloudTaf]; let removing: [PendingRemoval]? }
        let r: Response = try await send("GET", "/v1/tafs")
        return (r.tafs, r.removing ?? [])
    }

    /// Starts a taf from a confirmed KIFF draft, and runs it once now. tafs cloud claims
    /// the agent's key with the draft's claim secret and keeps it in Secrets Manager: the
    /// key itself never reaches the phone. The secret works once.
    func start(name: String, job: String, draftID: String, claimSecret: String, timezone: String = TimeZone.current.identifier) async throws -> Started {
        struct Body: Encodable {
            let name: String, job: String, draftID: String, claimSecret: String, timezone: String
            enum CodingKeys: String, CodingKey { case name, job, timezone, draftID = "draft_id", claimSecret = "claim_secret" }
        }
        return try await send("POST", "/v1/tafs", body: Body(name: name, job: job, draftID: draftID, claimSecret: claimSecret, timezone: timezone))
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
        var id: String { "\(session)|\(at.timeIntervalSince1970)|\(from.rawValue)|\(text.hashValue)" }
        let at: Date
        let from: From
        let kind: Kind
        let text: String
        let session: String

        init(at: Date, from: From, kind: Kind = .message, text: String, session: String) {
            self.at = at; self.from = from; self.kind = kind; self.text = text; self.session = session
        }

        enum CodingKeys: String, CodingKey { case at, from, kind, text, session }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            at = try c.decode(Date.self, forKey: .at)
            from = try c.decode(From.self, forKey: .from)
            kind = (try? c.decode(Kind.self, forKey: .kind)) ?? .message
            text = try c.decode(String.self, forKey: .text)
            session = try c.decode(String.self, forKey: .session)
        }
    }

    /// The taf's thread, oldest first.
    /// A taf's thread, oldest first, and the session it's at work in now, if any.
    struct Thread: Equatable {
        let messages: [ChatMessage]
        let working: String?
    }

    func conversation(_ id: String) async throws -> Thread {
        struct Working: Decodable { let session: String }
        struct Response: Decodable { let messages: [ChatMessage]; let working: Working? }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/conversation")
        return Thread(messages: r.messages, working: r.working?.session)
    }

    struct Sent: Decodable, Equatable {
        let session: String
        let newSession: Bool
        enum CodingKeys: String, CodingKey { case session, newSession = "new_session" }
    }

    /// Sends the owner's message: into the taf's current run, or a new one.
    func sendMessage(_ id: String, text: String) async throws -> Sent {
        try await send("POST", "/v1/tafs/\(escaped(id))/messages", body: ["text": text])
    }

    /// What a streamed message's run does, as tafs cloud relays it (#60).
    enum StreamEvent: Equatable {
        /// The chat the message went into, first.
        case session(String, newSession: Bool)
        /// More of the answer, as it is written.
        case text(String)
        /// A tool the agent calls, by name: never what it was given.
        case activity(String)
        /// The agent showed a component: the conversation has it.
        case component
        /// The run stopped: its status, and why.
        case end(status: String, stop: String)
        /// The stream broke on tafs cloud's side; the conversation has the rest.
        case broken
    }

    /// Sends the owner's message with its run streamed: returns once tafs cloud took it,
    /// with the run's events to follow. A refusal throws, as `sendMessage` does.
    func streamMessage(_ id: String, text: String) async throws -> AsyncThrowingStream<StreamEvent, Error> {
        guard let url = URL(string: "/v1/tafs/\(escaped(id))/messages/stream", relativeTo: streamURL) else {
            throw CloudError(status: 0, message: "Bad path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        // A run can think for minutes between two words.
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["text": text])
        guard let token else { throw CloudError(status: 401, message: "Sign in with KIFF first.") }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            var data = Data()
            for try await b in bytes { data.append(b) }
            let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "tafs cloud answered \(status)"
            throw CloudError(status: status, message: message)
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
        case "session": return (o["session"] as? String).map { .session($0, newSession: o["new_session"] as? Bool ?? false) }
        case "text": return (o["text"] as? String).map(StreamEvent.text)
        case "activity": return (o["tool"] as? String).map(StreamEvent.activity)
        case "component": return .component
        case "end": return .end(status: o["status"] as? String ?? "", stop: o["stop"] as? String ?? "")
        case "error": return .broken
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

    /// Starts the account's Setup from a confirmed no-tools KIFF draft (kiff-cloud#1254).
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
    /// Setup submitted, so it never reaches the phone or tafs cloud. Runs it once now.
    func startFromSetup(name: String, job: String, draftID: String, timezone: String = TimeZone.current.identifier) async throws -> Started {
        struct Body: Encodable {
            let name: String, job: String, draftID: String, timezone: String
            let fromSetup = true
            enum CodingKeys: String, CodingKey { case name, job, timezone, draftID = "draft_id", fromSetup = "from_setup" }
        }
        return try await send("POST", "/v1/tafs", body: Body(name: name, job: job, draftID: draftID, timezone: timezone))
    }

    /// Setup's thread: its messages and the components it showed.
    func setupThread(_ id: String) async throws -> SetupThread {
        struct Working: Decodable { let session: String }
        struct Response: Decodable { let messages: [ChatMessage]; let components: [LenientShown]?; let working: Working? }
        let r: Response = try await send("GET", "/v1/tafs/\(escaped(id))/conversation")
        return SetupThread(messages: r.messages, components: SetupThread.fold((r.components ?? []).compactMap(\.shown)),
                           working: r.working?.session)
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

    // MARK: Alerts

    /// Where KIFF posts the account's held calls, for tafs cloud to push to its phones.
    var alertsURL: URL { URL(string: "/v1/kiff/alerts", relativeTo: baseURL)!.absoluteURL }

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

    /// This phone stops getting the account's alerts.
    func unregisterPush(deviceToken: String) async throws {
        struct Response: Decodable { let removed: Bool }
        let _: Response = try await send("DELETE", "/v1/push/\(escaped(deviceToken))")
    }

    // MARK: Transport

    private func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await send(method, path, body: Optional<[String: String]>.none)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
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
            let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "tafs cloud answered \(status)"
            throw CloudError(status: status, message: message)
        }
        return try KIFFClient.decoder.decode(T.self, from: data)
    }
}
