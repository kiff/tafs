import CryptoKit
import XCTest
@testable import Tafs

/// Starting a connection from Connect apps, and giving an API-key vendor its key.
@MainActor
final class ConnectAppsTests: XCTestCase {
    func testStartAnswersAGrantToApprove() throws {
        let oauth = try JSONDecoder().decode(TafsCloud.ConnectStart.self, from: Data(#"{"grant":"g_1"}"#.utf8))
        XCTAssertEqual(oauth, TafsCloud.ConnectStart(grant: "g_1", needsKey: false))
        let key = try JSONDecoder().decode(TafsCloud.ConnectStart.self, from: Data(#"{"grant":"g_2","needs_key":true}"#.utf8))
        XCTAssertTrue(key.needsKey)
    }

    /// The key goes in its header. What the device signs is the grant's digest and the key's
    /// SHA-256, never the key, and nothing is sent for a grant whose digest doesn't match.
    func testAKeyIsGivenByItsHashInWhatsSigned() async throws {
        let request = #"{"agent":"desk","grant":"g_2","server":"https://desk.example","tenant":"t_01","tools":[],"v":"planque.grant.v1"}"#
        let digest = SHA256.hash(data: try JCS.canonical(Data(request.utf8))).map { String(format: "%02x", $0) }.joined()
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: URLSession(configuration: config))
        planque.tenant = "t_01"
        var signed: [String] = []
        planque.signer = { signed.append(String(decoding: $0, as: UTF8.self)); return "sig" }
        planque.deviceID = "dev_phone01"
        let grantJSON = #"{"id":"g_2","status":"pending","text":"t","digest":"\#(digest)","request":\#(request),"events":[]}"#
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(grantJSON)]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_2/key"] = (200, grantJSON.replacingOccurrences(of: "pending", with: "connected"))

        let grants = try await planque.grants()
        let grant = try XCTUnwrap(grants.first)
        let key = "desk_live_secret_123"
        let after = try await planque.giveKey(grant, key: " \(key)\n")
        XCTAssertEqual(after.status, "connected")

        let sent = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/accounts/t_01/grants/g_2/key" })
        XCTAssertEqual(sent.value(forHTTPHeaderField: PlanqueClient.keyHeader), key, "trimmed, in its header")
        let body = String(decoding: try XCTUnwrap(sent.httpBody), as: UTF8.self)
        XCTAssertFalse(body.contains(key), "the key is never in the body")
        XCTAssertFalse(signed.joined().contains(key), "nor in what's signed")
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])
        XCTAssertEqual(json["digest"] as? String, digest)
        XCTAssertEqual(json["key_sha256"] as? String, SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined())

        var other = grant
        other.digestMatches = false
        StubProtocol.sent = []
        do { _ = try await planque.giveKey(other, key: key); XCTFail("a key given for a request that doesn't match") } catch {
            XCTAssertEqual(error as? PlanqueClient.PlanqueError, .digestMismatch)
        }
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }
}
