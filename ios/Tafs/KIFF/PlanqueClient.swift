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
        /// `words`: planque's own line for the person, in their language, when it sends one ("You signed in
        /// as another account…"): shown as it is, before any line of the app's own.
        case server(status: Int, message: String, words: String? = nil)
        /// Signed in an approval window that has ended (kiff/tafs#494): nothing was done; a new
        /// window, then the same call again.
        case windowRequired
        /// Asked with no connector key, planque holds none that still stands (`409 needs_key`): the one answer
        /// on which the phone mints a key.
        case needsKey

        var errorDescription: String? {
            switch self {
            case .notSigning: return Words.Error.needsConfirmationFirst
            case .noDevice: return Words.Error.noDeviceName
            case .digestMismatch: return Words.Error.requestMismatch
            case .wordsUnread: return Words.Connect.profileWordsUnread
            // planque's own words for the person when it sends them, never a generic line over them (a 409
            // "changed" over "you signed in as another account" read as "try again"). Its reason is for the
            // code and the diagnostics; without words, the person reads a plain line by what happened.
            case .server(let status, _, let words): return words ?? PlainError.words(status: status)
            // The app's own words: planque's `error` is for the log, never the person.
            case .windowRequired: return Words.Error.approvalWindowEnded
            case .needsKey: return PlainError.words(status: 409)
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
    /// The approval window `signer` signs in (kiff/tafs#494): set only while an approval step that
    /// takes one runs, so its calls go as a tap (`v2`, with the window's id); planque asks KIFF
    /// whether it is open. Nil signs as before.
    var window: String?
    /// planque's `code` on the last refusal, or nil when it sent none: a 409 an older planque sent with no code at
    /// enrolment is another device's; today's planque names each of its own (`signature_replayed`, …).
    private(set) var lastRefusalCode: String?
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

    /// What the phone signs inside an approval window (planque's `device.MessageWindow`): `message`'s
    /// lines under `planque.request.v2`, with the window's id last. With no window, `message`.
    static func messageWindow(base: URL, tenant: String, method: String, uri: String, body: Data, signedAt: String, window: String?) -> Data {
        guard let window else { return message(base: base, tenant: tenant, method: method, uri: uri, body: body, signedAt: signedAt) }
        var b = base.absoluteString
        while b.hasSuffix("/") { b.removeLast() }
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        return Data(["planque.request.v2", b, tenant, method, uri, bodyHash, signedAt, window].joined(separator: "\n").utf8)
    }

    // MARK: Enrolment

    struct Enrolment: Decodable, Equatable {
        let tenant: String
        /// The phone key's fingerprint, as planque shows it in its record.
        let device: String
        /// The connector key planque holds still stands, so it kept it and took no new one.
        var kept: Bool?
    }

    /// Hands planque the KIFF connector key it keeps to register tools. Signed with
    /// `deviceID`, any of the owner's devices may (kiff/planque#10). The device's key goes
    /// too, for a planque from before that, which enrols one phone by its key. With no
    /// `connectorKey`, it only asks whether the enrolled key still stands: planque answers
    /// `kept`, or refuses (`needs_key`) and keeps nothing.
    func enrol(deviceKey: Data, connectorKey: String?) async throws -> Enrolment {
        var body = ["device_key": deviceKey.base64EncodedString()]
        if let connectorKey { body["connector_key"] = connectorKey }
        return try await send("POST", "enrolment", body: body)
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
            /// A browser grant's limits as planque holds them. The grant's digest covers them, so what the owner was
            /// shown is what is read here.
            struct Browser: Decodable, Equatable {
                /// `extension` for the owner's own Chrome; none for planque's hosted browser.
                var driver: String?
                /// The paired Chrome an extension grant uses.
                var chrome: String?
                /// All the sites it may reach.
                var sites: [String]?
                /// That Chrome's name as it was paired, as planque sets it.
                var chromeName: String?
                enum CodingKeys: String, CodingKey { case driver, chrome, sites, chromeName = "chrome_name" }
            }
            var browser: Browser? = nil
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
        /// The account at the vendor, as planque keeps it: what a trigger is sent back with, never
        /// a name to draw (for a managed app it can be the provider's id).
        let account: String?
        /// The connection's name for people, the same in every app: its app and, when known,
        /// its account ("Gmail · Demo Shop"). Drawn as sent.
        var title: String?
        /// The connection as the owner's list shows it, all planque's (its rows): the tafs that use it by
        /// their names, its health in plain words, and only the actions that can work for it now. The app
        /// keeps no rule of its own for these. None from a planque before them.
        var usedBy: [String]?
        var health: Health?
        var actions: [Action]?
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
        /// How planque signs it in again once signed out: `fresh`, a new sign-in that can't be compared with the old
        /// account and brings back every connection signed out on it (`connect-fresh`); else, or absent, on the same account.
        var reconnect: String? = nil
        /// Sign in again is a new sign-in (`connect-fresh`), never a reconnect planque would refuse.
        var reconnectsFresh: Bool { reconnect == "fresh" }
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

        enum CodingKeys: String, CodingKey { case id, status, statusWords = "status_words", text, terms, digest, request, account, title, usedBy = "used_by", health, actions, tools, reason, events, signedIn = "signed_in", heldUntil = "held_until", vendorName = "vendor", entry, provider, agentName = "agent_name", reconnect }

        /// The vendor, as the owner knows it: its server's host.
        /// The vendor's name, as planque gives it: what its server calls itself, else its
        /// host (kiff/planque#17). Shown as it is.
        let vendorName: String?
        var vendor: String { vendorName ?? request.server }

        /// A connection's health for people (planque's rows): `state` (`working`, `paused`, `signed_out`,
        /// `unfinished`, `waiting`, `ended`), `ok` only while it works, and `words`, its time in this phone's
        /// zone. Read leniently.
        struct Health: Decodable, Equatable {
            var state = ""
            var ok = false
            var words = ""
            enum CodingKeys: String, CodingKey { case state, ok, words }
            init(state: String = "", ok: Bool = false, words: String = "") { self.state = state; self.ok = ok; self.words = words }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                state = ((try? c.decodeIfPresent(String.self, forKey: .state)) ?? nil) ?? ""
                ok = ((try? c.decodeIfPresent(Bool.self, forKey: .ok)) ?? nil) ?? false
                words = (((try? c.decodeIfPresent(String.self, forKey: .words)) ?? nil) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }

        /// One thing the owner can do with a connection that works for it now, as planque offers it:
        /// `sign_in_again` (`approve`: a new sign-in on this same connection; `key`: a new key),
        /// `remove` (`delete`, with `stops`: what stops, for one more ask), `remove_unfinished` (`delete`).
        struct Action: Decodable, Equatable, Identifiable {
            static let signInAgain = "sign_in_again", remove = "remove", removeUnfinished = "remove_unfinished"
            var id = ""
            var words = ""
            var how = ""
            var stops: String?
            enum CodingKeys: String, CodingKey { case id, words, how, stops }
            init(id: String = "", words: String = "", how: String = "", stops: String? = nil) {
                self.id = id; self.words = words; self.how = how; self.stops = stops
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                func text(_ k: CodingKeys) -> String { (((try? c.decodeIfPresent(String.self, forKey: k)) ?? nil) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
                id = text(.id); words = text(.words); how = text(.how)
                let s = text(.stops); stops = s.isEmpty ? nil : s
            }
            /// One this app knows how to take, with its words: anything else isn't drawn.
            var known: Bool {
                guard !words.isEmpty else { return false }
                switch id {
                case Self.signInAgain: return how == "approve" || how == "key"
                case Self.remove, Self.removeUnfinished: return how == "delete"
                default: return false
                }
            }
            var removes: Bool { id == Self.remove || id == Self.removeUnfinished }
        }

        /// planque draws this connection's row (its health and actions): the list shows it as sent, and
        /// a tap opens its detail, where its actions are. Without them, the row is the app's as before.
        var rowFromPlanque: Bool { !(health?.words.isEmpty ?? true) && actions != nil }
        /// What a connection's row is called: planque's `title`, else the account it names, else
        /// nil (the row then says the app). Never the provider's id for a managed app, which planque
        /// sends as `account` only to be sent back.
        var name: String? {
            if let t = title?.trimmingCharacters(in: .whitespaces), !t.isEmpty { return t }
            if request.connector == nil, let a = account?.trimmingCharacters(in: .whitespaces), !a.isEmpty { return a }
            return nil
        }
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
        /// The signed-out connection in planque's words, which name the cause; before planque sends them, the app's
        /// plain "Signed out · sign in again", true whoever signed it out (not "by {vendor}": a sibling's removal does too).
        var signedOutWords: String {
            statusWords.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? Words.Connect.Status.signedOutPlain
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
        // Each grant with planque's entry at its own position, never looked up by id: two entries with one id
        // (one request, then another carrying the first's digest) would mark the second as matching from the
        // first's bytes, and approving it would sign what wasn't shown. An id that repeats, or a list of
        // another length, pairs nothing: no grant matches, and nothing is signed.
        let ids = grants.map(\.id)
        let paired = raws.count == grants.count && Set(ids).count == ids.count
        for i in grants.indices {
            guard paired, raws[i]["id"]?.string == grants[i].id, let request = raws[i]["request"] else { continue }
            let raw = raws[i]
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
        /// With `signed_in` and no sign-in link: planque holds a fresh sign-in and waits for this
        /// phone to confirm it with this one-use code, as a browser's code is; unconfirmed, planque
        /// revokes it at `confirm_by`.
        var confirmCode: String? = nil
        enum CodingKeys: String, CodingKey { case status, signInURL = "sign_in_url", expiresAt = "expires_at", confirmCode = "confirm" }
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
        /// For a grant that uses the owner's own Chrome: this phone's signature over its sites (`tafs-chrome/sites/v1`),
        /// raw r‖s in base64url. planque approves such a grant only with it. Added by the phone, never by tafs cloud.
        var chromeSitesSig: String? = nil
        enum CodingKeys: String, CodingKey { case digest, kiffProfile = "kiff_profile", chromeSitesSig = "chrome_sites_sig" }
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
        return PreparedKey(path: path, body: body, key: key, signedAt: signedAt,
                           message: try preparedMessage(tenant: tenant, path: path, body: body, signedAt: signedAt))
    }

    /// The `planque.request.v1` message of a POST to `path`, as planque rebuilds it: the path as
    /// sent, percent-encoded, over the exact body.
    private func preparedMessage(tenant: String, path: String, body: Data, signedAt: String) throws -> Data {
        guard let url = URL(string: "v1/accounts/\(escaped(tenant))/\(path)", relativeTo: baseURL) else {
            throw PlanqueError.server(status: 0, message: Words.Error.badPath)
        }
        let uri = url.path(percentEncoded: true) + (url.query(percentEncoded: true).map { "?" + $0 } ?? "")
        return Self.message(base: baseURL, tenant: tenant, method: "POST", uri: uri, body: body, signedAt: signedAt)
    }

    /// An approve built in full before it's signed, so its message can be signed together with
    /// another request's (`RequestsTogether`): its body's exact bytes, its `signed_at`, and the
    /// message planque rebuilds from them.
    struct PreparedApprove: Equatable {
        let path: String
        let body: Data
        let signedAt: String
        let message: Data
    }

    /// `approve`, built to be signed together with another request: nothing is sent yet.
    func prepareApprove(grantID: String, body: ApproveBody, signedAt: String) throws -> PreparedApprove {
        guard let tenant else { throw PlanqueError.notSigning }
        let bytes = try JSONEncoder().encode(body)
        let path = "grants/\(escaped(grantID))/approve"
        return PreparedApprove(path: path, body: bytes, signedAt: signedAt,
                               message: try preparedMessage(tenant: tenant, path: path, body: bytes, signedAt: signedAt))
    }

    /// Sends a prepared approve under a signature made over it and another request
    /// (`kiff.requests.v1`): `with` is the other request's hash.
    func approve(_ prepared: PreparedApprove, signature: String, with other: String) async throws -> Approved {
        let data = try await raw("POST", prepared.path, body: prepared.body,
                                 together: (signedAt: prepared.signedAt, signature: signature, with: other))
        return try KIFFClient.decoder.decode(Approved.self, from: data)
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

    /// A new sign-in for a signed-out connection planque can't sign in again on its old account (`reconnect: fresh`):
    /// the first step in place of approve, with the same body, signer and answer. Its confirm brings back every
    /// connection signed out on that account (`grants`).
    func connectFresh(_ grant: Grant, words: ProfileWords? = nil) async throws -> Approved {
        try await send("POST", "grants/\(escaped(grant.id))/connect-fresh", body: try Self.approveBody(grant, words: words))
    }

    /// Approves `grantID` with the body tafs cloud built for it (kiff/tafs#413): sent as it is,
    /// signed by this device. The phone checks nothing of it; tafs cloud read the grant and decided.
    func approve(grantID: String, body: ApproveBody) async throws -> Approved {
        try await send("POST", "grants/\(escaped(grantID))/approve", body: body)
    }

    /// Confirms the vendor sign-in with the code it handed back to the app. Only the phone
    /// that approved can, within planque's sign-in window (60 minutes); then planque registers the tools in KIFF.
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

    /// planque's refusal: its reason (`error`, for the code) and its line for the person (`words`), each
    /// only when it's a line of text. Read leniently: a body of another shape says neither.
    nonisolated static func refusal(_ data: Data) -> (error: String?, words: String?) {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (nil, nil) }
        func line(_ key: String) -> String? {
            guard let s = (object[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
            return s
        }
        return (line("error"), line("words"))
    }

    /// planque's code for a connect that ended failed (planque#329).
    nonisolated static let connectFailed = "connect_failed"

    /// planque's machine-readable `code` on a refusal, when it sends one as text.
    nonisolated static func refusalCode(_ data: Data) -> String? {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["code"] as? String
    }

    // MARK: Chrome (docs/chrome.md, "Pairing")

    /// A Chrome paired to the account, as planque lists it: the name the owner gave it, and the browser and system its
    /// extension reported.
    struct Chrome: Decodable, Equatable, Identifiable {
        let id: String
        let name: String
        var browser: String?
        var os: String?
        var pairedAt: Date?
        enum CodingKeys: String, CodingKey { case id = "chrome_id", name, browser, os, pairedAt = "paired_at" }
    }

    /// A pairing this phone opened: its id, and the four characters of the code planque looks it up by.
    struct ChromePairingOpened: Decodable, Equatable {
        let pairing: String
        let lookup: String
    }

    /// What an extension sent for a pairing: its key (SPKI DER, base64url), what it says it is, and its proof under the
    /// code's secret.
    struct ChromeOffer: Equatable {
        let key: String
        var version = ""
        var browser = ""
        var os = ""
        let proof: String
        /// The extension's KIFF device offer (`kiff.pair.v1`, view-only), as its text: the proof covers it.
        var kiffOffer = ""
    }

    /// A pairing as planque has it: `waiting_for_chrome`, then `waiting_for_phone` once an extension sent its key (the
    /// offer), then `confirmed`. An expired one is 404.
    struct ChromePairingState: Decodable, Equatable {
        let status: String
        var offer: ChromeOffer?
        enum CodingKeys: String, CodingKey { case status, key, proof, version, browser, os, kiffOffer = "kiff_offer" }
        init(status: String, offer: ChromeOffer? = nil) { self.status = status; self.offer = offer }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            status = try c.decode(String.self, forKey: .status)
            if let key = try c.decodeIfPresent(String.self, forKey: .key), let proof = try c.decodeIfPresent(String.self, forKey: .proof) {
                offer = ChromeOffer(key: key, version: (try? c.decodeIfPresent(String.self, forKey: .version)) ?? "",
                                    browser: (try? c.decodeIfPresent(String.self, forKey: .browser)) ?? "",
                                    os: (try? c.decodeIfPresent(String.self, forKey: .os)) ?? "", proof: proof,
                                    kiffOffer: (try? c.decodeIfPresent(String.self, forKey: .kiffOffer)) ?? "")
            }
        }
    }

    /// The Chromes paired to the account. planque answers 404 while it doesn't offer them, and the app then shows nothing.
    func chromes() async throws -> [Chrome] {
        struct List: Decodable { let chromes: [Chrome] }
        let list: List = try await send("GET", "chromes", body: Optional<[String: String]>.none)
        return list.chromes
    }

    /// The picture a Chrome kept just before a taf's step acted (docs/chrome.md, "Screenshots of a run's acts"): the step's id
    /// at the Chrome, when it was taken, and the WebP bytes, whose hash is checked against planque's before they're shown.
    struct ChromeScreenshot: Equatable {
        let step: String
        let takenAt: Date
        let image: Data

        /// The most a picture may weigh: planque keeps none over 120 KB.
        static let largest = 120 * 1024

        /// planque's answer for `step`, or nil when it isn't a picture to show: not the shape, not the step asked for (a misrouted
        /// answer is never shown under another step), not base64url, too big, not WebP, or bytes whose SHA-256 isn't the one
        /// planque kept.
        static func read(_ data: Data, step asked: String) -> ChromeScreenshot? {
            struct Raw: Decodable { let step: String; let taken_at: String; let sha256: String; let image: String }
            guard let raw = try? JSONDecoder().decode(Raw.self, from: data), raw.step == asked,
                  let bytes = ChromePairing.base64URLDecoded(raw.image), !bytes.isEmpty, bytes.count <= largest,
                  bytes.count >= 12, bytes.prefix(4) == Data("RIFF".utf8), bytes[8..<12] == Data("WEBP".utf8),
                  SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == raw.sha256.lowercased(),
                  let at = ISO8601DateFormatter().date(from: raw.taken_at) else { return nil }
            return ChromeScreenshot(step: raw.step, takenAt: at, image: bytes)
        }
    }

    /// The picture kept for a Chrome step, signed by this device as the Chromes list is. nil when none is kept: planque
    /// answers 404 for a step with none, one past its 30 days, and while it keeps none. A picture that doesn't check out
    /// is an error, never shown.
    func chromeScreenshot(step: String) async throws -> ChromeScreenshot? {
        do {
            let data = try await raw("GET", "chromes/screenshots/\(escaped(step))", body: nil)
            guard let shot = ChromeScreenshot.read(data, step: step) else { throw PlanqueError.server(status: 0, message: "bad_picture") }
            return shot
        } catch PlanqueError.server(let status, _, _) where status == 404 {
            return nil
        }
    }

    /// One picture kept for an operation: the step it came before and when it was taken. The bytes come from `chromeScreenshot(step:)`.
    struct ChromeShotRef: Equatable, Identifiable {
        let step: String
        let takenAt: Date
        var id: String { step }

        /// planque's list as it answers (`{"shots":[{"step","taken_at","sha256"}]}`, oldest first): nil when it isn't that
        /// shape; an entry without a step or a readable time is left out; the order is kept.
        static func readList(_ data: Data) -> [ChromeShotRef]? {
            struct Raw: Decodable { let step: String?; let taken_at: String? }
            struct List: Decodable { let shots: [Raw] }
            guard let list = try? JSONDecoder().decode(List.self, from: data) else { return nil }
            return list.shots.compactMap { shot in
                guard let step = shot.step, !step.isEmpty, let at = shot.taken_at.flatMap({ ISO8601DateFormatter().date(from: $0) }) else { return nil }
                return ChromeShotRef(step: step, takenAt: at)
            }
        }
    }

    /// The pictures kept for the Chrome steps of one operation (a taf's Activity row's `proposal_id`), oldest first, at most the
    /// newest 20, none past their 30 days; no bytes. `[]` when none are kept, or planque keeps none. Signed like the single read.
    func chromeShots(op: String) async throws -> [ChromeShotRef] {
        let query = op.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+#"))) ?? op
        let data = try await raw("GET", "chromes/screenshots?op=\(query)", body: nil)
        guard let shots = ChromeShotRef.readList(data) else { throw PlanqueError.server(status: 0, message: "bad_list") }
        return shots
    }

    func openChromePairing() async throws -> ChromePairingOpened {
        try await send("POST", "chromes/pairings", body: [String: String]())
    }

    func chromePairing(_ id: String) async throws -> ChromePairingState {
        try await send("GET", "chromes/pairings/\(escaped(id))", body: Optional<[String: String]>.none)
    }

    func confirmChromePairing(_ id: String, _ confirmation: ChromePairing.Confirmation) async throws {
        _ = try await raw("POST", "chromes/pairings/\(escaped(id))/confirm", body: try JSONEncoder().encode(confirmation))
    }

    func removeChrome(_ id: String) async throws {
        _ = try await raw("DELETE", "chromes/\(escaped(id))", body: nil)
    }

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
        request.setValue(KIFFClient.acceptLanguageHeader, forHTTPHeaderField: "Accept-Language")
        // This phone's zone, for the times in planque's words ("last used 00:32"); not part of what's signed either.
        request.setValue(TimeZone.current.identifier, forHTTPHeaderField: "X-Time-Zone")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        // planque asks KIFF whether this device signed (kiff/planque#12): never a key alone.
        if deviceID == nil, let load = deviceLoader { deviceID = await load() }
        guard let device = deviceID else { throw PlanqueError.noDevice }
        // Inside an approval window, the device key signs with the window's id: only the calls of a step that takes one.
        let held = override == nil && together == nil ? window : nil
        if let together {
            request.setValue("\(RequestsTogether.header(signedAt: together.signedAt, signature: together.signature, with: together.with)),d=\(device)",
                             forHTTPHeaderField: "Planque-Device-Signature")
        } else if let signer {
            let signedAt = SigningTime.next()
            // Signed as planque reads it: the path and query as sent, percent-encoded.
            let uri = url.path(percentEncoded: true) + (url.query(percentEncoded: true).map { "?" + $0 } ?? "")
            let message = Self.messageWindow(base: baseURL, tenant: tenant, method: method, uri: uri, body: body ?? Data(), signedAt: signedAt, window: held)
            let signature = try signer(message)
            request.setValue(held.map { "t=\(signedAt),v2=\(signature),w=\($0),d=\(device)" } ?? "t=\(signedAt),v1=\(signature),d=\(device)",
                             forHTTPHeaderField: "Planque-Device-Signature")
        }

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // Signed in a window that has ended: forgotten, and said in the app's own words.
            if status == 403, let held, Self.refusalCode(data) == KIFFClient.windowRequired {
                if window == held { window = nil }
                throw PlanqueError.windowRequired
            }
            lastRefusalCode = Self.refusalCode(data)
            if status == 409, lastRefusalCode == "needs_key" { throw PlanqueError.needsKey }
            let said = Self.refusal(data)
            // A connect that ended failed on planque's side (planque#329, `409 connect_failed`): its `error` is the reason for
            // Coach (a tool the vendor no longer lists, an argument's type), so the person reads that it isn't working on our
            // side, never its 409's "It changed in the meantime". planque's own words for the person, when sent, come first.
            // The status stays 409: sent again unchanged, it would fail the same way. As Android's `ErrorWords.CONNECT_FAILED`.
            let words = said.words ?? (status == 409 && lastRefusalCode == Self.connectFailed ? Words.Error.down : nil)
            throw PlanqueError.server(status: status, message: said.error ?? Words.Error.connectionAnswered(status: "\(status)"), words: words)
        }
        return data
    }
}
