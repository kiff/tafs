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
        /// KIFF takes this act only signed by the session's approval key, and this phone
        /// doesn't hold it (kiff/tafs#233): approving waits for a new sign-in.
        case approvalKeyRequired
        /// The session has an approval key already: KIFF's fingerprint of it.
        case approvalKeyExists(fingerprint: String?)

        var errorDescription: String? {
            switch self {
            case .authorizationPending: return "Waiting for you to approve the sign-in."
            case .slowDown: return "Asked KIFF too often; slowing down."
            case .expired: return "The sign-in code expired. Start again."
            case .unauthorized: return "Signed out of KIFF. Sign in again."
            case .server(_, let message, _): return message
            case .approvalKeyRequired: return KIFFClient.approvalKeyLost
            case .approvalKeyExists: return "KIFF holds an approval key for this session already."
            }
        }
    }

    let baseURL: URL
    /// Where a person answers in KIFF's own app.
    let webURL: URL
    /// KIFF's MCP gateway, which lists the account's connected tools.
    let gatewayURL: URL
    var token: String?
    /// Whether KIFF checks the phone's signature on an answer. Without
    /// it the phone does not answer KIFF's held calls: an answer that KIFF accepts on the
    /// session alone would not prove this iPhone's owner gave it.
    let serverVerifiesSignatures: Bool
    private let session: URLSession
    /// The account the phone signed in to, for signed setup calls.
    var tenant: String?
    /// Signs a setup call for a phone session bound to its key: set
    /// while a start the owner confirmed with Face ID runs, nil otherwise.
    var setupSigner: (@MainActor (Data) throws -> String)?
    /// Signs, with the approval key, a call that grants authority (`grantsAuthority`), for
    /// a session that registered one (kiff/tafs#233); nil otherwise, and the setup signer
    /// signs it as before.
    var approvalSigner: (@MainActor (Data) throws -> String)?
    /// The calls KIFF takes from a phone's session only with its signature: the draft that
    /// starts a taf, the alert target that tells tafs cloud when a call is held, the connector
    /// key planque keeps, the account's time zone, deleting the account (kiff/tafs#185), and
    /// the owner's go on past AI fair use (kiff/tafs#193).
    static let signedSetupPaths: Set<String> = ["/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys", "/v1/me/time-zone", "/v1/me/delete"]

    /// Whether KIFF takes `path` from a phone only with its signature: the paths above,
    /// confirming a draft someone else submitted (`/v1/me/agent-drafts/{id}/confirm`),
    /// resuming a halt (`/v1/agents/{id}/resume`, `/v1/resume`), and declining a request for
    /// more tasks (`/v1/me/pack-requests/{id}/decline`).
    static func signsSetup(_ path: String) -> Bool {
        if signedSetupPaths.contains(path) { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        // Groups and sharing: each change is the person's act behind Face ID.
        // A group, renamed or deleted (/v1/groups/{id}), or its people
        // (/v1/groups/{id}/members/{subject}); who a taf is shared with
        // (/v1/agents/{id}/grants, with ?group= or ?subject= on a removal).
        if path == "/v1/groups" { return true }
        if path.hasPrefix("/v1/groups/") && (parts.count == 4 || (parts.count == 6 && parts[4] == "members")) && !parts[3].isEmpty { return true }
        let bare = path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? path
        let bareParts = bare.split(separator: "/", omittingEmptySubsequences: false)
        if bareParts.count == 5 && bare.hasPrefix("/v1/agents/") && bareParts[4] == "grants" && !bareParts[3].isEmpty { return true }
        if parts.count == 6 && path.hasPrefix("/v1/me/pack-requests/") && parts[5] == "decline" && !parts[4].isEmpty { return true }
        // Resuming a halted agent, or the whole account: it gives authority back.
        if path == "/v1/resume" || (parts.count == 5 && path.hasPrefix("/v1/agents/") && parts[4] == "resume" && !parts[3].isEmpty) { return true }
        // Confirming a draft, and ending its trial (kiff/tafs#247): KIFF takes both from a
        // phone only signed.
        return parts.count == 6 && path.hasPrefix("/v1/me/agent-drafts/") && (parts[5] == "confirm" || parts[5] == "trial") && !parts[4].isEmpty
    }

    /// Whether KIFF takes `method` on `path` only signed by the approval key, once the
    /// session has one (kiff/tafs#233): submitting a draft (from a phone it is confirmed at
    /// once, so it issues Cards: Coach's, and Setup's with none), confirming one, plain or as
    /// a trial, and resuming a halt. Ending a trial takes authority away: the current key.
    static func grantsAuthority(_ method: String, _ path: String) -> Bool {
        guard method == "POST" else { return false }
        if path == "/v1/me/agent-drafts" { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        // Sharing a taf gives people its use. Taking it away is the current key.
        if parts.count == 5 && path.hasPrefix("/v1/agents/") && parts[4] == "grants" && !parts[3].isEmpty { return true }
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
    /// `approvalPublicKey` is the key that signs what grants authority (kiff/tafs#233).
    func startDeviceSignIn(publicKey: Data? = nil, approvalPublicKey: Data? = nil, deviceName: String? = nil, platform: String? = nil) async throws -> DeviceStart {
        var body: [String: String] = [:]
        if let publicKey { body["public_key"] = publicKey.base64EncodedString() }
        if let approvalPublicKey { body["approval_public_key"] = approvalPublicKey.base64EncodedString() }
        if let deviceName { body["device_name"] = deviceName }
        // KIFF's device list names the platform: ios, ipados, macos.
        if let platform { body["platform"] = platform }
        return try await send("POST", "/v1/auth/device/start", body: body, authorized: false)
    }

    /// What the person reads when this phone can't approve until they sign in again.
    nonisolated static let approvalKeyLost = "Sign in with KIFF again to approve from this phone."

    /// A key's fingerprint as KIFF shows it: the first 8 bytes of the SHA-256 of its SPKI
    /// DER, in hex, in groups of four ("ab12 cd34 ef56 7890").
    nonisolated static func fingerprint(_ der: Data) -> String {
        let hex = SHA256.hash(data: der).prefix(8).map { String(format: "%02x", $0) }.joined()
        return stride(from: 0, to: hex.count, by: 4).map { i in
            String(hex[hex.index(hex.startIndex, offsetBy: i)..<hex.index(hex.startIndex, offsetBy: i + 4)])
        }.joined(separator: " ")
    }

    /// Whether `fingerprint`, as KIFF wrote it, is `der`'s.
    nonisolated static func sameKey(_ fingerprint: String?, _ der: Data) -> Bool {
        guard let fingerprint else { return false }
        return fingerprint.lowercased().filter(\.isHexDigit) == Self.fingerprint(der).filter(\.isHexDigit)
    }

    private struct Fingerprint: Decodable { let fingerprint: String? }

    /// Registers the approval key for a session that signed in without one (kiff/tafs#233),
    /// signed by the current key with `signer`, and answers KIFF's fingerprint of the key it
    /// now holds. KIFF takes one per session: a session that has one throws
    /// `approvalKeyExists` with its fingerprint, and replacing it means signing in again.
    func registerApprovalKey(_ publicKey: Data, signer: @escaping @MainActor (Data) throws -> String) async throws -> String? {
        let out: Fingerprint = try await send("POST", "/v1/me/device/approval-key", body: ["public_key": publicKey.base64EncodedString()],
                                              signedBy: signer)
        return out.fingerprint
    }

    /// Called when KIFF answers that an act needs the approval key this phone doesn't hold.
    var approvalKeyRequired: (@MainActor () -> Void)?

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

    /// This device's id in KIFF (`dev_…`), from the person's device list:
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

    /// A device signed in as this person on the account (Settings, Devices).
    struct Device: Decodable, Identifiable, Equatable {
        let id: String
        let name: String?
        /// When it last reached KIFF; nil before its first use.
        let lastUsed: Date?
        let thisDevice: Bool

        enum CodingKeys: String, CodingKey { case id, name, lastUsedAt = "last_used_at", lastSeenAt = "last_seen_at", thisDevice = "this_device" }

        init(id: String, name: String?, lastUsed: Date?, thisDevice: Bool) {
            self.id = id; self.name = name; self.lastUsed = lastUsed; self.thisDevice = thisDevice
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            name = try c.decodeIfPresent(String.self, forKey: .name)
            // A device says when it was last used; a session from before devices, when last seen.
            lastUsed = try c.decodeIfPresent(Date.self, forKey: .lastUsedAt) ?? c.decodeIfPresent(Date.self, forKey: .lastSeenAt)
            thisDevice = try c.decodeIfPresent(Bool.self, forKey: .thisDevice) ?? false
        }
    }

    /// The devices signed in as this person on the account.
    func devices() async throws -> [Device] {
        struct Response: Decodable { let devices: [Device] }
        let r: Response = try await send("GET", "/v1/me/devices")
        return r.devices
    }

    /// Signs a device out of the account: it no longer approves, and its sessions are over.
    func removeDevice(_ id: String) async throws {
        let _: Empty = try await send("DELETE", "/v1/me/devices/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)")
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

        /// The held call's tool, as KIFF names it for people.
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
        /// The account's time zone, which ends a Card's day.
        var timeZone: String? = nil
        /// What this person may do on the account, as KIFF says ("cards.manage", …); nil
        /// from a KIFF that doesn't say.
        var capabilities: [String]? = nil

        enum CodingKeys: String, CodingKey {
            case tenantID = "tenant_id", slug = "tenant_slug", displayName = "tenant_display_name", email
            case timeZone = "time_zone", capabilities
        }

        /// The account as the owner knows it: its name, else its slug.
        var name: String { displayName.flatMap { $0.isEmpty ? nil : $0 } ?? slug }
    }

    func me() async throws -> Account {
        try await send("GET", "/v1/me")
    }

    /// The account's usage this billing period: the tafs meter and the KIFF meter, the tasks
    /// the period has (what the plan includes and the account's packs), what wasn't counted
    /// and why, who bills the plan, the packs on sale, members' requests for more tasks, and
    /// the trial.
    struct Usage: Decodable, Equatable {
        struct Period: Decodable, Equatable { let start: Date; let end: Date }
        /// What wasn't counted, by why ("never counted").
        struct Free: Decodable, Equatable {
            var refused = 0, heldRefused = 0, halted = 0, failed = 0, invalid = 0, retry = 0, read = 0, trial = 0, setupRead = 0
            enum CodingKeys: String, CodingKey {
                case refused, heldRefused = "held_refused", halted, failed, invalid, retry, read, trial, setupRead = "setup_read", setup
            }
            init() {}
            /// A reason KIFF doesn't name counted nothing.
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                func n(_ k: CodingKeys) throws -> Int { try c.decodeIfPresent(Int.self, forKey: k) ?? 0 }
                refused = try n(.refused); heldRefused = try n(.heldRefused); halted = try n(.halted); failed = try n(.failed)
                invalid = try n(.invalid); retry = try n(.retry); read = try n(.read); trial = try n(.trial)
                // Coach's and Setup's calls: KIFF names them `setup` once it frees every call made
                // with a setup_reader key (#206 Part 2), and `setup_read` before that.
                setupRead = try n(.setupRead) + n(.setup)
            }
            /// Each reason with a count, as the owner reads it, in the order they matter.
            var lines: [(String, Int)] {
                [("Refused by KIFF", refused), ("Held, then refused", heldRefused), ("Halted", halted), ("Failed at the tool", failed),
                 ("Not valid", invalid), ("Retries", retry), ("Reads", read), ("Trials of a new version", trial), ("Coach and Setup", setupRead)]
                    .filter { $0.1 > 0 }
            }
        }
        /// One taf's part of the period: what it counted, and what it didn't and why.
        struct AgentCount: Decodable, Equatable {
            let agent: String
            let counted: Int
            var free: Free? = nil
        }
        struct Meter: Decodable, Equatable {
            let counted: Int
            let included: Int
            /// The tafs meter only: the pack tasks the period has (used in it, and left on
            /// packs still valid), and the tasks left. `left` is nil where there's no limit.
            var packs: Int? = nil
            var left: Int? = nil
            var byAgent: [AgentCount]? = nil
            var free: Free? = nil
            enum CodingKeys: String, CodingKey { case counted, included, packs, left, byAgent = "by_agent", free }

            /// The tasks the period has: what the plan includes and the pack tasks.
            var total: Int { included + (packs ?? 0) }
            /// No tasks left: the tafs stop until a pack adds some.
            var usedUp: Bool { left == 0 }
        }
        struct Trial: Decodable, Equatable {
            /// Nil until the trial starts: it starts at checkout on the web.
            let endsAt: Date?
            let daysLeft: Int
            let tasksLeft: Int
            let actionsLeft: Int
            enum CodingKeys: String, CodingKey { case endsAt = "ends_at", daysLeft = "days_left", tasksLeft = "tasks_left", actionsLeft = "actions_left" }
        }
        /// AI fair use's part of the read (kiff/tafs#193): how many times the owner said go on
        /// this period. The use itself is tafs cloud's.
        struct AI: Decodable, Equatable {
            var goOns = 0
            enum CodingKeys: String, CodingKey { case goOns = "go_ons" }
        }
        /// Who bills the plan: "apple", "stripe" (KIFF), "contract", or "" when none does; and
        /// where it's managed, for a plan Apple bills.
        struct Billing: Decodable, Equatable {
            var source: String = ""
            var manageURL: URL? = nil
            /// The account moved from Solo in the App Store to Team, which KIFF bills now,
            /// and Solo still renews with Apple on this date unless it's cancelled there.
            var appleSoloUntil: Date? = nil
            enum CodingKeys: String, CodingKey { case source, manageURL = "manage_url", appleSoloUntil = "apple_solo_until" }
        }
        /// One of the account's packs with tasks left.
        struct HeldPack: Decodable, Equatable, Identifiable {
            let id: String
            let name: String
            let tasks: Int
            let tasksLeft: Int
            let expiresAt: Date
            enum CodingKeys: String, CodingKey { case id, name, tasks, tasksLeft = "tasks_left", expiresAt = "expires_at" }
        }
        /// What the app sells: the account's token for purchases, Solo, and the packs. The
        /// prices are the App Store's.
        struct Store: Decodable, Equatable {
            struct Pack: Decodable, Equatable, Identifiable {
                let id: String
                let name: String
                /// "Rayo · 70 tasks".
                let line: String
                let tasks: Int
                /// How long its tasks last.
                let months: Int
                let appleProduct: String
                enum CodingKeys: String, CodingKey { case id, name, line, tasks, months, appleProduct = "apple_product" }
            }
            var appAccountToken: UUID? = nil
            let soloProduct: String
            let packs: [Pack]
            enum CodingKeys: String, CodingKey { case appAccountToken = "app_account_token", soloProduct = "solo_product", packs }
        }
        let plan: String
        let period: Period
        var tasks: Meter
        let actions: Meter
        var trial: Trial?
        /// The people the plan pays for (admins and editors); 0 where there's no limit.
        var seats: Int? = nil
        var ai: AI? = nil
        var billing: Billing? = nil
        var packList: [HeldPack]? = nil
        /// Whether this person may add tasks: someone who can't asks an admin.
        var canAddTasks: Bool? = nil
        var store: Store? = nil
        /// Members' requests for more tasks: every open one for someone who may add tasks,
        /// this person's own otherwise.
        var requests: [PackRequest]? = nil

        enum CodingKeys: String, CodingKey {
            case plan, period, tasks, actions, trial, seats, ai, billing, packList = "pack_list", canAddTasks = "can_add_tasks", store, requests
        }
    }

    /// A member's request that an admin add a pack.
    struct PackRequest: Decodable, Equatable, Identifiable {
        let id: String
        let askedBy: String
        let pack: String
        var reason: String = ""
        /// open, approved, declined, withdrawn or expired.
        let status: String
        let createdAt: Date
        let expiresAt: Date
        var note: String? = nil
        enum CodingKeys: String, CodingKey { case id, askedBy = "asked_by", pack, reason, status, createdAt = "created_at", expiresAt = "expires_at", note }

        init(id: String, askedBy: String, pack: String, reason: String = "", status: String, createdAt: Date, expiresAt: Date, note: String? = nil) {
            self.id = id; self.askedBy = askedBy; self.pack = pack; self.reason = reason; self.status = status
            self.createdAt = createdAt; self.expiresAt = expiresAt; self.note = note
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            askedBy = try c.decode(String.self, forKey: .askedBy)
            pack = try c.decode(String.self, forKey: .pack)
            reason = try c.decodeIfPresent(String.self, forKey: .reason) ?? ""
            status = try c.decode(String.self, forKey: .status)
            createdAt = try c.decode(Date.self, forKey: .createdAt)
            expiresAt = try c.decode(Date.self, forKey: .expiresAt)
            note = try c.decodeIfPresent(String.self, forKey: .note)
        }
    }

    /// Sends a purchase the App Store reported, as Apple signed it, to KIFF: a plan or a pack
    /// for this account, with the member's request it answers. The app finishes the purchase
    /// only once this returns.
    func recordPurchase(signedTransaction: String, requestID: String? = nil) async throws {
        var body = ["signed_transaction": signedTransaction]
        if let requestID { body["request_id"] = requestID }
        let _: Empty = try await send("POST", "/v1/me/billing/apple/transactions", body: body)
    }

    /// Asks the account's admins for a pack, with a reason.
    func askForPack(_ pack: String, reason: String) async throws -> PackRequest {
        try await send("POST", "/v1/me/pack-requests", body: ["pack": pack, "reason": reason])
    }

    /// Takes back this person's request.
    func withdrawRequest(_ id: String) async throws -> PackRequest {
        try await send("POST", "/v1/me/pack-requests/\(escaped(id))/withdraw", body: [String: String]())
    }

    /// Declines a member's request, with a note: a signed call from a phone.
    func declineRequest(_ id: String, note: String) async throws -> PackRequest {
        try await send("POST", "/v1/me/pack-requests/\(escaped(id))/decline", body: ["note": note])
    }

    /// One counted task: the Record behind it, which taf, which tool, and when.
    struct CountedTask: Decodable, Equatable, Identifiable {
        let record: String
        let agent: String
        let tool: String
        /// The tool as the Record names it: "Refund an order".
        var toolTitle: String? = nil
        let at: Date
        var id: String { record }
        enum CodingKeys: String, CodingKey { case record, agent, tool, toolTitle = "tool_title", at }
    }

    func usage() async throws -> Usage {
        try await send("GET", "/v1/me/usage")
    }

    /// The period's counted tasks, newest first, a page at a time; with `agent`, that taf's
    /// only.
    func countedTasks(cursor: String? = nil, agent: String? = nil) async throws -> (tasks: [CountedTask], next: String?) {
        struct Page: Decodable { let items: [CountedTask]; var next: String? }
        let query = [agent.map { "agent=\(escaped($0))" }, cursor.map { "cursor=\(escaped($0))" }].compactMap { $0 }
        let page: Page = try await send("GET", "/v1/me/usage/tasks" + (query.isEmpty ? "" : "?" + query.joined(separator: "&")))
        return (page.items, page.next)
    }

    /// Sets the account's time zone (an IANA name): a signed setup call from a phone, which
    /// only the account's owner or an admin may make.
    func setTimeZone(_ zone: String) async throws -> String {
        struct Saved: Decodable { let timeZone: String; enum CodingKeys: String, CodingKey { case timeZone = "time_zone" } }
        let saved: Saved = try await send("PUT", "/v1/me/time-zone", body: ["time_zone": zone])
        return saved.timeZone
    }

    /// One call of the account's, as KIFF's activity records it: what a
    /// taf did, newest first, with the tool's title for people.
    struct ActivityCall: Decodable, Equatable {
        /// How the call ended at the tool, as KIFF's activity says: done, refused_by_tool,
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

    /// What the phone signs to answer a held call: KIFF's signed answer message,
    /// `kiff.answer.v1`, one field per line.
    static func answerMessage(tenant: String, exceptionID: String, answer: String, nextStep: String?,
                              note: String? = nil, accountingVersion: Int, signedAt: String) -> Data {
        let noteHash = note.flatMap { $0.isEmpty ? nil : $0 }
            .map { SHA256.hash(data: Data($0.utf8)).map { String(format: "%02x", $0) }.joined() } ?? ""
        let lines = ["kiff.answer.v1", tenant, exceptionID, answer, nextStep ?? "", noteHash,
                     String(accountingVersion), signedAt]
        return Data(lines.joined(separator: "\n").utf8)
    }

    /// What the phone signs for a setup call: KIFF's signed request message,
    /// `kiff.request.v1`.
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

    /// Gives the held call's Card the extra room this call needs, until that total resets,
    /// and lets the call go on (kiff/tafs#211, 6.13): KIFF's change-card, signed as an
    /// answer. Only for a call over a total kept across calls; it needs Card management.
    func changeCard(_ id: String, signature: AnswerSignature? = nil) async throws {
        let _: Empty = try await send("POST", "/v1/me/card-exceptions/\(escaped(id))/change-card",
                                      body: AnswerBody(signature: signature))
    }

    // MARK: Cards

    func revoke(cardID: String) async throws {
        let _: Empty = try await send("POST", "/v1/me/mandates/\(escaped(cardID))/revoke", body: [String: String]())
    }

    // MARK: Drafts

    /// A new agent and its Cards, submitted as one draft. Submitted
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
        /// An agent that holds no tools: Setup.
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
            /// `unit` and `scale` when the tool declares them.
            struct PerAction: Decodable, Equatable { let quantity: String; let max: Int64; var unit: String?; var scale: Int? }
            let action: String
            var title: String?
            let perActionMax: [PerAction]?
            enum CodingKeys: String, CodingKey { case action, title, perActionMax = "per_action_max" }
        }
        struct Aggregate: Decodable, Equatable { let quantity: String; let limit: Int64; let window: String; var unit: String?; var scale: Int? }
        let grants: [Grant]?
        let aggregates: [Aggregate]?
        /// A revision draft's Card for one the agent holds: that Card's id,
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

    /// A draft for Setup, an agent that holds no tools. From the phone,
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

    /// Confirms a draft another agent submitted, exactly the version the owner was shown:
    /// KIFF issues its Cards. A signed setup call.
    func confirmDraft(id: String, version: String) async throws -> AgentDraft {
        try await send("POST", "/v1/me/agent-drafts/\(escaped(id))/confirm", body: ["version": version])
    }

    /// Confirms a version's draft as a **trial** (kiff/tafs#173): KIFF issues its Cards as
    /// trial Cards of their own, with their own counters, and the live Cards stay as they
    /// are. The draft's status becomes "trying". A signed setup call, as any confirm.
    func confirmDraftAsTrial(id: String, version: String) async throws -> AgentDraft {
        try await send("POST", "/v1/me/agent-drafts/\(escaped(id))/confirm", body: ["version": version, "as": "trial"])
    }

    /// Go back (kiff/tafs#247): KIFF ends the draft's trial, so its Cards stop at once. A
    /// signed setup call: it takes authority away, so it needs no Face ID. A trial that had
    /// ended already answers 404 or 409, which counts as ended.
    func endTrial(draft id: String) async throws {
        do {
            let _: Empty = try await send("DELETE", "/v1/me/agent-drafts/\(escaped(id))/trial")
        } catch KIFFError.server(let status, _, _) where status == 404 || status == 409 {}
    }

    // MARK: Coach's tools (kiff/tafs#206)

    /// Coach's tools on tafs cloud's MCP server, each its own connection in KIFF; the reads
    /// are declared read-only.
    static let coachTools: [(name: String, readOnly: Bool)] = [
        ("tafs_state", true), ("taf_run", true), ("taf_task", false), ("run_stop", false),
        ("taf_pause", false), ("version_try", false), ("version_install", false),
    ]

    /// One of the account's tool connections in KIFF's gateway: its name (a held call's action),
    /// the server it reaches, and whether it's the account's control server.
    struct Connection: Decodable, Equatable {
        let name: String
        let url: String?
        let control: Bool?
    }

    /// The account's tool connections in KIFF's gateway.
    func connections() async throws -> [Connection] {
        struct Wrapped: Decodable { let connections: [Connection] }
        let raw: RawBody = try await send("GET", "/v1/connections", base: gatewayURL)
        return (try? JSONDecoder().decode(Wrapped.self, from: raw.data).connections) ?? (try? JSONDecoder().decode([Connection].self, from: raw.data)) ?? []
    }

    /// What giving Coach its tools needs, from the account's connections: the tools still to
    /// connect, or the first name another connection holds. One there counts as Coach's only
    /// when it's tafs cloud's server (`mcp`) as the control server: Coach's Cards grant by name,
    /// so any other would put a vendor's tool on them.
    static func coachToolsPlan(_ there: [Connection], mcp: URL) -> (missing: [(name: String, readOnly: Bool)], taken: String?) {
        let byName = Dictionary(there.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        var missing: [(name: String, readOnly: Bool)] = []
        for tool in coachTools {
            guard let c = byName[tool.name] else { missing.append(tool); continue }
            if c.url != mcp.absoluteString || c.control != true { return ([], tool.name) }
        }
        return (missing, nil)
    }

    /// Connects one of Coach's tools on tafs cloud's server (`mcp`) as the account's own control
    /// server: verify mode, so the server checks KIFF's permit on each call and KIFF holds no
    /// credential for it. A signed setup call to the gateway; one at a time, since each rewrites
    /// the account's gateway contract.
    func connectCoachTool(_ name: String, readOnly: Bool, mcp: URL) async throws {
        struct Body: Encodable {
            let name: String
            let url: String
            let tool: String
            let mode = "verify"
            let audience: String
            let control = true
            let idempotencyArgument = "tafs_op"
            let readOnly: Bool?
            enum CodingKeys: String, CodingKey {
                case name, url, tool, mode, audience, control
                case idempotencyArgument = "idempotency_argument", readOnly = "read_only"
            }
        }
        let _: Empty = try await send("POST", "/v1/connections", body: Body(name: name, url: mcp.absoluteString, tool: name,
                                                                           audience: mcp.absoluteString, readOnly: readOnly ? true : nil),
                                      base: gatewayURL)
    }

    /// Coach's Cards (kiff/tafs#206), for the account's Setup agent: the reads share one, 500 a
    /// day; a task 50 a day and a trial 20, then asked; stopping and pausing 50 a day each, then
    /// refused; and every install held for the owner. Stable ids, so sending them again revises
    /// the same Cards. From the phone, signed, they are issued at once.
    func submitCoachCards(agentID: String) async throws {
        struct Grant: Encodable { let action: String }
        struct Aggregate: Encodable {
            let quantity = "count"
            let limit: Int
            let window = "calendar_day"
        }
        struct Card: Encodable {
            let id: String
            let subject: String
            let domain = "gateway"
            let onExceed: String
            let holdExpiry: String?
            let grants: [Grant]
            let aggregates: [Aggregate]
            enum CodingKeys: String, CodingKey {
                case id, subject, domain, grants, aggregates
                case onExceed = "on_exceed", holdExpiry = "hold_expiry"
            }
        }
        struct Body: Encodable {
            let agentID: String
            let label = "Coach"
            let cards: [Card]
            enum CodingKeys: String, CodingKey { case label, cards, agentID = "agent_id" }
        }
        func card(_ id: String, _ actions: [String], limit: Int, asks: Bool) -> Card {
            Card(id: id, subject: agentID, onExceed: asks ? "owner" : "refuse", holdExpiry: asks ? "10m" : nil,
                 grants: actions.map(Grant.init), aggregates: [Aggregate(limit: limit)])
        }
        let cards = [
            card("coach-reads", ["tafs_state", "taf_run"], limit: 500, asks: false),
            card("coach-taf-task", ["taf_task"], limit: 50, asks: true),
            card("coach-version-try", ["version_try"], limit: 20, asks: true),
            card("coach-run-stop", ["run_stop"], limit: 50, asks: false),
            card("coach-taf-pause", ["taf_pause"], limit: 50, asks: false),
            card("coach-version-install", ["version_install"], limit: 0, asks: true),
        ]
        let _: AgentDraft = try await send("POST", "/v1/me/agent-drafts", body: Body(agentID: agentID, cards: cards))
    }

    // MARK: Alerts

    /// Where KIFF sends the account's held calls, as a phone registers it:
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

    /// The key planque keeps to register a taf's tools in KIFF: it
    /// connects tools and does nothing else. Minted from the phone with a signed setup call;
    /// the plaintext is shown this once and goes straight to planque, never to storage.
    func mintConnectorKey() async throws -> String {
        struct Minted: Decodable { let plaintext: String }
        struct Body: Encodable { let label: String; let roles: [String] }
        let minted: Minted = try await send("POST", "/v1/keys", body: Body(label: "planque", roles: ["connector"]))
        return minted.plaintext
    }

    /// Resumes a halted agent in KIFF, or with none the whole account.
    /// KIFF takes it only signed by this phone, after Face ID: set `setupSigner` first.
    func resume(agent: String?) async throws {
        let _: Empty = try await send("POST", agent.map { "/v1/agents/\(escaped($0))/resume" } ?? "/v1/resume", body: [String: String]())
    }

    /// Deletes the account in KIFF (kiff/tafs#185): its keys stop at once, and KIFF erases its
    /// data after its grace window. KIFF takes it only signed by this phone, after Face ID: set
    /// `setupSigner` first. It ends this phone's session too, so it comes last.
    func deleteAccount() async throws {
        let _: Empty = try await send("POST", "/v1/me/delete", body: [String: String]())
    }

    // MARK: Groups and sharing

    /// A group of the account's people: its manager sees the group's work on the tafs it
    /// uses; its members use them. Everyone is the group KIFF made of the account's people
    /// when groups arrived, so no one lost a taf.
    struct Group: Decodable, Identifiable, Equatable, Hashable {
        let id: String
        let name: String
        var everyone: Bool? = nil
        var members: [GroupMember] = []
        /// The KIFF agents the group is shared.
        var agents: [String] = []

        var managers: [GroupMember] { members.filter(\.isManager) }
    }

    struct GroupMember: Codable, Equatable, Hashable {
        let subject: String
        /// manager or member.
        let role: String
        var isManager: Bool { role == "manager" }
    }

    /// One of the account's people, as KIFF lists them. KIFF names them by email.
    struct Person: Decodable, Identifiable, Equatable, Hashable {
        let subject: String
        let role: String
        let status: String
        var email: String? = nil
        var you: Bool? = nil
        var id: String { subject }
        var active: Bool { status == "active" }
        /// What the app shows: their email, else KIFF's id for them.
        var label: String { email.flatMap { $0.isEmpty ? nil : $0 } ?? subject }
    }

    /// Who a taf is shared with: a group, or one person.
    struct Share: Codable, Equatable, Hashable {
        var group: String? = nil
        var subject: String? = nil
    }

    /// What a person may do with a taf, as KIFF answers it: use it, see everyone's work with
    /// it, change it.
    struct Access: Decodable, Equatable {
        var use = false
        var oversee = false
        var manage = false
        var groups: [String]? = nil
    }

    func groups() async throws -> [Group] {
        struct List: Decodable { let groups: [Group] }
        let list: List = try await send("GET", "/v1/groups")
        return list.groups
    }

    func people() async throws -> [Person] {
        struct List: Decodable { let members: [Person] }
        let list: List = try await send("GET", "/v1/tenant/members")
        return list.members
    }

    @discardableResult
    func createGroup(name: String) async throws -> Group {
        try await send("POST", "/v1/groups", body: ["name": name])
    }

    func renameGroup(_ id: String, to name: String) async throws {
        let _: Empty = try await send("PATCH", "/v1/groups/\(escaped(id))", body: ["name": name])
    }

    func deleteGroup(_ id: String) async throws {
        let _: Empty = try await send("DELETE", "/v1/groups/\(escaped(id))", body: [String: String]())
    }

    /// Adds someone to a group, or changes their role in it: manager or member.
    func setGroupMember(_ group: String, subject: String, role: String) async throws {
        let _: Empty = try await send("PUT", "/v1/groups/\(escaped(group))/members/\(escaped(subject))", body: ["role": role])
    }

    func removeGroupMember(_ group: String, subject: String) async throws {
        let _: Empty = try await send("DELETE", "/v1/groups/\(escaped(group))/members/\(escaped(subject))", body: [String: String]())
    }

    /// Who a taf's KIFF agent is shared with.
    func shares(agent: String) async throws -> [Share] {
        struct List: Decodable { let grants: [Share] }
        let list: List = try await send("GET", "/v1/agents/\(escaped(agent))/grants")
        return list.grants
    }

    func share(agent: String, with share: Share) async throws {
        let _: Empty = try await send("POST", "/v1/agents/\(escaped(agent))/grants", body: share)
    }

    func unshare(agent: String, from share: Share) async throws {
        let query = share.group.map { "group=\(escapedQuery($0))" } ?? share.subject.map { "subject=\(escapedQuery($0))" } ?? ""
        let _: Empty = try await send("DELETE", "/v1/agents/\(escaped(agent))/grants?\(query)", body: [String: String]())
    }

    /// What this person may do with each of the account's agents, by KIFF agent.
    func myAccess() async throws -> [String: Access] {
        struct Answer: Decodable { let agents: [String: Access] }
        let answer: Answer = try await send("GET", "/v1/me/access")
        return answer.agents
    }

    // MARK: Transport

    private struct Empty: Decodable {}

    private func escapedQuery(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? s
    }

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

    /// `signedBy` signs this one request, whatever `setupSigner` holds.
    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, base: URL? = nil, authorized: Bool = true,
                                                   signedBy: (@MainActor (Data) throws -> String)? = nil) async throws -> T {
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
        // The gateway takes a control-server connection from a phone only signed (kiff/tafs#206).
        let signed = base == nil ? (Self.signsSetup(path) || path == "/v1/me/device/approval-key") : (base == gatewayURL && path == "/v1/connections")
        // What grants authority is signed by the approval key, once the session has one,
        // within a step the owner confirmed; KIFF picks the key by route, so the header is
        // the same (kiff/tafs#233).
        let signer = signedBy ?? setupSigner.map { current in
            base == nil && Self.grantsAuthority(method, path) ? (approvalSigner ?? current) : current
        }
        if ["POST", "PUT", "PATCH", "DELETE"].contains(method), signed, let signer, let tenant {
            let signedAt = SigningTime.next()
            let uri = url.path + (url.query.map { "?" + $0 } ?? "")
            let message = Self.requestMessage(tenant: tenant, method: method, uri: uri, body: request.httpBody ?? Data(), signedAt: signedAt)
            request.setValue("t=\(signedAt),v1=\(try signer(message))", forHTTPHeaderField: "KIFF-Device-Signature")
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
            if status == 403, body?["error"] == "approval_key_required" || body?["reason"] == "approval_key_required" {
                approvalKeyRequired?()
                throw KIFFError.approvalKeyRequired
            }
            if status == 409, base == nil, path == "/v1/me/device/approval-key" {
                throw KIFFError.approvalKeyExists(fingerprint: body?["fingerprint"])
            }
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
