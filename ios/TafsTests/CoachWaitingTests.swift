import XCTest
@testable import Tafs

/// Coach waiting (docs/api.md › Coach waiting): a Coach chat paused on a card its person answers is
/// listed in Needs you in tafs cloud's words, and its way in opens that chat.
@MainActor
final class CoachWaitingTests: XCTestCase {
    private func home(_ waiting: String) throws -> TafsCloud.Home {
        let json = #"{"tafs":[],"others":[],"removing":[],"coach_waiting":\#(waiting),"needs_you":{"count":1,"words":"1 thing needs you"}}"#
        return try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(json.utf8))
    }

    /// Read leniently: one the app can't read, or without its chat or words, is left out, never Home with it.
    func testHomeListsEachWaitingChatAsSent() throws {
        let read = try home(#"""
        [{"taf":"taf-setup","session":"s1","run":"r1","since":"2026-11-13T23:34:46Z","words":{"line":"Coach is waiting for your approval.","open":"Open Coach"}},
         {"taf":"taf-setup","session":"s2","words":{"line":" ","open":"Open Coach"}},
         {"taf":"taf-setup","words":{"line":"Coach is waiting for your answer.","open":"Open Coach"}},
         7, {"session":"s3","words":"no"}]
        """#)
        let model = AppModel()
        model.show(read, [])
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1"])
        XCTAssertEqual(model.coachWaiting.first?.words.line, "Coach is waiting for your approval.")
        XCTAssertEqual(model.coachWaiting.first?.words.open, "Open Coach")
        XCTAssertEqual(model.needsYouCount, 1, "tafs cloud's count, which has it once")

        model.show(try home("[]"), [])
        XCTAssertTrue(model.coachWaiting.isEmpty, "answered: gone at the next read")
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(#"{"tafs":[],"others":[],"removing":[]}"#.utf8)).coachWaiting,
                     "a tafs cloud before it: none")
    }

    /// Needs you draws each with its line and way in, which opens Coach on that chat; with one waiting,
    /// it never says nothing is waiting.
    func testNeedsYouDrawsItAndOpensThatChat() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/NeedsYouView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("ForEach(model.coachWaiting) { waiting in CoachWaitingCard(waiting: waiting).id(waiting.id) }"))
        XCTAssertTrue(view.contains("&& model.coachWaiting.isEmpty {"), "not 'nothing waiting' while Coach waits")
        XCTAssertTrue(view.contains("Button(waiting.words.open) { Task { await model.openCoachWaiting(waiting) } }"))
        XCTAssertTrue(view.contains("if model.mayUseCoach {\n                Button(waiting.words.open)"), "no way in for someone who may not use Coach: the line alone")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(app.range(of: "func openCoachWaiting(_ waiting: TafsCloud.CoachWaiting) async {"))
        let body = String(app[start.upperBound...].prefix(250))
        XCTAssertTrue(body.contains("guard mode == .kiff, mayUseCoach, !waiting.session.isEmpty else { return }"))
        XCTAssertTrue(body.contains("coachOpen = true"))
        XCTAssertTrue(body.contains("await openSetupChat(waiting.session)"))
    }
    // MARK: Set aside (docs/api.md › Coach waiting), as Android has it since 86

    /// What Haptics played, for the test that feels the outcome.
    private var played: [String] = []

    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#
    private static let row1 = #"{"taf":"taf-setup","session":"s1","run":"r1","since":"2026-11-13T23:34:46Z","words":{"line":"Coach is waiting for your approval.","open":"Open Coach","set_aside":"Set aside"}}"#
    private static let row2 = #"{"taf":"taf-setup","session":"s2","run":"r2","since":"2026-11-14T08:00:00Z","words":{"line":"Coach is waiting for your answer.","open":"Open Coach","set_aside":"Set aside"}}"#
    private static let clear = #"{"words":{"button":"Set all aside","title":"End 2 waiting Coach chats?","text":"Nothing is approved or sent.","yes":"Set all aside","no":"Keep them"}}"#

    /// tafs cloud's Home with these rows waiting, its count, and "Set all aside" when sent.
    private static func homeJSON(_ rows: [String], clear: String? = nil) -> String {
        let all = clear.map { #","coach_waiting_clear":\#($0)"# } ?? ""
        let words = rows.isEmpty ? "Nothing needs you." : "\(rows.count) need you."
        return #"{"tafs":[],"others":[],"removing":[],"coach_waiting":[\#(rows.joined(separator: ","))]\#(all),"needs_you":{"count":\#(rows.count),"words":"\#(words)"}}"#
    }

    private func signedIn(_ rows: [String], clear: String? = nil, signer: FaceIDSigner = FaceIDSigner()) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.planqueAccount)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON(rows, clear: clear))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private func posts(_ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.host == "cloud.test" && $0.url?.path == path }
    }

    private func setAsides() -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path.hasPrefix("/v1/home/coach-waiting/") == true }
    }

    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    /// Set aside and Set all aside are drawn only when tafs cloud offers them: a row's swipe and
    /// button with its `words.set_aside`, Set all aside with `coach_waiting_clear` and two or more
    /// rows, signed in only. KIFF's holds, "Did this happen?" and steps never get either.
    func testSetAsideIsDrawnOnlyWhenTafsCloudOffersIt() async throws {
        let bare = #"{"taf":"taf-setup","session":"s3","words":{"line":"Coach is waiting for your answer.","open":"Open Coach"}}"#
        let model = await signedIn([Self.row1, bare], clear: Self.clear)
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1", "s3"])
        XCTAssertEqual(model.coachWaiting.first?.words.setAside, "Set aside", "tafs cloud's words, as sent")
        XCTAssertTrue(model.canSetAside(model.coachWaiting[0]))
        XCTAssertFalse(model.canSetAside(model.coachWaiting[1]), "no words, no swipe and no button")
        XCTAssertEqual(model.setAllAside?.words.title, "End 2 waiting Coach chats?", "the count, in tafs cloud's words")
        XCTAssertEqual(model.setAllAside?.words.no, "Keep them")

        model.show(try home("[\(Self.row1),\(Self.row2)]"), [])
        XCTAssertNil(model.setAllAside, "two rows, but no coach_waiting_clear: no Set all aside")
        let one = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(Self.homeJSON([Self.row1], clear: Self.clear).utf8))
        model.show(one, [])
        XCTAssertNil(model.setAllAside, "one row: no Set all aside")
        let odd = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(Self.homeJSON([Self.row1, Self.row2], clear: #""yes""#).utf8))
        XCTAssertFalse(odd.coachWaitingClear?.usable ?? false, "one the app can't read is none, never Home with it")
        model.show(odd, [])
        XCTAssertNil(model.setAllAside)

        let sample = AppModel()
        sample.show(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(Self.homeJSON([Self.row1, Self.row2], clear: Self.clear).utf8)), [])
        XCTAssertFalse(sample.coachWaiting.contains { sample.canSetAside($0) }, "the sample sets nothing aside")
        XCTAssertNil(sample.setAllAside)

        // The view draws both through the model alone, and only on Coach's rows.
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/NeedsYouView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("if let clear = model.setAllAside { SetAllAsideButton(clear: clear) }"))
        let card = try XCTUnwrap(view.range(of: "struct CoachWaitingCard: View {"))
        let cardBody = String(view[card.upperBound...].prefix(5000))
        XCTAssertTrue(cardBody.contains("if model.canSetAside(waiting) {\n            ZStack"), "the swipe only when offered")
        XCTAssertTrue(cardBody.contains("if model.canSetAside(waiting) {\n                Button(waiting.words.setAside) { setAside() }"), "the button too")
        XCTAssertEqual(view.components(separatedBy: "setAsideCoach(").count - 1, 2, "a row and Set all aside, nowhere else")
    }

    /// One call per gesture, and none while one is on its way (Android 86 sent eleven in three
    /// seconds): a second ask for the same chat, or Set all aside, sends nothing until tafs cloud
    /// answers. The row's swipe asks only when it ends, once, and not again while it's asked.
    func testOneCallPerGestureAndNoneWhileOneIsInFlight() async throws {
        let model = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":0}"#)
        StubProtocol.holdOnce = "POST /v1/home/coach-waiting/s1/set-aside"
        StubProtocol.sent = []
        let first = Task { await model.setAsideCoach("s1") }
        await untilHeldRequestArrives()
        XCTAssertTrue(model.settingAside.contains("s1"))
        let again = await model.setAsideCoach("s1")
        XCTAssertFalse(again, "asked again while the first is on its way: nothing sent")
        let all = await model.setAsideCoach(nil)
        XCTAssertFalse(all, "nor Set all aside, which takes it too")
        XCTAssertEqual(setAsides().count, 1)
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON([Self.row2]))
        StubProtocol.release.signal()
        let done = await first.value
        XCTAssertTrue(done)
        XCTAssertEqual(setAsides().count, 1, "one call for the gesture")
        XCTAssertTrue(model.settingAside.isEmpty)
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s2"])

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/NeedsYouView.swift"), encoding: .utf8)
        // The swipe asks only as the gesture ends, never as it moves.
        let changed = try XCTUnwrap(view.range(of: ".onChanged { drag in"))
        let ended = try XCTUnwrap(view.range(of: ".onEnded { drag in"))
        XCTAssertFalse(view[changed.upperBound..<ended.lowerBound].contains("setAside()"))
        XCTAssertTrue(view.contains("guard !asked, !model.settingAside.contains(waiting.session) else { return }"))
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        XCTAssertTrue(app.contains("guard !sessions.isEmpty, settingAside.isDisjoint(with: sessions) else { return false }\n        settingAside.formUnion(sessions)"),
                      "checked and marked before anything is awaited")
    }

    /// A refusal keeps the row and says why, in tafs cloud's words, with a warning; set aside, it's
    /// a success tick, and never Face ID: it's "not now", never an approval.
    func testARefusalKeepsTheRowAndSaysWhy() async throws {
        UserDefaults.standard.removeObject(forKey: Haptics.key)
        Haptics.forgetPresses()
        Haptics.played = { [weak self] in self?.played.append($0) }
        defer { Haptics.played = nil }
        let signer = FaceIDSigner()
        let model = await signedIn([Self.row1], signer: signer)
        played = []
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (409, #"{"error":"no Coach chat of yours waits by that id","words":"That chat no longer waits on you."}"#)
        Haptics.tap()
        let refused = await model.setAsideCoach("s1")
        XCTAssertFalse(refused)
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1"], "the row stays")
        XCTAssertEqual(model.lastError, "That chat no longer waits on you.", "tafs cloud's words")
        XCTAssertTrue(model.settingAside.isEmpty, "and it can be asked again")
        XCTAssertEqual(played, ["tap", "warning"])

        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":1}"#)
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON([]))
        let done = await model.setAsideCoach("s1")
        XCTAssertTrue(done)
        XCTAssertTrue(model.coachWaiting.isEmpty)
        XCTAssertEqual(played.last, "success")
        XCTAssertTrue(signer.prompts.isEmpty, "no Face ID for a not now")
    }

    /// Set all aside sends what Android sends: one `POST /v1/home/coach-waiting/set-aside`, with no
    /// body. One row's is `POST /v1/home/coach-waiting/{session}/set-aside`. Then Home is read again,
    /// and the badge is tafs cloud's `needs_you` count as sent.
    func testSetAllAsideSendsWhatAndroidSends() async throws {
        let model = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        XCTAssertEqual(model.needsYouCount, 2)
        StubProtocol.responses["POST /v1/home/coach-waiting/set-aside"] = (200, #"{"set_aside":["s1","s2"],"withdrawn":0}"#)
        StubProtocol.responses["GET /v1/home"] = (200, #"{"tafs":[],"others":[],"removing":[],"needs_you":{"count":1,"words":"1 needs you."}}"#)
        StubProtocol.sent = []
        let done = await model.setAsideCoach(nil)
        XCTAssertTrue(done)
        let sent = setAsides()
        XCTAssertEqual(sent.map { $0.url?.path }, ["/v1/home/coach-waiting/set-aside"], "one call for all")
        XCTAssertTrue((sent.first?.httpBody ?? Data()).isEmpty, "nothing in its body")
        XCTAssertTrue(model.coachWaiting.isEmpty)
        XCTAssertFalse(StubProtocol.sent.filter { $0.httpMethod == "GET" && $0.url?.path == "/v1/home" }.isEmpty, "Home read again")
        XCTAssertEqual(model.needsYouCount, 1, "tafs cloud's count, as sent")
        XCTAssertEqual(model.needsYouWords, "1 needs you.")

        let one = await signedIn([Self.row1])
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":0}"#)
        StubProtocol.sent = []
        let set = await one.setAsideCoach("s1")
        XCTAssertTrue(set)
        XCTAssertEqual(posts("/v1/home/coach-waiting/s1/set-aside").count, 1)
        XCTAssertEqual(one.cloud.setAsidePath(nil), "/v1/home/coach-waiting/set-aside")
        XCTAssertEqual(one.cloud.setAsidePath("s1"), "/v1/home/coach-waiting/s1/set-aside")
    }

    /// After Set aside, a new job starts a new Coach conversation: it never goes on in the ended
    /// session, whether that chat was the one shown or would be the newest at Coach's next read.
    func testANewJobAfterSetAsideStartsANewConversation() async throws {
        // The chat shown in Coach is set aside: the next message asks for a new chat.
        let model = await signedIn([Self.row1])
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, #"{"chats":[{"session":"s1","started":"2026-11-13T23:00:00.000Z","updated":"2026-11-13T23:34:46.000Z","title":"Refunds"}]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, #"{"messages":[{"at":"2026-11-13T23:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"}],"components":[]}"#)
        await model.loadSetup()
        await model.openSetupChat("s1")
        XCTAssertEqual(model.setupChat, "s1")
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":0}"#)
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON([]))
        let done = await model.setAsideCoach("s1")
        XCTAssertTrue(done)
        XCTAssertNil(model.setupChat, "Coach no longer shows the chat set aside")
        XCTAssertTrue(model.setupFresh, "the next message starts a new conversation")
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s_new","new_session":true}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_new"] = (200, #"{"messages":[],"components":[]}"#)
        StubProtocol.sent = []
        let delivered = await model.sendSetup("Answer order questions")
        XCTAssertTrue(delivered)
        let post = try XCTUnwrap(posts("/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(post)["new_chat"] as? Bool, true)
        XCTAssertNil(body(post)["session"], "never the session set aside")
        XCTAssertEqual(model.setupChat, "s_new")

        // Set aside before Coach was read: the newest chat is the one set aside, so Coach opens
        // on a new conversation, not on it.
        let later = await signedIn([Self.row1])
        later.planqueSetUp(for: "t_01")
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":0}"#)
        let setAside = await later.setAsideCoach("s1")
        XCTAssertTrue(setAside)
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON([]))
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, #"{"chats":[{"session":"s0","started":"2026-11-12T09:00:00.000Z","updated":"2026-11-12T09:10:00.000Z","title":"Older"},{"session":"s1","started":"2026-11-13T23:00:00.000Z","updated":"2026-11-13T23:34:46.000Z","title":"Refunds"}]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, #"{"messages":[{"at":"2026-11-13T23:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"}],"components":[]}"#)
        StubProtocol.sent = []
        await later.loadSetup()
        XCTAssertNil(later.setupChat)
        XCTAssertTrue(later.setupFresh)
        XCTAssertTrue(StubProtocol.sent.filter { $0.url?.path == "/v1/tafs/taf-setup/conversation/s1" }.isEmpty, "the chat set aside isn't read as Coach's")
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s_new","new_session":true}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_new"] = (200, #"{"messages":[],"components":[]}"#)
        let sent = await later.sendSetup("A new job")
        XCTAssertTrue(sent)
        let next = try XCTUnwrap(posts("/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(next)["new_chat"] as? Bool, true)
        XCTAssertNil(body(next)["session"])
        Keychain.delete(AppModel.planqueAccount)
    }

    // MARK: A new job after a hand-over (docs/api.md, `new_chat_after_handover`)

    private static let s1Chats = #"{"chats":[{"session":"s1","started":"2026-11-13T23:00:00.000Z","updated":"2026-11-13T23:34:46.000Z","title":"Refunds"}]}"#
    private static let s1Thread = #"{"messages":[{"at":"2026-11-13T23:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1","id":"r1.in"}],"components":[]}"#

    /// Signed in with Coach, its chat s1 (which handed a taf over) the one shown.
    private func coachOnS1() async -> AppModel {
        let model = await signedIn([])
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, Self.s1Chats)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, Self.s1Thread)
        await model.loadSetup()
        await model.openSetupChat("s1")
        return model
    }

    /// Every message to Coach says the app follows a new chat, on the stream route and the plain one,
    /// in the chat shown and in a new one. A target says nothing of it unless Coach's send sets it.
    func testEveryCoachMessageSaysItFollowsANewChat() async throws {
        let model = await coachOnS1()
        XCTAssertEqual(model.setupChat, "s1")
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"run":"r2","message":{"id":"r2.in","at":"2026-11-14T09:00:00.000Z"}}"#)
        StubProtocol.sent = []
        let sent = await model.sendSetup("Remind me each Monday")
        XCTAssertTrue(sent)
        // No stream here (404): it was asked first, then the plain route took it. Both say so.
        let stream = try XCTUnwrap(posts("/v1/tafs/taf-setup/messages/stream").last)
        XCTAssertEqual(body(stream)["new_chat_after_handover"] as? Bool, true)
        XCTAssertEqual(body(stream)["session"] as? String, "s1")
        let post = try XCTUnwrap(posts("/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(post)["new_chat_after_handover"] as? Bool, true)
        XCTAssertEqual(body(post)["session"] as? String, "s1", "the chat shown: tafs cloud says whether it's finished")
        XCTAssertNil(body(post)["new_chat"])
        XCTAssertEqual(model.setupChat, "s1")

        // A new conversation says it too.
        model.newSetupChat()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s_new","new_session":true}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_new"] = (200, #"{"messages":[],"components":[]}"#)
        StubProtocol.sent = []
        let fresh = await model.sendSetup("A new job")
        XCTAssertTrue(fresh)
        let next = try XCTUnwrap(posts("/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(next)["new_chat"] as? Bool, true)
        XCTAssertEqual(body(next)["new_chat_after_handover"] as? Bool, true)
        XCTAssertEqual(model.setupChat, "s_new")

        XCTAssertFalse(TafsCloud.ChatTarget.live.newChatAfterHandover)
        XCTAssertFalse(TafsCloud.ChatTarget.new.newChatAfterHandover)
        XCTAssertFalse(TafsCloud.ChatTarget(session: "s1").newChatAfterHandover)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// tafs cloud's answer and the stream's `session` event, as sent: `new_session` with the new chat's
    /// `session`. A `session` event without the flag is no new chat.
    func testTheNewChatIsReadFromTheAnswerAndTheSessionEvent() throws {
        let reply = #"{"session":"s2","new_session":true,"run":"r2","message":{"id":"r2.in","at":"2026-11-14T09:00:00.000Z"}}"#
        let sent = try KIFFClient.decoder.decode(TafsCloud.Sent.self, from: Data(reply.utf8))
        XCTAssertEqual(sent.session, "s2")
        XCTAssertTrue(sent.newSession)
        XCTAssertEqual(sent.message?.id, "r2.in")
        XCTAssertEqual(TafsCloud.streamEvent("session", data: Data(reply.utf8)),
                       .session("s2", newSession: true, run: "r2", message: "r2.in"))
        XCTAssertEqual(TafsCloud.streamEvent("session", data: Data(#"{"session":"s1","run":"r2"}"#.utf8)),
                       .session("s1", newSession: false, run: "r2", message: nil), "no flag: the same chat")
    }

    /// The plain answer says tafs cloud started a new chat for the message (the chat shown had handed a
    /// taf over): Coach shows the new chat, with the message just sent, once; the next message goes on
    /// there; the old chat stays under Previous conversations.
    func testANewChatInTheAnswerIsTheOneShownWithTheMessageSent() async throws {
        let model = await coachOnS1()
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Refunds, please"])
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s2","new_session":true,"run":"r2","message":{"id":"r2.in","at":"2026-11-14T09:00:00.000Z"}}"#)
        // tafs cloud's thread for the new chat has the message, by the id the send gave.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s2"] = (200, #"{"messages":[{"at":"2026-11-14T09:00:00.000Z","from":"owner","kind":"message","text":"Remind me each Monday","session":"s2","id":"r2.in"}],"components":[]}"#)
        StubProtocol.sent = []
        let sent = await model.sendSetup("Remind me each Monday")
        XCTAssertTrue(sent)
        XCTAssertEqual(model.setupChat, "s2", "the new chat is the one shown")
        XCTAssertFalse(model.setupFresh)
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Remind me each Monday"], "the message sent, once, and not the old chat's")
        XCTAssertEqual(model.setupThread.messages.first?.session, "s2")
        XCTAssertTrue(model.setupUnsent.isEmpty, "nothing kept as not sent")
        XCTAssertFalse(StubProtocol.sent.filter { $0.url?.path == "/v1/tafs/taf-setup/conversation/s2" }.isEmpty, "the new chat is read")

        // The next message goes on in the new chat.
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s2","new_session":false,"run":"r3","message":{"id":"r3.in","at":"2026-11-14T09:01:00.000Z"}}"#)
        StubProtocol.sent = []
        let again = await model.sendSetup("At 9")
        XCTAssertTrue(again)
        XCTAssertEqual(body(posts("/v1/tafs/taf-setup/messages").last)["session"] as? String, "s2")

        // The old chat stays under Previous conversations, as tafs cloud lists it.
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, #"{"chats":[{"session":"s2","started":"2026-11-14T09:00:00.000Z","updated":"2026-11-14T09:01:00.000Z","title":"Remind me each Monday"},{"session":"s1","started":"2026-11-13T23:00:00.000Z","updated":"2026-11-13T23:34:46.000Z","title":"Refunds"}]}"#)
        await model.loadSetupChats()
        XCTAssertEqual(model.setupChats.map(\.session), ["s2", "s1"])
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The stream's `session` event says the new chat: the answer streams into it, under the message
    /// just sent, and the recorded answer then takes the live one's place, each shown once.
    func testANewChatInTheStreamIsTheOneShownWithTheAnswerStreaming() async throws {
        let model = await coachOnS1()
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        let events = AsyncThrowingStream<TafsCloud.StreamEvent, Error> { continuation = $0 }
        let stream = try XCTUnwrap(continuation)
        model.streamOpener = { _, _ in events }
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s2"] = (200, #"{"messages":[],"components":[]}"#)
        let sent = await model.sendSetup("Remind me each Monday")
        XCTAssertTrue(sent)
        stream.yield(.session("s2", newSession: true, run: "r2", message: "r2.in"))
        for _ in 0..<2000 where model.setupChat != "s2" { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(model.setupChat, "s2", "the new chat is the one shown")
        XCTAssertEqual(model.setupLive?.session, "s2")
        XCTAssertEqual(model.setupLive?.run, "r2")
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Remind me each Monday"], "the message sent, not the old chat's")
        stream.yield(.text("Every Monday at 9."))
        for _ in 0..<2000 where model.setupLiveText.isEmpty { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(model.setupLiveText, "Every Monday at 9.", "the answer streams into the new chat")

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s2"] = (200, """
        {"messages":[{"at":"2026-11-14T09:00:00.000Z","from":"owner","kind":"message","text":"Remind me each Monday","session":"s2","id":"r2.in"},
                     {"at":"2026-11-14T09:00:05.000Z","from":"taf","kind":"message","text":"Every Monday at 9.","session":"s2"}],"components":[]}
        """)
        stream.yield(.end(status: "COMPLETED", stop: "answered"))
        stream.finish()
        await model.setupStream?.value
        XCTAssertNil(model.setupLive)
        XCTAssertEqual(model.setupChat, "s2")
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Remind me each Monday", "Every Monday at 9."], "each once")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// No new chat: the same session, even with `new_session`, or no flag, keeps the chat shown, with
    /// what it held and the message sent.
    func testTheSameChatOrNoFlagKeepsTheChatShown() async throws {
        let model = await coachOnS1()
        let both = #"{"messages":[{"at":"2026-11-13T23:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1","id":"r1.in"},{"at":"2026-11-14T09:00:00.000Z","from":"owner","kind":"message","text":"Remind me each Monday","session":"s1","id":"r2.in"}],"components":[]}"#
        // The stream names the chat shown, with new_session: still that chat.
        model.streamOpener = { _, _ in
            AsyncThrowingStream { c in
                c.yield(.session("s1", newSession: true, run: "r2", message: "r2.in"))
                c.yield(.end(status: "COMPLETED", stop: "answered"))
                c.finish()
            }
        }
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, both)
        let sent = await model.sendSetup("Remind me each Monday")
        XCTAssertTrue(sent)
        await model.setupStream?.value
        XCTAssertEqual(model.setupChat, "s1")
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Refunds, please", "Remind me each Monday"])

        // A session event without the flag: no new chat, whatever it names.
        model.streamOpener = { _, _ in
            AsyncThrowingStream { c in
                c.yield(.session("s9", newSession: false, run: "r3"))
                c.yield(.end(status: "COMPLETED", stop: "answered"))
                c.finish()
            }
        }
        let other = await model.sendSetup("And Fridays")
        XCTAssertTrue(other)
        await model.setupStream?.value
        XCTAssertEqual(model.setupChat, "s1")
        XCTAssertTrue(model.setupThread.messages.contains { $0.text == "Refunds, please" }, "the chat shown keeps what it held")

        // The plain answer: the same chat, no new one.
        model.streamOpener = nil
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"run":"r4","message":{"id":"r4.in","at":"2026-11-14T09:02:00.000Z"}}"#)
        let plain = await model.sendSetup("Thanks")
        XCTAssertTrue(plain)
        XCTAssertEqual(model.setupChat, "s1")
        XCTAssertTrue(model.setupThread.messages.contains { $0.text == "Refunds, please" })
        Keychain.delete(AppModel.planqueAccount)
    }
}
