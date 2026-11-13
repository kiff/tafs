import CryptoKit
import Foundation

/// planque (kiff/planque), which keeps a taf's vendor sign-ins so they never reach the
/// phone, tafs cloud, the agent or KIFF. The phone enrols once, approves each grant,
/// confirms each vendor sign-in and disconnects. Every call carries the phone key's
/// signature (`Planque-Device-Signature`), so planque acts only on what this iPhone's
/// owner signed. The API is contract 2 on kiff/planque#1.
@MainActor
final class PlanqueClient {
    enum PlanqueError: Error, Equatable, LocalizedError {
        case notSigning
        /// This device's id in KIFF couldn't be read: planque takes no signature without it
        /// (kiff/planque#12), so nothing is sent.
        case noDevice
        /// The grant's digest isn't the hash of the request shown: nothing is approved.
        case digestMismatch
        /// A connection with a profile is approved only once KIFF's words for it are shown.
        case wordsUnread
        case server(status: Int, message: String)

        var errorDescription: String? {
            switch self {
            case .notSigning: return Words.Error.needsConfirmationFirst
            case .noDevice: return Words.Error.noDeviceName
            case .digestMismatch: return Words.Error.requestMismatch
            case .wordsUnread: return Words.Connect.profileWordsUnread
            case .server(_, let message): return message
            }
        }
    }

    let baseURL: URL
    /// The account the phone signed in to KIFF with.
    var tenant: String?
    /// Signs one request: set while a step the owner confirmed with Face ID runs, and for
    /// reads, which ask for nothing.
    var signer: (@MainActor (Data) throws -> String)?
    /// This device's id in KIFF, sent with each signature (`d=`) so planque asks KIFF
    /// whether the device signed (kiff/planque#10). Without it, planque checks the key
    /// this phone enrolled with.
    var deviceID: String?
    /// Reads `deviceID` from KIFF when it isn't known yet, right before a signature: planque
    /// refuses one without it (kiff/planque#12).
    var deviceLoader: (@MainActor () async -> String?)?
    private let session: URLSession

