import Foundation

/// The KIFF API as the iPhone uses it: device sign-in (RFC 034-B), held calls
/// (`/v1/me/card-exceptions`), Cards (`/v1/me/mandates`), and what starting a taf
/// needs: the gateway's connected tools, a new agent, its Cards and its key.
///
/// tafs is a KIFF customer like any other: it only uses the public API.
@MainActor
final class KIFFClient {
    enum KIFFError: Error, Equatable, LocalizedError {
        case authorizationPending
        case slowDown
        case expired
        case unauthorized
        case server(status: Int, message: String)

        var errorDescription: String? {
            switch self {
            case .authorizationPending: return "Waiting for you to approve the sign-in."
            case .slowDown: return "Asked KIFF too often; slowing down."
            case .expired: return "The sign-in code expired. Start again."
            case .unauthorized: return "Signed out of KIFF. Sign in again."
            case .server(_, let message): return message
            }
        }
    }

    let baseURL: URL
    /// Where a person answers in KIFF's own app.
    let webURL: URL
    /// KIFF's MCP gateway, which lists the account's connected tools.
    let gatewayURL: URL
    var token: String?
    /// Whether KIFF checks the phone's signature on an answer (docs/needs-from-kiff.md, item 1).
    /// Until it does, the phone does not answer KIFF's held calls: an answer that KIFF accepts on
    /// the session alone would not prove this iPhone's owner gave it.
    let serverVerifiesSignatures: Bool
    private let session: URLSession

    init(baseURL: URL = URL(string: "https://api.kiff.dev")!,
         webURL: URL = URL(string: "https://app.kiff.dev")!,
         gatewayURL: URL = URL(string: "https://mcp.kiff.dev")!,
         token: String? = nil,
         serverVerifiesSignatures: Bool = false,
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.webURL = webURL
        self.gatewayURL = gatewayURL
        self.token = token
        self.serverVerifiesSignatures = serverVerifiesSignatures
        self.session = session
    }

    /// The held call's page in KIFF, where it can be answered today.
    func answerPage(for exceptionID: String) -> URL {
        webURL.appendingPathComponent("needs-you").appendingPathComponent(exceptionID)
    }

    // MARK: Device sign-in

    struct DeviceStart: Decodable, Equatable {
        let deviceCode: String
        let userCode: String
        let verificationURI: String
        let verificationURIComplete: String
        let expiresIn: Int
        let interval: Int

        enum CodingKeys: String, CodingKey {
            case deviceCode = "device_code", userCode = "user_code"
            case verificationURI = "verification_uri", verificationURIComplete = "verification_uri_complete"
            case expiresIn = "expires_in", interval
        }
    }

