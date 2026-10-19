import CryptoKit
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
    /// Whether KIFF checks the phone's signature on an answer (kiff/kiff-cloud#1218). Without
    /// it the phone does not answer KIFF's held calls: an answer that KIFF accepts on the
    /// session alone would not prove this iPhone's owner gave it.
    let serverVerifiesSignatures: Bool
    private let session: URLSession
    /// The account the phone signed in to, for signed setup calls.
    var tenant: String?
    /// Signs a setup call for a phone session bound to its key (kiff-cloud #1225): set
    /// while a start the owner confirmed with Face ID runs, nil otherwise.
    var setupSigner: (@MainActor (Data) throws -> String)?
    /// The calls KIFF takes from a phone's session only with its signature: the draft that
    /// starts a taf (kiff-cloud #1217, #1225), the alert target that tells tafs cloud when a
    /// call is held (#1234), and the connector key planque keeps (#1251).
    static let signedSetupPaths: Set<String> = ["/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys"]

    /// Whether KIFF takes `path` from a phone only with its signature: the paths above, and
    /// confirming a draft someone else submitted (`/v1/me/agent-drafts/{id}/confirm`).
    static func signsSetup(_ path: String) -> Bool {
        if signedSetupPaths.contains(path) { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return parts.count == 6 && path.hasPrefix("/v1/me/agent-drafts/") && parts[5] == "confirm" && !parts[4].isEmpty
    }

    init(baseURL: URL = URL(string: "https://api.kiff.dev")!,
         webURL: URL = URL(string: "https://app.kiff.dev")!,
         gatewayURL: URL = URL(string: "https://mcp.kiff.dev")!,
         token: String? = nil,
         serverVerifiesSignatures: Bool = true,
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

    /// Starts signing in. `publicKey` (SPKI DER) is the phone's signing key: KIFF shows its
    /// fingerprint when the owner approves in their browser, and binds it to this session.
    func startDeviceSignIn(publicKey: Data? = nil, deviceName: String? = nil, platform: String? = nil) async throws -> DeviceStart {
        var body: [String: String] = [:]
        if let publicKey { body["public_key"] = publicKey.base64EncodedString() }
        if let deviceName { body["device_name"] = deviceName }
        // KIFF's device list names the platform (kiff/kiff-cloud#1267): ios, ipados, macos.
        if let platform { body["platform"] = platform }
        return try await send("POST", "/v1/auth/device/start", body: body, authorized: false)
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

    /// This device's id in KIFF (`dev_…`, kiff-cloud #1267), from the person's device list:
    /// planque asks KIFF about the device by it (kiff/planque#10). Nil when KIFF lists
    /// this session without a device yet.
    func thisDeviceID() async throws -> String? {
        struct Device: Decodable {
            let id: String
            let thisDevice: Bool?
            enum CodingKeys: String, CodingKey { case id, thisDevice = "this_device" }
        }
        struct Response: Decodable { let devices: [Device] }
        let r: Response = try await send("GET", "/v1/me/devices")
        return r.devices.first { $0.thisDevice == true && $0.id.hasPrefix("dev_") }?.id
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
        /// The hold's accounting version: part of what the phone signs, so an answer
        /// counts only for the hold as it stood when the owner saw it.
        let accountingVersion: Int?

        /// The held call's tool, as KIFF names it for people (kiff-cloud#1277).
        var title: String? = nil

        enum CodingKeys: String, CodingKey {
            case id, subject, action, title, status, breaches, parameters, reason
            case entityID = "entity_id", nextSteps = "next_steps", accountingVersion = "accounting_version"
            case parameterUnits = "parameter_units", unitsUnavailable = "units_unavailable"
            case createdAt = "created_at", expiresAt = "expires_at"
        }
    }

    /// The KIFF account this sign-in is in (kiff/tafs#73): its name and slug, and the
    /// person's email when KIFF says it.
    struct Account: Decodable, Equatable {
        let tenantID: String
        let slug: String
        let displayName: String?
        let email: String?

        enum CodingKeys: String, CodingKey {
            case tenantID = "tenant_id", slug = "tenant_slug", displayName = "tenant_display_name", email
        }

        /// The account as the owner knows it: its name, else its slug.
        var name: String { displayName.flatMap { $0.isEmpty ? nil : $0 } ?? slug }
    }

    func me() async throws -> Account {
        try await send("GET", "/v1/me")
    }

    func heldCalls() async throws -> [CardException] {
        struct Response: Decodable { let exceptions: [CardException] }
        let response: Response = try await send("GET", "/v1/me/card-exceptions?status=held")
        return response.exceptions
    }

    /// The phone's signature on an answer, as KIFF checks it.
    struct AnswerSignature: Encodable, Equatable {
        let signedAt: String
        let value: String

        enum CodingKeys: String, CodingKey { case signedAt = "signed_at", value }
    }

    private struct AnswerBody: Encodable {
        var nextStep: String?
        var signature: AnswerSignature?

        enum CodingKeys: String, CodingKey { case nextStep = "next_step", signature }
    }

    /// What the phone signs to answer a held call: KIFF's `kiff.answer.v1` message, one
    /// field per line (kiff-cloud apps/api/internal/auth/devicekey.go).
    static func answerMessage(tenant: String, exceptionID: String, answer: String, nextStep: String?,
                              note: String? = nil, accountingVersion: Int, signedAt: String) -> Data {
        let noteHash = note.flatMap { $0.isEmpty ? nil : $0 }
            .map { SHA256.hash(data: Data($0.utf8)).map { String(format: "%02x", $0) }.joined() } ?? ""
        let lines = ["kiff.answer.v1", tenant, exceptionID, answer, nextStep ?? "", noteHash,
                     String(accountingVersion), signedAt]
        return Data(lines.joined(separator: "\n").utf8)
    }

    /// What the phone signs for a setup call: KIFF's `kiff.request.v1` message
    /// (kiff-cloud apps/api/internal/auth/devicekey.go).
    static func requestMessage(tenant: String, method: String, uri: String, body: Data, signedAt: String) -> Data {
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        return Data(["kiff.request.v1", tenant, method, uri, bodyHash, signedAt].joined(separator: "\n").utf8)
    }

    func approveOnce(_ id: String, signature: AnswerSignature? = nil) async throws {
        let _: Empty = try await send("POST", "/v1/me/card-exceptions/\(escaped(id))/approve-once",
                                      body: AnswerBody(signature: signature))
    }

    /// Refuses a held call; `nextStep` is one of the Card's next steps ("return first").
    func reject(_ id: String, nextStep: String? = nil, signature: AnswerSignature? = nil) async throws {
        let _: Empty = try await send("POST", "/v1/me/card-exceptions/\(escaped(id))/reject",
                                      body: AnswerBody(nextStep: nextStep, signature: signature))
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
            /// The tool's name for people, as KIFF keeps it from the vendor (kiff-cloud#1277).
            var title: String? = nil
            let perActionMax: [PerAction]?
            let nextSteps: [String]?

            enum CodingKeys: String, CodingKey {
                case action, title, perActionMax = "per_action_max", nextSteps = "next_steps"
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
            let window: String?
            /// The ceiling now, extra room included.
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

    /// A new agent and its Cards, submitted as one draft (kiff-cloud #1217). Submitted
    /// by someone who may issue Cards and give keys, from their phone and signed, it is
    /// confirmed at once: KIFF issues the Cards. Its key is minted only when the
    /// `claimSecret` is claimed, which tafs cloud does, so the key never reaches the phone.
    struct AgentDraft: Decodable, Equatable {
        let id: String
        let agentID: String
        /// draft (waits for someone who may confirm it), confirming, confirmed, rolling_back,
        /// failed, discarding, discarded, claimed.
        let status: String
        let version: String
        /// Shown once, in the answer to the submit.
        let claimSecret: String?
        let cardIDs: [String]?
        let reason: String?
        /// The Cards it would issue, as the owner reads them before confirming. Read
        /// leniently: a field KIFF adds later doesn't stop the draft being shown.
        var cards: [DraftCard]?
        /// An agent that holds no tools (kiff-cloud#1254): Setup.
        var noTools: Bool?
        /// The name it was submitted with, such as the taf's ("Refunds assistant").
        var label: String?
        enum CodingKeys: String, CodingKey {
            case id, status, version, reason, cards, label
            case agentID = "agent_id", claimSecret = "claim_secret", cardIDs = "card_ids", noTools = "no_tools"
        }
        var confirmed: Bool { status == "confirmed" }
        /// "Refunds assistant's Card": the name it was submitted with, never its agent id
        /// (kiff/tafs#94).
        var cardTitle: String {
            let name = (label ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? "The taf's Card" : "\(name)'s Card"
        }
    }

    /// One Card of a draft, as the owner reads it: each action and its limits.
    struct DraftCard: Decodable, Equatable {
        struct Grant: Decodable, Equatable {
            /// `unit` and `scale` when the tool declares them (kiff-cloud #1274).
            struct PerAction: Decodable, Equatable { let quantity: String; let max: Int64; var unit: String?; var scale: Int? }
            let action: String
            var title: String?
            let perActionMax: [PerAction]?
            enum CodingKeys: String, CodingKey { case action, title, perActionMax = "per_action_max" }
        }
        struct Aggregate: Decodable, Equatable { let quantity: String; let limit: Int64; let window: String; var unit: String?; var scale: Int? }
        let grants: [Grant]?
        let aggregates: [Aggregate]?

        /// The lines the owner approves, in their words (kiff/tafs#94): "Refund a payment ·
        /// create_refund_k3f9: up to €100.00 each", "€1,000.00 in total a day", "20 calls a
        /// day". The vendor's title stands beside KIFF's action, never instead of it; an
        /// amount's unit is KIFF's, or the number stays bare: never a guessed name or
        /// currency, never "sum(amount)".
        var lines: [String] {
            var out: [String] = []
            for g in grants ?? [] {
                let caps = (g.perActionMax ?? []).map { cap in
                    KIFFMapping.isSum(cap.quantity)
                        ? "up to \(KIFFMapping.money(cap.max, unit: cap.unit, scale: cap.scale).formatted) each"
                        : "up to \(cap.max) each"
                }
                let action = KIFFMapping.approvalName(g.action, title: g.title)
                out.append(caps.isEmpty ? action : "\(action): " + caps.joined(separator: ", "))
            }
            for a in aggregates ?? [] {
                let window = KIFFMapping.window(a.window).label
                out.append(KIFFMapping.isSum(a.quantity)
                    ? "\(KIFFMapping.money(a.limit, unit: a.unit, scale: a.scale).formatted) in total \(window)"
                    : "\(a.limit) \(a.limit == 1 ? "call" : "calls") \(window)")
            }
            return out
        }
    }

    /// A draft for Setup, an agent that holds no tools (kiff-cloud#1254). From the phone,
    /// signed, it is confirmed at once; tafs cloud then claims its key.
    func submitNoToolsDraft(agentID: String, label: String) async throws -> AgentDraft {
        struct Body: Encodable {
            let agentID: String
            let label: String
            let noTools = true
            enum CodingKeys: String, CodingKey { case label, agentID = "agent_id", noTools = "no_tools" }
        }
        return try await send("POST", "/v1/me/agent-drafts", body: Body(agentID: agentID, label: label))
    }

    func submitDraft(agentID: String, label: String, cards: [CardRequest]) async throws -> AgentDraft {
        struct Body: Encodable {
            let agentID: String
            let label: String
            let cards: [CardRequest]
            enum CodingKeys: String, CodingKey { case label, cards, agentID = "agent_id" }
        }
        return try await send("POST", "/v1/me/agent-drafts", body: Body(agentID: agentID, label: label, cards: cards))
    }

    /// A draft as it is now: has someone confirmed it since it was submitted?
    func draft(id: String) async throws -> AgentDraft {
        try await send("GET", "/v1/me/agent-drafts/\(escaped(id))")
    }

    /// Confirms a draft another agent submitted, exactly the version the owner was shown
    /// (kiff-cloud #1237): KIFF issues its Cards. A signed setup call.
    func confirmDraft(id: String, version: String) async throws -> AgentDraft {
        try await send("POST", "/v1/me/agent-drafts/\(escaped(id))/confirm", body: ["version": version])
    }

    /// Discards a draft nobody claimed, and revokes the Cards it issued.
    func discardDraft(id: String) async throws {
        let _: Empty = try await send("DELETE", "/v1/me/agent-drafts/\(escaped(id))")
    }

    // MARK: Alerts

    /// Where KIFF sends the account's held calls, as a phone registers it (kiff-cloud #1234):
    /// only to an app backend KIFF lists, for held calls and their answers only. The signing
    /// secret is shown this once.
    struct AlertTarget: Codable, Equatable {
        let id: String
        let signingSecret: String
        enum CodingKeys: String, CodingKey { case id, signingSecret = "signing_secret" }
    }

    /// Points the account's held-call alerts at `url`, replacing the target there before.
    /// A signed setup call: the owner confirms it with Face ID first.
    func registerAlertTarget(url: URL) async throws -> AlertTarget {
        try await send("POST", "/v1/me/alert-targets", body: ["url": url.absoluteString])
    }

    // MARK: Connector key

    /// The key planque keeps to register a taf's tools in KIFF (kiff-cloud #1251): it
    /// connects tools and does nothing else. Minted from the phone with a signed setup call;
    /// the plaintext is shown this once and goes straight to planque, never to storage.
    func mintConnectorKey() async throws -> String {
        struct Minted: Decodable { let plaintext: String }
        struct Body: Encodable { let label: String; let roles: [String] }
        let minted: Minted = try await send("POST", "/v1/keys", body: Body(label: "planque", roles: ["connector"]))
        return minted.plaintext
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
        if method == "POST", base == nil, Self.signsSetup(path), let setupSigner, let tenant {
            let signedAt = ISO8601DateFormatter().string(from: Date())
            let uri = url.path + (url.query.map { "?" + $0 } ?? "")
            let message = Self.requestMessage(tenant: tenant, method: method, uri: uri, body: request.httpBody ?? Data(), signedAt: signedAt)
            request.setValue("t=\(signedAt),v1=\(try setupSigner(message))", forHTTPHeaderField: "KIFF-Device-Signature")
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
