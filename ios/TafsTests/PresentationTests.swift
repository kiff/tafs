import XCTest
@testable import Tafs

@MainActor
final class PresentationTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount); Keychain.delete(AppModel.tenantAccount); Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    private func model(signer: ApprovalSigning = RecordingSigner()) async -> (AppModel, UUID) {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer, kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session), cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        await model.useSession("synthetic-session", tenant: "t_01")
        return (model, model.tafs.first!.id)
    }

    private func untilHeld() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    func testAPageReachedDuringRefreshLoadsAfterTheRefreshFinishes() async throws {
        let (model, id) = await model()
        let first = "GET /v1/tafs/depl_1/activity?filter=all"
        StubProtocol.responses[first] = (200, #"{"entries":[],"groups":[],"filters":[],"filter":"all","revision":"r","next_cursor":"c2"}"#)
        StubProtocol.responses[first + "&cursor=c2"] = (200, #"{"entries":[],"groups":[],"filters":[],"filter":"all","revision":"r","end_words":"Nothing older in this view"}"#)
        await model.loadActivityPage(id, filter: "all")
        StubProtocol.holdOnce = first
        let refresh = Task { await model.loadActivityPage(id, filter: "all") }
        await untilHeld()
        await model.loadActivityPage(id, filter: "all", more: true)
        XCTAssertTrue(model.activityPages[model.activityKey(id, "all")]?.moreWhenReady == true)
        StubProtocol.release.signal()
        await refresh.value
        let pages = try XCTUnwrap(model.activityPages[model.activityKey(id, "all")])
        XCTAssertEqual(pages.pages.count, 2)
        XCTAssertEqual(pages.pages.last?.end_words, "Nothing older in this view")
        XCTAssertFalse(pages.loading)
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.query == "filter=all&cursor=c2" }.count, 1)
    }

    func testRunPagingReachedDuringRefreshAlsoContinues() async throws {
        let (model, id) = await model()
        let first = "GET /v1/tafs/depl_1/work/runs?limit=20"
        StubProtocol.responses[first] = (200, #"{"requests":[],"title":"Its work","more_title":"Show all runs","next_cursor":"c2"}"#)
        StubProtocol.responses[first + "&cursor=c2"] = (200, #"{"requests":[],"title":"Its work","more_title":"Show all runs","end_words":"Nothing older in the retained runs"}"#)
        await model.loadRunPage(id, all: true)
        StubProtocol.holdOnce = first
        let refresh = Task { await model.loadRunPage(id, all: true) }
        await untilHeld()
        await model.loadRunPage(id, all: true, more: true)
        StubProtocol.release.signal()
        await refresh.value
        XCTAssertEqual(model.runPages[model.runKey(id, true)]?.pages.count, 2)
        XCTAssertEqual(model.runPages[model.runKey(id, true)]?.pages.last?.end_words, "Nothing older in the retained runs")
    }

    func testACurrencyExceptionNamesItsTafAndOpensOnlyAnOrdinaryCoachRequest() async throws {
        let (model, id) = await model()
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":true,"groups":[]},"setup":{"use":true,"oversee":true,"manage":true,"groups":[]}}}"#)
        await model.refreshPeople()
        let data = Data(#"{"id":"exc-example","kind":"card_unit","taf":"depl_1","name":"Rae","title":"Rae needs a USD limit","words":"Its Card only allows EUR. Add a USD limit?","history":"Tried 4 times since 2 Nov","action_title":"Add a USD limit to this action","dismiss_title":"Dismiss","can_approve_once":false,"coach_text":"Add a USD limit to this action for Rae. Read its Card first and ask me to approve."}"#.utf8)
        let ask = try JSONDecoder().decode(TafsCloud.CardUnitException.self, from: data)
        let before = StubProtocol.sent.count
        model.discussCardUnit(ask)
        XCTAssertTrue(model.coachOpen)
        XCTAssertEqual(model.setupImproving?.id, "depl_1")
        XCTAssertEqual(model.setupDraft, ask.coach_text)
        XCTAssertEqual(StubProtocol.sent.count, before, "opening the request signs and sends no Card change")
        XCTAssertTrue(model.manages(id))
    }

    func testCurrencyExceptionsNeverBecomeApprovableHoldsAndDismissUsesKiffsCurrentVersion() async throws {
        let signer = RecordingSigner()
        let (model, tafID) = await model(signer: signer)
        let raw = #"{"id":"exc-example","kind":"card_unit","subject":"rae-k3f9","action":"answer_case","entity_id":"tc-example","status":"held","breaches":[{"outcome":"amount_unit_mismatch","unit":"USD","card_unit":"EUR"}],"count":4,"created_at":"2026-11-02T12:00:00Z","last_at":"2026-11-04T11:00:00Z","accounting_version":3,"can_approve_once":false}"#
        let row = #"{"id":"exc-example","kind":"card_unit","taf":"depl_1","name":"Rae","title":"Rae needs a USD limit","words":"Its Card only allows EUR","history":"Tried 4 times since 2 Nov","action_title":"Add a USD limit to this action","coach_text":"Add a USD limit to this action for Rae","dismiss_title":"Dismiss","can_approve_once":false}"#
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[\#(raw)]}"#)
        let list = HomeJSON.list([HomeJSON.rae])
        StubProtocol.responses["GET /v1/home"] = (200, String(list.dropLast()) + ",\"card_unit_exceptions\":[" + row + "]}")
        await model.refresh()
        XCTAssertTrue(model.held.isEmpty, "the special kind has no ordinary approve-once card")
        XCTAssertEqual(model.cardUnitExceptions.count, 1)
        let exception = try XCTUnwrap(model.cardUnitExceptions.first)
        XCTAssertFalse(exception.can_approve_once)
        StubProtocol.responses["GET /v1/tafs/depl_1/activity?filter=all"] = (200, #"{"entries":[],"groups":[],"filters":[],"filter":"all","revision":"r","card_unit_exceptions":[\#(row)]}"#)
        await model.loadActivityPage(tafID, filter: "all")
        XCTAssertEqual(model.activityPages[model.activityKey(tafID, "all")]?.pages.first?.card_unit_exceptions?.count, 1)
        StubProtocol.holdOnce = "GET /v1/tafs/depl_1/activity?filter=all"
        let earlierPage = Task { await model.loadActivityPage(tafID, filter: "all") }
        await untilHeld()
        StubProtocol.responses["GET /v1/me/card-exceptions/exc-example"] = (200, raw)
        StubProtocol.responses["POST /v1/me/card-exceptions/exc-example/reject"] = (200, "{}")
        StubProtocol.thenRespond["POST /v1/me/card-exceptions/exc-example/reject"] = [
            "GET /v1/home": (200, list), "GET /v1/me/card-exceptions?status=held": (200, #"{"exceptions":[]}"#)
        ]
        await model.dismissCardUnit(exception)
        XCTAssertTrue(model.cardUnitExceptions.isEmpty)
        XCTAssertEqual(model.activityPages[model.activityKey(tafID, "all")]?.pages.first?.card_unit_exceptions, [], "the cached Activity row also clears after KIFF confirms")
        StubProtocol.release.signal()
        await earlierPage.value
        XCTAssertEqual(model.activityPages[model.activityKey(tafID, "all")]?.pages.first?.card_unit_exceptions, [], "an older in-flight page cannot bring back the dismissed question")
        XCTAssertFalse(model.activityPages[model.activityKey(tafID, "all")]?.loading == true)
        let request = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/me/card-exceptions/exc-example/reject" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        let signature = try XCTUnwrap(body["signature"] as? [String: Any])
        XCTAssertNotNil(signature["signed_at"])
        XCTAssertNotNil(signature["value"])
        let signedAt = try XCTUnwrap(signature["signed_at"] as? String)
        let expected = KIFFClient.answerMessage(tenant: "t_01", exceptionID: "exc-example", answer: "reject", nextStep: nil, accountingVersion: 3, signedAt: signedAt)
        XCTAssertTrue(signer.signed.contains(String(decoding: expected, as: UTF8.self)))
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path.hasSuffix("/approve-once") == true })
        XCTAssertFalse(model.dismissingCardUnits.contains(exception.id))
        let count = StubProtocol.sent.filter { $0.url?.path.hasSuffix("/reject") == true }.count
        StubProtocol.responses["GET /v1/me/card-exceptions/exc-example"] = (200, raw.replacingOccurrences(of: "\"kind\":\"card_unit\"", with: "\"kind\":\"hold\""))
        await model.dismissCardUnit(exception)
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path.hasSuffix("/reject") == true }.count, count, "a changed kind is read again rather than signed from a stale row")
    }
    func testTerminalPagesKeepCloudWordsAndVersionIncludesTheBuild() throws {
        let activity = try KIFFClient.decoder.decode(TafsCloud.ActivityPage.self, from: Data(#"{"entries":[],"groups":[],"filters":[],"filter":"all","revision":"r","card_unit_exceptions":[],"card_unit_exceptions_notice":"Currency requests couldn't be read.","end_words":"Nothing older in this view"}"#.utf8))
        XCTAssertNil(activity.next_cursor)
        XCTAssertEqual(activity.card_unit_exceptions, [])
        XCTAssertEqual(activity.card_unit_exceptions_notice, "Currency requests couldn't be read.")
        XCTAssertEqual(activity.end_words, "Nothing older in this view")
        let runs = try KIFFClient.decoder.decode(TafsCloud.RunsPage.self, from: Data(#"{"requests":[],"title":"Its work","more_title":"Show all runs","end_words":"Nothing older in the retained runs"}"#.utf8))
        XCTAssertEqual(runs.end_words, "Nothing older in the retained runs")
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–"
        XCTAssertEqual(SettingsView.versionLabel(), build.map { "\(version) (\($0))" } ?? version)
    }
    func testConditionalActivityKeepsItsValidatorAndOpaqueCursor() async throws {
        StubProtocol.responses = [:]; StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "synthetic-session", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held"] = (200, #"{"entries":[],"groups":[],"filters":[{"id":"held","title":"Held"}],"filter":"held","revision":"r","next_cursor":"opaque+/="}"#)
        let (page, _) = try await cloud.activityPage("taf-example", filter: "held", cursor: nil, etag: nil, selection: "")
        XCTAssertEqual(page?.next_cursor, "opaque+/=")
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held"] = (304, "")
        let (same, tag) = try await cloud.activityPage("taf-example", filter: "held", cursor: nil, etag: "\"view-1\"", selection: "")
        XCTAssertNil(same); XCTAssertEqual(tag, "\"view-1\"")
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "If-None-Match"), tag)
        XCTAssertEqual(StubProtocol.sent.last?.cachePolicy, .reloadIgnoringLocalCacheData, "the app owns cached pages and must see the wire 304")
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held&cursor=opaque%2B%2F%3D"] = (304, "")
        _ = try await cloud.activityPage("taf-example", filter: "held", cursor: page?.next_cursor, etag: nil, selection: "")
        XCTAssertTrue(StubProtocol.sent.last?.url?.absoluteString.hasSuffix("cursor=opaque%2B%2F%3D") == true)
    }

    /// Two Stripe connections on one taf must not draw two Stripe nodes, one
    /// "Choose actions", one "2 actions". One node per app now, the one with actions in front,
    /// the other sign-in inside its sheet.
    func testOneRingNodePerApp() throws {
        func app(_ id: String, _ name: String, actions: Int, reconnect: Bool = false) -> String {
            #"{"id":"\#(id)","name":"\#(name)","status":"\#(actions > 0 ? "connected" : "listed")","status_words":"s","reconnect":\#(reconnect),"action_count":\#(actions),"action_words":"\#(actions > 0 ? "\(actions) actions" : "Choose actions")","actions":[],"steps":[],"workflow_steps":[],"running":false,"stats":{"calls":0,"done":0,"held":0,"words":"w","complete":true,"activity_query":"q"}}"#
        }
        let json = "[" + [app("g1", "Stripe", actions: 0), app("g2", "Slack", actions: 1), app("g3", "Stripe", actions: 2)].joined(separator: ",") + "]"
        let apps = try JSONDecoder().decode([TafsCloud.ConnectedApp].self, from: Data(json.utf8))
        let nodes = ConnectedToolsRing.nodes(apps)
        XCTAssertEqual(nodes.map(\.app.id), ["g3", "g2"], "Stripe once, in its first place, the one with actions shown")
        XCTAssertEqual(nodes[0].others.map(\.id), ["g1"])
        XCTAssertTrue(nodes[1].others.isEmpty)
        // With none that has actions, the one to sign in again comes first.
        let two = try JSONDecoder().decode([TafsCloud.ConnectedApp].self, from: Data(("[" + app("a", "Stripe", actions: 0) + "," + app("b", "Stripe", actions: 0, reconnect: true) + "]").utf8))
        XCTAssertEqual(ConnectedToolsRing.nodes(two).map(\.app.id), ["b"])
    }
}
