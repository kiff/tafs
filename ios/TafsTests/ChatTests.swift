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

    private static let raeCard = HomeJSON.rae

    /// A model signed in with KIFF (as sign-in ends, through useSession) whose one taf is
    /// Rae, run by tafs cloud as depl_1.
    private func kiffModel(cards: [String] = [raeCard], others: [String] = []) async -> (AppModel, UUID) {
        let session = stubbedSession()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        stubKIFF(cards: cards, others: others)
        await model.useSession("kiff_dev_x")
        XCTAssertEqual(model.mode, .kiff)
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id, "Rae is named by tafs cloud"))
    }

    /// tafs cloud's list with `cards` as its tafs and `others` as other agents, and no held calls.
    private func stubKIFF(cards: [String], others: [String] = []) {
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list(cards, others: others))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
    }

    private func count(_ method: String, _ path: String) -> Int {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }.count
    }

    // MARK: Sample mode

    /// What the owner types to Coach is the model's, not the chat view's (kiff/tafs#245, item 3):
    /// closing Coach mode, opening it again or starting another conversation keeps it. Only
    /// Send or the owner clears it, and signing out leaves nothing of it.
    func testCoachsDraftOutlivesItsViewUntilSignOut() async throws {
        let (model, _) = await kiffModel()
        model.setupDraft = "Change Refunds to ask before refunding"
        model.newSetupChat()
        XCTAssertEqual(model.setupDraft, "Change Refunds to ask before refunding")
        await model.signOut()
        XCTAssertEqual(model.setupDraft, "")
    }

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
        // tafs cloud's copy is written about when it was sent: the sent message gives way to it
        // (an older message with the same words wouldn't, kiff/tafs#138, item 48).
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let now = iso.string(from: Date())
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:00:00Z","from":"owner","kind":"kickoff","text":"Work the refund queue","session":"sesn_9"},{"at":"\(now)","from":"owner","kind":"message","text":"Skip order 4410","session":"sesn_9"}]}
        """)

        let sent = await model.sendChat("Skip order 4410", to: id)
        XCTAssertTrue(sent)
        let post = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/depl_1/messages" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: post.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body, ["text": "Skip order 4410"])
        XCTAssertEqual(post.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x")
        XCTAssertEqual(model.chats[id]?.map(\.kind), [.kickoff, .message])
        XCTAssertTrue(model.awaitingReply(id), "the taf hasn't answered yet")
    }

    /// tafs cloud's two lines about a run are read as written: where its result went (`delivered`)
    /// and what it did, counted from the record (`counted`). A blank one is none, and with none
    /// sent nothing is drawn: the phone counts nothing itself.
    func testARunsLinesAreReadAsTafsCloudWroteThemAndNoneIsNone() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[
          {"id":"run_1.4","at":"2026-11-05T09:00:00Z","from":"taf","kind":"message","text":"Three refunds made.","session":"s1",
           "delivered":"Sent to Slack · #ops.","counted":"  From the record: 3 done · 1 refused\\n"},
          {"id":"run_2.4","at":"2026-11-05T10:00:00Z","from":"taf","kind":"message","text":"Nothing due today.","session":"s2","delivered":"","counted":" "},
          {"id":"run_3.4","at":"2026-11-05T11:00:00Z","from":"taf","kind":"message","text":"Done.","session":"s3"},
          {"id":"run_4.4","at":"2026-11-05T12:00:00Z","from":"taf","kind":"message","text":"Done again.","session":"s4","delivered":7,"counted":["5 done"]}]}
        """)
        await model.loadChat(id)
        let messages = try XCTUnwrap(model.chats[id])
        XCTAssertEqual(messages.count, 4, "a line the app can't read never drops its message")
        XCTAssertEqual(messages[0].delivered, "Sent to Slack · #ops.")
        XCTAssertEqual(messages[0].counted, "From the record: 3 done · 1 refused", "as written, without the space around it")
        // Above the taf's words, where the result went; under them, what the run did.
        XCTAssertEqual(messages[0].runLines.above, "Sent to Slack · #ops.")
        XCTAssertEqual(messages[0].runLines.under, "From the record: 3 done · 1 refused")
        XCTAssertNil(messages[1].delivered, "a blank line is no line")
        XCTAssertNil(messages[1].counted)
        XCTAssertNil(messages[2].runLines.above, "a tafs cloud that sends none: no line")
        XCTAssertNil(messages[2].runLines.under, "and nothing counted here")
        XCTAssertNil(messages[3].delivered, "not words: no line")
        XCTAssertNil(messages[3].counted)
        XCTAssertEqual(messages[3].text, "Done again.")
    }

    /// A connection the server refused: tafs cloud's lines on the message, read as written. A
    /// blank reason is no line; none sent, or all blank, is nothing drawn (as Android's RefusedConnectionTest).
    func testARefusedConnectionsLinesAreReadAsTafsCloudWroteThem() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[
          {"id":"run_1.2","at":"2026-11-13T03:00:00Z","from":"taf","kind":"message","text":"Shippo's sign-in isn't set up here.","session":"s1",
           "refused":{"words":"Coach couldn't connect Shippo yet","why":"Why: managed sign-in isn't set up for this app",
             "next":"Next: ask Coach to connect Shippo with its key instead, or to use another app."}},
          {"id":"run_2.2","at":"2026-11-13T03:05:00Z","from":"taf","kind":"message","text":"Tried again.","session":"s2",
           "refused":{"words":"Coach couldn't connect the app yet","why":" ","next":"Next: ask Coach to connect it with its key instead, or to use another app."}},
          {"id":"run_3.2","at":"2026-11-13T03:10:00Z","from":"taf","kind":"message","text":"Hm.","session":"s3","refused":{}},
          {"id":"run_4.2","at":"2026-11-13T03:15:00Z","from":"taf","kind":"message","text":"Connected.","session":"s4"}]}
        """)
        await model.loadChat(id)
        let messages = try XCTUnwrap(model.chats[id])
        XCTAssertEqual(messages[0].refusedLines, TafsCloud.ChatMessage.Refused(words: "Coach couldn't connect Shippo yet",
            why: "Why: managed sign-in isn't set up for this app", next: "Next: ask Coach to connect Shippo with its key instead, or to use another app."))
        XCTAssertNil(messages[1].refused?.why, "a blank reason is no line")
        XCTAssertEqual(messages[1].refused?.lines, ["Coach couldn't connect the app yet", "Next: ask Coach to connect it with its key instead, or to use another app."])
        XCTAssertNil(messages[2].refused, "all blank: nothing drawn")
        XCTAssertNil(messages[3].refused, "none from tafs cloud: nothing drawn")
        let owner = TafsCloud.ChatMessage(at: Date(), from: .owner, text: "Connect Shippo", session: "s1", refused: messages[0].refused)
        XCTAssertNil(owner.refusedLines, "an owner's message draws none")
    }

    /// The lines are a taf's: an owner's message or a run's kickoff draws none, whatever it carries.
    func testOnlyATafsMessageDrawsARunsLines() {
        let at = Date(timeIntervalSince1970: 1_700_000_000)
        let taf = TafsCloud.ChatMessage(at: at, from: .taf, text: "Done.", session: "s1", delivered: "Sent to Slack.", counted: "From the record: 1 done")
        XCTAssertEqual(taf.runLines.above, "Sent to Slack.")
        XCTAssertEqual(taf.runLines.under, "From the record: 1 done")
        let owner = TafsCloud.ChatMessage(at: at, from: .owner, text: "Do it.", session: "s1", delivered: "Sent to Slack.", counted: "From the record: 1 done")
        XCTAssertNil(owner.runLines.above)
        XCTAssertNil(owner.runLines.under)
        let kickoff = TafsCloud.ChatMessage(at: at, from: .taf, kind: .kickoff, text: "Work the queue", session: "s1", counted: "From the record: 1 done")
        XCTAssertNil(kickoff.runLines.under)
        // Only one of the two: the other draws nothing.
        let counted = TafsCloud.ChatMessage(at: at, from: .taf, text: "Done.", session: "s1", counted: "From the record: 1 done")
        XCTAssertNil(counted.runLines.above)
        XCTAssertEqual(counted.runLines.under, "From the record: 1 done")
        // The lines aren't part of what makes a message itself: its id is the same with or without them.
        XCTAssertEqual(taf.id, TafsCloud.ChatMessage(at: at, from: .taf, text: "Done.", session: "s1").id)
    }

    /// A taf hands a setting to Coach with an `ask_coach` card (kiff/tafs#476, gap 15): it's in
    /// its chat at its time, and no other card of its thread is; Ask Coach opens Coach on the
    /// taf with its words in the box, nothing sent.
    func testATafsAskCoachCardIsInItsChatAndAskCoachWritesItsWordsIn() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-11-06T08:00:00Z","from":"owner","kind":"message","text":"send from billing","session":"s1"},
                     {"at":"2026-11-06T08:00:05Z","from":"taf","kind":"message","text":"That's Coach's to change.","session":"s1"}],
         "components":[{"at":"2026-11-06T08:00:06Z","session":"s1","run":"r1","component":{"type":"ask_coach","id":"ask_1","text":"Send them from billing@acme.test"}},
                       {"at":"2026-11-06T08:00:07Z","session":"s1","run":"r1","component":{"type":"notice","id":"n_1","text":"A notice"}},
                       {"at":"2026-11-06T08:00:08Z","session":"s1","run":"r1","component":{"type":"ask_coach","id":"ask_2","text":" "}},
                       {"at":"nope","component":{"type":"ask_coach","id":"ask_3","text":"Unreadable"}}]}
        """)
        await model.loadChat(id)
        let items = model.chatItems(id)
        XCTAssertEqual(items.map(\.id).filter { $0.hasPrefix("a|") }, ["a|ask_1"], "the ask_coach card with words, and no other")
        XCTAssertEqual(items.last?.id, "a|ask_1", "after the taf's words")
        XCTAssertEqual(model.chats[id]?.count, 2, "a card the app can't read leaves the thread as it is")

        guard case .askCoach(let card) = try XCTUnwrap(items.last) else { return XCTFail("an ask_coach card") }
        // Someone who doesn't manage the taf gets nothing: the card reads alone.
        model.askCoachAbout(id, words: card.text)
        XCTAssertFalse(model.coachOpen)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":true,"groups":[]},"setup":{"use":true,"oversee":true,"manage":true,"groups":[]}}}"#)
        await model.refreshPeople()
        model.askCoachAbout(id, words: card.text)
        XCTAssertEqual(model.setupDraft, "Send them from billing@acme.test")
        XCTAssertTrue(model.coachOpen)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0, "nothing is sent")
    }

    /// A door's run shows in an open chat without reopening it (kiff/tafs#423, item 3): tafs
    /// cloud's `taf` push re-reads the chat that's been read. One never opened isn't read.
    func testATafPushReadsTheOpenChatAgain() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.liveChange("taf", cloudTafID: "depl_1")
        XCTAssertEqual(count("GET", "/v1/tafs/depl_1/conversation"), 0, "a chat never opened isn't read")

        await model.loadChat(id)
        XCTAssertEqual(model.chats[id]?.count, 0)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-11-02T16:00:00Z","from":"owner","kind":"message","text":"By email: refund order 4410","session":"sesn_9"}]}
        """)
        await model.liveChange("taf", cloudTafID: "depl_1")
        XCTAssertEqual(model.chats[id]?.map(\.text), ["By email: refund order 4410"])
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

    /// A message that isn't sent for any reason stays where it was written, marked not sent
    /// with why, through a reload (kiff/tafs#476). Retry sends it once, a second tap finds
    /// nothing; Edit gives its words back for the composer and takes it out of the thread.
    func testAMessageThatIsntSentStaysThroughAReloadWithRetryAndEdit() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.loadChat(id)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (500, #"{"error":"boom"}"#)
        let first = await model.sendChat("hello", to: id)
        let second = await model.sendChat("and this", to: id)
        XCTAssertFalse(first || second)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["hello", "and this"])
        XCTAssertFalse(model.chatState(id).unsent[0].why.isEmpty, "it says why")
        XCTAssertNil(model.chatErrors[id], "said on the message, not under the thread")

        // A reload keeps them where they were written.
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["hello", "and this"])

        // Retry, tapped twice: sent once, and it leaves "Not sent".
        let hello = model.chatState(id).unsent[0]
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.sent = []
        async let a: Void = model.retryChat(hello, to: id)
        async let b: Void = model.retryChat(hello, to: id)
        _ = await (a, b)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 1)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["and this"])

        // Edit: its words, for the composer, and it's gone from the thread; a second Edit has nothing.
        let other = model.chatState(id).unsent[0]
        XCTAssertEqual(model.editUnsentChat(other, from: id), "and this")
        XCTAssertNil(model.editUnsentChat(other, from: id))
        XCTAssertTrue(model.chatState(id).unsent.isEmpty)
    }

    /// A run kiff/agents refused says why in tafs cloud's words, on every path (kiff/tafs#396,
    /// #397): never the app's own line for the status, which shows only without words.
    func testARefusalSaysWhyInTafsCloudsWords() async throws {
        let (model, id) = await kiffModel()
        let words = "Your tafs have used this period's AI. It starts again on 1 Nov."
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (409, #"{"error":"ai_fair_use","words":"\#(words)"}"#)
        await model.sendChat("hello", to: id)
        XCTAssertEqual(model.chatState(id).unsent.first?.why, words)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (429, #"{"error":"too many","words":"\#(words)"}"#)
        await model.sendChat("again", to: id)
        XCTAssertEqual(model.chatState(id).unsent.last?.why, words, "a 429 with words says them")
        XCTAssertEqual(model.chatState(id).unsent.last?.busy, false)
        let bare = TafsCloud.refusal(409, Data(#"{"error":"conflict"}"#.utf8))
        XCTAssertEqual(bare.localizedDescription, "conflict", "without words, as before")
    }

    /// While the tafs wait for credits (docs/accounts.md › The balance read), a chat and Run now
    /// answer 409 with a credits code: tafs cloud's words, with no Retry, and the message stays
    /// in the composer, since sending it again changes nothing until the tafs go on.
    func testACreditsRefusalShowsItsWordsWithNoRetry() async throws {
        let (model, id) = await kiffModel()
        let words = "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it."
        for code in ["credits_used_up", "limit_reached", "no_plan"] {
            StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (409, #"{"error":"waiting","code":"\#(code)","words":"\#(words)"}"#)
            let sent = await model.sendChat("hello", to: id)
            XCTAssertFalse(sent, "kept in the composer")
            XCTAssertEqual(model.chatErrors[id], words, code)
            XCTAssertTrue(model.chatState(id).unsent.isEmpty, "no Not sent row, so no Retry")
            XCTAssertFalse(model.chats[id]?.contains { $0.session == "pending" } ?? false)
        }

        StubProtocol.responses["POST /v1/tafs/depl_1/run"] = (409, #"{"error":"waiting","code":"credits_used_up","words":"\#(words)"}"#)
        let cloudTaf = try XCTUnwrap(model.cloudTaf(for: id))
        let session = await model.runNow(cloudTaf)
        XCTAssertNil(session)
        XCTAssertEqual(model.lastError, words, "the words alone")

        // Another 409 isn't about credits: as before.
        XCTAssertNil(AppModel.waitsForCredits(TafsCloud.refusal(409, Data(#"{"error":"conflict","code":"held_full"}"#.utf8))))
        XCTAssertNil(AppModel.waitsForCredits(TafsCloud.refusal(503, Data(#"{"error":"down","code":"no_plan"}"#.utf8))))
    }

    /// The 755 retest: after Not sent for writing too fast, the warning stayed under every
    /// message that went through after it. A message that goes through takes it away.
    func testAWarningForWritingTooFastGoesOnceAMessageGoesThrough() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (429, #"{"error":"too many"}"#)
        await model.sendChat("12", to: id)
        XCTAssertEqual(model.chatState(id).unsent.first?.busy, true)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (500, #"{"error":"down"}"#)
        await model.sendChat("other", to: id)
        XCTAssertEqual(model.chatState(id).unsent.count, 2, "one not sent for another reason stays, and so does the first")

        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.sendChat("Do what it asks", to: id)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["other"], "only the one kept for writing too fast goes")
    }

    /// The 755 retest: Stop took a while, the taf still at work until its run reached its
    /// next step. Here it's over at once, though tafs cloud still says it's at work, and the
    /// chat is ready for the next message.
    func testStopIsAtOnceThoughTafsCloudStillHasItAtWork() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        XCTAssertTrue(model.chatThinking(id))
        StubProtocol.responses["POST /v1/tafs/depl_1/stop"] = (200, #"{"stopped":true}"#)
        await model.stopChat(id)
        XCTAssertFalse(model.chatThinking(id), "stopped as the owner sees it")
        XCTAssertEqual(model.chatState(id).ended, .stopped)

        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":null}"#)
        await model.loadChat(id)
        XCTAssertNil(model.chatState(id).stopped, "done with once tafs cloud says so")
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_10","run":"r2"}}"#)
        await model.loadChat(id)
        XCTAssertTrue(model.chatThinking(id), "a new run is at work")
    }

    /// The owner's message stays on screen while the taf answers, though tafs cloud's thread
    /// doesn't have it yet: a reload mid-answer took it away, so only the dots showed
    /// (kiff/tafs#138, item 48). Once the thread has it, it shows once.
    func testASentMessageStaysThroughReloadsUntilTheThreadHasIt() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages/stream"] = (200, """
        event: session
        data: {"session":"sesn_9","new_session":true}

        event: activity
        data: {"tool":"memory"}

        event: end
        data: {"status":"COMPLETED","stop":"answered"}


        """)
        let sent = await model.sendChat("What are the latest 3 payments", to: id)
        XCTAssertTrue(sent)
        await model.chatStreams[id]?.value
        await model.loadChat(id)
        let mine = { (model.chats[id] ?? []).filter { $0.from == .owner && $0.text == "What are the latest 3 payments" } }
        XCTAssertEqual(mine().count, 1, "the reloads didn't take it away")

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let now = iso.string(from: Date())
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"\(now)","from":"owner","kind":"message","text":"What are the latest 3 payments","session":"sesn_9"}]}
        """)
        await model.loadChat(id)
        XCTAssertEqual(mine().count, 1, "the thread's copy takes its place")
        XCTAssertEqual(mine().first?.session, "sesn_9")
    }

    /// The taf's answer streams (#64): tafs cloud's stream route takes the message, the
    /// words arrive as written, and only the end reloads the thread (kiff/tafs#206): a tool
    /// call doesn't. Nothing goes through the plain route.
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
        XCTAssertEqual(count("GET", "/v1/tafs/depl_1/conversation"), 1, "at the end only, not at the tool call")
        XCTAssertEqual(AppModel.tafActivityWords("memory"), "Reading its notes…")
        XCTAssertEqual(AppModel.tafActivityWords("refund_order"), "Asking KIFF to run refund order…")
        XCTAssertEqual(AppModel.tafActivityWords("stripe.create_refund"), "Asking KIFF to run create refund…", "past the server's prefix")
        XCTAssertEqual(AppModel.tafActivityWords("kiff__issue-credit"), "Asking KIFF to run issue credit…")
    }

    /// What the owner writes while the taf works waits, held by tafs cloud as one message
    /// (docs/chat.md); Stop names the session at work, and what waited for the stopped run
    /// isn't sent: the app sends nothing itself.
    func testWhatsWrittenWhileTheTafWorksWaitsAndAStopKeepsItUnsent() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        XCTAssertTrue(model.chatThinking(id))
        StubProtocol.sent = []
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (202, #"{"held":{"text":"Skip 4410","at":"2026-10-18T09:00:01.000Z"}}"#)
        let first = await model.submitChat("Skip 4410", to: id)
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (202, #"{"held":{"text":"Skip 4410\n\nand 4411","at":"2026-10-18T09:00:01.000Z"}}"#)
        let second = await model.submitChat("and 4411", to: id)
        XCTAssertTrue(first)
        XCTAssertTrue(second)
        XCTAssertEqual(model.chatState(id).queued, "Skip 4410\n\nand 4411")
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0)

        StubProtocol.responses["POST /v1/tafs/depl_1/stop"] = (200, #"{"stopped":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"working":null,"steering":{"run":"r1","status":"CANCELLED","untaken":[],"not_sent":[{"text":"Skip 4410\\n\\nand 4411","at":"2026-10-18T09:00:01.000Z","why":"You stopped it before it read this."}]}}
        """)
        await model.stopChat(id)
        let stop = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/tafs/depl_1/stop" })
        XCTAssertEqual(try JSONSerialization.jsonObject(with: stop.httpBody ?? Data()) as? [String: String], ["session": "sesn_9"])
        XCTAssertNil(model.chatState(id).queued)
        XCTAssertEqual(model.chatState(id).unsent.map(\.why), ["You stopped it before it read this."])
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0, "the app sends nothing itself")
    }

    /// Answering a held call of a taf has its paused run go on at once, by the held call,
    /// without waiting for KIFF's alert (kiff/tafs#206).
    func testAnsweringAHoldAsksTheRunToGoOn() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, """
        {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"rae-k3f9","action":"refund","entity_id":"pi_1",
          "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount)","limit":5000}],
          "parameters":{"amount":8000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2099-10-14T10:51:00Z","accounting_version":2}]}
        """)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/holds/ce_1/continue"] = (200, #"{"run":"r1","status":"PAUSED"}"#)
        await model.refresh()
        model.kiffTenant = "t1"
        model.sessionHasKey = true
        let call = try XCTUnwrap(model.held.first)
        XCTAssertEqual(call.tafID, id)
        StubProtocol.sent = []
        await model.answer(call, with: .approveOnce)
        for _ in 0..<50 where count("POST", "/v1/tafs/depl_1/holds/ce_1/continue") == 0 {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(count("POST", "/v1/me/card-exceptions/ce_1/approve-once"), 1)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/holds/ce_1/continue"), 1, "the run is asked to go on")
    }

    /// While the taf works, a message goes to its run once, with `hold` (docs/chat.md), and
    /// reads at its next step (kiff/agents#102). It shows as kiff/agents says: until the run
    /// has read it. What the run doesn't take, tafs cloud holds (202): it shows as waiting,
    /// and the app never sends it itself.
    func testAMessageWhileTheTafWorksGoesToItsRun() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (200, #"{"run":"r1","status":"RUNNING"}"#)
        await model.loadChat(id)
        StubProtocol.sent = []
        let taken = await model.submitChat("only the €20 one", to: id)
        XCTAssertTrue(taken)
        let steer = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/tafs/depl_1/steer" })
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: steer.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(body["run"] as? String, "r1")
        XCTAssertEqual(body["text"] as? String, "only the €20 one")
        XCTAssertEqual(body["hold"] as? Bool, true, "tafs cloud holds what the run doesn't take")
        XCTAssertEqual(model.chatState(id).steering?.untaken.map(\.text), ["only the €20 one"], "shown at once")
        XCTAssertNil(model.chatState(id).queued)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0)

        // kiff/agents holds it unread, then the run reads it.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"working":{"session":"sesn_9","run":"r1"},"steering":{"run":"r1","status":"RUNNING","untaken":[{"text":"only the €20 one","at":"2026-10-18T09:00:00.123Z"}]}}
        """)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).steering?.untaken.map(\.text), ["only the €20 one"])
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        XCTAssertNil(model.chatState(id).steering)

        // The run didn't take it: tafs cloud holds it for this chat, and it shows as waiting.
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (202, #"{"held":{"text":"and 4411","at":"2026-10-18T09:00:02.000Z"}}"#)
        let held = await model.submitChat("and 4411", to: id)
        XCTAssertTrue(held)
        XCTAssertEqual(model.chatState(id).queued, "and 4411")
        XCTAssertEqual(QueuedBubble.heldWords, "Waits until it's done, then goes as your next message", "as Android says it")
        // A second joins it, as tafs cloud says.
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (202, #"{"held":{"text":"and 4411\n\nand 4412","at":"2026-10-18T09:00:02.000Z"}}"#)
        _ = await model.submitChat("and 4412", to: id)
        XCTAssertEqual(model.chatState(id).queued, "and 4411\n\nand 4412")
        // The taf is done: the app sends nothing itself; tafs cloud sent it, and the thread says so.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":null}"#)
        await model.loadChat(id)
        XCTAssertNil(model.chatState(id).queued)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0, "never resent by the app")
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/steer"), 3, "each message written once")
    }

    /// The chat's run was over when the message was written: tafs cloud sent it at once, as the
    /// person's next message (202 `sent`), and the thread is read for the new run.
    func testAMessageToARunThatEndedGoesAtOnceAsTheNextMessage() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (202, #"{"sent":{"run":"r2","session":"sesn_9"}}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-18T09:00:03.000Z","from":"owner","text":"and 4411","session":"sesn_9"}],"working":{"session":"sesn_9","run":"r2"}}
        """)
        StubProtocol.sent = []
        let taken = await model.submitChat("and 4411", to: id)
        XCTAssertTrue(taken)
        XCTAssertEqual(model.chats[id]?.map(\.text), ["and 4411"], "the thread, read again, has it")
        XCTAssertEqual(model.chatState(id).workingRun, "r2")
        XCTAssertNil(model.chatState(id).queued)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0)
    }

    /// More than tafs cloud holds (409 `held_full`): nothing is cut, the words stay in the
    /// composer, and tafs cloud's why shows as it says it.
    func testMoreThanTafsCloudHoldsStaysInTheComposer() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        let words = "Up to 10 messages, 2,000 characters in all, can wait for the taf. Send this once it's done."
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (409, #"{"error":"\#(words)","code":"held_full","held":{"text":"a\n\nb","at":"2026-10-18T09:00:02.000Z"}}"#)
        let taken = await model.submitChat("one more", to: id)
        XCTAssertFalse(taken, "left with the composer")
        XCTAssertEqual(model.chatErrors[id], words)
        XCTAssertTrue(model.chatState(id).unsent.isEmpty)
    }

    /// A message the app couldn't get to tafs cloud stays in the thread as not sent, with why
    /// and Retry: the app never resends it on its own.
    func testASteerThatDidntReachTafsCloudIsNotSent() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[],"working":{"session":"sesn_9","run":"r1"}}"#)
        await model.loadChat(id)
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (503, #"{"error":"unavailable"}"#)
        let taken = await model.submitChat("skip 4410", to: id)
        XCTAssertTrue(taken)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"])
        XCTAssertNil(model.chatState(id).queued)
    }

    /// A run whose stream ends answered, without reading what the owner wrote to it: tafs
    /// cloud sends it as the next message (docs/chat.md), never the app.
    func testAMessageTheRunNeverReadIsTafsCloudsToSend() async throws {
        final class Sent { var texts: [String] = [] }
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/steer"] = (200, #"{"run":"r9","status":"RUNNING"}"#)
        let sent = Sent()
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        model.streamOpener = { _, text in
            sent.texts.append(text)
            return AsyncThrowingStream { continuation = $0 }
        }
        await model.sendChat("Work the queue", to: id)
        let stream = try XCTUnwrap(continuation)
        stream.yield(.session("sesn_9", newSession: false, run: "r9"))
        for _ in 0..<2000 where model.chatState(id).live?.run == nil { try await Task.sleep(nanoseconds: 1_000_000) }
        let taken = await model.submitChat("skip 4410", to: id)
        XCTAssertTrue(taken)
        // The run answered without reading it: tafs cloud sent it, and the thread has it.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-18T09:00:03.000Z","from":"owner","text":"skip 4410","session":"sesn_9"}],"working":{"session":"sesn_9","run":"r10"}}
        """)
        stream.yield(.end(status: "COMPLETED", stop: "answered"))
        stream.finish()
        await model.chatStreams[id]?.value
        XCTAssertEqual(sent.texts, ["Work the queue"], "the app sends nothing itself")
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0)
        XCTAssertTrue(model.chatState(id).unsent.isEmpty)
    }

    /// A run the person stopped: what it never read, and what waited for it, isn't sent.
    /// tafs cloud lists it under `not_sent`, with why, shown once as not sent, with Retry,
    /// which sends it as a new message.
    func testWhatAStoppedRunNeverReadShowsWithTafsCloudsWhy() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"steering":{"run":"r9","status":"CANCELLED","untaken":[],"not_sent":[{"text":"skip 4410","at":"2026-10-18T09:00:01.000Z","why":"You stopped it before it read this."},{"text":"bad","at":"never","why":"x"}]}}
        """)
        await model.loadChat(id)
        XCTAssertNil(model.chatErrors[id], "one the app can't read is left out, not the thread")
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"])
        XCTAssertEqual(model.chatState(id).unsent.first?.why, "You stopped it before it read this.")
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).unsent.count, 1, "shown once")
        // Retry sends it as a new message.
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.retryChat(try XCTUnwrap(model.chatState(id).unsent.first), to: id)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 1)
    }

    /// A run that stopped at its own cap writes nothing for it: the chat says why, never
    /// "…" (the 793 retest), and a run at work again clears it.
    func testARunStoppedAtItsCapSaysSo() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"capped":{"run":"r7","stop":"cost_cap"}}
        """)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).capped, "cost_cap")
        XCTAssertEqual(AppModel.capWords("cost_cap", taf: "Rae"),
                       "Rae stopped: this task would cost more than its limit for one task. Ask for something narrower, or someone who manages Rae can raise the limit.")
        XCTAssertNotNil(AppModel.capWords("turn_cap", taf: "Rae"))
        XCTAssertNil(AppModel.capWords("answered", taf: "Rae"))

        // tafs cloud's `stopped` names every stop, the two caps among them, and wins over `capped`.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"stopped":{"run":"r7","stop":"max_token"},"capped":null}
        """)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).capped, "max_token")
        XCTAssertEqual(AppModel.capWords("max_token", taf: "Rae"),
                       "Rae stopped: its reply ran out of room. Ask for something shorter, or for one part at a time.")
        XCTAssertEqual(AppModel.capWords("refused", taf: "Rae"), "Rae stopped: the AI declined to continue. Try asking another way.")
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"stopped":{"run":"r7","stop":"turn_cap"},"capped":{"run":"r7","stop":"turn_cap"}}
        """)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).capped, "turn_cap")

        // A cap the app can't read leaves the thread loading, and shows nothing.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"capped":{"stop":7}}
        """)
        await model.loadChat(id)
        XCTAssertNil(model.chatErrors[id])
        XCTAssertNil(model.chatState(id).capped)

        // The latest run answered since, unseen here: the thread has no cap, and nor does the chat.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"capped":{"run":"r7","stop":"cost_cap"}}
        """)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).capped, "cost_cap")
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[]}
        """)
        await model.loadChat(id)
        XCTAssertNil(model.chatState(id).capped, "an answered run leaves no stale line")

        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"working":{"session":"sesn_9","run":"r8"}}
        """)
        await model.loadChat(id)
        XCTAssertNil(model.chatState(id).capped, "a run at work is past it")
    }

    /// A run the owner stopped, or one no stream here saw end (the app reopened), keeps
    /// what it didn't read as not sent, with Retry: the owner decides.
    func testWhatAStoppedRunDidntReadIsNotSent() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[],"steering":{"run":"r9","status":"CANCELLED","untaken":[{"text":"skip 4410","at":"yesterday"},{"text":"and 4411","at":"2026-10-18T09:00:01.123456789Z"}]}}
        """)
        StubProtocol.sent = []
        await model.loadChat(id)
        XCTAssertNil(model.chatErrors[id], "a time the app can't read leaves the thread loading")
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["and 4411"], "the readable one (nanoseconds read too), as not sent")
        XCTAssertTrue(model.chatState(id).unsent.first?.why.contains("stopped") ?? false)
        await model.loadChat(id)
        XCTAssertEqual(model.chatState(id).unsent.count, 1, "dealt with once")
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/messages"), 0, "nothing sent")
    }

    /// What was dealt with stays dealt with after a relaunch, where the app keeps it: a
    /// message the owner deleted doesn't come back. Only a hash is kept, never the text.
    func testWhatWasDealtWithStaysAfterARelaunch() async throws {
        let suite = "tafs.tests.settled.\(UUID().uuidString)"
        let remembered = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { remembered.removePersistentDomain(forName: suite) }
        let thread = """
        {"messages":[],"steering":{"run":"r9","status":"CANCELLED","untaken":[{"text":"skip 4410","at":"2026-10-18T09:00:01.000Z"}]}}
        """
        func launch() async -> (AppModel, UUID) {
            let session = stubbedSession()
            let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
            let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
            let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud, remembered: remembered)
            stubKIFF(cards: [Self.raeCard])
            StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, thread)
            await model.useSession("kiff_dev_x")
            return (model, model.tafs.first { $0.name == "Rae" }!.id)
        }
        var (model, id) = await launch()
        await model.loadChat(id)
        let shown = try XCTUnwrap(model.chatState(id).unsent.first)
        model.discardUnsentChat(shown, from: id)
        (model, id) = await launch()
        await model.loadChat(id)
        XCTAssertTrue(model.chatState(id).unsent.isEmpty, "deleted before the relaunch, so it stays gone")
        let kept = remembered.stringArray(forKey: AppModel.settledKey) ?? []
        XCTAssertEqual(kept.count, 1)
        XCTAssertFalse(kept.joined().contains("skip"), "a hash, not the text")

        // Signing out leaves nothing of the chats on the phone, hashes included.
        model.kiff.token = nil
        await model.signOut()
        XCTAssertNil(remembered.stringArray(forKey: AppModel.settledKey))
    }

    /// A `not_sent` is remembered as handled only when Retry, Edit or Delete takes it, never
    /// when it's shown: a restart before that shows it again, and after Retry it doesn't, so a
    /// second Retry has nothing to resend.
    func testANotSentComesBackAfterARestartUntilRetryTakesIt() async throws {
        let suite = "tafs.tests.settled.\(UUID().uuidString)"
        let remembered = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { remembered.removePersistentDomain(forName: suite) }
        let thread = """
        {"messages":[],"steering":{"run":"r9","status":"CANCELLED","untaken":[],"not_sent":[{"text":"skip 4410","at":"2026-10-18T09:00:01.000Z","why":"You stopped it before it read this."}]}}
        """
        func launch() async -> (AppModel, UUID) {
            let session = stubbedSession()
            let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
            let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
            let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud, remembered: remembered)
            stubKIFF(cards: [Self.raeCard])
            StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, thread)
            await model.useSession("kiff_dev_x")
            let id = model.tafs.first { $0.name == "Rae" }!.id
            await model.loadChat(id)
            return (model, id)
        }
        var (model, id) = await launch()
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"])
        XCTAssertNil(remembered.stringArray(forKey: AppModel.settledKey), "shown is not handled")

        // Restarted before Retry or Edit: the owner's words are back.
        (model, id) = await launch()
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"], "back after a restart")

        // A Retry that fails keeps it as not sent again, still owed: back after a restart too.
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (500, #"{"error":"down"}"#)
        await model.retryChat(try XCTUnwrap(model.chatState(id).unsent.first), to: id)
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"])
        XCTAssertNil(remembered.stringArray(forKey: AppModel.settledKey), "not taken yet")
        (model, id) = await launch()
        XCTAssertEqual(model.chatState(id).unsent.map(\.text), ["skip 4410"])

        // Retry takes it: after a restart it isn't back.
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        await model.retryChat(try XCTUnwrap(model.chatState(id).unsent.first), to: id)
        XCTAssertTrue(model.chatState(id).unsent.isEmpty)
        XCTAssertEqual(remembered.stringArray(forKey: AppModel.settledKey)?.count, 1)
        (model, id) = await launch()
        XCTAssertTrue(model.chatState(id).unsent.isEmpty, "taken by Retry, so not back")
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

    /// A run that fails after a call went through says so, with what it acted on, from the
    /// run's own record, and how much moved, from KIFF's Record line for that call
    /// (kiff/agents#249); one that did nothing says only that it stopped. Retry clears it.
    func testARunThatFailsSaysWhatWentThrough() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        StubProtocol.responses["GET /v1/me/activity?limit=100"] = (200, #"""
        {"calls":[{"proposal_id":"p1","action_name":"create_refund","actor_id":"rae-k3f9","outcome":"allowed","held":false,
          "tool_outcome":{"status":"done"},"created_at":"2026-10-27T21:00:00Z","amount":{"value":"5.00","unit":"USD"},"entity_id":"tc-1"}]}
        """#)
        var done: [TafsCloud.DoneCall] = [.init(tool: "create_refund", resource: "ch_3001", invocation: "tc-1")]
        model.streamOpener = { _, _ in
            let calls = done
            return AsyncThrowingStream { c in
                c.yield(.session("sesn_9", newSession: false))
                c.yield(.end(status: "ERROR", stop: "", done: calls))
                c.finish()
            }
        }
        await model.sendChat("Refund T-3001", to: id)
        await model.chatStreams[id]?.value
        let moved = try XCTUnwrap(model.recordEntry(forCall: "tc-1")?.amountText, "KIFF's Record line, read once the run ended")
        XCTAssertEqual(model.failedWords(id, taf: "Refunds"), "Refunds stopped part-way, after this went through: create refund on ch_3001, \(moved).")
        done = []
        await model.retryChatRun(id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(model.failedWords(id, taf: "Refunds"), "Refunds stopped part-way, before it finished.")
        XCTAssertEqual(AppModel.failedWords(taf: "Refunds", done: [("create refund", "ch_1", "US$5"), ("send email", nil, nil)]),
                       "Refunds stopped part-way, after these went through: create refund on ch_1, US$5; send email.")
    }

    /// A run tafs cloud names why it failed (`ai_unavailable`) says its words as they are, with
    /// Retry; what went through before it stopped still follows. As Android's ChatStreamTest.
    func testARunTafsCloudNamesSaysItsWordsWithRetry() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        let words = "Our AI provider isn't serving us right now, so this stopped and used none of your credits. It's on our side, not yours: try again a little later."
        var why: String? = words
        model.streamOpener = { _, _ in
            let said = why
            return AsyncThrowingStream { c in
                c.yield(.session("sesn_9", newSession: false))
                c.yield(.end(status: "ERROR", stop: "error", done: [], code: said == nil ? nil : "ai_unavailable", words: said))
                c.finish()
            }
        }
        await model.sendChat("Refund Ana", to: id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(model.chatState(id).ended, .failed(retry: "Refund Ana"), "still with Retry")
        XCTAssertEqual(model.failedWords(id, taf: "Refunds"), words)
        // A retry that fails with no words says the app's own line again.
        why = nil
        await model.retryChatRun(id)
        await model.chatStreams[id]?.value
        XCTAssertEqual(model.failedWords(id, taf: "Refunds"), "Refunds stopped part-way, before it finished.")
        // What went through before it stopped still follows the words.
        XCTAssertEqual(AppModel.failedWords(taf: "Refunds", done: [("create refund", "ch_1", nil)], why: words + " "),
                       "\(words) This went through: create refund on ch_1.")
    }

    /// A held call shows after the taf's words that ask for it (the 789 retest), and is the
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
        let card = try XCTUnwrap(order.firstIndex(of: "k|" + AppModel.heldKey(held)))
        let answer = try XCTUnwrap(order.firstIndex(of: "m|" + messages[1].id))
        // After the taf's words that ask for it, not above them (the 789 retest).
        XCTAssertTrue(asked < answer && answer < card, "after the taf's words: \(order)")
        XCTAssertEqual(model.chatWaiting(id)?.id, held.id)
    }

    /// A streamed answer only grows (kiff/tafs#206): its words stay as they were written, and
    /// the held call's card comes after the words of the turn that asks for it (the 789
    /// retest), whatever KIFF's clock says.
    func testAStreamedAnswerOnlyGrowsAndItsHeldCallStaysWhereItWasMade() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, """
        {"exceptions":[{"id":"ce_7","proposal_id":"p7","subject":"rae-k3f9","action":"stripe_refund","entity_id":"ch_1",
          "status":"held","breaches":[{"mandate_id":"m1","outcome":"cumulative_exceeded","quantity":"sum(amount_cents)","limit":2800}],
          "parameters":{"amount_cents":2000},"created_at":"2026-10-14T08:00:00Z","expires_at":"2099-10-14T10:51:00Z",
          "accounting_version":2}]}
        """)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.loadChat(id)
        StubProtocol.responses["POST /v1/tafs/depl_1/messages/stream"] = (200, """
        event: session
        data: {"session":"sesn_9","new_session":false,"run":"r1"}

        event: text
        data: {"text":"Refunding $20."}

        event: activity
        data: {"tool":"create_refund"}

        event: held
        data: {"exception":"ce_7","invocation":"tc-7"}

        event: text
        data: {"text":"It waits for you."}

        event: end
        data: {"status":"PAUSED","stop":"paused"}


        """)
        // Recorded with KIFF's hold before the owner's message by the clock: the card still
        // comes after the words it followed.
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Refund $20 of T-3002","session":"sesn_9"},
                     {"at":"2026-10-14T09:01:05Z","from":"taf","kind":"message","text":"Refunding $20.","session":"sesn_9"},
                     {"at":"2026-10-14T09:01:09Z","from":"taf","kind":"message","text":"It waits for you.","session":"sesn_9"}]}
        """)
        _ = await model.sendChat("Refund $20 of T-3002", to: id)
        await model.chatStreams[id]?.value
        let held = try XCTUnwrap(model.heldCalls(for: id).first)
        let messages = try XCTUnwrap(model.chats[id])
        // The stub's messages are older than the send, so the copy shown on sending stays too:
        // the order of what tafs cloud recorded is what's checked.
        let order = model.chatItems(id).map(\.id).filter { !$0.hasPrefix("m|pending|") }
        // The card after the words that ask for it (the 789 retest), not between them.
        XCTAssertEqual(order, ["m|" + messages[0].id, "m|" + messages[1].id, "m|" + messages[2].id, "k|" + AppModel.heldKey(held)])
    }

    /// A reload while the answer streams (the taf read what the owner wrote meanwhile)
    /// brings the words it recorded so far: they show once, as the thread's message, not
    /// again as the live answer's (kiff/tafs#214, item 9). Words still only live stay.
    func testWordsTheThreadHoldsShowOnceWhileTheAnswerStreams() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        model.streamOpener = { _, _ in AsyncThrowingStream { continuation = $0 } }
        await model.sendChat("Refund $300 of T-3002", to: id)
        let stream = try XCTUnwrap(continuation)
        stream.yield(.session("sesn_9", newSession: false, run: "r1"))
        stream.yield(.text("I didn't refund the $300."))
        stream.yield(.activity("list_charges"))
        for _ in 0..<2000 where model.chatState(id).live?.parts.isEmpty ?? true { try await Task.sleep(nanoseconds: 1_000_000) }
        let now = ISO8601DateFormatter().string(from: Date())
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"\(now)","from":"owner","kind":"message","text":"Refund $300 of T-3002","session":"sesn_9"},
                     {"at":"\(now)","from":"taf","kind":"message","text":"I didn't refund the $300.","session":"sesn_9"}]}
        """)
        stream.yield(.taken)
        stream.yield(.text("Checking the charge."))
        for _ in 0..<2000 where model.chatState(id).live?.text.isEmpty ?? true { try await Task.sleep(nanoseconds: 1_000_000) }
        let texts = model.chatItems(id).compactMap { item -> String? in
            switch item {
            case .message(let m) where m.from == .taf: return m.text
            case .streaming(_, let m): return m.text
            default: return nil
            }
        }
        XCTAssertEqual(texts, ["I didn't refund the $300.", "Checking the charge."], "each once, the recorded words first")
        stream.finish()
        await model.chatStreams[id]?.value
    }

    /// While the taf works, the stream says what it thinks and each step it takes, and how
    /// each ended (kiff/tafs#285): the live answer keeps them, in order, and writing clears
    /// the thinking it led to.
    func testTheLiveAnswerKeepsItsThinkingAndSteps() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        model.streamOpener = { _, _ in AsyncThrowingStream { continuation = $0 } }
        await model.sendChat("Refund the last order", to: id)
        let stream = try XCTUnwrap(continuation)
        stream.yield(.session("sesn_9", newSession: false, run: "r1"))
        stream.yield(.thinking("The order is paid, "))
        stream.yield(.thinking("so a refund fits."))
        stream.yield(.activity("get_order", call: "c1"))
        stream.yield(.step(call: "c1", status: "error"))
        stream.yield(.activity("get_order", call: "c2"))
        stream.yield(.step(call: "c2", status: "done"))
        stream.yield(.activity("create_refund", call: "c3"))
        stream.yield(.activity("show", call: "c4"))
        for _ in 0..<2000 where (model.chatState(id).live?.steps.count ?? 0) < 3 { try await Task.sleep(nanoseconds: 1_000_000) }
        var live = try XCTUnwrap(model.chatState(id).live)
        XCTAssertEqual(live.thinking, "The order is paid, so a refund fits.")
        XCTAssertEqual(live.steps.map(\.tool), ["get_order", "get_order", "create_refund"], "show draws in the chat, not as a step")
        XCTAssertEqual(live.steps.map(\.status), [.error, .done, .running])
        stream.yield(.held(exception: "ce_7", invocation: "tc-7"))
        stream.yield(.text("It waits for you."))
        for _ in 0..<2000 where model.chatState(id).live?.text.isEmpty ?? true { try await Task.sleep(nanoseconds: 1_000_000) }
        live = try XCTUnwrap(model.chatState(id).live)
        XCTAssertEqual(live.steps.last?.status, .held, "a held call is the step that was running")
        XCTAssertEqual(live.thinking, "", "writing clears what it thought")
        stream.finish()
        await model.chatStreams[id]?.value
        XCTAssertNil(model.chatState(id).live)
    }

    /// A run of the person's that tafs cloud says is at work, with nothing here following it
    /// (they left the chat and came back), is picked back up once (kiff/tafs#285): its steps
    /// show as they happen, a message it wrote reloads the thread, and its end ends it.
    func testARunAtWorkIsPickedBackUpOnce() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Refund the last order","session":"sesn_9"}],
         "working":{"session":"sesn_9","run":"r1"}}
        """)
        var opened: [String] = []
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        model.resumeOpener = { _, run in opened.append(run); return AsyncThrowingStream { continuation = $0 } }
        await model.loadChat(id)
        await model.resumeChat(id)
        await model.resumeChat(id)
        XCTAssertEqual(opened, ["r1"], "once per run")
        let live = try XCTUnwrap(model.chatState(id).live)
        XCTAssertEqual(live.run, "r1")
        XCTAssertEqual(live.session, "sesn_9")
        let stream = try XCTUnwrap(continuation)
        stream.yield(.activity("get_order", call: "c1"))
        stream.yield(.step(call: "c1", status: "done"))
        for _ in 0..<2000 where model.chatState(id).live?.steps.first?.status != .done { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(model.chatState(id).live?.steps.map(\.status), [.done])
        let before = count("GET", "/v1/tafs/depl_1/conversation")
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Refund the last order","session":"sesn_9"},
                     {"at":"2026-10-14T09:01:20Z","from":"taf","kind":"message","text":"Refunded.","session":"sesn_9",
                      "work":{"ms":20000,"steps":[{"tool":"get_order"},{"tool":"create_refund","error":true}]}}]}
        """)
        stream.yield(.said)
        stream.yield(.end(status: "COMPLETED", stop: "answered"))
        stream.finish()
        await model.chatStreams[id]?.value
        XCTAssertGreaterThan(count("GET", "/v1/tafs/depl_1/conversation"), before)
        XCTAssertNil(model.chatState(id).live)
        let said = try XCTUnwrap(model.chats[id]?.last)
        XCTAssertEqual(said.work, TafsCloud.Work(ms: 20000, steps: [.init(tool: "get_order"), .init(tool: "create_refund", error: true)]))
        await model.resumeChat(id)
        XCTAssertEqual(opened, ["r1"], "an ended run isn't picked up again")
    }

    /// A run picked back up after the owner approved its held call (the 859 test): the stream
    /// replays what it did before the hold, then marks where it went on. Only what it does
    /// after shows as its work; the steps before are the first answer's.
    func testARunPickedUpAfterAHoldShowsOnlyWhatItDoesAfter() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, """
        {"messages":[{"at":"2026-10-14T09:01:00Z","from":"owner","kind":"message","text":"Refund T-5005","session":"sesn_9"},
                     {"at":"2026-10-14T09:01:22Z","from":"taf","kind":"message","text":"The refund waits for you.","session":"sesn_9",
                      "work":{"ms":22000,"steps":[{"tool":"list_charges"}]}}],
         "working":{"session":"sesn_9","run":"r1"}}
        """)
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        model.resumeOpener = { _, _ in AsyncThrowingStream { continuation = $0 } }
        await model.loadChat(id)
        await model.resumeChat(id)
        let stream = try XCTUnwrap(continuation)
        stream.yield(.activity("list_charges", call: "c1"))
        stream.yield(.step(call: "c1", status: "done"))
        stream.yield(.said)
        stream.yield(.activity("create_refund", call: "c2"))
        stream.yield(.held(exception: "exc_1", invocation: "tc-1"))
        stream.yield(.step(call: "c2", status: "done"))
        stream.yield(.part)
        stream.yield(.activity("get_charge", call: "c3"))
        for _ in 0..<2000 where model.chatState(id).live?.steps.last?.call != "c3" { try await Task.sleep(nanoseconds: 1_000_000) }
        let live = try XCTUnwrap(model.chatState(id).live)
        XCTAssertEqual(live.steps.map(\.call), ["c3"], "only what it does after the hold")
        XCTAssertEqual(live.parts, [], "the held call before it keeps its place in the thread")
        XCTAssertEqual(TafsCloud.streamEvent("part", data: Data(#"{"at":"2026-10-14T09:05:02Z"}"#.utf8)), .part)
        stream.finish()
        await model.chatStreams[id]?.value
    }

    /// Recorded words hide live ones only for the live session, from about when it started.
    func testOnlyThisAnswersRecordedWordsHideItsLiveOnes() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let said = TafsCloud.ChatMessage(at: start.addingTimeInterval(4), from: .taf, text: "The three tools are connected. Approve the rules.", session: "s1")
        XCTAssertTrue(AppModel.alreadyRecorded("The three tools are connected.", session: "s1", started: start, in: [said]),
                      "a recorded turn can hold more than the live words")
        XCTAssertFalse(AppModel.alreadyRecorded("The three tools are connected.", session: "s2", started: start, in: [said]))
        XCTAssertFalse(AppModel.alreadyRecorded("The three tools are connected.", session: nil, started: start, in: [said]))
        XCTAssertFalse(AppModel.alreadyRecorded("The three tools are connected.", session: "s1", started: start.addingTimeInterval(600), in: [said]),
                       "an earlier answer with the same words doesn't hide this one")
        XCTAssertFalse(AppModel.alreadyRecorded("  ", session: "s1", started: start, in: [said]))
        let owner = TafsCloud.ChatMessage(at: start.addingTimeInterval(4), from: .owner, text: "The three tools are connected.", session: "s1")
        XCTAssertFalse(AppModel.alreadyRecorded("The three tools are connected.", session: "s1", started: start, in: [owner]))
    }

    /// The owner's answer to a held call and KIFF's outcome for it are one line that
    /// keeps the owner's words (kiff/tafs#206): no "refused, not sent" in its place.
    func testKIFFsOutcomeKeepsTheOwnersWords() {
        let id = KIFFMapping.recordID("p7")
        let tafID = UUID()
        var mine = RecordEntry(id: id, tafID: tafID, kind: .refused, title: "Refused by you · 08:56 · Refund a charge", detail: "", at: Date())
        mine.byYou = true
        let kiffs = RecordEntry(id: id, tafID: tafID, kind: .refused, title: "Refund a charge", detail: "", at: Date())
        let (out, settled) = AppModel.merge([kiffs], decided: [id: mine])
        XCTAssertEqual(out.first?.title, "Refused by you · 08:56 · Refund a charge")
        XCTAssertEqual(out.first?.byYou, true, "the flag goes with the owner's words")
        XCTAssertTrue(settled.isEmpty, "kept, so the next refresh keeps it too")
        XCTAssertEqual(TafChatView.outcomeText(out[0]), "Refused by you · 08:56 · Refund a charge")
    }

    /// Which line the chat shows as it is comes from a flag, not from its words: the owner's
    /// own line in any wording, and KIFF's line gets its outcome even when the reader answered.
    func testTheOwnersOwnLineIsAFlagNotItsWords() {
        var own = RecordEntry(tafID: UUID(), kind: .refused, title: "Rechazado por ti · 08:56 · Reembolso", detail: "", at: Date())
        own.byYou = true
        XCTAssertEqual(TafChatView.outcomeText(own), "Rechazado por ti · 08:56 · Reembolso", "a translated line is still the owner's own")
        var kiffs = RecordEntry(tafID: UUID(), kind: .refused, title: "Refund a charge", detail: "", at: Date())
        kiffs.answer = .init(decision: "refused", you: true)
        XCTAssertEqual(TafChatView.outcomeText(kiffs), "Refund a charge: refused, not sent", "KIFF's own line, after a restart")
        let approved = RecordEntry(tafID: UUID(), kind: .approved, title: "Refused by you · looks like the owner's", detail: "", at: Date())
        XCTAssertEqual(TafChatView.outcomeText(approved), "Refused by you · looks like the owner's: approved, done",
                       "words alone don't make a line the owner's")
        // KIFF's approved line says "by you" only when the reader answered it.
        var mine = RecordEntry(tafID: UUID(), kind: .approved, title: "Refund a charge", detail: "", at: Date())
        mine.answer = .init(decision: "approved", you: true)
        XCTAssertEqual(TafChatView.outcomeText(mine), "Refund a charge: approved by you, done")
        mine.answer = .init(decision: "approved", you: false)
        XCTAssertEqual(TafChatView.outcomeText(mine), "Refund a charge: approved, done", "someone else approved it")
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
        let maxCard = HomeJSON.entry(agent: "max", name: "max", id: nil, cards: ["max-card"])
        let (model, raeID) = await kiffModel(cards: [Self.raeCard], others: [maxCard])
        XCTAssertTrue(model.canChat(raeID))
        let max = try XCTUnwrap(model.otherAgents.first, "listed apart")
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