    struct DeviceToken: Decodable, Equatable {
        let accessToken: String
        let expiresIn: Int
        let tenant: String
        let subject: String

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token", expiresIn = "expires_in", tenant, subject
        }
    }

    func startDeviceSignIn() async throws -> DeviceStart {
        try await send("POST", "/v1/auth/device/start", body: [String: String](), authorized: false)
    }

    /// One poll. Throws `.authorizationPending` until the person approves.
    func pollDeviceToken(deviceCode: String) async throws -> DeviceToken {
        do {
            return try await send("POST", "/v1/auth/device/token", body: ["device_code": deviceCode], authorized: false)
        } catch KIFFError.server(400, let message) {
            switch message {
            case "authorization_pending": throw KIFFError.authorizationPending
            case "slow_down": throw KIFFError.slowDown
            case "expired_token": throw KIFFError.expired
            default: throw KIFFError.server(status: 400, message: message)
            }
        }
    }

    func signOut() async throws {
        let _: Empty = try await send("POST", "/v1/auth/device/logout", body: [String: String]())
    }

    // MARK: Held calls

    struct CardException: Decodable, Equatable {
        struct Breach: Decodable, Equatable {
            let outcome: String
            let reason: String?
            let limit: Int64?
            let quantity: String?
            /// What the summed amount means, when KIFF could say.
            let unit: String?
            let scale: Int?
            let unitUnavailable: Bool?
            let unitAmbiguous: Bool?

            enum CodingKeys: String, CodingKey {
                case outcome, reason, limit, quantity, unit, scale
                case unitUnavailable = "unit_unavailable", unitAmbiguous = "unit_ambiguous"
            }
        }

        /// A parameter's declared unit and decimal places ("EUR", 2).
        struct ParameterUnit: Decodable, Equatable {
            let unit: String
            let scale: Int
        }

        let id: String
        let subject: String
        let action: String
        let entityID: String
        let status: String
        let breaches: [Breach]
        let parameters: [String: Int64]?
        let reason: String?
        /// The units the held action declares for its parameters; absent for a bare number.
        let parameterUnits: [String: ParameterUnit]?
        /// KIFF could not read the units: amounts are not bare, just unknown.
        let unitsUnavailable: Bool?
        let nextSteps: [String]?
        let createdAt: Date
        let expiresAt: Date?

        enum CodingKeys: String, CodingKey {
            case id, subject, action, status, breaches, parameters, reason
            case entityID = "entity_id", nextSteps = "next_steps"
            case parameterUnits = "parameter_units", unitsUnavailable = "units_unavailable"
            case createdAt = "created_at", expiresAt = "expires_at"
        }
    }

    func heldCalls() async throws -> [CardException] {
        struct Response: Decodable { let exceptions: [CardException] }
        let response: Response = try await send("GET", "/v1/me/card-exceptions?status=held")
        return response.exceptions
    }

    func approveOnce(_ id: String) async throws {
        let _: Empty = try await send("POST", "/v1/me/card-exceptions/\(escaped(id))/approve-once", body: [String: String]())
    }

    /// Refuses a held call; `nextStep` is one of the Card's next steps ("return first").
    func reject(_ id: String, nextStep: String? = nil) async throws {
        var body: [String: String] = [:]
        if let nextStep { body["next_step"] = nextStep }
        let _: Empty = try await send("POST", "/v1/me/card-exceptions/\(escaped(id))/reject", body: body)
    }

    // MARK: Cards

    struct Mandate: Decodable, Equatable {
        struct Grant: Decodable, Equatable {
            struct PerAction: Decodable, Equatable {
                let quantity: String
                let max: Int64
                /// What a summed limit means ("EUR", 2), when KIFF can say.
                var unit: String? = nil
                var scale: Int? = nil
            }
            let action: String
            let perActionMax: [PerAction]?
            let nextSteps: [String]?

            enum CodingKeys: String, CodingKey {
                case action, perActionMax = "per_action_max", nextSteps = "next_steps"
            }
        }

        struct Aggregate: Decodable, Equatable {
            let quantity: String
            let limit: Int64
            let window: String
            var unit: String? = nil
            var scale: Int? = nil
        }

        /// A limit's use in its current window (`?include=use`, the same lines as the
        /// Card's statement). `used` is absent when KIFF could not read it.
        struct Limit: Decodable, Equatable {
            let quantity: String
            let limit: Int64
            let used: Int64?
            let remaining: Int64?
            let status: String?
            var unit: String? = nil
            var scale: Int? = nil
        }

        let id: String
        let subject: String
        let grants: [Grant]
        let aggregates: [Aggregate]
        let active: Bool
        let validUntil: Date?
        let revokedAt: Date?
        let onExceed: String?
        let limits: [Limit]?

        enum CodingKeys: String, CodingKey {
            case id, subject, grants, aggregates, active, limits
            case validUntil = "valid_until", revokedAt = "revoked_at", onExceed = "on_exceed"
        }
    }

    func cards() async throws -> [Mandate] {
        struct Response: Decodable { let mandates: [Mandate] }
        // With each limit's use, so the app shows "$212 of $500 today" without a
        // statement call per Card.
        let response: Response = try await send("GET", "/v1/me/mandates?include=use")
        return response.mandates
    }

    func revoke(cardID: String) async throws {
        let _: Empty = try await send("POST", "/v1/me/mandates/\(escaped(cardID))/revoke", body: [String: String]())
    }

    // MARK: Starting a taf

    /// A tool the account connected to KIFF's gateway. A Card names it as its action.
    struct Connection: Decodable, Equatable, Identifiable {
        var id: String { name }
        let name: String
        let description: String?
        /// The argument that carries the amount, when the tool moves one.
        let amountArgument: String?
        /// What the amount means ("USD") and its decimal places (2), when declared.
        let amountUnit: String?
        let amountScale: Int?
        let readOnly: Bool

        enum CodingKeys: String, CodingKey {
            case name, description
            case amountArgument = "amount_argument", amountUnit = "amount_unit", amountScale = "amount_scale"
            case readOnly = "read_only"
        }

        var movesAmount: Bool { !(amountArgument ?? "").isEmpty }
    }

    /// The account's connected tools, from the gateway, with the person's own session.
    func connections() async throws -> [Connection] {
        struct Response: Decodable { let connections: [Connection] }
        let response: Response = try await send("GET", "/v1/connections", base: gatewayURL)
        return response.connections
    }

    /// Records a new agent. 409 when the name is taken.
    func createAgent(id: String) async throws {
        let _: Empty = try await send("POST", "/v1/me/agents", body: ["id": id])
    }

    /// A Card for one tool on the gateway domain, as the dashboard issues it.
    struct CardRequest: Encodable, Equatable {
        struct Grant: Encodable, Equatable {
            struct PerAction: Encodable, Equatable { let quantity: String; let max: Int64 }
            let action: String
            var perActionMax: [PerAction]?
            var nextSteps: [String]?
            enum CodingKeys: String, CodingKey { case action, perActionMax = "per_action_max", nextSteps = "next_steps" }
        }
        struct Aggregate: Encodable, Equatable {
            let quantity: String
            let limit: Int64
            let window: String
        }
        let id: String
        let subject: String
        var domain = "gateway"
        let grants: [Grant]
        let aggregates: [Aggregate]
        var onExceed = "owner"
        var holdExpiry = "10m"
        enum CodingKeys: String, CodingKey {
            case id, subject, domain, grants, aggregates
            case onExceed = "on_exceed", holdExpiry = "hold_expiry"
        }
    }

    @discardableResult
    func issueCard(_ card: CardRequest) async throws -> Mandate {
        try await send("POST", "/v1/me/mandates", body: card)
    }

    /// A key bound to the agent, for the gateway only. KIFF returns the secret once.
    struct AgentKey: Decodable, Equatable {
        let id: String
        let plaintext: String
        let agentID: String?
        enum CodingKeys: String, CodingKey { case id, plaintext, agentID = "agent_id" }
    }

    /// Only an admin may mint it: an editor gets 403.
    func mintGatewayKey(agentID: String, label: String) async throws -> AgentKey {
        struct Body: Encodable {
            let label: String
            let roles: [String]
            let agentID: String
            enum CodingKeys: String, CodingKey { case label, roles, agentID = "agent_id" }
        }
        return try await send("POST", "/v1/keys", body: Body(label: label, roles: ["gateway_agent"], agentID: agentID))
    }

    // MARK: Transport

    private struct Empty: Decodable {}

    private func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    private func send<T: Decodable>(_ method: String, _ path: String, base: URL? = nil, authorized: Bool = true) async throws -> T {
        try await send(method, path, body: Optional<[String: String]>.none, base: base, authorized: authorized)
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, base: URL? = nil, authorized: Bool = true) async throws -> T {
        guard let url = URL(string: path, relativeTo: base ?? baseURL) else { throw KIFFError.server(status: 0, message: "Bad path") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("tafs-ios", forHTTPHeaderField: "User-Agent")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if authorized {
            guard let token else { throw KIFFError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw KIFFError.unauthorized }
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "KIFF answered \(status)"
            throw KIFFError.server(status: status, message: message)
        }
        if T.self == Empty.self || data.isEmpty { return try JSONDecoder().decode(T.self, from: Data("{}".utf8)) }
        return try Self.decoder.decode(T.self, from: data)
    }

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let raw = try decoder.singleValueContainer().decode(String.self)
            let precise = ISO8601DateFormatter()
            precise.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = precise.date(from: raw) ?? ISO8601DateFormatter().date(from: raw) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Bad date \(raw)"))
        }
        return d
    }()
}