    init(baseURL: URL = URL(string: "https://planque.kiff.dev")!, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// What the phone signs (planque's `device.Message`): seven lines, no trailing newline.
    static func message(base: URL, tenant: String, method: String, uri: String, body: Data, signedAt: String) -> Data {
        var b = base.absoluteString
        while b.hasSuffix("/") { b.removeLast() }
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        return Data(["planque.request.v1", b, tenant, method, uri, bodyHash, signedAt].joined(separator: "\n").utf8)
    }

    // MARK: Enrolment

    struct Enrolment: Decodable, Equatable {
        let tenant: String
        /// The phone key's fingerprint, as planque shows it in its record.
        let device: String
    }

    /// Hands planque the KIFF connector key it keeps to register tools. Signed with
    /// `deviceID`, any of the owner's devices may (kiff/planque#10). The device's key goes
    /// too, for a planque from before that, which enrols one phone by its key.
    func enrol(deviceKey: Data, connectorKey: String) async throws -> Enrolment {
        try await send("POST", "enrolment", body: ["device_key": deviceKey.base64EncodedString(), "connector_key": connectorKey])
    }

    // MARK: Grants

    struct Grant: Decodable, Equatable, Identifiable {
        struct Request: Decodable, Equatable {
            struct Tool: Decodable, Equatable {
                let tool: String
                let name: String?
                /// What it does, in the owner's words, as Setup asked for it (kiff/planque#37).
                var title: String?
                let readOnly: Bool?
                /// Setup's own read, on Setup's reader Card, never the taf's (kiff/tafs#206 section 4).
                var setupReads: Bool? = nil
                enum CodingKeys: String, CodingKey { case tool, name, title, readOnly = "read_only", setupReads = "setup_reads" }
                /// One of the taf's own tools, not Setup's read.
                var tafs: Bool { setupReads != true }
                /// Its name in KIFF and at the relay: the Card's action.
                var action: String { name ?? tool }
                /// What the owner reads for it: its title, else its action in words.
                var words: String { KIFFMapping.name(action, title: title) }
            }
            /// A managed provider's connection: which provider, and its toolkit ("slackbot").
            struct Connector: Decodable, Equatable {
                var provider: String?
                var toolkit: String?
            }
            let server: String
            let agent: String
            let tools: [Tool]
            var connector: Connector?
            /// `api_key` for a vendor reached with a key the owner gives; none for one signed in to.
            var kind: String? = nil
        }
        struct Event: Decodable, Equatable {
            /// RFC 3339, as planque writes it (Go's nanoseconds included).
            let at: String
            /// In the owner's words, as planque writes it: the device's name, never an id
            /// (kiff/planque#16). Shown as it is.
            let what: String
        }
        let id: String
        /// pending, approved, signed_in, listed, connected, failed, disconnected, or
        /// needs_reconnect: connected once, then signed out by its vendor, so it waits for a
        /// real sign-in again.
        let status: String
        /// planque's words for the status, shown as they are: "Signed out by Stripe · sign in again".
        var statusWords: String?
        /// What the owner reads before approving: each tool in words (kiff/planque#37).
        let text: String
        /// Each tool's exact terms, one line each, as planque gives them: its name, the
        /// vendor's tool and what's pinned. Shown under Details; the phone signs the request
        /// they're built from. None from an older planque.
        var terms: [String]?
        let digest: String
        let request: Request
        let account: String?
        let tools: [String]?
        let reason: String?
        let events: [Event]
        /// planque holds a confirmed sign-in for it (contract 5 on kiff/agents#91): a listed
        /// grant, or one whose tools were set after it, approved with no second sign-in.
        let signedIn: Bool?
        /// When planque revokes a held sign-in nothing connected (RFC 3339, Go's nanoseconds
        /// included, so kept as written).
        let heldUntil: String?
        /// The catalog entry it was connected from ("connector:composio:slackbot") and its
        /// provider, as planque marks it: what the app matches on, never a vendor's name.
        var entry: String?
        var provider: String?
        /// The name of the taf it was made for, as planque keeps it: what a removed taf's
        /// connection is still called.
        var agentName: String?
        /// With `signed_in` and no sign-in link: planque holds a fresh sign-in and waits for the
        /// approving phone to confirm it with this one-use code, as a browser's code is; unconfirmed,
        /// planque revokes it at `confirm_by`.
        var confirmCode: String?
        /// The Slack bot connection a Slack door listens through.
        var slackBot: Bool { entry == Self.slackBotEntry || (entry == nil && request.connector?.toolkit == "slackbot") }
        static let slackBotEntry = "connector:composio:slackbot"
        /// Whether `digest` is the hash of the request as received: the app shows Approve
        /// only then. Set by `grants()`, never read from planque.
        var digestMatches = false
        /// The connection's `kiff.profile.v1` in the request, as planque sent it:
        /// the grant's digest covers it. The owner reads KIFF's words for it before
        /// approving. Set by `grants()`, never decoded.
        var profile: JCS.Value?

        enum CodingKeys: String, CodingKey { case id, status, statusWords = "status_words", text, terms, digest, request, account, tools, reason, events, signedIn = "signed_in", heldUntil = "held_until", vendorName = "vendor", entry, provider, agentName = "agent_name", confirmCode = "confirm" }

        /// The vendor, as the owner knows it: its server's host.
        /// The vendor's name, as planque gives it: what its server calls itself, else its
        /// host (kiff/planque#17). Shown as it is.
        let vendorName: String?
        var vendor: String { vendorName ?? request.server }
        var connected: Bool { status == "connected" || connectedNow }
        /// planque answered connected to this phone's approval, before its list says so
        /// (kiff/tafs#245): never decoded, only set by `marked(connected:)`.
        var connectedNow = false

        /// This grant as planque's answer to an approval left it.
        func marked(connected: Bool) -> Grant {
            var g = self
            g.connectedNow = connected
            return g
        }
        /// Connected first (contract 5): signed in, and planque listed the vendor's tools for
        /// Setup to choose from. Nothing is in KIFF yet.
        var listed: Bool { status == "listed" }
        /// Its vendor signed it out: the owner signs in again, and it's connected only once
        /// planque says so.
        var needsReconnect: Bool { status == "needs_reconnect" }
        /// Reached with a key the owner gives (planque's `api_key`), not a sign-in: it's fixed
        /// with a new key (gap 13).
        var takesKey: Bool { request.kind == "api_key" }
        /// A managed provider's connection, connected: planque can't sign it in again in place
        /// (it can't tell which account a new sign-in at the provider is), so it's disconnected
        /// and connected again instead.
        var signsInThroughProvider: Bool { request.connector != nil && connected && !takesKey }
        /// Approved, and waiting on a sign-in at the vendor that hasn't come back: the owner may
        /// cancel it, which ends the grant, and connect again.
        var waitingSignIn: Bool { status == "approved" && signedIn != true && !connectedNow }
        /// A request nobody answered yet, holding no sign-in: it's cancelled, not removed, since
        /// nothing was connected.
        var pendingRequest: Bool { status == "pending" && !holdsSignIn && !connectedNow }
        /// Ended with Cancel, not Remove access: a sign-in that never came back, or a request
        /// not answered yet.
        var cancellable: Bool { waitingSignIn || pendingRequest }
        /// The signed-out connection in planque's words; before planque sends them, the app's.
        var signedOutWords: String {
            statusWords.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? Words.Connect.Status.signedOut(vendor: "\(vendor)")
        }
        /// A sign-in planque holds that no taf uses yet: the owner can see and end it.
        var holdsSignIn: Bool { listed || (status == "pending" && signedIn == true) }
        /// `heldUntil` as a date: its fraction of a second dropped, which a formatter can't read past milliseconds.
        var heldUntilDate: Date? { heldUntil.flatMap(Self.date) }
        /// When planque last changed it, from its latest event (kiff/tafs#236, item 3).
        var changedAt: Date? { events.compactMap { Self.date($0.at) }.max() }

        static func date(_ raw: String) -> Date? {
            let whole = raw.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
            return ISO8601DateFormatter().date(from: whole)
        }
    }

    /// What a grant's digest covers, as its `digest_v` says. Without one, the request alone (v1). With
    /// `digest_v: 2`, the request with the words the person reads and their language,
    /// `{"lang", "request", "text"}`, so the phone proves the words it shows, in that language, are the
    /// ones signed. Nil for a version this app doesn't know, or a v2 grant without its words or language:
    /// its digest then never matches, and nothing is approved that the person couldn't have read.
    static func digestCovers(_ raw: JCS.Value) -> JCS.Value? {
        guard let request = raw["request"] else { return nil }
        switch raw["digest_v"] {
        case nil:
            return request
        case .number("2")?:
            guard let lang = raw["lang"], lang.string?.isEmpty == false, let text = raw["text"], text.string != nil else { return nil }
            return .object(["lang": lang, "request": request, "text": text])
        default:
            return nil
        }
    }

    /// Every grant for the account, each with its digest checked against what it covers (`digestCovers`).
    /// `readSigner` signs this read alone, leaving `signer` as it is: a refresh that runs
    /// while a Face ID step waits on the vendor's page must not end that step's signer.
    func grants(signedBy readSigner: (@MainActor (Data) throws -> String)? = nil) async throws -> [Grant] {
        struct Response: Decodable { let grants: [Grant] }
        let data = try await raw("GET", "grants", body: nil, signer: readSigner)
        var grants = try KIFFClient.decoder.decode(Response.self, from: data).grants
        // The request as planque sent it, numbers as written, so the digest is planque's.
        let raws = (try? JCS.parse(data))?["grants"]?.array ?? []
        for i in grants.indices {
            guard let raw = raws.first(where: { $0["id"]?.string == grants[i].id }), let request = raw["request"] else { continue }
            grants[i].profile = request["profile"]
            guard let covered = Self.digestCovers(raw), let canonical = try? JCS.canonical(covered) else { continue }
            let hash = SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
            grants[i].digestMatches = hash == grants[i].digest
        }
        return grants
    }

    struct Approved: Decodable, Equatable {
        let status: String
        /// The vendor's own sign-in page, to open in an `ASWebAuthenticationSession`. None when
        /// planque already holds the sign-in: approving the tools connected them (contract 5).
        let signInURL: URL?
        let expiresAt: String?
        enum CodingKeys: String, CodingKey { case status, signInURL = "sign_in_url", expiresAt = "expires_at" }
    }

    /// The approve body: the grant's digest, and for a grant with a profile, the digests of
    /// that profile and of KIFF's words for it. The device signs the body, so
    /// its signature covers both.
    struct ApproveBody: Codable, Equatable {
        struct KIFFProfile: Codable, Equatable {
            let profileSHA256: String
            let wordsSHA256: String
            enum CodingKeys: String, CodingKey { case profileSHA256 = "profile_sha256", wordsSHA256 = "words_sha256" }
        }
        let digest: String
        let kiffProfile: KIFFProfile?
        enum CodingKeys: String, CodingKey { case digest, kiffProfile = "kiff_profile" }
    }

    /// The body that approves `grant` as shown. Refused when the digest isn't the hash of the
    /// request the owner was shown, and for a grant with a profile, until KIFF's words for
    /// exactly that profile were read and checked.
    static func approveBody(_ grant: Grant, words: ProfileWords?) throws -> ApproveBody {
        guard grant.digestMatches else { throw PlanqueError.digestMismatch }
        guard let profile = grant.profile else { return ApproveBody(digest: grant.digest, kiffProfile: nil) }
        guard let words else { throw PlanqueError.wordsUnread }
        guard try ProfileWords.digest(profile) == words.profileSHA256 else { throw ProfileWords.Failure.otherProfile }
        return ApproveBody(digest: grant.digest, kiffProfile: .init(profileSHA256: words.profileSHA256, wordsSHA256: words.wordsSHA256))
    }

    /// The body that gives an API-key grant its key: the grant's digest, the key's SHA-256 and,
    /// with a profile, both its digests. The device signs it, so the signature covers which key;
    /// the key itself goes in a header, never in what's signed or logged.
    struct KeyBody: Encodable, Equatable {
        let digest: String
        let keySHA256: String
        let kiffProfile: ApproveBody.KIFFProfile?
        enum CodingKeys: String, CodingKey { case digest, keySHA256 = "key_sha256", kiffProfile = "kiff_profile" }
    }

    /// The header planque reads a grant's key from.
    static let keyHeader = "Planque-Vendor-Key"

    /// Gives an API-key grant its key, as shown, with the same checks as approving.
    func giveKey(_ grant: Grant, key: String, words: ProfileWords? = nil) async throws -> Grant {
        let approved = try Self.approveBody(grant, words: words)
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let sum = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        let body = try JSONEncoder().encode(KeyBody(digest: approved.digest, keySHA256: sum, kiffProfile: approved.kiffProfile))
        let data = try await raw("POST", "grants/\(escaped(grant.id))/key", body: body, headers: [Self.keyHeader: key])
        return try KIFFClient.decoder.decode(Grant.self, from: data)
    }

    /// A key request built in full before it's signed, so its message can be signed together
    /// with another request's (`RequestsTogether`): its body's exact bytes, the key for its
    /// header, its `signed_at`, and the `planque.request.v1` message planque rebuilds from them.
    struct PreparedKey: Equatable {
        let path: String
        let body: Data
        let key: String
        let signedAt: String
        let message: Data
    }

    /// `giveKey`, built to be signed together with another request: nothing is sent yet.
    func prepareKey(_ grant: Grant, key: String, words: ProfileWords? = nil, signedAt: String) throws -> PreparedKey {
        guard let tenant else { throw PlanqueError.notSigning }
        let approved = try Self.approveBody(grant, words: words)
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let sum = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        let body = try JSONEncoder().encode(KeyBody(digest: approved.digest, keySHA256: sum, kiffProfile: approved.kiffProfile))
        let path = "grants/\(escaped(grant.id))/key"
        guard let url = URL(string: "v1/accounts/\(escaped(tenant))/\(path)", relativeTo: baseURL) else {
            throw PlanqueError.server(status: 0, message: Words.Error.badPath)
        }
        let uri = url.path(percentEncoded: true) + (url.query(percentEncoded: true).map { "?" + $0 } ?? "")
        return PreparedKey(path: path, body: body, key: key, signedAt: signedAt,
                           message: Self.message(base: baseURL, tenant: tenant, method: "POST", uri: uri, body: body, signedAt: signedAt))
    }

    /// Sends a prepared key request under a signature made over it and another request
    /// (`kiff.requests.v1`): `with` is the other request's hash.
    func giveKey(_ prepared: PreparedKey, signature: String, with other: String) async throws -> Grant {
        let data = try await raw("POST", prepared.path, body: prepared.body, headers: [Self.keyHeader: prepared.key],
                                 together: (signedAt: prepared.signedAt, signature: signature, with: other))
        return try KIFFClient.decoder.decode(Grant.self, from: data)
    }

    /// Approves `grant` as shown, with KIFF's words for its profile when it has one.
    func approve(_ grant: Grant, words: ProfileWords? = nil) async throws -> Approved {
        try await send("POST", "grants/\(escaped(grant.id))/approve", body: try Self.approveBody(grant, words: words))
    }

    /// Approves `grantID` with the body tafs cloud built for it (kiff/tafs#413): sent as it is,
    /// signed by this device. The phone checks nothing of it; tafs cloud read the grant and decided.
    func approve(grantID: String, body: ApproveBody) async throws -> Approved {
        try await send("POST", "grants/\(escaped(grantID))/approve", body: body)
    }

    /// Confirms the vendor sign-in with the code it handed back to the app. Only the phone
    /// that approved can, within 15 minutes; then planque registers the tools in KIFF.
    func confirm(grantID: String, code: String) async throws -> Grant {
        try await send("POST", "grants/\(escaped(grantID))/confirm", body: ["confirm": code])
    }

    /// A Slack channel a Slack bot connection can see: setup metadata, never a message.
    struct Channel: Decodable, Equatable, Identifiable, Hashable {
        let id: String
        var name = ""
        var isPrivate = false
        enum CodingKeys: String, CodingKey { case id, name, isPrivate = "private" }
        init(id: String, name: String = "", isPrivate: Bool = false) { self.id = id; self.name = name; self.isPrivate = isPrivate }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
            isPrivate = try c.decodeIfPresent(Bool.self, forKey: .isPrivate) ?? false
        }
    }

