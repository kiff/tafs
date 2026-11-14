import XCTest
@testable import Tafs

/// "Your brief" (Missions design v2.1 §7, draft): `GET /v1/brief` read leniently in v2.1's field names,
/// drawn as sent; a recommendation is shown, never acted on; Needs you stays until there's a brief.
@MainActor
final class BriefTests: XCTestCase {
    private func brief(_ json: String) throws -> TafsCloud.Brief {
        try KIFFClient.decoder.decode(TafsCloud.Brief.self, from: Data(json.utf8))
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

    /// With no why, no answer is drawn as recommended: they show as equals.
    func testARecommendationNeedsItsWhy() throws {
        let b = try brief(#"{"decisions":[{"id":"d","question":"q","answers":[{"words":"Yes","answer":"approve","recommended":true},{"words":"No","answer":"reject"}],"meanwhile":"m","ref":{}}]}"#)
        XCTAssertNil(b.decisions.first?.recommended)
    }

    /// The sample's brief filters as tafs cloud does: Money keeps its decisions and cases, drops Support's,
    /// and its rest line counts only its own.
    func testTheSampleFiltersByLabel() {
        let all = TafsCloud.Brief.sample()
        let money = TafsCloud.Brief.sample(label: TafsCloud.Brief.sampleMoney)
        XCTAssertEqual(all.done.compactMap(\.mission), ["Money", "Support"])
        XCTAssertEqual(money.done.compactMap(\.mission), ["Money"])
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
        XCTAssertEqual(model.labelsAllWords, Words.Record.Filter.all)
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
