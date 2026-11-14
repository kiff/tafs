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
        /// Refused inside an approval window (kiff/tafs#494): `code` is KIFF's, `critical_key_required`
        /// (the act takes the approval key every time) or `window_required` (the window has ended).
        /// Thrown only where no approval key can sign it again.
        case windowRefused(code: String)

        var errorDescription: String? {
            switch self {
            case .authorizationPending: return Words.Error.signInPending
            case .slowDown: return Words.Error.slowDown
            case .expired: return Words.Error.signInCodeExpired
            case .unauthorized: return Words.Error.signedOut
            // KIFF's reason is for the code and the diagnostics; the person reads a plain line.
            case .server(let status, _, _): return PlainError.words(status: status)
            case .approvalKeyRequired: return KIFFClient.approvalKeyLost
            case .approvalKeyExists: return Words.Error.approvalKeyExists
            // The app's own words: KIFF's `detail` is for the log, never the person.
            case .windowRefused(let code): return code == KIFFClient.windowRequired ? Words.Error.approvalWindowEnded : Words.Error.needsConfirmationFirst
            }
        }
    }

    // MARK: The approval window

    /// An approval window as KIFF answers it (kiff/tafs#494): one Face ID opened it, and inside it a
    /// routine approval is a tap, signed by the device key with the window's id. KIFF alone says
    /// whether it is open; `words` are KIFF's for the next tap. Read leniently: a field of another
    /// shape is none (a window with no id isn't one).
    struct ApprovalWindow: Decodable, Equatable {
        let id: String
        var expires = ""
        var cap = ""
        var open = false
        var words: String?
        enum CodingKeys: String, CodingKey { case id, expires, cap, open, words }
        init(id: String, expires: String = "", cap: String = "", open: Bool = false, words: String? = nil) {
            self.id = id; self.expires = expires; self.cap = cap; self.open = open; self.words = words
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            expires = ((try? c.decodeIfPresent(String.self, forKey: .expires)) ?? nil) ?? ""
            cap = ((try? c.decodeIfPresent(String.self, forKey: .cap)) ?? nil) ?? ""
            open = ((try? c.decodeIfPresent(Bool.self, forKey: .open)) ?? nil) ?? false
            words = (try? c.decodeIfPresent(String.self, forKey: .words)) ?? nil
        }
    }

    /// `GET /v1/me/window`: the open window, if any, and KIFF's words for the next approval.
    struct WindowRead: Decodable, Equatable {
        var window: ApprovalWindow?
        var words: String?
        enum CodingKeys: String, CodingKey { case window, words }
        init(window: ApprovalWindow? = nil, words: String? = nil) { self.window = window; self.words = words }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            window = (try? c.decodeIfPresent(ApprovalWindow.self, forKey: .window)) ?? nil
            words = (try? c.decodeIfPresent(String.self, forKey: .words)) ?? nil
        }
    }

    /// KIFF's approval window (kiff/tafs#494): read, opened with the approval key, closed with the device key.
    nonisolated static let windowPath = "/v1/me/window"
    /// What opening a window signs, as its body's `message`.
    nonisolated static let windowMessage = "kiff.window.v1"
    /// KIFF's refusals inside a window: the act takes the approval key every time, or the window has ended.
    nonisolated static let criticalKeyRequired = "critical_key_required"
    nonisolated static let windowRequired = "window_required"

    /// What the device key signs inside an approval window: `kiff.request.v1`'s lines under
    /// `kiff.request.v2`, with the window's id last, so the signature can't be moved to another
    /// window (KIFF's `RequestMessageWindow`).
    nonisolated static func requestMessageWindow(tenant: String, method: String, uri: String, body: Data, signedAt: String, window: String) -> Data {
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        return Data(["kiff.request.v2", tenant, method, uri, bodyHash, signedAt, window].joined(separator: "\n").utf8)
    }

    /// The approval window this phone holds at KIFF (kiff/tafs#494), as KIFF last answered it.
    /// While it is held and `signsInWindow` is on, a call the approval key would sign goes with the
    /// device key and the window's id, a tap; KIFF decides whether it takes it. A refusal drops back
    /// to the approval key, and `window_required` forgets the window.
    var window: ApprovalWindow?
    /// On only while the approval step that took the window runs: its calls go as a tap. Off, the
    /// approval key signs as before.
    var signsInWindow = false
    /// Asks for Face ID again, so the approval key can sign a call KIFF refused inside the window.
    /// Set with `signsInWindow`; without it, or without an approval key, the refusal is thrown.
    var windowReconfirm: (@MainActor () async throws -> Void)?

    /// The open window, if any, with KIFF's words for the next approval.
    func readWindow() async throws -> WindowRead {
        try await send("GET", Self.windowPath)
    }

    private struct WindowOpenBody: Encodable { let message: String; let expires: String; let nonce: String }

    /// Opens an approval window with the approval key, under the Face ID this step just took:
    /// KIFF's `kiff.window.v1`, to `expires` at most five minutes ahead, with a fresh `nonce`; KIFF
    /// takes the signer's own session and device. The window is held once KIFF answers it. Set
    /// `setupSigner` first.
    func openWindow(expires: String, nonce: String) async throws -> ApprovalWindow {
        let read: WindowRead = try await send("POST", Self.windowPath, body: WindowOpenBody(message: Self.windowMessage, expires: expires, nonce: nonce))
        guard let opened = read.window else { throw KIFFError.server(status: 0, message: Words.Error.kiffOpenedNoWindow) }
        window = opened
        return opened
    }

    /// Closes the held window, signed by the device key (`signedBy`): the app was away, or the
    /// screen locked. Forgotten here either way.
    func closeWindow(signedBy sign: @escaping @MainActor (Data) throws -> String) async throws {
        guard let held = window else { return }
        window = nil
        let _: Empty = try await send("POST", "\(Self.windowPath)/\(escaped(held.id))/close", body: [String: String](), signedBy: sign)
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
    /// raising the spend limit (docs/accounts.md › The balance).
    static let signedSetupPaths: Set<String> = ["/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys", "/v1/me/time-zone", "/v1/me/delete",
                                                      "/v1/me/usage/limit"]

    /// Whether KIFF takes `path` from a phone only with its signature: the paths above,
    /// confirming a draft someone else submitted (`/v1/me/agent-drafts/{id}/confirm`),
    /// resuming a halt (`/v1/agents/{id}/resume`, `/v1/resume`), and declining a request for
    /// a raise or a pack (`/v1/me/pack-requests/{id}/decline`).
    static func signsSetup(_ path: String) -> Bool {
        if signedSetupPaths.contains(path) { return true }
        // Opening an approval window, and closing it (kiff/tafs#494).
        if path == windowPath || (path.hasPrefix(windowPath + "/") && path.hasSuffix("/close")) { return true }
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
        // The owner's answer to "Did this happen?": "No" gives a Card its room back.
        if Self.answersHappened(path) { return true }
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
        // Opening an approval window stands for a Face ID: only the approval key opens one (kiff/tafs#494).
        if path == windowPath { return true }
        if path == "/v1/me/agent-drafts" || answersHappened(path) { return true }
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

    /// The held call's page in KIFF, where it can be answered today, in the language the app's
    /// lines resolved to (`lang`); the browser's is KIFF's fallback.
    func answerPage(for exceptionID: String, language: String = KIFFClient.appLanguage) -> URL {
        webURL.appendingPathComponent("needs-you").appendingPathComponent(exceptionID)
            .appending(queryItems: [URLQueryItem(name: "lang", value: language)])
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

        /// The approval page's link in the language the app's lines resolved to (`lang`), so
        /// KIFF's page speaks the app's language whatever the browser's; a link that already
        /// names one keeps it.
        func inAppLanguage(_ language: String = KIFFClient.appLanguage) -> DeviceStart {
            let link = verificationURIComplete
            guard !link.isEmpty, link.range(of: "[?&]lang=", options: .regularExpression) == nil else { return self }
            return DeviceStart(deviceCode: deviceCode, userCode: userCode, verificationURI: verificationURI,
                               verificationURIComplete: link + (link.contains("?") ? "&" : "?") + "lang=" + language,
                               expiresIn: expiresIn, interval: interval)
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
    func startDeviceSignIn(publicKey: Data? = nil, approvalPublicKey: Data? = nil, deviceName: String? = nil, platform: String? = nil, kind: String? = nil) async throws -> DeviceStart {
        var body: [String: String] = [:]
        if let publicKey { body["public_key"] = publicKey.base64EncodedString() }
        if let approvalPublicKey { body["approval_public_key"] = approvalPublicKey.base64EncodedString() }
        if let deviceName { body["device_name"] = deviceName }
        // KIFF's device list names the platform: ios, ipados, macos.
        if let platform { body["platform"] = platform }
        // What the device is (iphone, ipad): KIFF draws its name in the list from it, in the reader's language.
        if let kind { body["kind"] = kind }
        let start: DeviceStart = try await send("POST", "/v1/auth/device/start", body: body, authorized: false)
        return start.inAppLanguage()
    }

    /// What the person reads when this device can't approve until they approve it again, named
    /// by what it asks for ("Your Face ID changed…"), never "Face ID or fingerprints".
    nonisolated static var approvalKeyLost: String {
        Words.Approval.keyGoneBiometry(biometry: Biometry.kind ?? Words.Approval.faceIdTouchId)
    }
    /// What they read when an approval's prompt closed unrecognized while the key is still good.
    nonisolated static var notRecognized: String {
        Words.Approval.notRecognizedBiometry(biometry: Biometry.kind ?? Words.Approval.faceIdTouchId)
    }

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

    /// What a re-link's new key signs, under its biometric prompt, so KIFF knows it works
    /// before it replaces the old one (kiff/tafs#233, case 2): one field per line, the
    /// session named by its device key.
    nonisolated static func relinkMessage(tenant: String, deviceKey: Data, newKey: Data, signedAt: String) -> Data {
        let hex = { (d: Data) in SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }
        return Data(["kiff.approval-key-relink.v1", tenant, hex(deviceKey), hex(newKey), signedAt].joined(separator: "\n").utf8)
    }

    /// Asks KIFF to let this phone approve again with `approvalPublicKey`, after its faces or
    /// fingerprints changed: signed by the device key with `signer`, and by the new key itself
    /// (`signature` over `relinkMessage`). The person approves it in their browser, as for a
    /// sign-in, and the session goes on. A KIFF without it answers 404.
    func startRelink(approvalPublicKey: Data, signedAt: String, signature: String,
                     signer: @escaping @MainActor (Data) throws -> String) async throws -> DeviceStart {
        let start: DeviceStart = try await send("POST", "/v1/me/device/relink",
                       body: ["approval_public_key": approvalPublicKey.base64EncodedString(), "signed_at": signedAt, "signature": signature],
                       signedBy: signer)
        return start.inAppLanguage()
    }

    /// One poll of a re-link: the fingerprint of the key KIFF now holds, once approved.
    /// Throws as `pollDeviceToken` does until then.
    func pollRelink(deviceCode: String) async throws -> String? {
        struct Relinked: Decodable { let status: String; let fingerprint: String? }
        do {
            let out: Relinked = try await send("POST", "/v1/auth/device/token", body: ["device_code": deviceCode], authorized: false)
            // A token here would be a KIFF bug: a re-link never signs in.
            guard out.status == "relinked" else { throw KIFFError.server(status: 200, message: "not a re-link") }
            return out.fingerprint
        } catch KIFFError.server(400, let message, _) {
            switch message {
            case "authorization_pending": throw KIFFError.authorizationPending
            case "slow_down": throw KIFFError.slowDown
            case "expired_token": throw KIFFError.expired
            default: throw KIFFError.server(status: 400, message: message)
            }
        }
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

        enum CodingKeys: String, CodingKey { case id, name, displayName = "display_name", lastUsedAt = "last_used_at", lastSeenAt = "last_seen_at", thisDevice = "this_device" }

        init(id: String, name: String?, lastUsed: Date?, thisDevice: Bool) {
            self.id = id; self.name = name; self.lastUsed = lastUsed; self.thisDevice = thisDevice
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            // KIFF's name for it in the app's language; the name it gave at sign-in from a KIFF without one.
            name = try c.decodeIfPresent(String.self, forKey: .displayName) ?? c.decodeIfPresent(String.self, forKey: .name)
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
    static func devicePath(_ id: String) -> String {
        "/v1/me/devices/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)"
    }

    /// Signing another device out, built to be signed by the approval key: removing a device that holds
    /// an approval key or a passkey is an approval (desktop-client.md), `kiff.request.v1` over the
    /// DELETE, its path and the empty body, never inside a window.
    func prepareRemoveDevice(_ id: String, signedAt: String) throws -> Prepared {
        try prepare(method: "DELETE", path: Self.devicePath(id), body: Data(), gateway: false, signedAt: signedAt)
    }

    static let pairingsPath = "/v1/me/pairings"

    /// KIFF's answer to the phone's pairing approval (desktop-client.md §5.3): the code KIFF worked out,
    /// which the phone shows once more beside the one the person checked.
    struct Pairing: Decodable, Equatable {
        let id: String
        let code: String
    }

    /// A computer's pairing, built to be signed by the approval key: `kiff.request.v1` over
    /// `POST /v1/me/pairings` and the QR's fields, which the body carries as read.
    func preparePairing(_ request: PairingRequest, signedAt: String) throws -> Prepared {
        try prepare(method: "POST", path: Self.pairingsPath, body: request.body, gateway: false, signedAt: signedAt)
    }

    /// Sends the pairing, prepared and signed. Never inside an approval window: it's presigned.
    func approvePairing(_ prepared: Prepared, signatureHeader: String) async throws -> Pairing {
        struct Answer: Decodable { let pairing: Pairing }
        let answer: Answer = try await send(prepared.method, prepared.path, body: RawJSON(data: prepared.body), presigned: signatureHeader)
        return answer.pairing
    }

    /// Sends a device's removal, prepared and signed.
    func removeDevice(_ prepared: Prepared, signatureHeader: String) async throws {
        let _: Empty = try await send("DELETE", prepared.path, body: Optional<RawJSON>.none, presigned: signatureHeader)
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
            /// For a total: what it had used, what this call carries, and its window.
            var used: Int64? = nil
            var draw: Int64? = nil
            var window: String? = nil
            /// For a total for each item apart: the argument it counts by, and the item this
            /// call was on ("invoice_id", "in_42").
            var per: String? = nil
            var value: String? = nil

            enum CodingKeys: String, CodingKey {
                case outcome, reason, limit, quantity, unit, scale, used, draw, window, per, value
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
        /// Why it's held, in KIFF's own words for people: shown as it comes, so the apps never
        /// word a hold apart. Absent from an older KIFF.
        var reasonText: String? = nil
        /// KIFF's answer to whether approving once can pass this call (kiff/tafs#138, item 49).
        /// Absent from an older response: then the app offers it, as before.
        var canApproveOnce: Bool? = nil
        var kind: String? = nil
        var count: Int? = nil
        var lastAt: Date? = nil
        /// What the call is on and why, in the taf's own words, kept by KIFF with the hold
        /// (`taf_about`, a business reference such as "Order 1042"; `taf_reason`, the customer's
        /// reason). Unsigned, never used to decide anything, and possibly from a customer's
        /// text: the apps draw them as the taf's words, plain, and nothing else is taken from
        /// them. One that isn't text is left out, never the hold.
        var tafAboutText: LenientItem<String>? = nil
        var tafReasonText: LenientItem<String>? = nil
        var tafAbout: String? { tafAboutText?.value }
        var tafReason: String? { tafReasonText?.value }

        enum CodingKeys: String, CodingKey {
            case proposalID = "proposal_id", canApproveOnce = "can_approve_once", reasonText = "reason_text"
            case tafAboutText = "taf_about", tafReasonText = "taf_reason"
            case id, subject, action, title, arguments, status, breaches, parameters, reason, kind, count
            case entityID = "entity_id", nextSteps = "next_steps", accountingVersion = "accounting_version"
            case parameterUnits = "parameter_units", unitsUnavailable = "units_unavailable"
            case createdAt = "created_at", expiresAt = "expires_at"
            case lastAt = "last_at"
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

    /// KIFF's usage read, as the apps still read it once credits are the balance (pricing phase
    /// 2): what the store sells, who bills the plan, and members' requests. The balance and its
    /// words are tafs cloud's (`TafsCloud.Balance`). Lenient: the tasks parts a KIFF from before
    /// still sends are left unread, and a part that can't be read is left out, never the read.
    struct Usage: Decodable, Equatable {
        /// Who bills the plan: "apple", "google", "stripe" (KIFF), "contract", or "" when none
        /// does; and where it's managed, for a plan Apple bills.
        struct Billing: Decodable, Equatable {
            var source: String = ""
            var manageURL: URL? = nil
            /// The account moved from Solo in the App Store to Company, which KIFF bills now,
            /// and Solo still renews with Apple on this date unless it's cancelled there.
            var appleSoloUntil: Date? = nil
            enum CodingKeys: String, CodingKey { case source, manageURL = "manage_url", appleSoloUntil = "apple_solo_until" }
        }
        /// What the app sells: the account's token for purchases, Solo, and the packs of
        /// credits. The prices are the App Store's.
        struct Store: Decodable, Equatable {
            struct Pack: Decodable, Equatable, Identifiable {
                let id: String
                let name: String
                /// KIFF's line for it, shown as it is.
                let line: String
                /// The pack's credits, from a KIFF that says.
                var credits: Int? = nil
                let appleProduct: String
                /// The First month pack: bought once per account. KIFF drops it once bought.
                var once = false
                enum CodingKeys: String, CodingKey { case id, name, line, credits, appleProduct = "apple_product", once }

                init(id: String, name: String, line: String, credits: Int? = nil, appleProduct: String, once: Bool = false) {
                    self.id = id; self.name = name; self.line = line; self.credits = credits; self.appleProduct = appleProduct; self.once = once
                }

                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    id = try c.decode(String.self, forKey: .id)
                    name = try c.decode(String.self, forKey: .name)
                    line = try c.decode(String.self, forKey: .line)
                    credits = (try? c.decodeIfPresent(Int.self, forKey: .credits)) ?? nil
                    appleProduct = try c.decode(String.self, forKey: .appleProduct)
                    once = ((try? c.decodeIfPresent(Bool.self, forKey: .once)) ?? nil) ?? false
                }
            }
            var appAccountToken: UUID? = nil
            let soloProduct: String
            var packs: [Pack] = []
            enum CodingKeys: String, CodingKey { case appAccountToken = "app_account_token", soloProduct = "solo_product", packs }

            init(appAccountToken: UUID? = nil, soloProduct: String, packs: [Pack] = []) {
                self.appAccountToken = appAccountToken; self.soloProduct = soloProduct; self.packs = packs
            }

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                appAccountToken = (try? c.decodeIfPresent(UUID.self, forKey: .appAccountToken)) ?? nil
                soloProduct = try c.decode(String.self, forKey: .soloProduct)
                packs = ((try? c.decodeIfPresent([LenientItem<Pack>].self, forKey: .packs)) ?? nil)?.compactMap(\.value) ?? []
            }

            /// Every product the App Store is asked for.
            var products: [String] { [soloProduct] + packs.map(\.appleProduct) }

            /// The packs the app lists (docs/accounts.md): the First month pack first, and only
            /// while tafs cloud says it can be bought (`firstMonth`); then the packs that can be
            /// bought again. Never the First month pack alone or greyed.
            func offered(firstMonth: Bool) -> [Pack] {
                var listed: [Pack] = []
                if firstMonth, let first = packs.first(where: \.once) { listed.append(first) }
                listed.append(contentsOf: packs.filter { !$0.once })
                return listed
            }
        }
        /// KIFF's plan: "solo", "team" (Company), "company" (an agreement), "internal", or none.
        var plan: String? = nil
        var billing: Billing? = nil
        var store: Store? = nil
        /// Members' requests for a raise or a pack: every open one for someone who manages
        /// billing, this person's own otherwise.
        var requests: [PackRequest]? = nil

        enum CodingKeys: String, CodingKey { case plan, billing, store, requests }

        init(plan: String? = nil, billing: Billing? = nil, store: Store? = nil, requests: [PackRequest]? = nil) {
            self.plan = plan; self.billing = billing; self.store = store; self.requests = requests
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            plan = (try? c.decodeIfPresent(String.self, forKey: .plan)) ?? nil
            billing = (try? c.decodeIfPresent(Billing.self, forKey: .billing)) ?? nil
            store = (try? c.decodeIfPresent(Store.self, forKey: .store)) ?? nil
            requests = ((try? c.decodeIfPresent([LenientItem<PackRequest>].self, forKey: .requests)) ?? nil)?.compactMap(\.value)
        }
    }

    /// A member's request to the account's admins: a raise of the spend limit (`kind: "raise"`)
    /// or a pack of credits (`kind: "pack"`, naming the pack).
    struct PackRequest: Decodable, Equatable, Identifiable {
        let id: String
        let askedBy: String
        /// "raise" or "pack"; a request from a KIFF that doesn't say is for a pack.
        var kind: String = "pack"
        var pack: String? = nil
        var reason: String = ""
        /// open, approved, declined, withdrawn or expired.
        let status: String
        let createdAt: Date
        let expiresAt: Date
        var note: String? = nil
        enum CodingKeys: String, CodingKey { case id, askedBy = "asked_by", kind, pack, reason, status, createdAt = "created_at", expiresAt = "expires_at", note }

        init(id: String, askedBy: String, kind: String = "pack", pack: String? = nil, reason: String = "", status: String, createdAt: Date, expiresAt: Date,
             note: String? = nil) {
            self.id = id; self.askedBy = askedBy; self.kind = kind; self.pack = pack; self.reason = reason; self.status = status
            self.createdAt = createdAt; self.expiresAt = expiresAt; self.note = note
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            askedBy = try c.decode(String.self, forKey: .askedBy)
            kind = ((try? c.decodeIfPresent(String.self, forKey: .kind)) ?? nil) ?? "pack"
            pack = (try? c.decodeIfPresent(String.self, forKey: .pack)) ?? nil
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

    /// Asks the account's admins, with a reason: for a raise (`{"kind": "raise", "reason"}`,
    /// no pack), or for a pack as before (`{"pack", "reason"}`, where no `kind` means a pack).
    func askAdmin(raise: Bool, pack: String?, reason: String) async throws -> PackRequest {
        var body = ["reason": reason]
        if raise { body["kind"] = "raise" } else if let pack { body["pack"] = pack }
        return try await send("POST", "/v1/me/pack-requests", body: body)
    }

    /// Takes back this person's request.
    func withdrawRequest(_ id: String) async throws -> PackRequest {
        try await send("POST", "/v1/me/pack-requests/\(escaped(id))/withdraw", body: [String: String]())
    }

    /// Raises the spend limit one step from `fromCents`, the limit the balance read gave
    /// (docs/accounts.md › The balance): the act of someone who manages billing, a signed call
    /// from a phone. The signature covers the body, so it names the limit it raises from: a
    /// replayed one is refused, and so is a raise from a limit that changed meanwhile (409
    /// `limit_changed`). The answer's new limit isn't read: the balance is read again after.
    func raiseLimit(from fromCents: Int) async throws {
        struct Raise: Encodable {
            let raise = true
            let from_cents: Int
        }
        let _: Empty = try await send("PUT", "/v1/me/usage/limit", body: Raise(from_cents: fromCents))
    }

    /// Declines a member's request, with a note: a signed call from a phone.
    func declineRequest(_ id: String, note: String) async throws -> PackRequest {
        try await send("POST", "/v1/me/pack-requests/\(escaped(id))/decline", body: ["note": note])
    }

    func usage() async throws -> Usage {
        try await send("GET", "/v1/me/usage")
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
            var refusedBy: String? = nil
            var code: String? = nil
            enum CodingKeys: String, CodingKey { case status, message, code, refusedBy = "refused_by" }
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
        var detail: String? = nil
        enum CodingKeys: String, CodingKey {
            case title, outcome, held, amount, answer, receipt, reasons, detail
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
        return r.calls ?? []
    }

    func heldCalls() async throws -> [CardException] {
        struct Response: Decodable { let exceptions: [CardException] }
        let response: Response = try await send("GET", "/v1/me/card-exceptions?status=held")
        return response.exceptions
    }

    func cardException(_ id: String) async throws -> CardException {
        try await send("GET", "/v1/me/card-exceptions/\(escaped(id))")
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

    /// What the phone signs to answer a step a taf handed its owner (docs/api.md › Your step):
    /// KIFF's `kiff.handoff.v1`, one field per line. `answer` is "done" or "skipped"; `version` is
    /// the hand-off's shape, 1 until it changes. Pinned by KIFF's vector (YourStepTests).
    static func handoffMessage(tenant: String, handoffID: String, answer: String, version: Int = 1, signedAt: String) -> Data {
        Data(["kiff.handoff.v1", tenant, handoffID, answer, String(version), signedAt].joined(separator: "\n").utf8)
    }

    /// A step a taf handed its owner, as KIFF keeps it: its `version` is what the phone signs, and a
    /// `status` other than "waiting" means it was answered or has ended.
    struct Handoff: Decodable, Equatable {
        let id: String
        var version = 1
        var status = ""
        enum CodingKeys: String, CodingKey { case id, version, status }
        init(id: String, version: Int = 1, status: String = "") { self.id = id; self.version = version; self.status = status }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            version = (try? c.decodeIfPresent(Int.self, forKey: .version)) ?? 1
            status = (try? c.decodeIfPresent(String.self, forKey: .status)) ?? ""
        }
    }

    func handoff(_ id: String) async throws -> Handoff {
        try await send("GET", "/v1/me/handoffs/\(escaped(id))")
    }

    private struct HandoffAnswerBody: Encodable {
        let answer: String
        let signature: AnswerSignature
    }

    /// Answers a step handed to the owner, "done" or "skipped", signed with the device key.
    /// It grants nothing, so the approval key isn't asked for; KIFF answers it once.
    func answerHandoff(_ id: String, answer: String, signature: AnswerSignature) async throws {
        let _: Empty = try await send("POST", "/v1/me/handoffs/\(escaped(id))/answer",
                                      body: HandoffAnswerBody(answer: answer, signature: signature))
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
        /// The held call a draft was made from (KIFF's allow-draft), when KIFF says.
        var fromHold: String?
        enum CodingKeys: String, CodingKey {
            case id, status, version, reason, cards, label, retires
            case agentID = "agent_id", claimSecret = "claim_secret", cardIDs = "card_ids", noTools = "no_tools", fromHold = "from_hold"
        }
        struct Retired: Decodable, Equatable {
            let id: String
            var current: DraftCard.Terms?
        }
        var confirmed: Bool { status == "confirmed" }
        /// KIFF took the owner's signature for it: confirmed, or confirmed and its key claimed since.
        var signed: Bool { status == "confirmed" || status == "claimed" }
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
            return name.isEmpty ? Words.Card.theTafsCard : Words.Coach.cardOf(owner: name)
        }
    }

    /// One Card of a draft, as the owner reads it: each action and its limits.
    struct DraftCard: Decodable, Equatable {
        struct Grant: Decodable, Equatable {
            /// `unit` and `scale` when the tool declares them.
            struct PerAction: Decodable, Equatable { let quantity: String; let max: Int64; var unit: String?; var scale: Int? }
            /// What a grant may act on: only these values of one parameter.
            struct Resources: Decodable, Equatable { let parameter: String; var values: [String]? }
            let action: String
            var title: String?
            let perActionMax: [PerAction]?
            /// The grant's other terms, read so a draft can be compared with the Card in force
            /// (`HoldAllow`): its scope, the states it applies in, and the owner's other answers.
            var resources: Resources?
            var entityStates: [String]?
            var nextSteps: [String]?
            enum CodingKeys: String, CodingKey {
                case action, title, perActionMax = "per_action_max", resources, entityStates = "entity_states", nextSteps = "next_steps"
            }
        }
        struct Aggregate: Decodable, Equatable {
            let quantity: String; let limit: Int64; let window: String; var unit: String?; var scale: Int?
            /// What the total counts (attempts or effects; KIFF's default when a draft leaves it
            /// out), and the parameter it's kept for each value of, when it's per item.
            var measure: String?
            var per: String?
        }
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
        /// What KIFF does with a call past a limit: "owner" holds it for the owner, "refuse"
        /// refuses it; nil from a KIFF that doesn't say.
        var onExceed: String?
        /// KIFF's own words for the Card, shown as they come: the app words it only for a KIFF
        /// that doesn't send them, so the two apps and KIFF never word a Card apart.
        var kiffLines: [String]?
        /// The Card's other terms, as a draft's body carries them, read so the draft can be
        /// compared with the Card in force (`HoldAllow`): who holds it, where it applies, and
        /// when it starts and ends, as KIFF writes them. `holdExpiry` and `revokedAt` are a
        /// draft's alone: left out, the Card in force keeps its own.
        var subject: String?
        var subjects: [String]?
        var domain: String?
        var environment: String?
        var location: String?
        var validFrom: String?
        var validUntil: String?
        var holdExpiry: String?
        var revokedAt: String?
        enum CodingKeys: String, CodingKey {
            case grants, aggregates, revises, current, unchanged, onExceed = "on_exceed", kiffLines = "lines"
            case subject, subjects, domain, environment, location, validFrom = "valid_from", validUntil = "valid_until"
            case holdExpiry = "hold_expiry", revokedAt = "revoked_at"
        }

        /// A Card's terms alone: what `current` holds.
        struct Terms: Decodable, Equatable {
            let grants: [Grant]?
            let aggregates: [Aggregate]?
            var onExceed: String?
            var kiffLines: [String]?
            /// The Card in force's other terms (see `DraftCard`).
            var subject: String?
            var subjects: [String]?
            var domain: String?
            var environment: String?
            var location: String?
            var validFrom: String?
            var validUntil: String?
            enum CodingKeys: String, CodingKey {
                case grants, aggregates, onExceed = "on_exceed", kiffLines = "lines"
                case subject, subjects, domain, environment, location, validFrom = "valid_from", validUntil = "valid_until"
            }
            var lines: [String] { DraftCard(grants: grants, aggregates: aggregates, onExceed: onExceed, kiffLines: kiffLines).lines }
        }

        /// Past a limit, the call asks the owner first: the lines say so.
        var asksPastLimits: Bool { onExceed == "owner" }

        /// The lines the owner approves, in their words (kiff/tafs#94): "Refund a payment: up
        /// to €100.00 each", "€1,000.00 in total a day", "20 calls a day"; on a Card that asks
        /// past its limits, "…; more asks you first", "…, then it asks you", and a count of 0
        /// is "Each call asks you first". KIFF's own `lines` win when sent. Each action by its
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
            if let kiffLines, !kiffLines.isEmpty { return kiffLines }
            var out: [String] = []
            for g in grants ?? [] {
                let name = KIFFMapping.name(g.action, title: g.title)
                let caps = (g.perActionMax ?? []).map { cap in
                    KIFFMapping.isSum(cap.quantity)
                        ? Words.Card.Line.upToEach(amount: "\(KIFFMapping.money(cap.max, unit: cap.unit, scale: cap.scale).formatted)")
                        : Words.Card.Line.upToEach(amount: "\(cap.max)")
                }
                let action = alike.contains(name.lowercased()) ? "\(name) · \(KIFFMapping.toolWords(g.action))" : name
                let more = asksPastLimits ? Words.Card.Line.moreAsks : ""
                out.append(caps.isEmpty ? action : "\(action): " + caps.joined(separator: ", ") + more)
            }
            let then = asksPastLimits ? Words.Card.Line.thenAsks : ""
            for a in aggregates ?? [] {
                // KIFF's own window, exactly: never a lifetime or hourly total read as a day's.
                let window = Window(a.window)
                if KIFFMapping.isSum(a.quantity) {
                    out.append("\(KIFFMapping.money(a.limit, unit: a.unit, scale: a.scale).formatted) \(window.sumLabel)\(then)")
                } else if asksPastLimits && a.limit == 0 {
                    out.append(Words.Card.Held.eachCallAsks)
                } else {
                    out.append(Words.Card.Line.callsInWindow(count: Int(a.limit), window: window.label, then: then))
                }
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

    /// A request built in full before it's signed, so its message can be signed together with
    /// another request's (`RequestsTogether`): its body's exact bytes, its `signed_at`, and the
    /// `kiff.request.v1` message KIFF rebuilds from them.
    struct Prepared: Equatable {
        let method: String
        let path: String
        let body: Data
        let signedAt: String
        let message: Data
        /// For KIFF's gateway, not its API.
        var gateway = false
    }

    /// The confirm of a draft, at exactly `version`, built to be signed together with another request.
    func prepareConfirmDraft(id: String, version: String, signedAt: String) throws -> Prepared {
        guard let tenant else { throw KIFFError.unauthorized }
        let path = "/v1/me/agent-drafts/\(escaped(id))/confirm"
        guard let url = URL(string: path, relativeTo: baseURL) else { throw KIFFError.server(status: 0, message: Words.Error.badPath) }
        let body = try JSONEncoder().encode(["version": version])
        let uri = url.path + (url.query.map { "?" + $0 } ?? "")
        return Prepared(method: "POST", path: path, body: body, signedAt: signedAt,
                        message: Self.requestMessage(tenant: tenant, method: "POST", uri: uri, body: body, signedAt: signedAt))
    }

    /// A request tafs cloud built, to KIFF's API or its gateway, ready to be signed together with
    /// another (Coach's first start): `body` is sent as these bytes, which its message covers.
    func prepare(method: String, path: String, body: Data, gateway: Bool, signedAt: String) throws -> Prepared {
        guard let tenant else { throw KIFFError.unauthorized }
        guard let url = URL(string: path, relativeTo: gateway ? gatewayURL : baseURL) else { throw KIFFError.server(status: 0, message: Words.Error.badPath) }
        let uri = url.path + (url.query.map { "?" + $0 } ?? "")
        return Prepared(method: method, path: path, body: body, signedAt: signedAt,
                        message: Self.requestMessage(tenant: tenant, method: method, uri: uri, body: body, signedAt: signedAt), gateway: gateway)
    }

    /// Sends Coach's tools batch, prepared and signed together with its draft.
    func connectBatch(_ prepared: Prepared, signatureHeader: String) async throws {
        let _: Empty = try await send(prepared.method, prepared.path, body: RawJSON(data: prepared.body), base: gatewayURL, presigned: signatureHeader)
    }

    /// Sends Coach's draft, prepared and signed together with its batch: KIFF answers the draft,
    /// confirmed, with the claim secret tafs cloud takes Coach's key with.
    func submitDraft(_ prepared: Prepared, signatureHeader: String) async throws -> AgentDraft {
        try await send(prepared.method, prepared.path, body: RawJSON(data: prepared.body), presigned: signatureHeader)
    }

    /// Submits a draft exactly as tafs cloud sent it, signed as any setup call, and answers it.
    func submitDraftAnswered(_ body: Data) async throws -> AgentDraft {
        try await send("POST", "/v1/me/agent-drafts", body: RawJSON(data: body))
    }

    /// Sends a prepared confirm with the signature header made for it: the bytes that were signed, unchanged.
    func confirmDraft(_ prepared: Prepared, signatureHeader: String) async throws -> AgentDraft {
        try await send(prepared.method, prepared.path, body: RawJSON(data: prepared.body), presigned: signatureHeader)
    }

    /// As `confirmDraft`, naming the held call the draft answers: KIFF lets that call go once
    /// under the same signature, so one Face ID covers the Card and the call
    /// (docs/allow-from-now-on.md).
    func confirmDraft(id: String, version: String, hold: String, accountingVersion: Int) async throws -> AgentDraft {
        struct Body: Encodable {
            struct Hold: Encodable {
                let id: String
                let accountingVersion: Int
                enum CodingKeys: String, CodingKey { case id, accountingVersion = "accounting_version" }
            }
            let version: String
            let hold: Hold
        }
        return try await send("POST", "/v1/me/agent-drafts/\(escaped(id))/confirm",
                              body: Body(version: version, hold: .init(id: hold, accountingVersion: accountingVersion)))
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

    /// Gives Coach's tools their connections: tafs cloud's batch (`tools.connect.body`), sent
    /// to the gateway as these bytes and signed. The phone builds none of it (docs/coach.md).
    func connectBatch(_ body: Data) async throws {
        let _: Empty = try await send("POST", "/v1/connections/batch", body: RawJSON(data: body), base: gatewayURL)
    }

    /// Submits a draft exactly as tafs cloud sent it (Coach's Cards, `tools.card.body`): from
    /// the phone, signed by the approval key, it is issued at once.
    func submitDraft(_ body: Data) async throws {
        let _: Empty = try await send("POST", "/v1/me/agent-drafts", body: RawJSON(data: body))
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

        /// The name as the person reads it: KIFF's default group (`everyone`) in the person's
        /// language ("Tout le monde"), since KIFF keeps it as "Everyone"; any other group as it
        /// was named. As Android's `Group.shownName`.
        var shownName: String { everyone == true ? Words.People.everyoneGroup : name }
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

    /// The languages the app's own screens are written in, most preferred first: what it asks
    /// KIFF and tafs cloud for, so their words (a held call's reason, a Record line, a run's
    /// line) match the screen's (docs/languages.md, kiff/tafs#75). The language the screens
    /// resolved to comes first, then the phone's other languages the app ships, at most four;
    /// `acceptLanguage` adds English last.
    nonisolated static var screenLanguages: [String] {
        screenLanguages(resolved: Bundle.main.preferredLocalizations.first, preferred: Locale.preferredLanguages, shipped: Words.languages)
    }

    /// The one language the app's own screens resolved to: what KIFF's pages and kiff.dev's are
    /// opened in. Not the main actor's: a link is built wherever it is shown.
    nonisolated static var appLanguage: String { screenLanguages.first ?? "en" }

    /// Each language by its code as the app ships it ("pt-BR" is "pt"), once, and only one the
    /// app ships: a language only Siri's phrases are in never counts.
    nonisolated static func screenLanguages(resolved: String?, preferred: [String], shipped: [String]) -> [String] {
        var out: [String] = []
        for tag in [resolved].compactMap({ $0 }) + preferred {
            let code = tag.lowercased().split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? ""
            if shipped.contains(code), !out.contains(code) { out.append(code) }
            if out.count == 4 { break }
        }
        return out.isEmpty ? ["en"] : out
    }

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
    /// `presigned` is the whole signature header of a request built and signed beforehand
    /// (`prepareConfirmDraft`): sent as it is, and nothing here signs.
    /// `inWindow` false signs it outside any approval window: KIFF refused it inside one.
    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?, base: URL? = nil, authorized: Bool = true,
                                                   signedBy: (@MainActor (Data) throws -> String)? = nil, presigned: String? = nil,
                                                   inWindow: Bool = true) async throws -> T {
        guard let url = URL(string: path, relativeTo: base ?? baseURL) else { throw KIFFError.server(status: 0, message: Words.Error.badPath) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("tafs-ios", forHTTPHeaderField: "User-Agent")
        // The language the app shows, not the device's list: an English app on a Spanish iPad
        // read KIFF's reasons in Spanish (kiff/tafs#138, 21 Oct). When the app is translated,
        // KIFF follows it.
        request.setValue(Self.acceptLanguage(Self.screenLanguages), forHTTPHeaderField: "Accept-Language")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try (body as? RawJSON)?.data ?? JSONEncoder().encode(body)
        }
        // The gateway takes a control-server connection from a phone only signed (kiff/tafs#206).
        let signed = base == nil ? (Self.signsSetup(path) || path == "/v1/me/device/approval-key" || path == "/v1/me/device/relink")
            : (base == gatewayURL && (path == "/v1/connections" || path == "/v1/connections/batch"))
        // What grants authority is signed by the approval key, once the session has one,
        // within a step the owner confirmed; KIFF picks the key by route, so the header is
        // the same (kiff/tafs#233).
        let authority = signedBy == nil && setupSigner != nil && base == nil && Self.grantsAuthority(method, path)
        // Inside a held approval window, for the approval step that took it, what the approval key would
        // sign goes with the device key and the window's id: a tap (kiff/tafs#494). Opening a window is
        // never signed in one.
        let held = inWindow && signsInWindow && authority && presigned == nil && path != Self.windowPath ? window?.id : nil
        let signer = signedBy ?? setupSigner.map { current in
            authority && held == nil ? (approvalSigner ?? current) : current
        }
        if let presigned {
            request.setValue(presigned, forHTTPHeaderField: "KIFF-Device-Signature")
        } else if ["POST", "PUT", "PATCH", "DELETE"].contains(method), signed, let signer, let tenant {
            let signedAt = SigningTime.next()
            let uri = url.path + (url.query.map { "?" + $0 } ?? "")
            if let held {
                let message = Self.requestMessageWindow(tenant: tenant, method: method, uri: uri, body: request.httpBody ?? Data(), signedAt: signedAt, window: held)
                request.setValue("t=\(signedAt),v2=\(try signer(message)),w=\(held)", forHTTPHeaderField: "KIFF-Device-Signature")
            } else {
                let message = Self.requestMessage(tenant: tenant, method: method, uri: uri, body: request.httpBody ?? Data(), signedAt: signedAt)
                request.setValue("t=\(signedAt),v1=\(try signer(message))", forHTTPHeaderField: "KIFF-Device-Signature")
            }
        }
        if authorized {
            guard let token else { throw KIFFError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        // A failed request, by its route and status only, for a drive's diagnostics (docs/drive.md).
        if !(200..<300).contains(status) { DriveDiagnostics.shared.failed(method: method, path: path, status: status) }
        if status == 401 { throw KIFFError.unauthorized }
        guard (200..<300).contains(status) else {
            // The refusal's string fields: a body with a number in it too (a raise's `limit_cents`)
            // still gives its `error` and `reason`.
            let fields = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?.compactMapValues { $0 as? String }
            // Refused in the window: the act takes the approval key every time, or the window ended. The
            // approval key signs it again, once, behind a new Face ID; an ended window is forgotten.
            if status == 403, let held, let refused = fields?["error"], refused == Self.criticalKeyRequired || refused == Self.windowRequired {
                if refused == Self.windowRequired, window?.id == held { window = nil }
                guard approvalSigner != nil, let reconfirm = windowReconfirm else { throw KIFFError.windowRefused(code: refused) }
                try await reconfirm()
                return try await send(method, path, body: body, base: base, authorized: authorized, signedBy: signedBy, presigned: presigned, inWindow: false)
            }
            if status == 403, fields?["error"] == "approval_key_required" || fields?["reason"] == "approval_key_required" {
                approvalKeyRequired?()
                throw KIFFError.approvalKeyRequired
            }
            if status == 409, base == nil, path == "/v1/me/device/approval-key" {
                throw KIFFError.approvalKeyExists(fingerprint: fields?["fingerprint"])
            }
            // A pairing's refusal: KIFF's own words for the person, with its code as the reason.
            if path == Self.pairingsPath, let code = fields?["error"] {
                throw KIFFError.server(status: status, message: fields?["words"] ?? code, reason: code)
            }
            throw KIFFError.server(status: status, message: fields?["error"] ?? Words.Error.kiffAnswered(status: "\(status)"), reason: fields?["reason"])
        }
        if T.self == RawBody.self, let raw = RawBody(data: data) as? T { return raw }
        if T.self == Empty.self || data.isEmpty { return try JSONDecoder().decode(T.self, from: Data("{}".utf8)) }
        return try Self.decoder.decode(T.self, from: data)
    }

    /// `/v1/me/did-this-happen/{proposal}`: an answer to "Did this happen?".
    static func answersHappened(_ path: String) -> Bool {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return parts.count == 5 && path.hasPrefix("/v1/me/did-this-happen/") && !parts[4].isEmpty
    }

    /// A money call whose outcome KIFF can't prove: its answer was lost, or the vendor refused
    /// it with nothing proving no effect. The owner says whether it happened.
    struct Unproven: Decodable, Equatable, Identifiable {
        let proposal: String
        var agent: String?
        var tool: String?
        var title: String
        /// unknown, refused_by_tool, or probation (the first outcomes of an untried money operation).
        var status: String
        /// In minor units of `unit`, at `scale`.
        var amount: Int64?
        var unit: String?
        var scale: Int?
        var at: String?
        /// KIFF's sentence for the call ("Refund a payment: 40.00 EUR."), shown as it is.
        var words: String?
        /// Why KIFF asks, in its words: the answer was lost, the tool refused it, or probation.
        var statusWords: String?
        var id: String { proposal }
        var money: Money? { amount.map { Money(minor: $0, currency: unit, scale: scale ?? 2) } }
        enum CodingKeys: String, CodingKey { case proposal, agent, tool, title, status, amount, unit, scale, at, words, statusWords = "status_words" }
        init(proposal: String, agent: String? = nil, tool: String? = nil, title: String, status: String,
             amount: Int64? = nil, unit: String? = nil, scale: Int? = nil, at: String? = nil, words: String? = nil, statusWords: String? = nil) {
            self.proposal = proposal; self.agent = agent; self.tool = tool; self.title = title; self.status = status
            self.amount = amount; self.unit = unit; self.scale = scale; self.at = at; self.words = words; self.statusWords = statusWords
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            proposal = try c.decode(String.self, forKey: .proposal)
            agent = try c.decodeIfPresent(String.self, forKey: .agent)
            tool = try c.decodeIfPresent(String.self, forKey: .tool)
            title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
            status = try c.decodeIfPresent(String.self, forKey: .status) ?? "unknown"
            amount = try c.decodeIfPresent(Int64.self, forKey: .amount)
            unit = try c.decodeIfPresent(String.self, forKey: .unit)
            scale = try c.decodeIfPresent(Int.self, forKey: .scale)
            at = try c.decodeIfPresent(String.self, forKey: .at)
            words = try c.decodeIfPresent(String.self, forKey: .words)
            statusWords = try c.decodeIfPresent(String.self, forKey: .statusWords)
        }
    }

    /// "Did this happen?": the money calls waiting for the owner to say, newest first.
    func didThisHappen() async throws -> [Unproven] {
        struct Response: Decodable { var calls: [Unproven]? }
        let r: Response = try await send("GET", "/v1/me/did-this-happen")
        return r.calls ?? []
    }

    /// The owner's answer, signed on the phone like an approval: "No" gives the Card its room
    /// back, "Yes" keeps it counted. The first answer stands.
    func answerHappened(_ proposal: String, happened: Bool) async throws {
        let _: Empty = try await send("POST", "/v1/me/did-this-happen/\(escaped(proposal))", body: ["happened": happened])
    }

    /// A request body sent as these bytes, untouched.
    struct RawJSON: Encodable {
        let data: Data
        func encode(to encoder: Encoder) throws {}
    }

    /// KIFF's words for `profile`, the `kiff.profile.v1` in a grant's request, read with the
    /// person's own session: it reads nothing of the account and changes
    /// nothing. Checked against the profile before they're returned (`ProfileWords.checked`).
    func profileWords(for profile: JCS.Value) async throws -> ProfileWords {
        let answer: RawBody = try await send("POST", "/v1/profiles/words", body: RawJSON(data: try JCS.canonical(profile)))
        return try ProfileWords.checked(answer.data, profile: profile)
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