    /// The channels a Slack bot connection can see, read through its own list-channels action.
    func channels(grantID: String, signedBy readSigner: (@MainActor (Data) throws -> String)? = nil) async throws -> [Channel] {
        struct Response: Decodable { let channels: [Channel] }
        let data = try await raw("GET", "grants/\(escaped(grantID))/channels", body: nil, signer: readSigner)
        return try KIFFClient.decoder.decode(Response.self, from: data).channels
    }

    /// The trigger a Slack door needs: planque subscribes these channels of the connection's
    /// account to its message event, the request signed by this phone. Its id comes back.
    static let slackMessageTrigger = "SLACKBOT_CHANNEL_MESSAGE_RECEIVED"

    func subscribe(grantID: String, account: String, slug: String, channels: [String]) async throws -> String {
        struct Body: Encodable { let account: String; let slug: String; let channels: [String] }
        struct Response: Decodable {
            var trigger: String?
            var id: String?
        }
        let r: Response = try await send("POST", "grants/\(escaped(grantID))/triggers", body: Body(account: account, slug: slug, channels: channels))
        guard let id = r.id ?? r.trigger else { throw PlanqueError.server(status: 502, message: Words.Setup.Knob.noSubscription) }
        return id
    }

    /// Any app event: the server-supplied filters go in the same signed request as its
    /// account and event. No device-specific filters or defaults are added.
    func subscribe(grantID: String, account: String, slug: String, config: [String: JSONValue],
                   signedBy: @escaping @MainActor (Data) throws -> String) async throws -> String {
        struct Body: Encodable { let account: String; let slug: String; let config: [String: JSONValue] }
        struct Response: Decodable { let id: String?; let trigger: String? }
        let body = try JSONEncoder().encode(Body(account: account, slug: slug, config: config))
        let data = try await raw("POST", "grants/\(escaped(grantID))/triggers", body: body, signer: signedBy)
        let response = try KIFFClient.decoder.decode(Response.self, from: data)
        guard let id = response.id ?? response.trigger, !id.isEmpty else {
            throw PlanqueError.server(status: 502, message: Words.Setup.Knob.noSubscription)
        }
        return id
    }

