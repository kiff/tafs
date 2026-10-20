import CryptoKit
import XCTest
@testable import Tafs

/// Counts Face ID prompts apart from the signatures made without asking again.
@MainActor
final class FaceIDSigner: ApprovalSigning {
    var prompts: [String] = []
    var quiet: [String] = []
    var refuse = false

    var publicKeyDER: Data { Data([0x30, 0x59, 0x01]) }

    func sign(_ message: Data, reason: String) async throws -> String {
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
    static func grantJSONFor(_ id: String = "g_01", status: String = "pending", digest: String = PlanqueTests.digest, signedIn: Bool = false) -> String {
        #"{"id":"\#(id)","status":"\#(status)",\#(signedIn ? #""signed_in":true,"# : "")"text":"refunds may refund payments at mcp.stripe.com","digest":"\#(digest)","request":\#(Self.request),"audience":"https://planque.kiff.dev/mcp/t_01/\#(id)","vendor":"Stripe","events":[{"at":"2026-10-17T10:00:00.123456789Z","what":"asked by setup-agent"}]}"#
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
        XCTAssertTrue(header.hasPrefix("t=") && header.hasSuffix(",v1=sig"), header)
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
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"dev_ipad01"}"#)
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

    /// Setting up planque is its own Face ID, once: planque gets the phone's key and a
    /// connector key minted with a signed setup call.
    func testSetUpPlanqueOnce() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12"}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        XCTAssertFalse(model.planqueReady)

        let ready = await model.setUpPlanque()
        XCTAssertTrue(ready, model.lastError ?? "")
        XCTAssertTrue(model.planqueReady)
        XCTAssertEqual(signer.prompts, ["tafs.planque.v1|t_01"])
        let mint = try XCTUnwrap(sent("POST", "api.test", "/v1/keys").first)
        XCTAssertNotNil(mint.value(forHTTPHeaderField: "KIFF-Device-Signature"), "minting is a signed setup call")
        XCTAssertEqual(body(mint)["roles"] as? [String], ["connector"])
        XCTAssertEqual(body(sent("POST", "planque.test", "/v1/accounts/t_01/enrolment").first)["connector_key"] as? String, "kiff_live_connector")
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

        let connected = await model.connect(grantID: "g_01", draftID: "d_01")
        XCTAssertEqual(connected?.status, "connected", model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.connect.v1|t_01|g_01|\(Self.digest)"], "one Face ID, naming the grant")
        XCTAssertEqual(vendor.opened, [URL(string: "https://access.stripe.com/authorize?x=1")!])

        let order = StubProtocol.sent.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" }
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

        let connected = await model.connect(grantID: "g_01")
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

            let connected = await model.connect(grantID: "g_01", draftID: "d_01")
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

        let done = await model.connect(grantID: "g_01", draftID: "d_01")
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
        _ = await again.connect(grantID: "g_01")
        XCTAssertEqual(signer.prompts.count, 1)
    }

    /// A grant whose digest isn't its request's is never put to Face ID.
    func testMismatchedGrantIsNotPutToFaceID() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON(digest: String(repeating: "0", count: 64)))]}"#)
        StubProtocol.sent = []
        let connected = await model.connect(grantID: "g_01")
        XCTAssertNil(connected)
        XCTAssertEqual(model.lastError, PlanqueClient.PlanqueError.digestMismatch.errorDescription)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/approve").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    func testConnectingNeedsPlanqueSetUp() async {
        let signer = FaceIDSigner()
        let model = await model(signer: signer, vendor: StubVendor())
        StubProtocol.sent = []
        let connected = await model.connect(grantID: "g_01")
        XCTAssertNil(connected)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.isEmpty)
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
}
