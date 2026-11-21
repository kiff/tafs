import CryptoKit
import LocalAuthentication
import XCTest
@testable import Tafs

/// Counts Face ID prompts apart from the signatures made without asking again.
@MainActor
final class FaceIDSigner: ApprovalSigning {
    var prompts: [String] = []
    var quiet: [String] = []
    var refuse = false
    /// The prompt's own error, such as `LAError(.userCancel)` for a prompt the person closed.
    var throwing: Error?

    var publicKeyDER: Data { Data([0x30, 0x59, 0x01]) }

    func sign(_ message: Data, reason: String) async throws -> String {
        if let throwing { throw throwing }
        if refuse { throw ApprovalSigner.SignerError.notAuthenticated }
        prompts.append(String(decoding: message, as: UTF8.self))
        return "signature"
    }

    func signConfirmed(_ message: Data) throws -> String {
        quiet.append(String(decoding: message, as: UTF8.self))
        return "signature"
    }
}

/// The vendor's sign-in page, played by the test: it answers with `callback`.
@MainActor
final class StubVendor: VendorSigningIn {
    var opened: [URL] = []
    var callback: URL?
    var cancel = false
    /// What happens while the owner is on the vendor's page, such as the app's refresh
    /// when it comes back to the front.
    var meanwhile: (@MainActor () async -> Void)?

    func signIn(at url: URL) async throws -> URL {
        opened.append(url)
        await meanwhile?()
        if cancel { throw VendorSignIn.SignInError.cancelled }
        return try XCTUnwrap(callback)
    }
}

/// planque's phone API (contract 2 on kiff/planque#1), and connecting a taf's tools
/// through it (#51). The vectors were made with planque's own Go code.
@MainActor
final class PlanqueTests: XCTestCase {
    // MARK: What planque checks

