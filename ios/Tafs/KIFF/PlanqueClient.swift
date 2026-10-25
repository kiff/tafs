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
        case server(status: Int, message: String)

        var errorDescription: String? {
            switch self {
            case .notSigning: return "This step needs your confirmation first."
            case .noDevice: return "Couldn't reach KIFF to name this device. Check the connection and try again."
            case .digestMismatch: return "This request doesn't match what it asks for. Nothing was approved."
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
            let server: String
            let agent: String
            let tools: [Tool]
        }
        struct Event: Decodable, Equatable {
            /// RFC 3339, as planque writes it (Go's nanoseconds included).
            let at: String
            /// In the owner's words, as planque writes it: the device's name, never an id
            /// (kiff/planque#16). Shown as it is.
            let what: String
        }
        let id: String
        /// pending, approved, signed_in, listed, connected, failed or disconnected.
        let status: String
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
        /// Whether `digest` is the hash of the request as received: the app shows Approve
        /// only then. Set by `grants()`, never read from planque.
        var digestMatches = false

        enum CodingKeys: String, CodingKey { case id, status, text, terms, digest, request, account, tools, reason, events, signedIn = "signed_in", heldUntil = "held_until", vendorName = "vendor" }

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

    /// Every grant for the account, each with its digest checked against its request.
    /// `readSigner` signs this read alone, leaving `signer` as it is: a refresh that runs
    /// while a Face ID step waits on the vendor's page must not end that step's signer.
    func grants(signedBy readSigner: (@MainActor (Data) throws -> String)? = nil) async throws -> [Grant] {
        struct Response: Decodable { let grants: [Grant] }
        let data = try await raw("GET", "grants", body: nil, signer: readSigner)
        var grants = try KIFFClient.decoder.decode(Response.self, from: data).grants
        // The request as planque sent it, numbers as written, so the digest is planque's.
        let raws = (try? JCS.parse(data))?["grants"]?.array ?? []
        for i in grants.indices {
            guard let raw = raws.first(where: { $0["id"]?.string == grants[i].id }), let request = raw["request"],
                  let canonical = try? JCS.canonical(request) else { continue }
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

    /// Approves `grant` as shown: its digest is signed. Refused here when the digest isn't
    /// the hash of the request the owner was shown.
    func approve(_ grant: Grant) async throws -> Approved {
        guard grant.digestMatches else { throw PlanqueError.digestMismatch }
        return try await send("POST", "grants/\(escaped(grant.id))/approve", body: ["digest": grant.digest])
    }

    /// Confirms the vendor sign-in with the code it handed back to the app. Only the phone
    /// that approved can, within 15 minutes; then planque registers the tools in KIFF.
    func confirm(grantID: String, code: String) async throws -> Grant {
        try await send("POST", "grants/\(escaped(grantID))/confirm", body: ["confirm": code])
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

    // MARK: Transport

    private func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        let data = try await raw(method, path, body: try body.map { try JSONEncoder().encode($0) })
        return try KIFFClient.decoder.decode(T.self, from: data)
    }

    private func raw(_ method: String, _ path: String, body: Data?,
                     signer override: (@MainActor (Data) throws -> String)? = nil) async throws -> Data {
        guard let tenant, let signer = override ?? signer else { throw PlanqueError.notSigning }
        // The account itself when path is empty (erasing it), else one of its routes.
        let account = "v1/accounts/\(escaped(tenant))"
        guard let url = URL(string: path.isEmpty ? account : "\(account)/\(path)", relativeTo: baseURL) else {
            throw PlanqueError.server(status: 0, message: "Bad path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("tafs-ios", forHTTPHeaderField: "User-Agent")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let signedAt = SigningTime.next()
        // Signed as planque reads it: the path and query as sent, percent-encoded.
        let uri: String
        if #available(macOS 13, iOS 16, *) {
            uri = url.path(percentEncoded: true) + (url.query(percentEncoded: true).map { "?" + $0 } ?? "")
        } else {
            // macOS 12, the Mac app's oldest (kiff/tafs#85): the same, read through URLComponents.
            let parts = URLComponents(url: url, resolvingAgainstBaseURL: true)
            uri = (parts?.percentEncodedPath ?? "") + (parts?.percentEncodedQuery.map { "?" + $0 } ?? "")
        }
        let message = Self.message(base: baseURL, tenant: tenant, method: method, uri: uri, body: body ?? Data(), signedAt: signedAt)
        // planque asks KIFF whether this device signed (kiff/planque#12): never a key alone.
        if deviceID == nil, let load = deviceLoader { deviceID = await load() }
        guard let device = deviceID else { throw PlanqueError.noDevice }
        request.setValue("t=\(signedAt),v1=\(try signer(message)),d=\(device)", forHTTPHeaderField: "Planque-Device-Signature")

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "The connection answered \(status)"
            throw PlanqueError.server(status: status, message: message)
        }
        return data
    }
}
