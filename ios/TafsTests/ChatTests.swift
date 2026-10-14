import XCTest
@testable import Tafs

@MainActor
final class ChatTests: XCTestCase {
    private func stubbedSession() -> URLSession {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private static let raeCard = #"{"id":"rae-k3f9-stripe_refund","subject":"rae-k3f9","grants":[],"aggregates":[],"active":true}"#

    /// A model signed in with KIFF (through the Keychain, as on a phone) whose one taf is
    /// Rae, run by tafs cloud as depl_1.
    private func kiffModel(cards: [String] = [raeCard]) async -> (AppModel, UUID) {
        let session = stubbedSession()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        stubKIFF(cards: cards)
        Keychain.save("kiff_dev_x", for: AppModel.tokenAccount)
        await model.restore()
        XCTAssertEqual(model.mode, .kiff)
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id, "Rae is named by tafs cloud"))
    }

    private func stubKIFF(cards: [String]) {
        let list = #"{"mandates":["# + cards.joined(separator: ",") + "]}"
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, list) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
    }

    private func count(_ method: String, _ path: String) -> Int {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }.count
    }

    // MARK: Sample mode

    func testASampleTafSaysHelloAndAnswers() async throws {
        let model = AppModel(signer: StubSigner())
        model.sampleReplyDelay = 0
        await model.startSample()
        let bills = try XCTUnwrap(model.tafs.first { $0.name == "Bill pay" })
        XCTAssertTrue(model.canChat(bills.id))

        await model.loadChat(bills.id)
        XCTAssertEqual(model.chats[bills.id]?.count, 1)
        XCTAssertEqual(model.chats[bills.id]?.first?.from, .taf)

        let sent = await model.sendChat("  What did you do?  ", to: bills.id)
        XCTAssertTrue(sent)
        let thread = try XCTUnwrap(model.chats[bills.id])
        XCTAssertEqual(thread.map(\.from), [.taf, .owner, .taf])
        XCTAssertEqual(thread[1].text, "What did you do?")
        XCTAssertFalse(model.awaitingReply(bills.id))
    }

    func testTellingASampleTafToStopPausesIt() async throws {
        let model = AppModel(signer: StubSigner())
        model.sampleReplyDelay = 0
        await model.startSample()
        let access = try XCTUnwrap(model.tafs.first { $0.name == "Access" })
        await model.sendChat("Please stop for today", to: access.id)
        XCTAssertEqual(model.taf(access.id)?.state, .paused)
        await model.sendChat("go", to: access.id)
        XCTAssertNotEqual(model.taf(access.id)?.state, .paused)
    }

    func testAskingForMoreRoomIsAnsweredWithTheCard() async throws {
        let model = AppModel(signer: StubSigner())
        model.sampleReplyDelay = 0
        await model.startSample()
        let refunds = try XCTUnwrap(model.tafs.first { $0.name == "Refunds" })
        XCTAssertEqual(model.chats[refunds.id]?.count, 3, "the sample opens Refunds with a short exchange")
        await model.sendChat("Raise your limit to $5,000", to: refunds.id)
        XCTAssertTrue(model.chats[refunds.id]?.last?.text.contains("only you can") ?? false)
    }

    func testEmptyAndOverlongMessagesAreNotSent() async throws {
        let model = AppModel(signer: StubSigner())
        model.sampleReplyDelay = 0
        await model.startSample()
        let id = try XCTUnwrap(model.tafs.first?.id)
        await model.loadChat(id)
        let before = model.chats[id]?.count
        let empty = await model.sendChat("   \n ", to: id)
        let long = await model.sendChat(String(repeating: "a", count: AppModel.chatLimit + 1), to: id)
        XCTAssertFalse(empty)
        XCTAssertFalse(long)
        XCTAssertEqual(model.chats[id]?.count, before)
        XCTAssertNotNil(model.chatErrors[id])
    }

    // MARK: On KIFF, through tafs cloud

    func testSendsThroughTafsCloudAndReloadsTheThread() async throws {
        let (model, id) = await kiffModel()
        XCTAssertTrue(model.canChat(id))
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[{"at":"2026-10-14T09:00:00Z","from":"owner","kind":"kickoff","text":"Work the refund queue","session":"sesn_9"},{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Skip order 4410","session":"sesn_9"}]}"#)

        let sent = await model.sendChat("Skip order 4410", to: id)
        XCTAssertTrue(sent)
        let post = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/depl_1/messages" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: post.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body, ["text": "Skip order 4410"])
        XCTAssertEqual(post.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x")
        XCTAssertEqual(model.chats[id]?.map(\.kind), [.kickoff, .message])
        XCTAssertTrue(model.awaitingReply(id), "the taf hasn't answered yet")
    }

    func testAFloodedTafSaysToWait() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (429, #"{"error":"too many"}"#)
        let sent = await model.sendChat("hello", to: id)
        XCTAssertFalse(sent)
        XCTAssertTrue(model.chats[id]?.isEmpty ?? true, "the unsent message is taken back")
        XCTAssertTrue(model.chatErrors[id]?.contains("a lot of messages") ?? false)
    }

    func testOneMessageAtATimePerTaf() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        let first = Task { await model.sendChat("one", to: id) }
        let second = Task { await model.sendChat("two", to: id) }
        let (a, b) = (await first.value, await second.value)
        XCTAssertEqual([a, b].filter { $0 }.count, 1)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 1)
    }

    /// Waits, off the main actor, until the held request has reached the stub.
    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    func testAThreadLoadingWhenTheAccountChangesIsDropped() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[{"at":"2026-10-14T09:00:00Z","from":"taf","kind":"message","text":"Account A's secret","session":"sesn_a"}]}"#)
        StubProtocol.holdOnce = "GET /v1/tafs/depl_1/conversation"
        let load = Task { await model.loadChat(id) }
        await untilHeldRequestArrives()
        await model.signOut()
        StubProtocol.release.signal()
        await load.value
        XCTAssertTrue(model.chats.isEmpty, "account A's thread doesn't come back after sign-out")
        XCTAssertTrue(model.chatErrors.isEmpty)
    }

    func testPollingStopsWhenTheBudgetForAnUnansweredMessageRunsOut() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[{"at":"2026-10-14T09:00:00Z","from":"owner","kind":"message","text":"Anyone there?","session":"sesn_1"}]}"#)
        model.chatPollInterval = 2_000_000   // 2 ms
        model.chatPollBudget = 3
        let follow = Task { await model.followChat(id) }
        try await Task.sleep(nanoseconds: 300_000_000)
        follow.cancel()
        await follow.value
        XCTAssertTrue(model.awaitingReply(id))
        XCTAssertEqual(count("GET", "/v1/tafs/depl_1/conversation"), 1 + 3, "the first load, then three reloads, then none")
    }

    func testATafNotOnTafsCloudCannotChat() async throws {
        let maxCard = #"{"id":"max-card","subject":"max","grants":[],"aggregates":[],"active":true}"#
        let (model, raeID) = await kiffModel(cards: [Self.raeCard, maxCard])
        XCTAssertTrue(model.canChat(raeID))
        let max = try XCTUnwrap(model.tafs.first { $0.id != raeID })
        XCTAssertFalse(model.canChat(max.id), "an agent holding Cards that tafs cloud doesn't run has no thread")
    }
}
