import Foundation

/// tafs cloud: the few calls that need the Anthropic key, which the phone never holds.
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
    var token: String?
    private let session: URLSession

    static let defaultURL = URL(string: "https://qvjurway3eczrk7pqorfuy7z5y0vuztq.lambda-url.us-east-1.on.aws")!

    init(baseURL: URL = TafsCloud.defaultURL, token: String? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
    }

    func tafs() async throws -> [CloudTaf] {
        struct Response: Decodable { let tafs: [CloudTaf] }
        let r: Response = try await send("GET", "/v1/tafs")
        return r.tafs
    }

    /// Starts a taf on its job, acting with the agent key, and runs it once now.
    func start(name: String, job: String, agentKey: String, timezone: String = TimeZone.current.identifier) async throws -> Started {
        struct Body: Encodable {
            let name: String, job: String, agentKey: String, timezone: String
            enum CodingKeys: String, CodingKey { case name, job, timezone, agentKey = "agent_key" }
        }
        return try await send("POST", "/v1/tafs", body: Body(name: name, job: job, agentKey: agentKey, timezone: timezone))
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
