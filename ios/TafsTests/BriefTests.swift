import XCTest
@testable import Tafs

/// "Your brief" (Missions design v2.1 §7, draft): `GET /v1/brief` read leniently in v2.1's field names,
/// drawn as sent; a recommendation is shown, never acted on; Needs you stays until there's a brief.
@MainActor
final class BriefTests: XCTestCase {
    private func brief(_ json: String) throws -> TafsCloud.Brief {
        try KIFFClient.decoder.decode(TafsCloud.Brief.self, from: Data(json.utf8))
    }

    // MARK: The live route: the switch is tafs cloud answering it

    private static let liveBrief = #"""
    {"decisions":[{"id":"d1","question":"Pay 3 supplier bills due Friday, €1,240?","why":"All three match their orders.","meanwhile":"these three wait.",
      "answers":[{"words":"Pay the 3","answer":"approve","recommended":true},{"words":"Not now","answer":"refuse","decline":true}],
      "later":{"words":"Later"},"ref":{"kind":"hold","id":"ce_1","taf":"depl_1"}}]}
    """#

    private func stubbedSession() -> URLSession {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func kiffModel() async -> AppModel {
        let session = stubbedSession()
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        return model
    }

    /// The route's own shape (docs/api.md, `GET /v1/brief`): the tab's name in `words.title`, why a
    /// decision can't wait in `now_why`, and a held call's and a step's `ref`.
    func testTheRoutesShapeAsTafsCloudServesIt() throws {
        let b = try brief(#"""
        {"at":"2026-11-16T07:00:00Z","next_at":"2026-11-16T17:00:00Z",
         "decisions":[{"id":"hold:ce_1","question":"Refund order 4502, €480.00?","answers":[{"words":"Approve","answer":"approve","recommended":false},{"words":"Refuse","answer":"refuse","recommended":false}],
                       "meanwhile":"Nothing is sent.","urgent":true,"now_why":"It lapses at 08:30, before the next brief.","ref":{"kind":"hold","id":"ce_1","taf":"depl_1"}},
                      {"id":"step:h1","question":"Sign the delivery note","answers":[{"words":"Done","answer":"done","recommended":false}],
                       "meanwhile":"Rae waits.","urgent":false,"later":{"words":"Later"},"ref":{"kind":"step","id":"h1","taf":"depl_1"}}],
         "done":[],"rest":{"count":3,"words":"3 things wait that need nothing from you now"},"labels":[],
         "words":{"title":"Your brief","decisions":"Decisions · 2","done":"Done","rest":"The rest"}}
        """#)
        XCTAssertEqual(b.words.tab, "Your brief", "the tab's name, from `title`")
        XCTAssertEqual(b.decisions.map(\.id), ["hold:ce_1", "step:h1"])
        XCTAssertEqual(b.decisions[0].cantWait, "It lapses at 08:30, before the next brief.")
        XCTAssertNil(b.decisions[0].later, "urgent: no Later")
        XCTAssertEqual(b.decisions[1].later, "Later")
        XCTAssertEqual(AppModel.destination(of: b.decisions[0], answer: b.decisions[0].answers[0]), "hold:ce_1")
        XCTAssertEqual(AppModel.destination(of: b.decisions[1], answer: b.decisions[1].answers[0]), "step:h1")
    }

    /// The brief is read with every refresh, not only when an alert or a label chip asks for it: a
    /// signed-in phone or Mac shows it as soon as tafs cloud serves one (the owner's Mac build 7 never
    /// read it).
    func testARefreshReadsTheBrief() async {
        let session = stubbedSession()
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/brief"] = (200, Self.liveBrief)
        await model.useSession("kiff_dev_x")
        XCTAssertNotNil(model.brief, "read with the sign-in's refresh")
        XCTAssertTrue(StubProtocol.sent.contains { $0.url?.path == "/v1/brief" })
        StubProtocol.responses["GET /v1/brief"] = (404, #"{"error":"not found"}"#)
        await model.refresh()
        XCTAssertNil(model.brief, "and dropped when tafs cloud stops serving it")
        XCTAssertEqual(model.needsYouTabTitle, Words.NeedsYou.title)
    }

    func testNoRouteYetMeansNoBriefAndNeedsYouAsToday() async {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/brief"] = (404, #"{"error":"not found"}"#)
        await model.loadBrief()
        XCTAssertNil(model.brief)
        XCTAssertEqual(model.needsYouTabTitle, Words.NeedsYou.title)
    }

    func testOnceTheRouteAnswersTheBriefShows() async {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/brief"] = (200, Self.liveBrief)
        await model.loadBrief()
        XCTAssertEqual(model.briefDecisions.map(\.id), ["d1"])
    }

    func testAnAnswerOpensWhereItsSignedAnswerIsGivenNeverAnsweringHere() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/brief"] = (200, Self.liveBrief)
        await model.loadBrief()
        let decision = try XCTUnwrap(model.briefDecisions.first)
        StubProtocol.sent = []
        model.answerBrief(decision, decision.answers[0])
        XCTAssertEqual(model.requestedHold, .init(kiffID: "ce_1", answer: nil),
                       "the held call opens on its own card with nothing chosen: never the notification's path, where an unlock counts as Face ID")
        model.answerBrief(decision, decision.answers[1])
        XCTAssertEqual(model.requestedHold, .init(kiffID: "ce_1", answer: nil), "a decline too")
        XCTAssertTrue(StubProtocol.sent.allSatisfy { $0.httpMethod == "GET" }, "nothing is sent or signed from the brief")
        XCTAssertEqual(model.briefDecisions.map(\.id), ["d1"], "it stays until it's answered there")
        var unknown = decision
        unknown.ref = .init(kind: "proposal", id: "p1")
        XCTAssertEqual(AppModel.destination(of: unknown, answer: decision.answers[0]), "needs-you")
        unknown.ref = .init(kind: "hold", id: "")
        XCTAssertEqual(AppModel.destination(of: unknown, answer: decision.answers[0]), "needs-you")
    }

    /// A taf whose scheduled check keeps failing (`ref.kind: check`): "Open the taf" opens that taf's page,
    /// found by tafs cloud's id; a taf the app doesn't list, or no id, opens Needs you.
    func testACheckDecisionOpensItsTaf() async throws {
        let model = await kiffModel()
        var decision = try brief(Self.liveBrief).decisions[0]
        decision.ref = .init(kind: "check", id: "", taf: "depl_1")
        let destination = AppModel.destination(of: decision, answer: decision.answers[0])
        XCTAssertEqual(destination, "taf:depl_1", "opened by the taf, whatever its id")
        model.open(destination)
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" })
        XCTAssertEqual(model.requestedTab, .tafs)
        XCTAssertEqual(model.requestedTafPush, rae.id, "the phone's Home pushes its page")
        XCTAssertEqual(model.requestedTaf, rae.id, "a wide screen's sidebar selects it")
        model.requestedTab = nil
        model.open("taf:depl_gone")
        XCTAssertEqual(model.requestedTab, .needsYou, "a taf the app doesn't list opens Needs you")
        decision.ref = .init(kind: "check", id: "c1", taf: "")
        XCTAssertEqual(AppModel.destination(of: decision, answer: decision.answers[0]), "needs-you")
    }

    /// Needs you stays reachable whatever the brief shows, matched by item: equal counts of different items
    /// still leave a link (a held call Later moved out, a step the brief doesn't carry).
    func testNeedsYouStaysReachableMatchedByItemNeverByNumber() {
        func decision(_ kind: String, _ id: String) -> TafsCloud.Brief.Decision {
            var d = TafsCloud.Brief.Decision()
            d.id = "\(kind):\(id)"; d.ref = .init(kind: kind, id: id)
            return d
        }
        let brief = [decision("hold", "ce_1"), decision("step", "h1"), decision("hold", "ce_2")]
        XCTAssertEqual(AppModel.needsYouBeyondBrief(items: ["hold:ce_1", "step:h1", "hold:ce_9"], others: 0, brief: brief), 1,
                       "three and three, but one of Needs you's isn't in the brief")
        XCTAssertEqual(AppModel.needsYouBeyondBrief(items: ["hold:ce_1", "step:h1"], others: 0, brief: brief), 0, "the brief shows it all")
        XCTAssertEqual(AppModel.needsYouBeyondBrief(items: ["hold:ce_1"], others: 2, brief: brief), 2, "what the brief can't carry counts")
        XCTAssertEqual(AppModel.needsYouBeyondBrief(items: ["step:ce_1"], others: 0, brief: brief), 1, "matched by kind too")
    }

    func testLaterMovesItOnlyOnceTafsCloudHas() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/brief"] = (200, Self.liveBrief)
        StubProtocol.responses["POST /v1/brief/decisions/d1/later"] = (200, "{}")
        await model.loadBrief()
        let decision = try XCTUnwrap(model.briefDecisions.first)
        model.laterBrief(decision)
        for _ in 0..<50 where !model.briefDecisions.isEmpty { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertNotNil(decision.later, "the decision offers Later")
        XCTAssertEqual(StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path.hasPrefix("/v1/brief") == true }.map { $0.url?.path },
                       ["/v1/brief/decisions/d1/later"], "Later's route, once")
    }

    /// tafs cloud's decision ids ("hold:ce_1") reach its Later route as written: the ":" isn't escaped.
    func testLatersRouteKeepsTheDecisionIdAsWritten() async throws {
        let session = stubbedSession()
        StubProtocol.responses["POST /v1/brief/decisions/hold:ce_1/later"] = (200, #"{"id":"hold:ce_1","until":"2026-11-16T17:00:00Z"}"#)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "dev_token", session: session)
        try await cloud.briefLater("hold:ce_1")
        let sent = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" })
        XCTAssertEqual(sent.url?.absoluteString, "https://cloud.test/v1/brief/decisions/hold:ce_1/later")
    }

    /// A heading tafs cloud didn't send is the shared catalog's (`brief.*`), never blank; one it sent wins.
    func testAHeadingTafsCloudDidntSendIsTheSharedCatalogs() throws {
        let bare = try brief(#"{"decisions":[]}"#)
        XCTAssertEqual(bare.words.title, Words.Brief.title)
        XCTAssertEqual(bare.words.tab, Words.Brief.title, "the tab is the title")
        XCTAssertEqual(bare.words.decisions, Words.Brief.decisions)
        XCTAssertEqual(bare.words.done, Words.Brief.done)
        XCTAssertEqual(bare.words.nextMove, Words.Brief.nextMove)
        XCTAssertEqual(bare.words.recommends, Words.Brief.recommends)
        XCTAssertEqual(bare.words.meanwhile, Words.Brief.meanwhile)
        XCTAssertEqual(bare.words.evidence, Words.Brief.evidence)
        XCTAssertEqual(bare.words.urgent, Words.Brief.urgent)
        XCTAssertEqual(bare.words.when, "", "no shared word for when: it's tafs cloud's or nothing")
        XCTAssertEqual(try brief(#"{"decisions":[],"words":"Your brief"}"#).words, .shared, "words of another shape are as none")
        let sent = try brief(#"{"decisions":[],"words":{"title":"Morning brief","decisions":"Decisions · 2","urgent":" "}}"#)
        XCTAssertEqual(sent.words.title, "Morning brief")
        XCTAssertEqual(sent.words.tab, "Morning brief")
        XCTAssertEqual(sent.words.decisions, "Decisions · 2")
        XCTAssertEqual(sent.words.urgent, Words.Brief.urgent, "a blank word counts as not sent")
        XCTAssertEqual(sent.words.done, Words.Brief.done)
    }

    /// A mission's log names itself and how it reads in the shared words (`mission.*`), and marks a removed
    /// taf's entries.
    func testTheLogsOwnWordsAreTheSharedCatalogs() throws {
        let view = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tafs/Views/BriefView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains(".navigationTitle(Words.Mission.log)"))
        XCTAssertTrue(view.contains("Text(Words.Mission.readOnly)"))
        XCTAssertTrue(view.contains("if item.removed { Text(Words.Mission.removed)"))
        XCTAssertFalse(Words.Mission.log.isEmpty)
    }

    func testTheBriefReadsV21sFieldsAsSent() throws {
        let b = try brief(#"""
        {"at":"2026-11-14T08:00:00+01:00","next_at":"2026-11-14T18:00:00+01:00",
         "decisions":[{"id":"d1","question":"Pay 3 supplier bills due Friday, €1,240?",
                       "answers":[{"words":"Pay the 3","answer":"approve","recommended":true},{"words":"Not now","answer":"reject","decline":true}],
                       "why":"All three match their orders.","meanwhile":"these three wait.","deadline":"by Fri 12:00","urgent":false,
                       "later":{"words":"Later"},"ref":{"kind":"hold","id":"h1","taf":"t1"}},
                      {"id":"d2","question":"Pay Mercier €2,100 before 12:00?","answers":[{"words":"Pay €2,100","answer":"approve","recommended":true}],
                       "why":"It matches order 0388.","meanwhile":"held.","urgent":true,"cant_wait":"This can't wait: it expires at 12:00.","ref":{"kind":"hold","id":"h2","taf":"t1"}},
                      {"id":"d3","question":"","answers":[]}, 7],
         "more":{"count":2,"words":"2 more decisions"},
         "done":[{"mission":"Money","cases":[{"name":"Brio Bikes, invoice 0139","words":"paid Thursday · closed","state":"closed"},{"words":"x","state":"waiting","next_check":"Friday"}],"value":"€3,400 due back","log":"m1"},{"cases":[]}],
         "next_move":{"words":"Prepare an escalation summary?","cases":["0142"],"start":"Start","dismiss":"Dismiss"},
         "rest":{"count":14,"words":"14 things wait that need nothing from you now"},
         "credits":{"words":"Today: 38 credits."},
         "labels":[{"id":"money","name":"Money","color":2,"count":5,"waiting":true},{"id":"","name":"x"}],
         "words":{"tab":"Your brief","title":"Your brief","decisions":"Decisions · 2","next_move":"Next move","recommends":"Coach recommends"}}
        """#)
        XCTAssertEqual(b.decisions.map(\.id), ["d1", "d2"], "an empty question, no answers, or another shape: left out")
        let d1 = try XCTUnwrap(b.decisions.first)
        XCTAssertEqual(d1.recommended?.words, "Pay the 3")
        XCTAssertEqual(d1.answers.last?.decline, true)
        XCTAssertEqual(d1.later, "Later")
        XCTAssertEqual(d1.ref, .init(kind: "hold", id: "h1", taf: "t1"))
        let d2 = b.decisions[1]
        XCTAssertTrue(d2.urgent)
        XCTAssertNil(d2.later, "it can't be put off")
        XCTAssertEqual(d2.cantWait, "This can't wait: it expires at 12:00.")
        XCTAssertEqual(b.more?.words, "2 more decisions")
        XCTAssertEqual(b.done.count, 1, "a group with no cases: left out")
        XCTAssertTrue(b.done[0].cases[0].closed)
        XCTAssertEqual(b.done[0].cases[1].nextCheck, "Friday")
        XCTAssertEqual(b.nextMove?.start, "Start")
        XCTAssertEqual(b.rest?.count, 14)
        XCTAssertEqual(b.credits, "Today: 38 credits.")
        XCTAssertEqual(b.labels.map(\.id), ["money"])
        XCTAssertEqual(b.words.nextMove, "Next move")
    }

    /// Coach's chats waiting on the person (`coach_waiting`, tafs cloud's line from Needs you's own count):
    /// drawn only with a count above 0 and its words; another shape is left out, never the brief.
    func testCoachWaitingIsReadOnlyWithACountAndItsWords() throws {
        let b = try brief(#"{"decisions":[],"coach_waiting":{"count":17,"words":"17 Coach chats wait for your answer."}}"#)
        XCTAssertEqual(b.coachWaiting, .init(count: 17, words: "17 Coach chats wait for your answer."))
        XCTAssertNil(try brief(#"{"coach_waiting":{"count":0,"words":"0 Coach chats wait for your answer."}}"#).coachWaiting)
        XCTAssertNil(try brief(#"{"coach_waiting":{"count":2,"words":"  "}}"#).coachWaiting)
        let other = try brief(#"{"coach_waiting":"17","rest":{"count":3,"words":"3 things wait"}}"#)
        XCTAssertNil(other.coachWaiting)
        XCTAssertEqual(other.rest?.count, 3, "the rest of the brief still reads")
        XCTAssertNil(try brief(#"{}"#).coachWaiting, "an older tafs cloud sends none")
    }

    /// With no why, no answer is drawn as recommended: they show as equals.
    func testARecommendationNeedsItsWhy() throws {
        let b = try brief(#"{"decisions":[{"id":"d","question":"q","answers":[{"words":"Yes","answer":"approve","recommended":true},{"words":"No","answer":"reject"}],"meanwhile":"m","ref":{}}]}"#)
        XCTAssertNil(b.decisions.first?.recommended)
    }

    /// The sample's brief filters as tafs cloud does: the parcels' label (`money`) keeps its decisions and
    /// cases, drops the leads' and Support's, and its rest line counts only its own. Its missions read from
    /// the shared words, so a translated sample keeps them.
    func testTheSampleFiltersByLabel() {
        let all = TafsCloud.Brief.sample()
        let money = TafsCloud.Brief.sample(label: TafsCloud.Brief.sampleMoney)
        let label = Words.Sample.Brief.Label.self
        XCTAssertEqual(all.done.compactMap(\.mission), [label.money, label.clients, label.support])
        XCTAssertEqual(money.done.compactMap(\.mission), [label.money])
        XCTAssertEqual(all.rest?.count, 14)
        XCTAssertEqual(money.rest?.count, 9)
        XCTAssertEqual(all.decisions.count, 2)
        XCTAssertTrue(all.decisions.allSatisfy { $0.recommended != nil }, "each with Coach's pick and its why")
    }

    /// A mission's log reads `{at, by, kind, words, evidence, value?}`; a removed taf's entry stays, marked.
    func testTheLogReadsItsItemsAndKeepsARemovedTafsEntries() throws {
        let log = try KIFFClient.decoder.decode(TafsCloud.MissionLog.self, from: Data(#"""
        {"items":[{"at":"2026-11-14T07:02:00+01:00","by":"taf","kind":"delivered","words":"12 bills sorted.","evidence":[{"kind":"run","id":"r1","words":"run"},{"kind":"x"}],"value":"€4,120 sorted"},
                  {"at":"2026-11-13T09:12:00+01:00","by":"taf","kind":"delivered","words":"3 quotes drafted.","removed":true},{"words":""}],
         "before":"c1"}
        """#.utf8))
        XCTAssertEqual(log.items.count, 2)
        XCTAssertEqual(log.items[0].evidence.map(\.words), ["run"])
        XCTAssertEqual(log.items[0].value, "€4,120 sorted")
        XCTAssertTrue(log.items[1].removed)
        XCTAssertEqual(log.before, "c1")
        XCTAssertTrue(TafsCloud.Brief.sampleLog().items.contains { $0.removed })
    }

    /// The tafs list's chips: none without labels, so the list is as today; a chip keeps only its tafs,
    /// in the same order, and All gives them all back.
    /// The log as tafs cloud serves it (kiff/tafs#1030's `logPage`): `{items, before?}`, each item
    /// `{id, at, by: {kind, name?}, kind, words, evidence: [{kind, id}], removed?}`.
    func testTheLogReadsTafsCloudsShape() throws {
        let json = #"""
        {"items":[
          {"id":"log_r2","at":"2026-11-16T07:10:00Z","by":{"kind":"taf","name":"Bill pay"},"kind":"delivered","words":"6 done · 1 held for you","evidence":[{"kind":"run","id":"r2"},{"kind":"item","id":"inv_0412"}]},
          {"id":"log_r1","at":"2026-11-16T06:10:00Z","by":{"kind":"taf"},"kind":"delivered","words":"3 done","evidence":[{"kind":"run","id":"r1"}],"removed":true},
          {"id":"log_n1","at":"2026-11-16T06:00:00Z","by":{"kind":"lead"},"kind":"note","words":"Started the month's close.","evidence":[]}
        ],"before":"log_n1"}
        """#
        let log = try JSONDecoder().decode(TafsCloud.MissionLog.self, from: Data(json.utf8))
        XCTAssertEqual(log.items.map(\.id), ["log_r2", "log_r1", "log_n1"])
        XCTAssertEqual(log.items.map(\.byWords), ["Bill pay", Words.NeedsYou.aTaf, Words.Coach.title],
                       "a removed taf has no name; the lead is Coach")
        XCTAssertEqual(log.items.map(\.removed), [false, true, false])
        XCTAssertEqual(log.items[0].kind, "delivered")
        XCTAssertTrue(log.items[0].evidence.isEmpty, "refs only, never shown as data")
        XCTAssertEqual(log.before, "log_n1")
    }

    func testTheTafsListsChipsFilterWithoutReordering() throws {
        let model = AppModel()
        XCTAssertTrue(model.listLabels.isEmpty, "no labels: no chips")
        XCTAssertNil(model.listLabel)
        model.filterList(TafsCloud.Brief.sampleMoney)
        XCTAssertEqual(model.listLabel, TafsCloud.Brief.sampleMoney)
        model.filterList(nil)
        XCTAssertNil(model.listLabel)
        XCTAssertEqual(TafsCloud.Brief.sampleLabels.map { $0.label.id }, [TafsCloud.Brief.sampleMoney, TafsCloud.Brief.sampleClients, "ops"])

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let filtered = String(app[try XCTUnwrap(app.range(of: "func filtered(_ tafs: [Taf]) -> [Taf] {")).upperBound...].prefix(200))
        XCTAssertTrue(filtered.contains("return tafs.filter {"), "a filter keeps the list's order")
        let home = try String(contentsOf: root.appendingPathComponent("Views/HomeView.swift"), encoding: .utf8)
        XCTAssertTrue(home.contains("if !model.listLabels.isEmpty { LabelChips("))
        XCTAssertTrue(home.contains("TafLabels(taf: taf)"))
        let overview = try String(contentsOf: root.appendingPathComponent("Views/ShareViews.swift"), encoding: .utf8)
        XCTAssertTrue(overview.contains("ForEach(model.filtered(model.listedTafs)) { taf in"))
    }

    /// Home's labels as tafs cloud sends them (docs/labels.md): in order, `waiting` a count, `headline` an
    /// object, `labels_words.all`, and each taf's ids. A label or a field the app can't read is left out,
    /// never Home; with none sent, no chips and the line as before.
    func testHomesLabelsReadAsSent() throws {
        let read = { (extra: String, taf: String) throws -> TafsCloud.Home in
            try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(#"{"tafs":[{"id":"t1","name":"Rae","job":"j","kiff_agent":"rae","status":"active","next_run":null,"last_run":null,"color":0,"state":"idle","waiting":0,"card":{"ids":[],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]}\#(taf)}],"others":[],"removing":[]\#(extra)}"#.utf8))
        }
        let home = try read(#","labels":[{"id":"l1","name":"Money","color":"money","count":2,"waiting":1,"headline":{"words":"2 tafs working · Money"}},{"id":"l2","name":"Ops","color":"operations","count":1,"waiting":0},{"id":"","name":"x"},7],"labels_words":{"all":"All"}"#,
                            #","labels":["l1","l2"]"#)
        let labels = try XCTUnwrap(home.labels?.items)
        XCTAssertEqual(labels.map(\.id), ["l1", "l2"], "in order; one with no id, and one that isn't a label, left out")
        XCTAssertEqual(labels[0].waiting, 1)
        XCTAssertEqual(labels[0].color, "money")
        XCTAssertEqual(labels[0].headline, "2 tafs working · Money")
        XCTAssertNil(labels[1].headline)
        XCTAssertEqual(home.labelsWords?.all, "All")
        XCTAssertEqual(home.tafs.first?.cloudTaf?.labels, ["l1", "l2"])

        let none = try read("", "")
        XCTAssertNil(none.labels)
        XCTAssertEqual(none.tafs.first?.cloudTaf?.labels, [], "missing is none")
        let odd = try read(#","labels":{"id":"l1"},"labels_words":"All""#, #","labels":"l1""#)
        XCTAssertEqual(odd.labels?.items ?? [], [], "another shape: none, and Home still reads")
        XCTAssertNil(odd.labelsWords?.all)
        XCTAssertEqual(odd.tafs.first?.cloudTaf?.labels, [])

        let model = AppModel()
        XCTAssertTrue(model.listLabels.isEmpty, "no labels: no chips")
        XCTAssertEqual(model.listHeadline, model.workingWords, "and the line as before")
        model.filterList("gone")
        XCTAssertEqual(model.listHeadline, model.workingWords, "a label that's gone: the whole list's line")
        XCTAssertEqual(model.labelsAllWords, Words.Home.labelsAll)
    }

    /// The urgent alert (the owner's mockup 3): a brief's or an urgent push opens the brief, and only those;
    /// an urgent card has tafs cloud's word on its badge and never offers Later.
    func testTheUrgentAlertOpensTheBriefAndItsCardHasNoLater() throws {
        XCTAssertEqual(PushDelegate.briefDestination(["brief_kind": "urgent", "decision": "hold:ce_1"]), "needs-you")
        XCTAssertEqual(PushDelegate.briefDestination(["brief_kind": "brief"]), "needs-you")
        XCTAssertNil(PushDelegate.briefDestination(["brief_kind": "hold"]), "another kind opens as it says")
        XCTAssertNil(PushDelegate.briefDestination(["kind": "brief"]), "`kind` is an alert's other field, not the brief's")
        XCTAssertNil(PushDelegate.briefDestination([:]))
        XCTAssertNil(PushDelegate.briefDestination(["brief_kind": 1]))

        let words = try KIFFClient.decoder.decode(TafsCloud.Brief.Words.self, from: Data(#"{"urgent":"Urgent","all":"All"}"#.utf8))
        XCTAssertEqual(words.urgent, "Urgent")

        let brief = TafsCloud.Brief.sample(urgent: true)
        let first = try XCTUnwrap(brief.decisions.first)
        XCTAssertTrue(first.urgent, "the urgent one leads")
        XCTAssertNil(first.later)
        XCTAssertNotNil(first.cantWait)
        XCTAssertFalse(TafsCloud.Brief.sample().decisions.contains { $0.urgent }, "only with -briefUrgent")

        // The model refuses Later on an urgent decision too, even one sent with a Later.
        let model = AppModel()
        var putOff = first
        putOff.later = "Later"
        model.laterBrief(putOff)
        XCTAssertFalse(model.briefSetAside.contains(putOff.id), "an urgent decision is never put off")
        putOff.urgent = false
        model.laterBrief(putOff)
        XCTAssertTrue(model.briefSetAside.contains(putOff.id))

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/BriefView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("let later = decision.urgent ? nil : decision.later"), "no Later on an urgent card, whatever was sent")
        let push = try String(contentsOf: root.appendingPathComponent("Services/PushDelegate.swift"), encoding: .utf8)
        XCTAssertTrue(push.contains("} else if destination == nil, let brief = Self.briefDestination(info) {"), "an alert's own `open` still wins")
    }

    /// Needs you stays, unchanged, until there's a brief; its tab then says tafs cloud's word for it.
    /// Answering from the brief signs nothing.
    func testNeedsYouStaysUntilThereIsABriefAndNothingIsSignedFromIt() throws {
        let model = AppModel()
        XCTAssertNil(model.brief)
        XCTAssertEqual(model.needsYouTabTitle, Words.NeedsYou.title)

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/BriefView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("} else {\n            NeedsYouView()\n        }"), "no brief: Needs you, as today")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(app.range(of: "func answerBrief("))
        let end = try XCTUnwrap(app.range(of: "func dismissNextMove()", range: start.upperBound..<app.endIndex))
        let actions = String(app[start.upperBound..<end.lowerBound])
        XCTAssertFalse(actions.contains("signer.") || actions.contains("sign("), "a recommendation is shown, never acted on")
        let load = String(app[try XCTUnwrap(app.range(of: "func loadBrief() async {")).upperBound...].prefix(400))
        XCTAssertTrue(load.contains("if mode == .sample, Screenshots.brief {"), "only the sample's, until tafs cloud's flag is on")
        XCTAssertTrue(load.contains("brief = nil"))
    }
}