    /// A paused connection says planque's words for it, in the person's language, and never the pause's English `why`, which
    /// carries the vendor and the raw tool name and is for the log and Coach: the app has no field for it, so whatever
    /// planque puts there can't reach a screen (the paused-grant leak, before wave 1a).
    func testAPausedGrantsWhyNeverReachesThePerson() throws {
        let why = "Google Sheets changed GOOGLESHEETS_BATCH_UPDATE since you approved it"
        let json = Self.grantJSONFor(status: "paused")
            .replacingOccurrences(of: #""status":"paused","#,
                                  with: #""status":"paused","status_words":"Pausiert · Werkzeug geändert · erneut verbinden, um es zu genehmigen","paused":{"kind":"changed","why":"\#(why)"},"why":"\#(why)","#)
        let grant = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
        XCTAssertEqual(grant.statusWords, "Pausiert · Werkzeug geändert · erneut verbinden, um es zu genehmigen")
        XCTAssertEqual(grant.signedOutWords, grant.statusWords, "the status line is planque's words")
        // Nothing the app reads from the grant carries a word of the English why.
        for shown in [grant.statusWords, grant.signedOutWords, grant.reason, grant.health?.words].compactMap({ $0 }) {
            for word in ["changed", "GOOGLESHEETS", "approved", "Google Sheets"] {
                XCTAssertFalse(shown.contains(word), "\(word) in \(shown)")
            }
        }
    }

    /// planque's device.Message for the same inputs, byte for byte.
    func testMessageIsPlanques() {
        let base = URL(string: "https://planque.kiff.dev/")!
        let approve = PlanqueClient.message(base: base, tenant: "t_01", method: "POST", uri: "/v1/accounts/t_01/grants/g_01/approve",
                                            body: Data(#"{"digest":"abc"}"#.utf8), signedAt: "2026-10-17T10:00:00Z")
        XCTAssertEqual(String(decoding: approve, as: UTF8.self),
                       "planque.request.v1\nhttps://planque.kiff.dev\nt_01\nPOST\n/v1/accounts/t_01/grants/g_01/approve\n"
                       + "7920738f0c11207c12d8c7a665ce2da4a76564241191aebe12cfeb5ea9756972\n2026-10-17T10:00:00Z")
        let read = PlanqueClient.message(base: base, tenant: "t_01", method: "GET", uri: "/v1/accounts/t_01/grants",
                                         body: Data(), signedAt: "2026-10-17T10:00:00Z")
        XCTAssertEqual(String(decoding: read, as: UTF8.self),
                       "planque.request.v1\nhttps://planque.kiff.dev\nt_01\nGET\n/v1/accounts/t_01/grants\n"
                       + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n2026-10-17T10:00:00Z")
    }

    /// planque's jcs tests (RFC 8785 §3.2.2, UTF-16 key order, nesting).
    func testCanonicalIsPlanques() throws {
        let cases: [(String, String)] = [
            (#"{"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],"string":"\u20ac$\u000F\u000aA'\u0042\u0022\u005c\\\"\/","literals":[null,true,false]}"#,
             #"{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\u000f\nA'B\"\\\\\"/"}"#),
            (#"{"\ud83d\ude00":1,"\ufb33":2,"a":3}"#, #"{"a":3,"😀":1,"דּ":2}"#),
            (#"{"b":{"z":1,"a":[1,2]},"a":"x"}"#, #"{"a":"x","b":{"a":[1,2],"z":1}}"#),
        ]
        for (input, want) in cases {
            XCTAssertEqual(String(decoding: try JCS.canonical(Data(input.utf8)), as: UTF8.self), want, input)
        }
    }

    func testNumbersPrintAsECMAScript() throws {
        let cases: [(Double, String)] = [
            (0, "0"), (1, "1"), (-1, "-1"), (100, "100"), (1e21, "1e+21"), (1e20, "100000000000000000000"),
            (0.000001, "0.000001"), (0.0000001, "1e-7"), (123.456, "123.456"), (9007199254740991, "9007199254740991"),
            (5e-324, "5e-324"), (Double.greatestFiniteMagnitude, "1.7976931348623157e+308"), (-0.0, "0"), (1.5, "1.5"),
        ]
        for (d, want) in cases { XCTAssertEqual(try JCS.ecmaScript(d), want, "\(d)") }
    }

    /// The same refusals as planque's and KIFF's.
    func testUnsafeIntegersAreRefused() throws {
        for input in [#"{"id":9007199254740992}"#, #"{"id":9007199254740993}"#, #"{"a":[{"id":-9007199254740993}]}"#] {
            XCTAssertThrowsError(try JCS.canonical(Data(input.utf8)), input)
        }
        XCTAssertNoThrow(try JCS.canonical(Data(#"{"id":9007199254740991,"f":1e300}"#.utf8)))
    }

    /// A grant request, and its digest as planque computes it.
    static let request = #"{"v":"planque.grant.v1","grant":"g_01","tenant":"t_01","server":"https://mcp.stripe.com","sign_in_at":"https://access.stripe.com","agent":"refunds","tools":[{"tool":"create_refund","name":"refund_payment","amount_argument":"amount","amount_unit":"EUR","amount_scale":2,"fixed_arguments":{"reason":"requested_by_customer","metadata.n":1.50,"big":1e30}},{"tool":"list_payments","read_only":true}],"asked_by":"setup-agent","created_at":"2026-10-17T10:00:00Z","note":"é😀\u000f"}"#
    static let digest = "6bc63ba1c1235801b7ddcb66a2e66947faefdd819e0ac0bb669062a063c048a3"

    func testDigestIsPlanques() throws {
        let canonical = try JCS.canonical(Data(Self.request.utf8))
        XCTAssertEqual(String(decoding: canonical, as: UTF8.self),
                       #"{"agent":"refunds","asked_by":"setup-agent","created_at":"2026-10-17T10:00:00Z","grant":"g_01","note":"é😀\u000f","server":"https://mcp.stripe.com","sign_in_at":"https://access.stripe.com","tenant":"t_01","tools":[{"amount_argument":"amount","amount_scale":2,"amount_unit":"EUR","fixed_arguments":{"big":1e+30,"metadata.n":1.5,"reason":"requested_by_customer"},"name":"refund_payment","tool":"create_refund"},{"read_only":true,"tool":"list_payments"}],"v":"planque.grant.v1"}"#)
        XCTAssertEqual(SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined(), Self.digest)
    }

    /// The signed text in the person's language (v2): with `digest_v: 2`, the digest covers
    /// `{"lang", "request", "text"}`, so the words shown and their language are what's signed. The
    /// expected digest here was computed apart from this app (sorted-key JSON of strings, ints and
    /// booleans, where JCS gives the same bytes).
    static let v2Request = #"{"v":"planque.grant.v1","grant":"g_02","tenant":"t_01","server":"https://mcp.qonto.com/mcp","agent":"books","tools":[{"tool":"list_invoices","read_only":true}],"asked_by":"setup-agent","created_at":"2026-11-11T16:00:00Z"}"#
    static let v2Text = "books pourra lire vos factures sur mcp.qonto.com"
    static let v2Digest = "f1ca3ab371ecf97a82d134204e4931ef39ea474b7cd6a3e233b36f97499fbb32"

    static func v2Grant(digest: String = PlanqueTests.v2Digest, lang: String? = "fr", text: String = PlanqueTests.v2Text, version: String = "2") -> String {
        #"{"id":"g_02","status":"pending","text":"\#(text)",\#(lang.map { #""lang":"\#($0)","# } ?? "")"digest_v":\#(version),"digest":"\#(digest)","request":\#(v2Request),"audience":"https://planque.kiff.dev/mcp/t_01/g_02","vendor":"Qonto","events":[]}"#
    }

    func testTheV2DigestCoversTheWordsAndTheirLanguage() throws {
        let covered = try XCTUnwrap(PlanqueClient.digestCovers(JCS.parse(Data(Self.v2Grant().utf8))))
        XCTAssertEqual(String(decoding: try JCS.canonical(covered), as: UTF8.self),
                       #"{"lang":"fr","request":{"agent":"books","asked_by":"setup-agent","created_at":"2026-11-11T16:00:00Z","grant":"g_02","server":"https://mcp.qonto.com/mcp","tenant":"t_01","tools":[{"read_only":true,"tool":"list_invoices"}],"v":"planque.grant.v1"},"text":"books pourra lire vos factures sur mcp.qonto.com"}"#)
        // Without digest_v, the request alone, as before.
        let v1 = try XCTUnwrap(PlanqueClient.digestCovers(JCS.parse(Data(#"{"id":"g_01","request":\#(Self.request)}"#.utf8))))
        XCTAssertEqual(v1, try JCS.parse(Data(Self.request.utf8)))
        // A version this app doesn't know, or v2 without its language: nothing is covered, so nothing matches.
        XCTAssertNil(PlanqueClient.digestCovers(try JCS.parse(Data(Self.v2Grant(version: "3").utf8))))
        XCTAssertNil(PlanqueClient.digestCovers(try JCS.parse(Data(Self.v2Grant(lang: nil).utf8))))
    }

    /// Each grant is checked against planque's entry at its own position, never one found by id: two entries
    /// with one id, the second a different request carrying the first's digest, would otherwise show the second
    /// as matching, and approving it would sign what wasn't shown. A repeated id matches nothing.
    func testARepeatedIdMatchesNothingAndEachGrantIsCheckedAtItsOwnPosition() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        let first = Self.grantJSONFor("g_01")
        let other = Self.grantJSONFor("g_01").replacingOccurrences(of: #""agent":"refunds""#, with: #""agent":"payouts""#)
        XCTAssertNotEqual(first, other, "another request, under the first's id and digest")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(first),\#(other)]}"#)
        let repeated = try await planque.grants()
        XCTAssertEqual(repeated.map(\.digestMatches), [false, false], "a repeated id: nothing matches, nothing is signed")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(first),\#(other.replacingOccurrences(of: #""id":"g_01""#, with: #""id":"g_02""#))]}"#)
        let distinct = try await planque.grants()
        XCTAssertEqual(distinct.map(\.id), ["g_01", "g_02"])
        XCTAssertEqual(distinct.map(\.digestMatches), [true, false], "each against its own bytes: the second request isn't the first's digest")
    }

    /// A v2 grant matches only with the words and language it was signed over; a v1 grant beside it
    /// still matches as before, so a grant issued before the switch keeps working.
    func testAV2GrantWhoseWordsOrLanguageChangedIsRefused() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        func listed(_ v2: String) -> String { #"{"grants":[\#(grantJSON()),\#(v2)]}"# }

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, listed(Self.v2Grant()))
        let both = try await planque.grants()
        XCTAssertEqual(both.map(\.digestMatches), [true, true], "v1 and v2 side by side")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, listed(Self.v2Grant(text: "books pourra lire et payer vos factures sur mcp.qonto.com")))
        let reworded = try await planque.grants()
        XCTAssertEqual(reworded.map(\.digestMatches), [true, false], "one changed word")
        do { _ = try await planque.approve(reworded[1]); XCTFail("approved words that weren't signed") } catch {
            XCTAssertEqual(error as? PlanqueClient.PlanqueError, .digestMismatch)
        }

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, listed(Self.v2Grant(lang: "en")))
        let relabelled = try await planque.grants()
        XCTAssertEqual(relabelled.map(\.digestMatches), [true, false], "another language code")

        // The digest_v marker with a v1 digest (planque's step 1 never sends that): refused, never guessed.
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, listed(Self.v2Grant(digest: "adcf390e1971e767d02d3ca528a4156047bf6de4648b720f91b75b07bd6050b6")))
        let marked = try await planque.grants()
        XCTAssertEqual(marked.map(\.digestMatches), [true, false])
    }

    /// planque writes the words the person approves in their language: every request says which.
    func testPlanqueIsToldThePersonsLanguages() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        _ = try await planque.grants()
        let header = try XCTUnwrap(sent("GET", "planque.test", "/v1/accounts/t_01/grants").last?.value(forHTTPHeaderField: "Accept-Language"))
        XCTAssertEqual(header, KIFFClient.acceptLanguage(KIFFClient.screenLanguages))
    }

    func testSignedInCallback() {
        XCTAssertEqual(PlanqueClient.signedIn(from: URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")!),
                       PlanqueClient.SignedIn(tenant: "t_01", grant: "g_01", code: "c0de"))
        for bad in ["tafs://planque/signed-in?tenant=t_01&grant=g_01", "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=",
                    "tafs://kiff/signed-in?tenant=t_01&grant=g_01&confirm=c", "https://planque/signed-in?tenant=t_01&grant=g_01&confirm=c"] {
            XCTAssertNil(PlanqueClient.signedIn(from: URL(string: bad)!), bad)
        }
    }

    // MARK: The client

    private func grantJSON(_ id: String = "g_01", status: String = "pending", digest: String = PlanqueTests.digest) -> String {
        Self.grantJSONFor(id, status: status, digest: digest)
    }

    /// A grant as planque lists it, over the request whose digest is `PlanqueTests.digest`.
    static func grantJSONFor(_ id: String = "g_01", status: String = "pending", digest: String = PlanqueTests.digest, signedIn: Bool = false, reason: String? = nil, statusWords: String? = nil) -> String {
        #"{"id":"\#(id)","status":"\#(status)",\#(signedIn ? #""signed_in":true,"# : "")\#(reason.map { #""reason":"\#($0)","# } ?? "")\#(statusWords.map { #""status_words":"\#($0)","# } ?? "")"text":"refunds may refund payments at mcp.stripe.com","digest":"\#(digest)","request":\#(Self.request),"audience":"https://planque.kiff.dev/mcp/t_01/\#(id)","vendor":"Stripe","events":[{"at":"2026-10-17T10:00:00.123456789Z","what":"asked by setup-agent"}]}"#
    }

    private func clients() -> (KIFFClient, PlanqueClient, URLSession) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        return (kiff, PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session), session)
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    private func sent(_ method: String, _ host: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.host == host && $0.url?.path == path }
    }

    func testEveryCallIsSignedAndTheDigestIsChecked() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        do { _ = try await planque.grants(); XCTFail("read without a signer") } catch {
            XCTAssertEqual(error as? PlanqueClient.PlanqueError, .notSigning)
        }
        var signed: [String] = []
        planque.signer = { signed.append(String(decoding: $0, as: UTF8.self)); return "sig" }
        planque.deviceID = "dev_phone01"
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON()),\#(grantJSON("g_02", digest: String(repeating: "0", count: 64)))]}"#)

        let grants = try await planque.grants()
        XCTAssertEqual(grants.map(\.digestMatches), [true, false])
        XCTAssertEqual(grants[0].vendor, "Stripe")
        XCTAssertEqual(grants[0].request.tools.map(\.action), ["refund_payment", "list_payments"])
        XCTAssertEqual(grants[0].events.first?.what, "asked by setup-agent")
        let header = try XCTUnwrap(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"))
        XCTAssertTrue(header.hasPrefix("t=") && header.hasSuffix(",v1=sig,d=dev_phone01"), header)
        XCTAssertTrue(signed.last?.hasPrefix("planque.request.v1\nhttps://planque.test\nt_01\nGET\n/v1/accounts/t_01/grants\n") ?? false)

        StubProtocol.sent = []
        do { _ = try await planque.approve(grants[1]); XCTFail("approved a grant whose digest doesn't match") } catch {
            XCTAssertEqual(error as? PlanqueClient.PlanqueError, .digestMismatch)
        }
        XCTAssertTrue(StubProtocol.sent.isEmpty, "nothing is sent for it")

        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00.5Z"}"#)
        let approved = try await planque.approve(grants[0])
        XCTAssertEqual(approved.signInURL?.host, "access.stripe.com")
        XCTAssertEqual(body(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").last)["digest"] as? String, Self.digest)

        // Tools set on a held sign-in (contract 5): planque connects at once, with no link.
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, grantJSON(status: "connected"))
        let connected = try await planque.approve(grants[0])
        XCTAssertEqual(connected.status, "connected")
        XCTAssertNil(connected.signInURL)
    }

    /// A held sign-in's end, as Go writes it (nanoseconds), reads as a date; without one, none.
    func testHeldUntilReadsGoTime() throws {
        let held = #"{"id":"g_01","status":"listed","signed_in":true,"held_until":"2026-10-18T17:05:00.123456789Z","text":"t","digest":"d","request":{"server":"https://mcp.stripe.com","agent":"refunds","tools":[]},"events":[]}"#
        let grant = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(held.utf8))
        XCTAssertTrue(grant.holdsSignIn)
        XCTAssertEqual(grant.heldUntilDate, ISO8601DateFormatter().date(from: "2026-10-18T17:05:00Z"))
        let plain = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(PlanqueTests.grantJSONFor(status: "pending").utf8))
        XCTAssertNil(plain.heldUntilDate)
        XCTAssertFalse(plain.holdsSignIn)
    }

    /// The approval names tools in words; their exact terms come apart, for Details
    /// (kiff/planque#37, kiff/tafs#138 items 52, 53). An older planque sends neither.
    func testAGrantsToolsReadByTitleAndItsTermsComeApart() throws {
        let json = #"{"id":"g_01","status":"pending","text":"The taf may:\n• Refund a payment: moves money (amount)","terms":["bxkwf7vi_refund_payment (stripe_api_write with stripe_api_operation_id = \"PostRefunds\")"],"digest":"d","request":{"server":"https://mcp.stripe.com","agent":"refunds","tools":[{"tool":"stripe_api_write","name":"bxkwf7vi_refund_payment","title":"Refund a payment"},{"tool":"list_charges"}]},"events":[]}"#
        let grant = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
        XCTAssertEqual(grant.terms, [#"bxkwf7vi_refund_payment (stripe_api_write with stripe_api_operation_id = "PostRefunds")"#])
        XCTAssertEqual(grant.request.tools.map(\.words), ["Refund a payment", "list charges"], "the title, else the action in words")
        let plain = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(PlanqueTests.grantJSONFor().utf8))
        XCTAssertNil(plain.terms)
    }

    func testEnrolConfirmAndDisconnectBodies() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12 cd34 ef56 7890"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        StubProtocol.responses["DELETE /v1/accounts/t_01/grants/g_01"] = (200, grantJSON(status: "disconnected"))

        _ = try await planque.enrol(deviceKey: Data([0x30, 0x59, 0x01]), connectorKey: "kiff_live_connector")
        XCTAssertEqual(body(StubProtocol.sent.last) as? [String: String], ["device_key": "MFkB", "connector_key": "kiff_live_connector"])
        let connected = try await planque.confirm(grantID: "g_01", code: "c0de")
        XCTAssertTrue(connected.connected)
        XCTAssertEqual(body(StubProtocol.sent.last) as? [String: String], ["confirm": "c0de"])
        let gone = try await planque.disconnect(grantID: "g_01")
        XCTAssertEqual(gone.status, "disconnected")
        XCTAssertNil(StubProtocol.sent.last?.httpBody)
        XCTAssertEqual(StubProtocol.sent.filter { $0.value(forHTTPHeaderField: "Planque-Device-Signature") == nil }.count, 0)
    }

    /// A device KIFF knows names itself in each signature (kiff/planque#10), and still sends
    /// its key at enrolment for a planque from before that.
    func testADeviceKIFFKnowsSignsWithItsID() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_ipad01"
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"dev_ipad01"}"#)
        let enrolled = try await planque.enrol(deviceKey: Data([0x30, 0x59, 0x01]), connectorKey: "kiff_live_connector")
        XCTAssertEqual(enrolled.device, "dev_ipad01")
        let header = try XCTUnwrap(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"))
        XCTAssertTrue(header.hasPrefix("t=") && header.hasSuffix(",v1=sig,d=dev_ipad01"), header)
        XCTAssertEqual(body(StubProtocol.sent.last) as? [String: String], ["device_key": "MFkB", "connector_key": "kiff_live_connector"])
    }

    /// planque takes no signature without the device's KIFF id (kiff/planque#12): the client
    /// reads it right before signing when it isn't known, and sends nothing without it.
    func testASignatureAlwaysNamesTheDevice() async throws {
        let (_, planque, _) = clients()
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        var reads = 0
        planque.deviceLoader = { reads += 1; return "dev_late01" }
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        _ = try await planque.grants()
        let header = try XCTUnwrap(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"))
        XCTAssertTrue(header.hasSuffix(",d=dev_late01"), header)
        _ = try await planque.grants()
        XCTAssertEqual(reads, 1, "read once, then kept")

        let (_, unknown, _) = clients()
        unknown.tenant = "t_01"
        unknown.signer = { _ in "sig" }
        unknown.deviceLoader = { nil }
        StubProtocol.sent = []
        do {
            _ = try await unknown.grants()
            XCTFail("signed without the device's id")
        } catch let error as PlanqueClient.PlanqueError {
            XCTAssertEqual(error, .noDevice)
        }
        XCTAssertTrue(StubProtocol.sent.isEmpty, "nothing sent")
    }

    /// The vendor and a grant's events are planque's words, shown as they are
    /// (kiff/planque#16, #17): the app makes up no name from a host or an id.
    func testTheVendorAndEventsAreAsPlanqueSaysThem() throws {
        let g = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(PlanqueTests.grantJSONFor().utf8))
        XCTAssertEqual(g.vendor, "Stripe")
        XCTAssertEqual(g.events.first?.what, "asked by setup-agent")
    }

    /// Setting up on a second device: the app reads its KIFF device id and signs with it,
    /// so planque asks KIFF instead of refusing a second phone (kiff/planque#10).
    func testSetUpPlanqueSignsAsTheKIFFDevice() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_iphone01","this_device":false},{"id":"dev_ipad01","this_device":true}]}"#)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"dev_ipad01","kept":true}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)

        let ready = await model.setUpPlanque()
        XCTAssertTrue(ready, model.lastError ?? "")
        for path in ["/v1/accounts/t_01/enrolment", "/v1/accounts/t_01/grants"] {
            let method = path.hasSuffix("enrolment") ? "POST" : "GET"
            let header = try XCTUnwrap(sent(method, "planque.test", path).last?.value(forHTTPHeaderField: "Planque-Device-Signature"))
            XCTAssertTrue(header.hasSuffix(",d=dev_ipad01"), "\(path): \(header)")
        }
        XCTAssertEqual(sent("GET", "api.test", "/v1/me/devices").count, 1, "read once")
        Keychain.delete(AppModel.planqueAccount)
    }

    // MARK: Connecting a taf's tools

    private func model(signer: FaceIDSigner, vendor: StubVendor) async -> AppModel {
        let (kiff, planque, session) = clients()
        Keychain.delete(AppModel.planqueAccount)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: vendor)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    /// planque's enrolled connector key still stands (another device enrolled it): asked first with no
    /// key, planque answers `kept`, and no connector key is minted, so none is left live in KIFF.
    func testAKeptEnrolmentMintsNoConnectorKey() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (200, #"{"tenant":"t_01","device":"ab12","kept":true}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)

        let ready = await model.setUpPlanque()
        XCTAssertTrue(ready, model.lastError ?? "")
        XCTAssertTrue(model.planqueReady)
        XCTAssertTrue(sent("POST", "api.test", "/v1/keys").isEmpty, "nothing minted")
        let enrolments = sent("POST", "planque.test", "/v1/accounts/t_01/enrolment")
        XCTAssertEqual(enrolments.count, 1)
        XCTAssertNil(body(enrolments.first)["connector_key"])
        XCTAssertNotNil(enrolments.first?.value(forHTTPHeaderField: "Planque-Device-Signature"), "signed, as every enrolment")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A 2xx that doesn't say `kept` mints nothing, and isn't taken as set up: it stops and says so, and the next
    /// Start asks again (as Windows and the Mac). Only `409 needs_key` mints (the rule since 17:20).
    func testATwoHundredWithoutKeptMintsNothing() async throws {
        for answer in [(201, #"{"tenant":"t_01","device":"ab12"}"#), (200, #"{"tenant":"t_01","device":"ab12","kept":false}"#)] {
            let signer = FaceIDSigner()
            let model = await model(signer: signer, vendor: StubVendor())
            StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
            StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = answer
            StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)

            let ready = await model.setUpPlanque()
            XCTAssertFalse(ready, answer.1)
            XCTAssertFalse(model.planqueReady, answer.1)
            XCTAssertEqual(model.lastError, Words.Coach.connectingFailed, answer.1)
            XCTAssertTrue(sent("POST", "api.test", "/v1/keys").isEmpty, "\(answer.1): nothing minted")
            XCTAssertEqual(sent("POST", "planque.test", "/v1/accounts/t_01/enrolment").count, 1, answer.1)
            Keychain.delete(AppModel.planqueAccount)
        }
    }

    /// No standing key (`409 needs_key`): a key is minted once and handed over, under the one Face ID.
    func testWithNoStandingKeyOneIsMintedAndHandedOver() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        let route = "POST /v1/accounts/t_01/enrolment"
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses[route] = (409, #"{"error":"no connector key stands for this account","code":"needs_key"}"#)
        StubProtocol.thenRespond[route] = [route: (201, #"{"tenant":"t_01","device":"ab12"}"#)]
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)

        let ready = await model.setUpPlanque()
        XCTAssertTrue(ready, model.lastError ?? "")
        XCTAssertEqual(sent("POST", "api.test", "/v1/keys").count, 1)
        let enrolments = sent("POST", "planque.test", "/v1/accounts/t_01/enrolment")
        XCTAssertEqual(enrolments.map { body($0)["connector_key"] as? String }, [nil, "kiff_live_connector"])
        XCTAssertEqual(signer.prompts, ["tafs.planque.v1|t_01"], "under the one Face ID")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Only planque saying it needs a key (`needs_key`) mints one. Planque not
    /// reached, or a 5xx, mints nothing: a second enrolment would likely fail too and leave a key live in KIFF
    /// that no device can revoke. It says why, and the next Start asks again.
    func testPlanqueNotReachedOrFailingMintsNoConnectorKey() async throws {
        for failure in ["not reached", "503", "replayed", "400"] {
            let signer = FaceIDSigner()
            let model = await model(signer: signer, vendor: StubVendor())
            let route = "POST /v1/accounts/t_01/enrolment"
            StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
            if failure == "503" {
                StubProtocol.responses[route] = (503, #"{"error":"unavailable"}"#)
            } else if failure == "400" {
                // planque's 400 is a body it couldn't read: our own, so a second enrolment would fail the same way.
                StubProtocol.responses[route] = (400, #"{"error":"bad request"}"#)
            } else if failure == "replayed" {
                // Today's planque names each of its 409s: only a code-less one, an older planque's, is another device.
                StubProtocol.responses[route] = (409, #"{"error":"this signature was already used","code":"signature_replayed"}"#)
            } else {
                StubProtocol.dropped[route] = [.notConnectedToInternet]
            }
            defer { StubProtocol.dropped = [:] }

            let ready = await model.setUpPlanque()
            XCTAssertFalse(ready, failure)
            XCTAssertFalse(model.planqueReady, failure)
            XCTAssertTrue(sent("POST", "api.test", "/v1/keys").isEmpty, "\(failure): nothing minted")
            XCTAssertNotNil(model.lastError, "\(failure): it says why")
            XCTAssertNotEqual(model.lastError, Words.Coach.connectOtherDevice, "\(failure): never \"another device\"")
            Keychain.delete(AppModel.planqueAccount)
        }
    }

    /// An older planque's 409 with no code is another device's enrolment: it says so, and mints nothing.
    func testACodelessConflictIsAnotherDevice() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (409, #"{"error":"another phone is enrolled"}"#)
        let ready = await model.setUpPlanque()
        XCTAssertFalse(ready)
        XCTAssertEqual(model.lastError, Words.Coach.connectOtherDevice)
        XCTAssertTrue(sent("POST", "api.test", "/v1/keys").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Setting up planque is its own Face ID, once: planque gets the phone's key and, as it needs one
    /// (`needs_key`), a connector key minted with a signed setup call.
    func testSetUpPlanqueOnce() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (409, #"{"error":"no connector key stands for this account","code":"needs_key"}"#)
        StubProtocol.thenRespond["POST /v1/accounts/t_01/enrolment"] = ["POST /v1/accounts/t_01/enrolment": (201, #"{"tenant":"t_01","device":"ab12"}"#)]
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        XCTAssertFalse(model.planqueReady)

        let ready = await model.setUpPlanque()
        XCTAssertTrue(ready, model.lastError ?? "")
        XCTAssertTrue(model.planqueReady)
        XCTAssertEqual(signer.prompts, ["tafs.planque.v1|t_01"])
        let mint = try XCTUnwrap(sent("POST", "api.test", "/v1/keys").first)
        XCTAssertNotNil(mint.value(forHTTPHeaderField: "KIFF-Device-Signature"), "minting is a signed setup call")
        XCTAssertEqual(body(mint)["roles"] as? [String], ["connector"])
        // Asked first with no key; with nothing kept, the minted key goes in the second.
        let enrolments = sent("POST", "planque.test", "/v1/accounts/t_01/enrolment")
        XCTAssertNil(body(enrolments.first)["connector_key"])
        XCTAssertEqual(body(enrolments.last)["connector_key"] as? String, "kiff_live_connector")
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.planque.signer, "nothing signs afterwards")

        StubProtocol.sent = []
        let again = await model.setUpPlanque()
        XCTAssertTrue(again)
        XCTAssertEqual(signer.prompts.count, 1, "no second Face ID")
        XCTAssertTrue(sent("POST", "api.test", "/v1/keys").isEmpty, "no second key")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// One Face ID approves the grant; the vendor's sign-in comes back with planque's code,
    /// and the phone confirms it. A draft named with a grant not yet connected isn't read:
    /// KIFF takes a taf's Cards only once its tools are connected.
    func testConnectOneFaceIDThenSignIn() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON())]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        StubProtocol.sent = []

        let connected = await model.connect(grantID: "g_01", shown: Self.digest, draftID: "d_01")
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(Self.digest)"], "one Face ID, naming the grant")
        XCTAssertEqual(vendor.opened, [URL(string: "https://access.stripe.com/authorize?x=1")!])

        // planque's requests, in order (KIFF's device read goes to its own host).
        let order = StubProtocol.sent.filter { $0.url?.host == "planque.test" }.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" }
        XCTAssertEqual(Array(order.prefix(3)), ["GET /v1/accounts/t_01/grants", "POST /v1/accounts/t_01/grants/g_01/approve",
                                                "POST /v1/accounts/t_01/grants/g_01/confirm"])
        XCTAssertEqual(body(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").first)["confirm"] as? String, "c0de")
        XCTAssertTrue(sent("GET", "api.test", "/v1/me/agent-drafts/d_01").isEmpty, "no draft before its tools are connected")
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/agent-drafts/d_01/confirm").isEmpty)
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.planque.signer)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Coming back from the vendor's page brings the app to the front, which refreshes the
    /// connections. That refresh must not end the Face ID step's signer: the sign-in is still
    /// confirmed (the owner's phone test, kiff/tafs#73: "This step needs your Face ID first").
    func testARefreshWhileSigningInDoesNotEndTheStep() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON())]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        vendor.meanwhile = { await model.refreshConnections() }
        StubProtocol.sent = []

        let connected = await model.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts.count, 1, "still one Face ID")
        XCTAssertFalse(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").isEmpty, "the sign-in is confirmed")
        XCTAssertNil(model.planque.signer, "nothing signs afterwards")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A sign-in that comes back for another grant, or is closed, connects nothing and
    /// confirms no Cards.
    func testNothingIsConfirmedWithoutThisGrantsCode() async throws {
        for callback in ["tafs://planque/signed-in?tenant=t_01&grant=g_other&confirm=c0de", "tafs://planque/signed-in?tenant=t_02&grant=g_01&confirm=c0de", nil] {
            let signer = FaceIDSigner()
            let vendor = StubVendor()
            let model = await model(signer: signer, vendor: vendor)
            model.planqueSetUp(for: "t_01")
            StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON())]}"#)
            StubProtocol.responses["GET /v1/me/agent-drafts/d_01"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"draft","version":"v3"}"#)
            StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
            if let callback { vendor.callback = URL(string: callback) } else { vendor.cancel = true }
            StubProtocol.sent = []

            let connected = await model.connect(grantID: "g_01", shown: Self.digest, draftID: "d_01")
            XCTAssertNil(connected, callback ?? "closed")
            XCTAssertNotNil(model.lastError)
            XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").isEmpty, callback ?? "closed")
            XCTAssertTrue(sent("POST", "api.test", "/v1/me/agent-drafts/d_01/confirm").isEmpty, callback ?? "closed")
            Keychain.delete(AppModel.planqueAccount)
        }
    }

    /// The second approval: the vendor is connected, so one Face ID confirms only the
    /// taf's draft, the version shown; planque isn't asked again.
    func testConnectedGrantConfirmsOnlyTheDraft() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_01"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"draft","version":"v3"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_01/confirm"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"confirmed","version":"v3","card_ids":["m1"]}"#)
        StubProtocol.sent = []

        let done = await model.connect(grantID: "g_01", shown: Self.digest, draftID: "d_01", shownDraft: "v3")
        XCTAssertEqual(done?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.cards.v1|t_01|d_01|v3"])
        XCTAssertTrue(vendor.opened.isEmpty, "no second vendor sign-in")
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").isEmpty)
        let cards = try XCTUnwrap(sent("POST", "api.test", "/v1/me/agent-drafts/d_01/confirm").first)
        XCTAssertEqual(body(cards)["version"] as? String, "v3")
        XCTAssertNotNil(cards.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertNil(model.kiff.setupSigner)

        // Nothing to confirm: nothing asked.
        let again = await self.model(signer: signer, vendor: vendor)
        again.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        _ = await again.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertEqual(signer.prompts.count, 1)
    }

    /// A grant whose digest isn't its request's is never put to Face ID.
    func testMismatchedGrantIsNotPutToFaceID() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(digest: String(repeating: "0", count: 64)))]}"#)
        StubProtocol.sent = []
        let connected = await model.connect(grantID: "g_01", shown: String(repeating: "0", count: 64))
        XCTAssertNil(connected)
        XCTAssertEqual(model.lastError, PlanqueClient.PlanqueError.digestMismatch.errorDescription)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// kiff/tafs#115: Face ID signs exactly what the owner was shown. A grant revised after
    /// the card rendered it (shown A, latest B) isn't signed: B goes on screen, and the owner
    /// approves again. Nothing shown, nothing signed.
    func testOnlyTheGrantShownIsSigned() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON())]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        StubProtocol.sent = []

        let shownA = String(repeating: "a", count: 64)
        let stale = await model.connect(grantID: "g_01", shown: shownA)
        XCTAssertNil(stale)
        XCTAssertTrue(model.shownTermsChanged)
        XCTAssertNotNil(model.lastError)
        XCTAssertTrue(signer.prompts.isEmpty, "no Face ID over terms the owner wasn't shown")
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").isEmpty, "nothing approved")
        XCTAssertEqual(model.grants.first(where: { $0.id == "g_01" })?.digest, Self.digest, "the latest terms are on screen now")

        let none = await model.connect(grantID: "g_01", shown: nil)
        XCTAssertNil(none)
        XCTAssertTrue(signer.prompts.isEmpty, "nothing shown, nothing signed")

        let connected = await model.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(Self.digest)"], "the terms now shown, approved again")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The same for a taf's Cards: a draft that changed after it was shown isn't confirmed.
    func testOnlyTheDraftShownIsConfirmed() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_01"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"draft","version":"v4"}"#)
        StubProtocol.sent = []

        let stale = await model.connect(grantID: "g_01", shown: Self.digest, draftID: "d_01", shownDraft: "v3")
        XCTAssertNil(stale)
        XCTAssertTrue(model.shownTermsChanged)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/agent-drafts/d_01/confirm").isEmpty, "nothing confirmed")
        XCTAssertEqual(model.setupDrafts["d_01"]?.version, "v4", "the latest Cards are on screen now")
        Keychain.delete(AppModel.planqueAccount)
    }

    func testConnectingNeedsPlanqueSetUp() async {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.sent = []
        let connected = await model.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertNil(connected)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }

    /// Refunds asked for g_01; Chargebacks, when listed, draws on its refund tool through its own Card.
    private func sharedModel(withChargebacks: Bool) async throws -> (AppModel, UUID, FaceIDSigner) {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        model.sessionHasKey = true
        // The approval key signs the removal's DELETE for this account.
        model.kiffTenant = "t_01"
        let chargebacks = #"{"id":"depl_2","name":"Chargebacks","job":"disputes","kiff_agent":"chargebacks","status":"active","next_run":null,"last_run":null,"color":1,"state":"working","waiting":0,"card":{"ids":["chargebacks-card"],"active":true,"expires":null,"tools":[],"rules":[{"action":"refund_payment","label":"Refund a payment","outside":"hold","window":"day"}],"used":{},"usage_unknown":[]}}"#
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.entry(agent: "refunds", name: "Refunds", cards: ["refunds-card"])] + (withChargebacks ? [chargebacks] : [])))
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Refunds","job":"refunds","kiff_agent":"refunds","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        StubProtocol.responses["POST /v1/me/mandates/refunds-card/revoke"] = (200, "{}")
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":true,"removed":["schedule"],"failed":[]}"#)
        await model.refresh()
        let refunds = try XCTUnwrap(model.tafs.first { $0.name == "Refunds" }?.id)
        return (model, refunds, signer)
    }

    /// Removing a taf leaves its connections to tafs cloud: the phone sends no disconnect of its
    /// own and asks for one confirmation, the removal's, whether another taf uses the
    /// connection or not.
    func testRemovingATafLeavesItsConnectionsToTafsCloud() async throws {
        for shared in [true, false] {
            let (model, refunds, signer) = try await sharedModel(withChargebacks: shared)
            StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":true,"removed":["schedule","connections"],"failed":[]}"#)
            let removed = await model.removeTaf(refunds)
            let words = try XCTUnwrap(removed)
            XCTAssertTrue(sent("DELETE", "planque.test", "/v1/accounts/t_01/grants/g_01").isEmpty, "the phone ends no connection itself")
            XCTAssertEqual(signer.prompts.count, 1, "one confirmation, the removal's")
            XCTAssertTrue(words.hasPrefix("Its Cards are revoked in KIFF. Its access to apps is removed."), words)
            XCTAssertFalse(words.contains("Kept"), words)
            Keychain.delete(AppModel.planqueAccount)
        }
    }

    /// Remove for good with Face ID closed, or a face not recognised, says so and changes nothing:
    /// never a button that just comes back. A key that couldn't sign says why.
    func testARemovalNobodyConfirmedSaysSoAndChangesNothing() async throws {
        let (model, refunds, signer) = try await sharedModel(withChargebacks: false)
        StubProtocol.sent = []
        for error: Error in [LAError(.userCancel), LAError(.authenticationFailed)] {
            signer.throwing = error
            let said = await model.removeTaf(refunds)
            XCTAssertEqual(said, "Not removed. Nothing changed.")
            XCTAssertEqual(said, Words.Taf.Remove.notConfirmed)
        }
        signer.throwing = nil
        signer.refuse = true
        let refused = await model.removeTaf(refunds)
        let failed = try XCTUnwrap(refused)
        XCTAssertTrue(failed.hasPrefix("Remove didn't go through."), failed)
        XCTAssertTrue(sent("DELETE", "cloud.test", "/v1/tafs/depl_1").isEmpty, "nothing is removed")
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/mandates/refunds-card/revoke").isEmpty, "no Card is revoked")
        XCTAssertNotNil(model.taf(refunds))
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The words say only what tafs cloud did: connections it ended are said once, ones it
    /// couldn't end are named among the steps left, and a removal that says nothing of them
    /// claims nothing.
    func testTheRemovalSaysOfItsConnectionsOnlyWhatTafsCloudDid() async throws {
        func removal(_ json: String) throws -> TafsCloud.Removal {
            try KIFFClient.decoder.decode(TafsCloud.Removal.self, from: Data(json.utf8))
        }
        let ended = try removal(#"{"done":true,"removed":["schedule","connections"],"failed":[]}"#)
        XCTAssertEqual(AppModel.removalCards("Cards.", ended), "Cards. Its access to apps is removed.")
        let silent = try removal(#"{"done":true,"removed":["schedule"],"failed":[]}"#)
        XCTAssertEqual(AppModel.removalCards("Cards.", silent), "Cards.", "not said to be removed when tafs cloud doesn't say so")
        let left = try removal(#"{"done":false,"removed":["schedule"],"failed":["connections"]}"#)
        XCTAssertEqual(AppModel.removalCards("Cards.", left), "Cards.")
        XCTAssertEqual(AppModel.removalStep("connections"), "access to apps", "named in the app's words, never by its id")
        XCTAssertEqual(AppModel.removalStep("key"), "key")

        let (model, refunds, _) = try await sharedModel(withChargebacks: false)
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":false,"removed":["schedule"],"failed":["connections"]}"#)
        let removed = await model.removeTaf(refunds)
        let words = try XCTUnwrap(removed)
        XCTAssertTrue(words.contains("its access to apps couldn't be removed"), words)
        XCTAssertFalse(words.contains("Its access to apps is removed."), words)
        XCTAssertTrue(sent("DELETE", "planque.test", "/v1/accounts/t_01/grants/g_01").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Disconnecting asks for Face ID, then planque revokes the sign-in.
    func testDisconnectAsksFaceID() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        await model.refreshConnections()
        XCTAssertEqual(model.grants.map(\.id), ["g_01"])
        XCTAssertTrue(signer.prompts.isEmpty, "reading asks for nothing")

        signer.refuse = true
        let refused = await model.disconnect(grantID: "g_01")
        XCTAssertFalse(refused)
        XCTAssertTrue(sent("DELETE", "planque.test", "/v1/accounts/t_01/grants/g_01").isEmpty)

        signer.refuse = false
        StubProtocol.responses["DELETE /v1/accounts/t_01/grants/g_01"] = (200, grantJSON(status: "disconnected"))
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "disconnected"))]}"#)
        let done = await model.disconnect(grantID: "g_01")
        XCTAssertTrue(done)
        XCTAssertEqual(signer.prompts, ["tafs.disconnect.v1|t_01|g_01"])
        XCTAssertEqual(sent("DELETE", "planque.test", "/v1/accounts/t_01/grants/g_01").count, 1)
        Keychain.delete(AppModel.planqueAccount)
    }

    // MARK: A connection its vendor signed out

    private static func signedOutJSON(_ id: String = "g_01") -> String {
        grantJSONFor(id, status: "needs_reconnect")
            .replacingOccurrences(of: #""status":"needs_reconnect","#, with: #""status":"needs_reconnect","status_words":"Signed out by Stripe · sign in again","#)
    }

    func testASignedOutGrantReadsInPlanquesWords() throws {
        let g = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(Self.signedOutJSON().utf8))
        XCTAssertTrue(g.needsReconnect)
        XCTAssertFalse(g.connected)
        XCTAssertEqual(g.signedOutWords, "Signed out by Stripe · sign in again")
        let bare = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(Self.grantJSONFor(status: "needs_reconnect").utf8))
        XCTAssertEqual(bare.signedOutWords, "Signed out · sign in again", "the app's plain words until planque sends its own")
    }

    /// A grant its vendor signed out is approved and signed in again, as a new one is: one
    /// Face ID, the vendor's page, then planque's confirmation, on the same grant.
    func testASignedOutGrantSignsInAgain() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(Self.signedOutJSON())]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")

