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
        // tafs cloud passes planque's grant on whole: its id is what's approved.
        let whole = try JSONDecoder().decode(TafsCloud.ConnectStart.self, from: Data(#"{"grant":{"id":"g_3","request":{"profile":{}}},"needs_key":true}"#.utf8))
        XCTAssertEqual(whole, TafsCloud.ConnectStart(grant: "g_3", needsKey: true))
    }

    /// Connect apps comes a page at a time: the next with the provider and cursor the last gave,
    /// a cursor's + kept as a value.
    func testTheNextPageAsksWithItsCursor() async throws {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/connect/apps?q=mail"] = (200, #"{"apps":[{"entry":"connector:composio:gmail","vendor":"Gmail","title":"Gmail","how":"oauth"}],"by_key":true,"provider":"composio","next_cursor":"a+b/c"}"#)
        let first = try await cloud.connectApps("mail")
        XCTAssertEqual(first.provider, "composio")
        XCTAssertEqual(first.nextCursor, "a+b/c")
        _ = try? await cloud.connectApps("mail", provider: first.provider, cursor: first.nextCursor) // what it asks is what's checked
        let url = try XCTUnwrap(StubProtocol.sent.last?.url)
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "cursor" }?.value, "a+b/c")
        XCTAssertEqual(items.first { $0.name == "provider" }?.value, "composio")
        XCTAssertEqual(items.first { $0.name == "q" }?.value, "mail")
        XCTAssertTrue(url.absoluteString.contains("a%2Bb"), url.absoluteString)
    }

    /// A Slack door listens through the taf's Slack bot connection, known by planque's mark,
    /// and the phone signs only the account, the event and the channel: planque builds the rest.
    func testASlackDoorSubscribesOnlyTheApprovedChannel() async throws {
        let grantJSON = #"{"id":"g_9","status":"connected","text":"t","digest":"d","account":"T0123","events":[],"request":{"server":"https://composio.test","agent":"desk","tools":[],"connector":{"provider":"composio","toolkit":"slackbot"}}}"#
        let grant = try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(grantJSON.utf8))
        XCTAssertTrue(grant.slackBot)
        var marked = grant
        marked.entry = "connector:composio:notion"
        XCTAssertFalse(marked.slackBot, "planque's entry decides, when it's there")

        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: URLSession(configuration: config))
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_9/triggers"] = (201, #"{"id":"ti_1","account":"T0123","channels":["C0REFUNDS"]}"#)
        let trigger = try await planque.subscribe(grantID: grant.id, account: "T0123", slug: PlanqueClient.slackMessageTrigger, channels: ["C0REFUNDS"])
        XCTAssertEqual(trigger, "ti_1")
        let sent = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/accounts/t_01/grants/g_9/triggers" })
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(sent.httpBody)) as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["account", "slug", "channels"], "no config: planque builds it from the channels")
        XCTAssertEqual(body["channels"] as? [String], ["C0REFUNDS"])
        XCTAssertNotNil(sent.value(forHTTPHeaderField: "Planque-Device-Signature"))
    }

    /// A connection starts for one taf: its tafs cloud id goes in the body.
    func testStartNamesItsTaf() async throws {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["POST /v1/connect/apps/holded@1"] = (200, #"{"grant":{"id":"g_4"}}"#)
        let start = try await cloud.startConnect("holded@1", taf: "taf_01")
        XCTAssertEqual(start.grant, "g_4")
        let sent = try XCTUnwrap(StubProtocol.sent.last)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(sent.httpBody)) as? [String: Any])
        XCTAssertEqual(json["taf"] as? String, "taf_01")
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
