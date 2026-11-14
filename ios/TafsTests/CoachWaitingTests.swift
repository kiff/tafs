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

    /// A row being set aside isn't drawn while tafs cloud answers, and the badge leaves it out (as
    /// Android 94): drawn on, it sprang back and looked untouched, so the person swiped again. A
    /// refusal brings it back with tafs cloud's words; once set aside, it stays gone. Set all aside
    /// hides every row it covers the same way.
    func testARowBeingSetAsideIsHiddenUntilTafsCloudAnswers() async throws {
        let model = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        XCTAssertEqual(model.needsYouCount, 2)

        // Refused: hidden while held, then back with why.
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (409, #"{"error":"no Coach chat of yours waits by that id","words":"That chat no longer waits on you."}"#)
        StubProtocol.holdOnce = "POST /v1/home/coach-waiting/s1/set-aside"
        let refused = Task { await model.setAsideCoach("s1") }
        await untilHeldRequestArrives()
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s2"], "not drawn while in flight")
        XCTAssertEqual(model.needsYouCount, 1, "and the badge leaves it out")
        // A Home read meanwhile doesn't draw it again.
        model.show(try home("[\(Self.row1),\(Self.row2)]"), [])
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s2"])
        XCTAssertEqual(model.needsYouCount, 0, "tafs cloud's 1, less the row in flight")
        StubProtocol.release.signal()
        let wasRefused = await refused.value
        XCTAssertFalse(wasRefused)
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1", "s2"], "refused: back")
        XCTAssertEqual(model.lastError, "That chat no longer waits on you.", "with tafs cloud's words")

        // Taken: hidden while held, and gone after.
        let taken = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        StubProtocol.responses["POST /v1/home/coach-waiting/s1/set-aside"] = (200, #"{"set_aside":["s1"],"withdrawn":0}"#)
        StubProtocol.holdOnce = "POST /v1/home/coach-waiting/s1/set-aside"
        let done = Task { await taken.setAsideCoach("s1") }
        await untilHeldRequestArrives()
        XCTAssertEqual(taken.coachWaiting.map(\.session), ["s2"])
        XCTAssertEqual(taken.needsYouCount, 1)
        StubProtocol.responses["GET /v1/home"] = (200, Self.homeJSON([Self.row2]))
        StubProtocol.release.signal()
        let wasTaken = await done.value
        XCTAssertTrue(wasTaken)
        XCTAssertEqual(taken.coachWaiting.map(\.session), ["s2"], "set aside: stays gone")
        XCTAssertEqual(taken.needsYouCount, 1)

        // Set all aside: every row it covers is hidden while in flight, and the count never goes below 0.
        let all = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        StubProtocol.responses["POST /v1/home/coach-waiting/set-aside"] = (409, #"{"error":"busy","words":"Try again in a moment."}"#)
        StubProtocol.holdOnce = "POST /v1/home/coach-waiting/set-aside"
        let every = Task { await all.setAsideCoach(nil) }
        await untilHeldRequestArrives()
        XCTAssertTrue(all.coachWaiting.isEmpty, "none drawn while in flight")
        XCTAssertNil(all.setAllAside)
        XCTAssertEqual(all.needsYouCount, 0)
        StubProtocol.release.signal()
        let allSet = await every.value
        XCTAssertFalse(allSet)
        XCTAssertEqual(all.coachWaiting.map(\.session), ["s1", "s2"], "refused: both back")
        XCTAssertEqual(all.needsYouCount, 2)
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

    // MARK: The chat open on screen

    /// The Coach chat open on screen isn't in Needs you while it's open: no row for it, and the
    /// badge leaves it out (the live run: the badge said 1 for the very chat the person was in,
    /// waiting on their own card). Of tafs cloud's count, only the rows hidden come off, never
    /// below 0; closed, it's back as Home listed it.
    func testTheChatOpenInCoachIsNotCounted() async throws {
        let model = await signedIn([Self.row1, Self.row2], clear: Self.clear)
        XCTAssertEqual(model.needsYouCount, 2)
        await model.openSetupChat("s1")
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1", "s2"], "Coach closed: still listed")
        XCTAssertNil(model.coachChatOnScreen)

        model.coachOpen = true
        XCTAssertEqual(model.coachChatOnScreen, "s1")
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s2"], "no row for the chat on screen")
        XCTAssertEqual(model.needsYouCount, 1, "tafs cloud's 2, less the row hidden")
        XCTAssertEqual(model.needsYouWords, Words.NeedsYou.count(count: 1), "not tafs cloud's line, which counts it")
        XCTAssertNil(model.setAllAside, "one row left: no Set all aside")

        model.coachOpen = false
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1", "s2"], "closed: back as Home lists it")
        XCTAssertEqual(model.needsYouCount, 2)
        XCTAssertEqual(model.needsYouWords, "2 need you.")

        // A new conversation in Coach is on no waiting chat: nothing hidden.
        model.coachOpen = true
        model.newSetupChat()
        XCTAssertNil(model.coachChatOnScreen)
        XCTAssertEqual(model.needsYouCount, 2)

        // Never below 0: tafs cloud's count may already leave it out.
        await model.openSetupChat("s1")
        let counted = #"{"tafs":[],"others":[],"removing":[],"coach_waiting":[\#(Self.row1)],"needs_you":{"count":0,"words":"Nothing needs you."}}"#
        model.show(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(counted.utf8)), [])
        XCTAssertTrue(model.coachWaiting.isEmpty)
        XCTAssertEqual(model.needsYouCount, 0)

        // Without tafs cloud's count, the app's own counts the rows it draws.
        let uncounted = #"{"tafs":[],"others":[],"removing":[],"coach_waiting":[\#(Self.row1),\#(Self.row2)]}"#
        model.show(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(uncounted.utf8)), [])
        XCTAssertEqual(model.needsYouCount, 1)

        // The bar's badge and the sidebar's are this count.
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let bar = try String(contentsOf: root.appendingPathComponent("Views/TafsBar.swift"), encoding: .utf8)
        XCTAssertTrue(bar.contains("model.needsYouCount"))
        let split = try String(contentsOf: root.appendingPathComponent("Views/HomeSplitView.swift"), encoding: .utf8)
        XCTAssertTrue(split.contains("badge: model.needsYouCount"))
    }

    /// One tap on a row's way in opens the chat (the live run: the first tap did nothing). The
    /// swipe that sets it aside is on the row's line alone, never over its buttons, so no drag
    /// recognizer sits around "Open Coach". The tap itself can't be felt here: only the structure is pinned.
    func testOneTapOpensTheChatTheSwipeIsOnTheLineAlone() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/NeedsYouView.swift"), encoding: .utf8)
        let card = try XCTUnwrap(view.range(of: "struct CoachWaitingCard: View {"))
        let end = try XCTUnwrap(view.range(of: "struct SetAllAsideButton: View {"))
        let cardBody = String(view[card.upperBound..<end.lowerBound])
        XCTAssertEqual(cardBody.components(separatedBy: "Gesture(").count - 1, 2, "one drag, and its one use")
        XCTAssertTrue(cardBody.contains("row.simultaneousGesture(swipe)"), "on the line")
        XCTAssertFalse(cardBody.contains("onTapGesture"), "no tap recognizer beside the button's")
        let top = try XCTUnwrap(cardBody.range(of: "var body: some View {"))
        let panel = try XCTUnwrap(cardBody.range(of: "private var panel: some View {"))
        XCTAssertFalse(cardBody[top.upperBound..<panel.lowerBound].contains("Gesture"), "nothing around the whole row")
        XCTAssertTrue(cardBody.contains("DragGesture(minimumDistance: 20, coordinateSpace: .global)"), "the line moves with the row")
        // The tap opens Coach on that chat at once, and the row and its badge go together.
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        XCTAssertTrue(app.contains("        coachOpen = true\n        await openSetupChat(waiting.session)"))
    }
}
