import XCTest
@testable import Tafs

/// Answers requests from canned responses, and records what was sent.
final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var responses: [String: (Int, String)] = [:]   // "METHOD path" → (status, body)
    nonisolated(unsafe) static var sent: [URLRequest] = []
    /// One request ("METHOD path") to hold until `release` is signalled; `arrived` says it came.
    nonisolated(unsafe) static var holdOnce: String?
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
        let (status, body) = Self.responses[key] ?? (404, #"{"error":"not stubbed"}"#)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
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

    func testHeldCallsDecodeAndMap() async throws {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, """
        {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"refund-agent","action":"refund_order","entity_id":"ord_4502",
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
        XCTAssertEqual(held.proposed.summary, "Refund order ord_4502")
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
        StubProtocol.responses["GET /v1/me/mandates?include=use"] = (401, #"{"error":"bad token"}"#)
        do {
            _ = try await client.cards()
            XCTFail("expected unauthorized")
        } catch let error as KIFFClient.KIFFError {
            XCTAssertEqual(error, .unauthorized)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testCardsBecomeOneTafPerHolder() async throws {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/mandates?include=use"] = (200, """
        {"mandates":[
          {"id":"m1","subject":"refund-agent","domain":"d","environment":"production","active":true,"on_exceed":"owner",
           "grants":[{"action":"refund_order","entity_states":[],"per_action_max":[{"quantity":"sum(amount_cents)","max":15000,"unit":"USD","scale":2}]}],
           "aggregates":[{"quantity":"sum(amount_cents)","limit":200000,"window":"1d","unit":"USD","scale":2}],
           "limits":[{"quantity":"sum(amount_cents)","window":"1d","limit":200000,"used":21200,"remaining":178800,"status":"ok","unit":"USD","scale":2}]},
          {"id":"m2","subject":"deploy-agent","domain":"d","environment":"production","active":true,
           "grants":[{"action":"deploy","entity_states":[]}],
           "aggregates":[{"quantity":"count","limit":10,"window":"1d"}]},
          {"id":"m3","subject":"old-agent","domain":"d","environment":"production","active":false,"revoked_at":"2026-10-01T00:00:00Z",
           "grants":[],"aggregates":[]}
        ]}
        """)
        var ids: [String: UUID] = [:]
        let tafs = KIFFMapping.tafs(from: try await client.cards(), ids: &ids, owner: "You")
        XCTAssertEqual(tafs.map(\.name), ["deploy-agent", "refund-agent"], "revoked Cards drop out")
        let refunds = try XCTUnwrap(tafs.first { $0.name == "refund-agent" })
        XCTAssertEqual(refunds.card.rules.first?.perAction, Money(minor: 15000, currency: "USD", scale: 2), "$150.00 each, in KIFF's unit")
        XCTAssertEqual(refunds.card.rules.first?.perWindow, Money(minor: 200000, currency: "USD", scale: 2))
        XCTAssertEqual(refunds.used["refund_order"], Money(minor: 21200, currency: "USD", scale: 2), "$212.00 used today, from the list")
        XCTAssertEqual(refunds.kiffCardIDs, ["m1"])
        let deploys = try XCTUnwrap(tafs.first { $0.name == "deploy-agent" })
        XCTAssertEqual(deploys.card.rules.first?.countPerWindow, 10, "a count stays a count, never money")
        XCTAssertTrue(deploys.used.isEmpty, "no use line, nothing shown as used")
        XCTAssertEqual(refunds.usageUnknown, [])
    }

    /// The meter pairs a total with its own use: a Card capping the same amount a day and
    /// a month shows the daily total with today's use, at its ceiling now (extra room in).
    func testUseMatchesTheTotalShown() async throws {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/mandates?include=use"] = (200, """
        {"mandates":[{"id":"m1","subject":"refund-agent","active":true,"on_exceed":"owner",
           "grants":[{"action":"refund_order","entity_states":[]}],
           "aggregates":[{"quantity":"sum(amount_cents)","limit":50000,"window":"1d","unit":"EUR","scale":2},
                         {"quantity":"sum(amount_cents)","limit":900000,"window":"30d","unit":"EUR","scale":2}],
           "limits":[{"quantity":"sum(amount_cents)","window":"30d","limit":900000,"used":400000,"status":"ok","unit":"EUR","scale":2},
                     {"quantity":"sum(amount_cents)","window":"1d","limit":60000,"used":21200,"status":"ok","unit":"EUR","scale":2}]}]}
        """)
        var ids: [String: UUID] = [:]
        let cards = try await client.cards()
        let taf = try XCTUnwrap(KIFFMapping.tafs(from: cards, ids: &ids, owner: "You").first)
        let rule = try XCTUnwrap(taf.card.rules.first)
        XCTAssertEqual(rule.window, .day)
        XCTAssertEqual(rule.perWindow, Money(minor: 60000, currency: "EUR", scale: 2), "today's ceiling with its extra room")
        XCTAssertEqual(taf.used["refund_order"], Money(minor: 21200, currency: "EUR", scale: 2), "today's use, not the month's")
    }

    /// Use KIFF couldn't read is unknown, never zero.
    func testUnreadableUseIsUnknown() async throws {
        let client = makeClient()
        StubProtocol.responses["GET /v1/me/mandates?include=use"] = (200, """
        {"mandates":[{"id":"m1","subject":"refund-agent","active":true,
           "grants":[{"action":"refund_order","entity_states":[]}],
           "aggregates":[{"quantity":"sum(amount_cents)","limit":50000,"window":"1d","unit":"EUR","scale":2}],
           "limits":[{"quantity":"sum(amount_cents)","window":"1d","limit":50000,"status":"unavailable"}]},
          {"id":"m2","subject":"old-api-agent","active":true,
           "grants":[{"action":"pay_invoice","entity_states":[]}],
           "aggregates":[{"quantity":"sum(amount_cents)","limit":50000,"window":"1d"}]}]}
        """)
        var ids: [String: UUID] = [:]
        let tafs = KIFFMapping.tafs(from: try await client.cards(), ids: &ids, owner: "You")
        let refunds = try XCTUnwrap(tafs.first { $0.name == "refund-agent" })
        XCTAssertNil(refunds.used["refund_order"])
        XCTAssertEqual(refunds.usageUnknown, ["refund_order"])
        XCTAssertEqual(refunds.card.rules.first?.perWindow, Money(minor: 50000, currency: "EUR", scale: 2))
        let old = try XCTUnwrap(tafs.first { $0.name == "old-api-agent" })
        XCTAssertEqual(old.usageUnknown, ["pay_invoice"], "no use line from KIFF is unknown too")
    }
}

final class SampleStoreTests: XCTestCase {
    @MainActor
    func testSampleSurvivesARestart() async {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sample-\(UUID()).json")
        let store = SampleStore(url: url)
        let first = AppModel(signer: StubSigner(), store: store)
        await first.startSample()
        XCTAssertFalse(first.tafs.isEmpty)

        let second = AppModel(signer: StubSigner(), store: store)
        await second.restore()
        XCTAssertEqual(second.mode, .sample)
        XCTAssertEqual(second.tafs.map(\.id), first.tafs.map(\.id))
        XCTAssertEqual(second.held.count, first.held.count)
        store.clear()
    }
}
