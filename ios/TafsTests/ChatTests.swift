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

    /// A model signed in with KIFF (as sign-in ends, through useSession) whose one taf is
    /// Rae, run by tafs cloud as depl_1.
    private func kiffModel(cards: [String] = [raeCard]) async -> (AppModel, UUID) {
        let session = stubbedSession()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        stubKIFF(cards: cards)
        await model.useSession("kiff_dev_x")
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

    /// A message that isn't sent stays in the thread, marked not sent with why, and
    /// Retry sends it (#64).
    func testAFloodedTafKeepsTheMessageWithRetry() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (429, #"{"error":"too many"}"#)
        let sent = await model.sendChat("hello", to: id)
        XCTAssertFalse(sent)
        XCTAssertFalse(model.chats[id]?.contains { $0.session == "pending" } ?? false)
        let unsent = try XCTUnwrap(model.chatState(id).unsent.first)
        XCTAssertEqual(unsent.text, "hello")
        XCTAssertTrue(unsent.why.contains("a lot of messages"))
        XCTAssertNil(model.chatErrors[id], "the message says it, not a line under the thread")

        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        StubProtocol.sent = []
        await model.retryChat(unsent, to: id)
        XCTAssertTrue(model.chatState(id).unsent.isEmpty)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 1)
    }

    /// The taf's answer streams (#64): tafs cloud's stream route takes the message, the
    /// words arrive as written, and the end reloads the thread. Nothing goes through
    /// the plain route.
    func testATafsAnswerStreams() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages/stream"] = (200, """
        event: session
        data: {"session":"sesn_9","new_session":false}

        event: text
        data: {"text":"Skipping 4410."}

        event: activity
        data: {"tool":"memory"}

        event: end
        data: {"status":"COMPLETED","stop":"answered"}


        """)
        StubProtocol.sent = []
        let sent = await model.sendChat("Skip order 4410", to: id)
        XCTAssertTrue(sent)
        XCTAssertTrue(model.chatStreamWorks)
        await model.chatStreams[id]?.value
        XCTAssertNil(model.chatState(id).live, "the stream ended")
        XCTAssertFalse(model.chatThinking(id))
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0, "not sent twice")
        XCTAssertGreaterThanOrEqual(count("GET", "/v1/tafs/depl_1/conversation"), 2, "at the tool call and at the end")
        XCTAssertEqual(AppModel.tafActivityWords("memory"), "Reading its notes…")
        XCTAssertEqual(AppModel.tafActivityWords("refund_order"), "Asking KIFF to run refund order…")
    }

    /// What the owner writes while the taf works waits, and is sent once it's done, as
    /// one message; Stop names the session at work.
    func testWhatsWrittenWhileTheTafWorksIsSentWhenItsDone() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        await model.loadChat(id)
        XCTAssertTrue(model.chatThinking(id))
        StubProtocol.sent = []
        let first = await model.submitChat("Skip 4410", to: id)
        let second = await model.submitChat("and 4411", to: id)
        XCTAssertTrue(first)
        XCTAssertTrue(second)
        XCTAssertEqual(model.chatState(id).queued, "Skip 4410\n\nand 4411")
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0)

        StubProtocol.responses["POST /v1/tafs/depl_1/stop"] = (200, #"{"stopped":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":null}"#)
        await model.stopChat(id)
        let stop = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/tafs/depl_1/stop" })
        XCTAssertEqual(try JSONSerialization.jsonObject(with: stop.httpBody ?? Data()) as? [String: String], ["session": "sesn_9"])
        XCTAssertEqual(model.chatState(id).ended, nil, "the queued message went out, so the stop mark went with it")
        XCTAssertNil(model.chatState(id).queued)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 1, "what waited is sent once the taf is idle")
    }

    /// A run that fails part-way offers Retry, which sends its message again; a stopped
    /// one is marked stopped.
    func testARunThatFailsPartWayOffersRetry() async throws {
        final class Sent { var texts: [String] = [] }
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        let sent = Sent()
        var ending = "ERROR"
        model.streamOpener = { _, text in
            sent.texts.append(text)
            let status = ending
            return AsyncThrowingStream { c in
                c.yield(.session("sesn_9", newSession: false))
                c.yield(.end(status: status, stop: status.lowercased()))
                c.finish()
            }
        }
        await model.sendChat("Work the queue", to: id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(model.chatState(id).ended, .failed(retry: "Work the queue"))
        await model.retryChatRun(id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(sent.texts, ["Work the queue", "Work the queue"])
        ending = "CANCELLED"
        await model.sendChat("again", to: id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(model.chatState(id).ended, .stopped)
    }

    /// A held call shows where it was held, between the messages around it, and is the
    /// one the chat says needs the owner (#64).
    func testAHeldCallShowsWhereItWasHeld() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, """
        {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"rae-k3f9","action":"stripe_refund","entity_id":"ord_4502",
          "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
          "parameters":{"amount_cents":48000},"created_at":"2026-10-14T09:02:00Z","expires_at":"2099-10-14T10:51:00Z",
          "accounting_version":2}]}
        """)
        await model.refresh()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Refund 4502","session":"sesn_9"},
                     {"at":"2026-10-14T09:03:00Z","from":"taf","kind":"message","text":"It's over my Card: it waits for you.","session":"sesn_9"}]}
        """)
        await model.loadChat(id)
        let held = try XCTUnwrap(model.heldCalls(for: id).first)
        let messages = try XCTUnwrap(model.chats[id])
        let order = model.chatItems(id).map(\.id)
        let asked = try XCTUnwrap(order.firstIndex(of: "m|" + messages[0].id))
        let card = try XCTUnwrap(order.firstIndex(of: "h|" + held.id.uuidString))
        let answer = try XCTUnwrap(order.firstIndex(of: "m|" + messages[1].id))
        XCTAssertTrue(asked < card && card < answer, "between the owner's message and the taf's: \(order)")
        XCTAssertEqual(model.chatWaiting(id)?.id, held.id)
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
        // Signed out without a request (the stub holds its one loading thread), so the
        // test doesn't wait out the logout's timeout.
        model.kiff.token = nil
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

    // MARK: Memory

    func testASampleTafRemembersAndForgets() async throws {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        let bills = try XCTUnwrap(model.tafs.first { $0.name == "Bill pay" })
        await model.loadMemory(bills.id)
        let notes = try XCTUnwrap(model.memories[bills.id])
        XCTAssertEqual(notes.count, 3)
        XCTAssertEqual(notes.map(\.updatedAt), notes.map(\.updatedAt).sorted(by: >), "newest first")

        let forgotten = await model.forget(notes[1], from: bills.id)
        XCTAssertTrue(forgotten)
        XCTAssertEqual(model.memories[bills.id]?.map(\.id), [notes[0].id, notes[2].id])
    }

    func testANotesTitleComesFromItsPath() {
        let note = TafsCloud.Note(id: "mem_1", path: "/customers/acme-co.md", content: "x", updatedAt: Date())
        XCTAssertEqual(note.title, "customers · acme co")
    }

    func testLoadsWhatATafRemembersFromTafsCloud() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/memory"] = (200, #"{"notes":[{"id":"mem_2","path":"/customers/acme.md","content":"Acme buys wholesale: ask first.","updated_at":"2026-10-14T11:00:00.123456Z"},{"id":"mem_1","path":"/refunds.md","content":"Offer store credit first.","updated_at":"2026-10-13T09:00:00Z"}]}"#)
        await model.loadMemory(id)
        XCTAssertEqual(model.memories[id]?.map(\.id), ["mem_2", "mem_1"])
        XCTAssertEqual(model.memories[id]?.first?.content, "Acme buys wholesale: ask first.")
        XCTAssertNil(model.memoryErrors[id])
    }

    func testForgettingANoteDeletesItOnTafsCloud() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/memory"] = (200, #"{"notes":[{"id":"mem_2","path":"/a.md","content":"A","updated_at":"2026-10-14T11:00:00Z"},{"id":"mem_1","path":"/b.md","content":"B","updated_at":"2026-10-13T09:00:00Z"}]}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/memory/mem_2"] = (200, #"{"forgotten":"mem_2"}"#)
        await model.loadMemory(id)
        let note = try XCTUnwrap(model.memories[id]?.first)
        let forgotten = await model.forget(note, from: id)
        XCTAssertTrue(forgotten)
        XCTAssertEqual(count("DELETE", "/v1/tafs/depl_1/memory/mem_2"), 1)
        XCTAssertEqual(model.memories[id]?.map(\.id), ["mem_1"])
    }

    func testANoteTafsCloudWontForgetComesBack() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/memory"] = (200, #"{"notes":[{"id":"mem_2","path":"/a.md","content":"A","updated_at":"2026-10-14T11:00:00Z"}]}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/memory/mem_2"] = (403, #"{"error":"your role on this KIFF account cannot manage Cards"}"#)
        await model.loadMemory(id)
        let note = try XCTUnwrap(model.memories[id]?.first)
        let forgotten = await model.forget(note, from: id)
        XCTAssertFalse(forgotten)
        XCTAssertEqual(model.memories[id]?.map(\.id), ["mem_2"])
        XCTAssertTrue(model.memoryErrors[id]?.contains("cannot manage Cards") ?? false)
    }

    func testARefusedForgetDoesNotBringBackANoteForgottenMeanwhile() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/memory"] = (200, #"{"notes":[{"id":"mem_2","path":"/a.md","content":"A","updated_at":"2026-10-14T11:00:00Z"},{"id":"mem_1","path":"/b.md","content":"B","updated_at":"2026-10-13T09:00:00Z"}]}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/memory/mem_2"] = (500, #"{"error":"try again"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/memory/mem_1"] = (200, #"{"forgotten":"mem_1"}"#)
        await model.loadMemory(id)
        let notes = try XCTUnwrap(model.memories[id])
        async let refused = model.forget(notes[0], from: id)
        async let done = model.forget(notes[1], from: id)
        let (r, d) = await (refused, done)
        XCTAssertFalse(r)
        XCTAssertTrue(d)
        XCTAssertEqual(model.memories[id]?.map(\.id), ["mem_2"])
    }
}