        let connected = await model.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(Self.digest)"])
        XCTAssertEqual(vendor.opened.count, 1)
        XCTAssertNil(model.connectStep, "the check is over")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Gap 13: a connection that's still connected signs in again in place: one Face ID, the
    /// vendor's page, then planque's confirmation, on the same grant. Without `again`, a
    /// connected grant is the second approval and nothing goes to planque.
    func testAConnectedGrantSignsInAgainInPlace() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(status: "connected"))]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"connected","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grantJSON(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        StubProtocol.sent = []

        let connected = await model.connect(grantID: "g_01", shown: Self.digest, again: true)
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(Self.digest)"])
        XCTAssertEqual(vendor.opened.count, 1)
        XCTAssertEqual(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").count, 1)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A connected grant with a profile, as planque lists it: KIFF's Stripe vector.
    private func profiledGrant(status: String = "connected") throws -> (json: String, digest: String, words: String) {
        let bundle = Bundle(for: ProfileWordsTests.self)
        let profile = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "stripe-refunds", withExtension: "json")))
        let words = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "stripe-refunds.expected", withExtension: "json")))
        let profileJSON = String(decoding: try JCS.canonical(try JCS.parse(profile)), as: UTF8.self)
        let request = #"{"agent":"refunds","grant":"g_01","server":"https://mcp.stripe.com","tenant":"t_01","tools":[],"v":"planque.grant.v1","profile":\#(profileJSON)}"#
        let digest = SHA256.hash(data: try JCS.canonical(Data(request.utf8))).map { String(format: "%02x", $0) }.joined()
        let json = #"{"id":"g_01","status":"\#(status)","text":"t","digest":"\#(digest)","request":\#(request),"vendor":"Stripe","events":[]}"#
        return (json, digest, String(decoding: words, as: UTF8.self))
    }

    /// Android 36's phone round, on iOS too: Sign in again on a connected grant with a profile
    /// refused before Face ID, as nothing had read KIFF's words. It reads them first now, as the
    /// approval card does, and signs them into the approval.
    func testSignInAgainReadsTheProfilesWordsBeforeItApproves() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        let (grant, digest, words) = try profiledGrant()
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grant)]}"#)
        StubProtocol.responses["POST /v1/profiles/words"] = (200, words)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"connected","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, grant)
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        await model.refreshConnections()
        let g = try XCTUnwrap(model.grants.first { $0.id == "g_01" })
        XCTAssertNil(model.shownWords(for: g), "nothing has read KIFF's words yet")
        StubProtocol.sent = []

        await model.reconnect(g)
        XCTAssertNil(model.reconnectErrors["g_01"])
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path == "/v1/profiles/words" }.count, 1)
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(digest)"])
        let approve = body(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").first)
        XCTAssertNotNil(approve["kiff_profile"], "the words read are signed into the approval")
        XCTAssertEqual(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").count, 1)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// KIFF's words can't be read: Sign in again says why, with no Face ID and nothing approved.
    func testSignInAgainWhoseWordsCantBeReadApprovesNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        let (grant, _, _) = try profiledGrant()
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grant)]}"#)
        StubProtocol.responses["POST /v1/profiles/words"] = (503, #"{"error":"unavailable"}"#)
        await model.refreshConnections()
        let g = try XCTUnwrap(model.grants.first { $0.id == "g_01" })
        StubProtocol.sent = []

        await model.reconnect(g)
        XCTAssertNotNil(model.reconnectErrors["g_01"])
        XCTAssertEqual(model.reconnectErrors["g_01"], model.profileWordsError["g_01"])
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Back from the vendor, it's signed in only once planque says connected, never because
    /// the page closed.
    func testASignInPlanqueDoesntConnectIsNotSignedIn() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(Self.signedOutJSON())]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/authorize?x=1","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, Self.signedOutJSON())
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")

        let connected = await model.connect(grantID: "g_01", shown: Self.digest)
        XCTAssertNil(connected)
        XCTAssertEqual(model.lastError, "The sign-in at Stripe didn't finish, so nothing was connected. Try signing in again.")
        Keychain.delete(AppModel.planqueAccount)
    }
}

