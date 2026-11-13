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

    /// The read carries this phone's signed grants read when it has one, as Setup's does, so
    /// tafs cloud can list each removed taf's connections; without one, no header is sent.
    func testTheReadSendsTheSignedGrantsRead() async throws {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/connect/apps"] = (200, #"{"apps":[],"removed_tafs":[{"agent":"gone-1","grants":[{"grant":"g2","used_by":[]}]}]}"#)
        let page = try await cloud.connectApps(proof: "t=1,v1=sig,d=dev_phone01")
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"), "t=1,v1=sig,d=dev_phone01")
        XCTAssertEqual(page.removedTafs.first?.grants?.first?.grant, "g2")
        _ = try await cloud.connectApps()
        XCTAssertNil(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"))
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
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_9/triggers"] = (201, #"{"trigger":"ti_1","account":"T0123","channels":["C0REFUNDS"]}"#)
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

    private func signedInModel() async -> AppModel {
        StubProtocol.responses = [:]; StubProtocol.thenRespond = [:]; StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
            kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
            cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private static let appsPage = #"{"apps":[{"entry":"connector:composio:gmail","vendor":"Gmail","title":"Gmail","how":"oauth","connected":true}],"by_key":true}"#

    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    func testAccountResetClearsAppsAndTheOpenApproval() async {
        let model = await signedInModel()
        StubProtocol.responses["GET /v1/connect/apps"] = (200, Self.appsPage)
        await model.loadConnectApps()
        XCTAssertNotNil(model.connectApps)
        model.connectStart = TafsCloud.ConnectStart(grant: "g_1", needsKey: false)
        model.forgetSession()
        XCTAssertNil(model.connectApps)
        XCTAssertNil(model.connectStart)
    }

    func testAnAppsResponseAfterAccountResetIsDropped() async {
        let model = await signedInModel()
        StubProtocol.responses["GET /v1/connect/apps"] = (200, Self.appsPage)
        StubProtocol.holdOnce = "GET /v1/connect/apps"
        let load = Task { await model.loadConnectApps() }
        await untilHeldRequestArrives()
        model.forgetSession()
        StubProtocol.release.signal()
        await load.value
        XCTAssertNil(model.connectApps)
    }

    func testACancelledSearchDoesNotReplaceThePage() async {
        let model = await signedInModel()
        StubProtocol.responses["GET /v1/connect/apps?q=mail"] = (200, Self.appsPage)
        StubProtocol.holdOnce = "GET /v1/connect/apps?q=mail"
        let load = Task { await model.loadConnectApps("mail") }
        await untilHeldRequestArrives()
        load.cancel()
        StubProtocol.release.signal()
        await load.value
        XCTAssertNil(model.connectApps)
        model.forgetSession()
    }

    func testATriggerBackedDoorCannotBeRemovedWithoutASigningSession() async {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: URLSession(configuration: config))
        let model = AppModel(signer: StubSigner(), cloud: cloud)
        model.forgetSession()
        var door = DoorsModel.sample.doors[0]
        door.grant = "g_1"; door.trigger = "ti_1"
        let doors = DoorsModel(cloud: cloud, tafID: "taf_01")
        StubProtocol.sent = []
        await model.removeSlackDoor(door, doors: doors)
        XCTAssertFalse(StubProtocol.sent.contains { $0.httpMethod == "DELETE" })
        XCTAssertTrue(model.lastError?.contains("subscription must be stopped") == true)
    }


    /// Disconnect names the one taf it ends, says other tafs' connections stay, and the Face ID
    /// prompt asks the same; once its taf was removed, it says so (the owner's rule).
    func testDisconnectNamesTheOneTafItEnds() {
        XCTAssertEqual(DisconnectWords.title("Stripe", taf: "Invoice reminders"), "Remove access to Stripe for Invoice reminders?")
        XCTAssertEqual(DisconnectWords.title("Stripe", taf: nil), "Remove access to Stripe?")
        let message = DisconnectWords.message("Stripe", taf: "Invoice reminders")
        XCTAssertTrue(message.hasPrefix("Invoice reminders' Stripe tools stop working."), message)
        XCTAssertTrue(message.contains("Other tafs that use Stripe keep their access."), message)
        XCTAssertTrue(DisconnectWords.message("Stripe", taf: nil).hasPrefix("No taf uses Stripe now."))
        // Another taf whose Card draws on these tools stops too, and is named (qa-kiff on tafs#546).
        XCTAssertEqual(DisconnectWords.title("Stripe", taf: "Refunds", alsoStops: ["Chargebacks"]), "Remove access to Stripe for Refunds? Chargebacks stops too.")
        let shared = DisconnectWords.message("Stripe", taf: "Refunds", alsoStops: ["Chargebacks"])
        XCTAssertTrue(shared.contains("Chargebacks' Card also uses these tools, so it stops too."), shared)
        XCTAssertEqual([Words.possessive("Coach"), Words.possessive("Refunds")], ["Coach's", "Refunds'"], "by the lines' language's rule")
        XCTAssertEqual(DisconnectWords.footer, "Removing access takes its tools from that taf only, and from any taf whose Card uses them. Other tafs keep theirs.")
    }
}
