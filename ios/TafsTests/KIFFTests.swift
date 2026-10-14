import XCTest
@testable import Tafs

/// Answers requests from canned responses, and records what was sent.
final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var responses: [String: (Int, String)] = [:]   // "METHOD path" → (status, body)
    nonisolated(unsafe) static var sent: [URLRequest] = []

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
        let (status, body) = Self.responses["\(request.httpMethod ?? "GET") \(path)"] ?? (404, #"{"error":"not stubbed"}"#)
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
        XCTAssertEqual(held.proposed.amount, .usd(480))
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
        StubProtocol.responses["GET /v1/me/mandates"] = (401, #"{"error":"bad token"}"#)
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
        StubProtocol.responses["GET /v1/me/mandates"] = (200, """
        {"mandates":[
          {"id":"m1","subject":"refund-agent","domain":"d","environment":"production","active":true,"on_exceed":"owner",
           "grants":[{"action":"refund_order","entity_states":[],"per_action_max":[{"quantity":"sum(amount_cents)","max":15000}]}],
           "aggregates":[{"quantity":"sum(amount_cents)","limit":200000,"window":"1d"}]},
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
        XCTAssertEqual(refunds.card.rules.first?.perAction, .usd(150))
        XCTAssertEqual(refunds.card.rules.first?.perWindow, .usd(2_000))
        XCTAssertEqual(refunds.kiffCardIDs, ["m1"])
        let deploys = try XCTUnwrap(tafs.first { $0.name == "deploy-agent" })
        XCTAssertEqual(deploys.card.rules.first?.countPerWindow, 10, "a count stays a count, never money")
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
