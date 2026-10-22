import CryptoKit
import Foundation

/// The KIFF API as the iPhone uses it: device sign-in (RFC 034-B), held calls
/// (`/v1/me/card-exceptions`), revoking a Card, and what starting a taf needs: the
/// gateway's connected tools, a new agent, its Cards and its key. The Cards themselves
/// reach the app through tafs cloud's list (`TafsCloud.home`).
///
/// tafs is a KIFF customer like any other: it only uses the public API.
@MainActor
final class KIFFClient {
    enum KIFFError: Error, Equatable, LocalizedError {
        case authorizationPending
        case slowDown
        case expired
        case unauthorized
        /// reason is KIFF's machine-readable why, when it gives one ("card_revised").
        case server(status: Int, message: String, reason: String? = nil)

        var errorDescription: String? {
            switch self {
            case .authorizationPending: return "Waiting for you to approve the sign-in."
            case .slowDown: return "Asked KIFF too often; slowing down."
            case .expired: return "The sign-in code expired. Start again."
            case .unauthorized: return "Signed out of KIFF. Sign in again."
            case .server(_, let message, _): return message
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
    /// call is held (#1234), the connector key planque keeps (#1251), and the account's
    /// time zone (#1278).
    static let signedSetupPaths: Set<String> = ["/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys", "/v1/me/time-zone"]

    /// Whether KIFF takes `path` from a phone only with its signature: the paths above,
    /// confirming a draft someone else submitted (`/v1/me/agent-drafts/{id}/confirm`), and
    /// resuming a halt (`/v1/agents/{id}/resume`, `/v1/resume`).
    static func signsSetup(_ path: String) -> Bool {
        if signedSetupPaths.contains(path) { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        // Resuming a halted agent, or the whole account (kiff/kiff-cloud#1061): it gives authority back.
        if path == "/v1/resume" || (parts.count == 5 && path.hasPrefix("/v1/agents/") && parts[4] == "resume" && !parts[3].isEmpty) { return true }
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
        } catch KIFFError.server(400, let message, _) {
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

        /// One argument of a held call, as text: a string as it is, a number or a flag
        /// written out, anything else left as "…".
        struct ArgumentText: Decodable, Equatable {
            let text: String
            init(from decoder: Decoder) throws {
                let c = try decoder.singleValueContainer()
                if let s = try? c.decode(String.self) { text = s }
                else if let i = try? c.decode(Int64.self) { text = String(i) }
                else if let d = try? c.decode(Double.self) { text = Self.plain(d) }
                else if let b = try? c.decode(Bool.self) { text = b ? "true" : "false" }
                else { text = "…" }
            }

            /// A decimal written out plainly, as sent: "0.000015", never "1.5e-05".
            static func plain(_ d: Double) -> String {
                let f = NumberFormatter()
                f.locale = Locale(identifier: "en_US_POSIX")
                f.numberStyle = .decimal
                f.usesGroupingSeparator = false
                f.usesSignificantDigits = true
                f.maximumSignificantDigits = 15
                return f.string(from: NSNumber(value: d)) ?? String(d)
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
        /// What a held gateway call asks, as KIFF keeps it (its arguments), each as text:
        /// what the call is on, which the tool-call id it's filed under doesn't say.
        var arguments: [String: ArgumentText]? = nil
        /// The proposal KIFF held: what its activity files the call under, so the owner's
        /// answer and KIFF's outcome are one line (kiff/tafs#138, item 51).
        var proposalID: String? = nil
        /// KIFF's answer to whether approving once can pass this call (kiff/tafs#138, item 49).
        /// Absent from an older response: then the app offers it, as before.
        var canApproveOnce: Bool? = nil

        enum CodingKeys: String, CodingKey {
            case proposalID = "proposal_id", canApproveOnce = "can_approve_once"
            case id, subject, action, title, arguments, status, breaches, parameters, reason
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
        /// The account's time zone, which ends a Card's day (kiff-cloud #1121).
        var timeZone: String? = nil

        enum CodingKeys: String, CodingKey {
            case tenantID = "tenant_id", slug = "tenant_slug", displayName = "tenant_display_name", email
            case timeZone = "time_zone"
        }

        /// The account as the owner knows it: its name, else its slug.
        var name: String { displayName.flatMap { $0.isEmpty ? nil : $0 } ?? slug }
    }

    func me() async throws -> Account {
        try await send("GET", "/v1/me")
    }

    /// Sets the account's time zone (an IANA name): a signed setup call from a phone
    /// (kiff-cloud #1278), which only the account's owner or an admin may make.
    func setTimeZone(_ zone: String) async throws -> String {
        struct Saved: Decodable { let timeZone: String; enum CodingKeys: String, CodingKey { case timeZone = "time_zone" } }
        let saved: Saved = try await send("PUT", "/v1/me/time-zone", body: ["time_zone": zone])
        return saved.timeZone
    }

    /// One call of the account's, as KIFF's activity records it (kiff-cloud #1279): what a
    /// taf did, newest first, with the tool's title for people.
    struct ActivityCall: Decodable, Equatable {
        /// How the call ended at the tool (kiff-cloud `tooloutcome`): done, refused_by_tool,
        /// not_sent, sending, unknown or not_recorded, with the tool's or gateway's message.
        struct ToolOutcome: Decodable, Equatable {
            let status: String
            var message: String?
        }
        let proposalID: String
        let actionName: String
        var title: String?
        var actorID: String?
        let outcome: String
        let createdAt: Date
        let held: Bool
        var toolOutcome: ToolOutcome?
        /// What the Record says of a call (kiff/tafs#167). Each is absent when it doesn't apply.
        /// The money it moves, as a decimal string and its unit ("150.00", "EUR"). A gateway
        /// call's integer amount has no unit (KIFF writes it with omitempty).
        struct Amount: Equatable, Hashable, Codable {
            let value: String
            var unit: String?
        }
        var amount: Amount?
        var readOnly: Bool?
        /// KIFF's key for the call (the gateway's "tc-…"), what tafs' call_ref names it by.
        var entityID: String?
        /// What it acted on, as KIFF signs it: "sha256:<hex>" of the value, and the name of
        /// the argument it came from ("payment"). KIFF keeps no argument but numbers.
        var onHash: String?
        var onArg: String?
        /// The owner's answer to a held call: approved once or refused, signed on which device.
        struct Answer: Equatable, Hashable, Codable {
            let decision: String
            /// The answerer's role ("admin"), not who they are.
            var by: String?
            /// The person reading answered it themselves (KIFF knows; a role can't say).
            var you: Bool?
            var signed: Bool?
            var device: String?
            var at: Date?
        }
        var answer: Answer?
        /// Its KIFF receipt: signed, or pending until KIFF's next sweep (about 30 s).
        struct Receipt: Equatable, Hashable, Codable {
            let traceID: String
            let status: String
            enum CodingKeys: String, CodingKey { case status; case traceID = "trace_id" }
        }
        var receipt: Receipt?
        /// KIFF's reasons for its decision ("over the €100.00 limit for one refund").
        var reasons: [String]?
        /// When the call was made: its first decision. createdAt is its latest, so a held call
        /// answered later keeps its place by this (kiff/tafs#138).
        var madeAt: Date?
        /// Why it wasn't sent, in KIFF's words, in the app's language.
        var reason: String?
        enum CodingKeys: String, CodingKey {
            case title, outcome, held, amount, answer, receipt, reasons
            case proposalID = "proposal_id", actionName = "action_name", actorID = "actor_id"
            case createdAt = "created_at", toolOutcome = "tool_outcome"
            case readOnly = "read_only", entityID = "entity_id", onHash = "on_hash", onArg = "on_arg"
            case madeAt = "made_at", reason
        }
    }

    /// A receipt as KIFF wrote it (kiff/tafs#167): its bytes, since its signed chain is
    /// checked as written, never decoded and written again.
    func receipt(traceID: String) async throws -> Data {
        let raw: RawBody = try await send("GET", "/v1/receipts/\(escaped(traceID))")
        return raw.data
    }

    /// The account's latest calls, for Activity and Record.
    func activity(limit: Int = 100) async throws -> [ActivityCall] {
        struct Response: Decodable { let calls: [ActivityCall] }
        let r: Response = try await send("GET", "/v1/me/activity?limit=\(limit)")
        return r.calls
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

    func revoke(cardID: String) async throws {
        let _: Empty = try await send("POST", "/v1/me/mandates/\(escaped(cardID))/revoke", body: [String: String]())
    }

    // MARK: Drafts

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
        /// The agent's Cards confirming it revokes: going back to a version that didn't hold
        /// them (kiff/tafs#107), so its tools and limits go back together. Each with its terms
        /// now, for the owner to read before confirming; the draft's version names them.
        var retires: [Retired]?
        enum CodingKeys: String, CodingKey {
            case id, status, version, reason, cards, label, retires
            case agentID = "agent_id", claimSecret = "claim_secret", cardIDs = "card_ids", noTools = "no_tools"
        }
        struct Retired: Decodable, Equatable {
            let id: String
            var current: DraftCard.Terms?
        }
        var confirmed: Bool { status == "confirmed" }
        /// Each Card's lines, as the owner reads them together before one Face ID confirms them
        /// all: two grants that read alike, on one Card or two, each add their action
        /// (kiff/tafs#138). Every place that shows a draft's Cards reads them from here.
        var cardLines: [[String]] {
            let all = cards ?? []
            let alike = DraftCard.alike(all.flatMap { $0.grants ?? [] })
            return all.map { $0.lines(alike: alike) }
        }
        /// Every Card's lines in one list (`cardLines`).
        var lines: [String] { cardLines.flatMap { $0 } }

        /// "Refunds assistant's Card": the name it was submitted with, never its agent id
        /// (kiff/tafs#94).
        var cardTitle: String {
            let name = (label ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            // "Stripe refunds' Card", not "Stripe refunds's" (kiff/tafs#138, item 54).
            return name.isEmpty ? "The taf's Card" : name.hasSuffix("s") ? "\(name)' Card" : "\(name)'s Card"
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
        /// A revision draft's Card for one the agent holds (kiff-cloud#1287): that Card's id,
        /// and its terms as they are now, so the owner reads now → after.
        var revises: String?
        var current: Terms?
        /// KIFF says a Card the draft revises keeps its full terms (kiff/tafs#138): its grants,
        /// caps, scopes and states, totals, policy, expiry and holder. Only KIFF holds both, so
        /// the app never works it out from the lines it shows: those leave terms out. Without
        /// it, the Card reads now → after.
        var unchanged: Bool?
        var isUnchanged: Bool { unchanged == true && current != nil }

        /// A Card's terms alone: what `current` holds.
        struct Terms: Decodable, Equatable {
            let grants: [Grant]?
            let aggregates: [Aggregate]?
            var lines: [String] { DraftCard(grants: grants, aggregates: aggregates).lines }
        }

        /// The lines the owner approves, in their words (kiff/tafs#94): "Refund a payment: up
        /// to €100.00 each", "€1,000.00 in total a day", "20 calls a day". Each action by its
        /// title, never its name (kiff/tafs#138). Where two read alike (a vendor's shared label,
        /// "Write Stripe API data"), each adds its action in words, never a grant prefix. An
        /// amount's unit is KIFF's, or the number stays bare: never a guessed name or currency,
        /// never "sum(amount)".
        var lines: [String] { lines(alike: Self.alike(grants ?? [])) }

        /// The names, ignoring case, that more than one of these grants would read as.
        static func alike(_ grants: [Grant]) -> Set<String> {
            var seen: Set<String> = [], twice: Set<String> = []
            for g in grants {
                let name = KIFFMapping.name(g.action, title: g.title).lowercased()
                if !seen.insert(name).inserted { twice.insert(name) }
            }
            return twice
        }

        /// The lines, where a grant whose name is in `alike` (from every Card the owner reads
        /// together) adds its action in words.
        func lines(alike: Set<String>) -> [String] {
            var out: [String] = []
            for g in grants ?? [] {
                let name = KIFFMapping.name(g.action, title: g.title)
                let caps = (g.perActionMax ?? []).map { cap in
                    KIFFMapping.isSum(cap.quantity)
                        ? "up to \(KIFFMapping.money(cap.max, unit: cap.unit, scale: cap.scale).formatted) each"
                        : "up to \(cap.max) each"
                }
                let action = alike.contains(name.lowercased()) ? "\(name) · \(KIFFMapping.toolWords(g.action))" : name
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

    /// A draft as it is now: has someone confirmed it since it was submitted?
    func draft(id: String) async throws -> AgentDraft {
        try await send("GET", "/v1/me/agent-drafts/\(escaped(id))")
    }

    /// Confirms a draft another agent submitted, exactly the version the owner was shown
    /// (kiff-cloud #1237): KIFF issues its Cards. A signed setup call.
    func confirmDraft(id: String, version: String) async throws -> AgentDraft {
        try await send("POST", "/v1/me/agent-drafts/\(escaped(id))/confirm", body: ["version": version])
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

    /// Resumes a halted agent in KIFF, or with none the whole account (kiff/kiff-cloud#1061).
    /// KIFF takes it only signed by this phone, after Face ID: set `setupSigner` first.
    func resume(agent: String?) async throws {
        let _: Empty = try await send("POST", agent.map { "/v1/agents/\(escaped($0))/resume" } ?? "/v1/resume", body: [String: String]())
    }

    // MARK: Transport

    private struct Empty: Decodable {}

    private func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    /// The languages to ask KIFF for, most preferred first, so KIFF writes what it says to the
    /// owner (a held call's reason, a Record line's) in the app's language at its source, never
    /// translated by the app (kiff/tafs#75). English is the last fallback.
    static func acceptLanguage(_ preferred: [String]) -> String {
        var tags = preferred.prefix(4).map { $0.replacingOccurrences(of: "_", with: "-") }
        if !tags.contains(where: { $0 == "en" || $0.hasPrefix("en-") }) { tags.append("en") }
        return tags.enumerated().map { i, tag in
            i == 0 ? tag : "\(tag);q=\(String(format: "%.1f", max(0.1, 1.0 - Double(i) * 0.1)))"
        }.joined(separator: ", ")
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
        // The language the app shows, not the device's list: an English app on a Spanish iPad
        // read KIFF's reasons in Spanish (kiff/tafs#138, 21 Oct). When the app is translated,
        // KIFF follows it.
        request.setValue(Self.acceptLanguage(Bundle.main.preferredLocalizations), forHTTPHeaderField: "Accept-Language")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if method == "POST" || method == "PUT", base == nil, Self.signsSetup(path), let setupSigner, let tenant {
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
            let body = try? JSONDecoder().decode([String: String].self, from: data)
            throw KIFFError.server(status: status, message: body?["error"] ?? "KIFF answered \(status)", reason: body?["reason"])
        }
        if T.self == RawBody.self, let raw = RawBody(data: data) as? T { return raw }
        if T.self == Empty.self || data.isEmpty { return try JSONDecoder().decode(T.self, from: Data("{}".utf8)) }
        return try Self.decoder.decode(T.self, from: data)
    }

    /// A response's bytes, untouched.
    struct RawBody: Decodable {
        let data: Data
        init(data: Data) { self.data = data }
        init(from decoder: Decoder) throws { data = Data() }
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