/// A signer with a real P-256 key, as the device's: the Chrome pairing's signature is checked with its public key.
@MainActor
final class P256Signer: ApprovalSigning {
    let key = P256.Signing.PrivateKey()
    var prompts: [String] = []
    var publicKeyDER: Data { key.publicKey.derRepresentation }
    func sign(_ message: Data, reason: String) async throws -> String {
        prompts.append(reason)
        return try key.signature(for: message).derRepresentation.base64EncodedString()
    }
    func signConfirmed(_ message: Data) throws -> String { try key.signature(for: message).derRepresentation.base64EncodedString() }
}

// MARK: Chrome (docs/chrome.md)

extension PlanqueTests {
    private func planqueModel(signer: ApprovalSigning) async -> AppModel {
        let (kiff, planque, session) = clients()
        Keychain.delete(AppModel.planqueAccount)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (200, #"{"tenant":"t_01","device":"ab12","kept":true}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        _ = await model.setUpPlanque()
        return model
    }

    /// Account shows Chrome only once planque offers it: its route's 404 leaves the row out, a list shows it.
    func testChromesShowOnlyOncePlanqueOffersThem() async {
        let model = await planqueModel(signer: FaceIDSigner())
        XCTAssertTrue(model.planqueReady)
        StubProtocol.responses["GET /v1/accounts/t_01/chromes"] = (404, #"{"error":"not found"}"#)
        await model.refreshChromes()
        XCTAssertNil(model.chromes)
        XCTAssertNil(model.chromesError)
        StubProtocol.responses["GET /v1/accounts/t_01/chromes"] = (200, #"{"chromes":[{"chrome_id":"chr_1","name":"Work Mac","browser":"Chrome 131","os":"macOS","version":"0.1.0"}]}"#)
        await model.refreshChromes()
        XCTAssertEqual(model.chromes?.map(\.name), ["Work Mac"])
        XCTAssertFalse(sent("GET", "planque.test", "/v1/accounts/t_01/chromes").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// An offer is shown only when its proof holds under the phone's secret; the confirmation is the phone's proof and
    /// a raw r‖s signature over the pairing's signed bytes, behind one prompt naming the Chrome.
    func testAChromeIsPairedOnlyOnAProvenOfferAndTheConfirmationVerifies() async throws {
        let signer = P256Signer()
        let model = await planqueModel(signer: signer)
        StubProtocol.responses["POST /v1/accounts/t_01/chromes/pairings"] = (201, #"{"pairing":"pr_1","lookup":"K7QF"}"#)
        let maybeOpened = await model.openChromePairing()
        let opened = try XCTUnwrap(maybeOpened)
        XCTAssertEqual(opened.lookup, "K7QF")
        XCTAssertEqual(opened.secret.count, 6)
        XCTAssertFalse(sent("POST", "planque.test", "/v1/accounts/t_01/chromes/pairings").contains { String(decoding: $0.httpBody ?? Data(), as: UTF8.self).contains(opened.secret) }, "the secret never leaves the phone")

        let chromeKey = P256.Signing.PrivateKey().publicKey.derRepresentation
        let key = ChromePairing.base64URL(chromeKey)
        let good = ChromePairing.base64URL(ChromePairing.hmac(opened.secret, ChromePairing.extensionProofLabel, chromeKey))
        let bad = ChromePairing.base64URL(ChromePairing.hmac("AAAAAA", ChromePairing.extensionProofLabel, chromeKey))
        let offer = { (proof: String) in #"{"pairing":"pr_1","status":"waiting_for_phone","key":"\#(key)","key_sha256":"x","proof":"\#(proof)","version":"0.1.0","browser":"Chrome 131","os":"macOS"}"# }

        StubProtocol.responses["GET /v1/accounts/t_01/chromes/pairings/pr_1"] = (200, #"{"pairing":"pr_1","status":"waiting_for_chrome"}"#)
        let waiting = await model.chromePairingStep(opened)
        XCTAssertEqual(waiting, .waiting)
        StubProtocol.responses["GET /v1/accounts/t_01/chromes/pairings/pr_1"] = (200, offer(bad))
        let refused = await model.chromePairingStep(opened)
        XCTAssertEqual(refused, .refused, "another extension used the lookup")
        StubProtocol.responses["GET /v1/accounts/t_01/chromes/pairings/pr_1"] = (200, offer(good))
        guard case .offered(let proven) = await model.chromePairingStep(opened) else { return XCTFail("a proven offer is shown") }
        StubProtocol.responses["GET /v1/accounts/t_01/chromes/pairings/pr_1"] = (404, #"{"error":"no such pairing, or it expired"}"#)
        let expired = await model.chromePairingStep(opened)
        XCTAssertEqual(expired, .expired)

        StubProtocol.responses["POST /v1/accounts/t_01/chromes/pairings/pr_1/confirm"] = (200, #"{"chrome_id":"chr_1","name":"Work Mac"}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/chromes"] = (200, #"{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}"#)
        let paired = await model.confirmChrome(opened, offer: proven, name: " Work Mac ")
        XCTAssertTrue(paired, model.chromesError ?? "")
        XCTAssertEqual(signer.prompts, [Words.Chrome.pairPrompt(name: "Work Mac")])
        let sentBody = body(sent("POST", "planque.test", "/v1/accounts/t_01/chromes/pairings/pr_1/confirm").first)
        let confirmation = ChromePairing.confirmation(secret: opened.secret, chromeKey: chromeKey, name: "Work Mac", phoneKey: signer.publicKeyDER)
        XCTAssertEqual(sentBody["phone_proof"] as? String, confirmation.phoneProof)
        XCTAssertEqual(sentBody["chrome_key_sha256"] as? String, ChromePairing.chromeKeyHash(chromeKey))
        let raw = try XCTUnwrap(ChromePairing.base64URLDecoded(try XCTUnwrap(sentBody["phone_sig"] as? String)))
        XCTAssertEqual(raw.count, 64)
        XCTAssertTrue(signer.key.publicKey.isValidSignature(try P256.Signing.ECDSASignature(rawRepresentation: raw),
                                                            for: try ChromePairing.signedBytes(pairing: "pr_1", confirmation)))
        XCTAssertEqual(model.chromes?.map(\.id), ["chr_1"])
        Keychain.delete(AppModel.planqueAccount)
    }
}