    /// Stops a trigger: planque turns its delivery off, then removes it upstream.
    func unsubscribe(grantID: String, trigger: String) async throws {
        _ = try await raw("DELETE", "grants/\(escaped(grantID))/triggers/\(escaped(trigger))", body: nil)
    }

    /// Removes the grant's tools from KIFF and revokes its token at the vendor.
    func disconnect(grantID: String) async throws -> Grant {
        try await send("DELETE", "grants/\(escaped(grantID))", body: Optional<[String: String]>.none)
    }

    /// Erases the account in planque (kiff/tafs#185): every connection disconnected, its vendor
    /// sign-ins revoked, and the enrolment forgotten. Asked again once nothing is kept, planque
    /// answers 204 (kiff/planque#45), which is done too.
    func eraseAccount() async throws {
        _ = try await raw("DELETE", "", body: nil)
    }

    /// What a finished vendor sign-in hands back: `tafs://planque/signed-in?tenant&grant&confirm`.
    struct SignedIn: Equatable {
        let tenant: String
        let grant: String
        let code: String
    }

    static func signedIn(from url: URL) -> SignedIn? {
        guard url.scheme == "tafs", url.host == "planque", url.path == "/signed-in",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value.flatMap { $0.isEmpty ? nil : $0 } }
        guard let tenant = value("tenant"), let grant = value("grant"), let code = value("confirm") else { return nil }
        return SignedIn(tenant: tenant, grant: grant, code: code)
    }

    /// The device signs its own read; the cloud only forwards this proof.
    func grantsReadProof(signedBy sign: @MainActor (Data) throws -> String) async throws -> String {
        guard let tenant else { throw PlanqueError.notSigning }
        if deviceID == nil, let load = deviceLoader { deviceID = await load() }
        guard let device = deviceID else { throw PlanqueError.noDevice }
        let path = "/v1/accounts/\(escaped(tenant))/grants"
        let at = SigningTime.next()
        let bytes = Self.message(base: baseURL, tenant: tenant, method: "GET", uri: path, body: Data(), signedAt: at)
        return "t=\(at),v1=\(try sign(bytes)),d=\(device)"
    }

    // MARK: Transport

    private func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        let data = try await raw(method, path, body: try body.map { try JSONEncoder().encode($0) })
        return try KIFFClient.decoder.decode(T.self, from: data)
    }

    /// `together` is a signature already made over this request and another (`kiff.requests.v1`):
    /// its `signed_at`, the signature, and the other request's hash. Nothing here signs then.
    private func raw(_ method: String, _ path: String, body: Data?,
                     signer override: (@MainActor (Data) throws -> String)? = nil, headers: [String: String] = [:],
                     together: (signedAt: String, signature: String, with: String)? = nil) async throws -> Data {
        guard let tenant else { throw PlanqueError.notSigning }
        let signer = override ?? signer
        guard signer != nil || together != nil else { throw PlanqueError.notSigning }
        // The account itself when path is empty (erasing it), else one of its routes.
        let account = "v1/accounts/\(escaped(tenant))"
        guard let url = URL(string: path.isEmpty ? account : "\(account)/\(path)", relativeTo: baseURL) else {
            throw PlanqueError.server(status: 0, message: Words.Error.badPath)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("tafs-ios", forHTTPHeaderField: "User-Agent")
        // The person's languages, as tafs cloud gets them: planque writes the words they approve in the first it has.
        // Not part of what's signed (the method, the path and query, the body).
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        // planque asks KIFF whether this device signed (kiff/planque#12): never a key alone.
        if deviceID == nil, let load = deviceLoader { deviceID = await load() }
        guard let device = deviceID else { throw PlanqueError.noDevice }
        if let together {
            request.setValue("\(RequestsTogether.header(signedAt: together.signedAt, signature: together.signature, with: together.with)),d=\(device)",
                             forHTTPHeaderField: "Planque-Device-Signature")
        } else if let signer {
            let signedAt = SigningTime.next()
            // Signed as planque reads it: the path and query as sent, percent-encoded.
            let uri = url.path(percentEncoded: true) + (url.query(percentEncoded: true).map { "?" + $0 } ?? "")
            let message = Self.message(base: baseURL, tenant: tenant, method: method, uri: uri, body: body ?? Data(), signedAt: signedAt)
            request.setValue("t=\(signedAt),v1=\(try signer(message)),d=\(device)", forHTTPHeaderField: "Planque-Device-Signature")
        }

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? Words.Error.connectionAnswered(status: "\(status)")
            throw PlanqueError.server(status: status, message: message)
        }
        return data
    }
}
