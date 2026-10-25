import XCTest
@testable import Tafs

/// Answers requests from canned responses, and records what was sent.
final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var responses: [String: (Int, String)] = [:]   // "METHOD path" → (status, body)
    nonisolated(unsafe) static var sent: [URLRequest] = []
    /// One request ("METHOD path") to hold until `release` is signalled; `arrived` says it came.
    nonisolated(unsafe) static var holdOnce: String?
    /// Once a request ("METHOD path") is served, these responses replace others: what a
    /// server answers after it changed something.
    nonisolated(unsafe) static var thenRespond: [String: [String: (Int, String)]] = [:]
    /// Requests ("METHOD path") whose next tries fail as the network does, in order: what
    /// iOS does to a socket while the app is in the background.
    nonisolated(unsafe) static var dropped: [String: [URLError.Code]] = [:]
    static let arrived = DispatchSemaphore(value: 0)
    static let release = DispatchSemaphore(value: 0)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var request = self.request
        if request.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                data.append(buffer, count: n)
            }
            stream.close()
            request.httpBody = data
        }
        Self.sent.append(request)
        let path = request.url.map { $0.path + ($0.query.map { "?" + $0 } ?? "") } ?? ""
        let key = "\(request.httpMethod ?? "GET") \(path)"
        if Self.holdOnce == key {
            Self.holdOnce = nil
            Self.arrived.signal()
            Self.release.wait()
        }
        if var codes = Self.dropped[key], !codes.isEmpty {
            let code = codes.removeFirst()
            Self.dropped[key] = codes
            client?.urlProtocol(self, didFailWithError: URLError(code))
            return
        }
        let (status, body) = Self.responses[key] ?? (404, #"{"error":"not stubbed"}"#)
        if let next = Self.thenRespond.removeValue(forKey: key) { Self.responses.merge(next) { $1 } }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// tafs cloud's home list (#91), as tests serve it at `GET /v1/home`.
enum HomeJSON {
    /// A taf tafs cloud runs (`id` set) or another agent with Cards (`id` nil), holding `cards`.
    static func entry(agent: String, name: String, id: String? = "depl_1", status: String = "active", state: String? = nil,
                      cards: [String]? = nil, color: Int = 0, waiting: Int = 0) -> String {
        let ids = (cards ?? ["\(agent)-card"]).map { "\"\($0)\"" }.joined(separator: ",")
        let state = state ?? (id == nil ? "idle" : status == "paused" ? "paused" : "working")
        let idJSON = id.map { "\"\($0)\"" } ?? "null"
        let statusJSON = id == nil ? "null" : "\"\(status)\""
        let job = id == nil ? "null" : "\"refunds\""
        return #"{"id":\#(idJSON),"name":"\#(name)","job":\#(job),"kiff_agent":"\#(agent)","status":\#(statusJSON),"next_run":null,"last_run":null,"color":\#(color),"state":"\#(state)","waiting":\#(waiting),"card":{"ids":[\#(ids)],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]}}"#
    }

    static func list(_ tafs: [String] = [], others: [String] = [], removing: String = "[]") -> String {
        #"{"tafs":[\#(tafs.joined(separator: ","))],"others":[\#(others.joined(separator: ","))],"removing":\#(removing)}"#
    }

    /// Rae, run by tafs cloud as depl_1, acting as rae-k3f9 under its refund Card.
    static let rae = entry(agent: "rae-k3f9", name: "Rae", cards: ["rae-k3f9-stripe_refund"])
    static let empty = list()
}

@MainActor
final class KIFFTests: XCTestCase {
    /// A client whose requests are answered by `StubProtocol`, with nothing sent yet.
    private func makeClient() -> KIFFClient {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return KIFFClient(baseURL: URL(string: "https://api.test")!, token: "dev_token", session: URLSession(configuration: config))
    }

    func testDeviceSignInPendingThenToken() async throws {
        let client = makeClient()
        StubProtocol.responses["POST /v1/auth/device/start"] = (200, #"{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://app.test/auth/device","verification_uri_complete":"https://app.test/auth/device?code=ABCD-EFGH","expires_in":600,"interval":5}"#)
        let start = try await client.startDeviceSignIn()
        XCTAssertEqual(start.userCode, "ABCD-EFGH")
        XCTAssertNil(StubProtocol.sent.last?.value(forHTTPHeaderField: "Authorization"), "sign-in start carries no token")

        StubProtocol.responses["POST /v1/auth/device/token"] = (400, #"{"error":"authorization_pending"}"#)
        do {
            _ = try await client.pollDeviceToken(deviceCode: "dc")
            XCTFail("expected pending")
        } catch let error as KIFFClient.KIFFError {
            XCTAssertEqual(error, .authorizationPending)
        }

        StubProtocol.responses["POST /v1/auth/device/token"] = (200, #"{"access_token":"tok","token_type":"Bearer","expires_in":3600,"tenant":"t1","subject":"user_1"}"#)
        let token = try await client.pollDeviceToken(deviceCode: "dc")
        XCTAssertEqual(token.accessToken, "tok")
        XCTAssertEqual(String(data: StubProtocol.sent.last?.httpBody ?? Data(), encoding: .utf8), #"{"device_code":"dc"}"#)
    }

    func testSignInSendsThePhonesKey() async throws {
        let client = makeClient()
        StubProtocol.responses["POST /v1/auth/device/start"] = (200, #"{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://app.test/auth/device","verification_uri_complete":"https://app.test/auth/device?code=ABCD-EFGH","expires_in":600,"interval":5}"#)
        _ = try await client.startDeviceSignIn(publicKey: Data([1, 2, 3]), deviceName: "tafs on iPhone", platform: "ios")
        let sent = try XCTUnwrap(StubProtocol.sent.last?.httpBody)
        let body = try JSONDecoder().decode([String: String].self, from: sent)
        XCTAssertEqual(body, ["public_key": "AQID", "device_name": "tafs on iPhone", "platform": "ios"])
    }

    func testHeldCallsDecodeAndMap() async throws {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, """
        {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"refund-agent","action":"refund_order","title":"Refund an order","entity_id":"ord_4502",
          "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000,"used":0,"draw":48000,"reason":"Over $150 each"}],
          "parameters":{"amount_cents":48000},"next_steps":["return first"],
          "created_at":"2026-10-14T10:41:00.123Z","expires_at":"2026-10-14T10:51:00Z"}]}
        """)
        let calls = try await client.heldCalls()
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "Authorization"), "Bearer dev_token")

        let held = KIFFMapping.held(calls[0], tafID: UUID())
        XCTAssertEqual(held.kiffID, "ce_1")
        XCTAssertEqual(held.proposed.amount, .bare(48000), "no declared unit: a bare number, never US cents")
        XCTAssertEqual(held.reason, "Over $150 each")
        XCTAssertEqual(held.nextSteps, ["return first"])
        XCTAssertEqual(held.proposed.summary, "Refund an order ord_4502", "the title, never the tool's name, on an approval")
    }

    func testAnswersHitTheRightEndpoints() async throws {
        let client = makeClient()
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_2/reject"] = (200, #"{"id":"ce_2"}"#)
        try await client.approveOnce("ce_1")
        try await client.reject("ce_2", nextStep: "return first")
        XCTAssertEqual(String(data: StubProtocol.sent.last?.httpBody ?? Data(), encoding: .utf8), #"{"next_step":"return first"}"#)
    }

    func testUnauthorizedIsItsOwnError() async {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (401, #"{"error":"bad token"}"#)
        do {
            _ = try await client.heldCalls()
            XCTFail("expected unauthorized")
        } catch let error as KIFFClient.KIFFError {
            XCTAssertEqual(error, .unauthorized)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    /// A held call's decimal arguments read as written, never in exponent form (#104 review).
    func testADecimalArgumentIsWrittenPlainly() throws {
        let json = #"{"rate":1.5e-05,"share":0.1,"amount":2550,"weight":2.5}"#
        let args = try KIFFClient.decoder.decode([String: KIFFClient.CardException.ArgumentText].self, from: Data(json.utf8))
        XCTAssertEqual(args["rate"]?.text, "0.000015")
        XCTAssertEqual(args["share"]?.text, "0.1")
        XCTAssertEqual(args["amount"]?.text, "2550")
        XCTAssertEqual(args["weight"]?.text, "2.5")
    }

    /// The home list is tafs cloud's (#91): the app shows each entry as it is, its state in
    /// the app's words and its Card's lines and use in KIFF's units.
    func testATafFromTafsCloudIsShownAsItIs() throws {
        let json = #"""
        {"tafs":[{"id":"taf-1","name":"Rae","job":"refunds","kiff_agent":"rae","status":"active","next_run":null,"last_run":null,
          "color":3,"state":"working","waiting":0,
          "card":{"ids":["m1"],"active":true,"expires":null,"tools":["Refund a payment","list_charges"],
            "rules":[{"action":"refund","label":"Refund a payment","outside":"hold","per_action":{"minor":15000,"unit":"USD","scale":2},
                      "per_window":{"minor":200000,"unit":"USD","scale":2},"count_per_window":10,"window":"day"}],
            "used":{"refund":{"minor":21200,"unit":"USD","scale":2}},"usage_unknown":["list_charges"]}}],
         "others":[{"id":null,"name":"support-demo","job":null,"kiff_agent":"support-demo","status":null,"next_run":null,"last_run":null,
          "color":4,"state":"idle","waiting":0,
          "card":{"ids":["m2"],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]}}],
         "removing":[]}
        """#
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(json.utf8))
        let rae = KIFFMapping.taf(home.tafs[0], id: UUID(), owner: "You")
        XCTAssertEqual(rae.name, "Rae")
        XCTAssertEqual(rae.state, .working)
        XCTAssertEqual(rae.now, "Running")
        XCTAssertEqual(rae.colorIndex, 3, "tafs cloud's colour")
        XCTAssertEqual(rae.tools, ["Refund a payment", "list_charges"])
        let rule = try XCTUnwrap(rae.card.rules.first)
        XCTAssertEqual(rule.label, "Refund a payment")
        XCTAssertEqual(rule.perAction, Money(minor: 15000, currency: "USD", scale: 2), "$150.00 each, in KIFF's unit")
        XCTAssertEqual(rule.perWindow, Money(minor: 200000, currency: "USD", scale: 2))
        XCTAssertEqual(rule.countPerWindow, 10)
        XCTAssertEqual(rae.used["refund"], Money(minor: 21200, currency: "USD", scale: 2))
        XCTAssertEqual(rae.usageUnknown, ["list_charges"])
        XCTAssertEqual(rae.kiffCardIDs, ["m1"])
        XCTAssertNil(rae.card.expires, "no end date")
        XCTAssertEqual(home.tafs[0].cloudTaf?.id, "taf-1")

        let demo = KIFFMapping.taf(home.others[0], id: UUID(), owner: "You")
        XCTAssertEqual(demo.state, .idle)
        XCTAssertNil(home.others[0].cloudTaf, "an agent tafs doesn't run has no chat or schedule")
    }

    /// A taf that works from its chat stays one on its page (kiff/tafs#206 section 2): no Run
    /// now, Pause or "On its schedule".
    func testATafThatWorksFromItsChatKeepsItInItsCloudTaf() throws {
        let json = #"""
        {"id":"taf-2","name":"Refunds","job":"refunds","unscheduled":true,"kiff_agent":"refunds-k3f9","status":"active","next_run":null,"last_run":null,
         "color":1,"state":"working","waiting":0,
         "card":{"ids":["m1"],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]}}
        """#
        let taf = try KIFFClient.decoder.decode(TafsCloud.HomeTaf.self, from: Data(json.utf8))
        XCTAssertEqual(taf.cloudTaf?.worksFromChat, true)
    }
}

/// Each signed request carries its own time (kiff/tafs#245): two in the same second were the
/// same message, and planque or KIFF refused the second as already made.
final class SigningTimeTests: XCTestCase {
    func testTwoRequestsInTheSameMillisecondGetDifferentTimes() {
        let now = Date(timeIntervalSince1970: 2_000_000_000.123)
        let a = SigningTime.next(now: now)
        let b = SigningTime.next(now: now)
        XCTAssertNotEqual(a, b)
        XCTAssertLessThan(a, b, "later, so the order is kept")
    }

    func testItIsRFC3339WithMilliseconds() {
        let t = SigningTime.next(now: Date(timeIntervalSince1970: 2_100_000_000.5))
        XCTAssertNotNil(t.range(of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$"#, options: .regularExpression), t)
    }
}
