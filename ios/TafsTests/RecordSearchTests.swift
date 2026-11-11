import XCTest
@testable import Tafs

/// The Record searched across the person's tafs, one case, and receipts for an accountant
/// (docs/ios-app.md › The Record): what tafs cloud finds and words, read as it sends it; the app
/// keeps no copy of KIFF's receipts and rebuilds none. The same reads as Android's.
@MainActor
final class RecordSearchTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        try? FileManager.default.removeItem(at: AppModel.exportedFiles)
        super.tearDown()
    }

    private static func hit(_ id: String, about: String?, taf: String = "depl_1", name: String = "Rae") -> String {
        #"{"id":"\#(id)","at":"2026-11-11T09:00:00Z","kind":"done","title":"Refund a payment","detail":"€150 to Mara","time":"10:00","amount_text":"€150.00","call":{"proposal_id":"p_\#(id)","action_name":"create_refund","actor_id":"rae-k3f9","outcome":"allowed","held":false,"created_at":"2026-11-11T09:00:00Z"},"taf":{"id":"\#(taf)","name":"\#(name)"}"#
            + (about.map { #","about":"\#($0)""# } ?? "") + "}"
    }

    private static let hint = "Search by order, invoice, amount or action"
    private static let page1 = #"{"q":"1042","hint":"\#(hint)","entries":[\#(hit("e1", about: "Order 1042"))],"groups":[{"id":"2026-11-11","title":"Today","entries":[\#(hit("e1", about: "Order 1042"))]}],"next_cursor":"c2"}"#
    private static let page2 = #"{"q":"1042","hint":"\#(hint)","entries":[\#(hit("e2", about: nil))],"groups":[{"id":"2026-11-10","title":"Yesterday","entries":[\#(hit("e2", about: nil))]}],"end_words":"Nothing older"}"#

    /// Signed in with KIFF, with Rae (depl_1, rae-k3f9) as the one taf, and `access` as KIFF answers it.
    private func kiffModel(access: String = #"{"use":true,"oversee":true,"manage":true,"groups":[]}"#) async -> AppModel {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":\#(access)}}"#)
        await model.useSession("kiff_dev_x")
        await model.refreshPeople()
        return model
    }

    private func asked(_ prefix: String) -> [String] {
        StubProtocol.sent.compactMap(\.url).filter { $0.path == prefix }.map { $0.query ?? "" }
    }

    func testASearchReadsTafsCloudsGroupsAndHintThenItsNextPage() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/record?q=1042"] = (200, Self.page1)
        StubProtocol.responses["GET /v1/record?q=1042&cursor=c2"] = (200, Self.page2)
        await model.searchRecord(" 1042 ")
        let first = try XCTUnwrap(model.recordSearch)
        XCTAssertEqual(first.q, "1042", "what's typed, trimmed")
        XCTAssertEqual(first.pages.first?.shownGroups.map(\.title), ["Today"], "tafs cloud's groups")
        XCTAssertEqual(first.pages.first?.entries.first?.about, "Order 1042")
        XCTAssertEqual(first.pages.first?.entries.first?.taf, TafsCloud.RecordHit.Taf(id: "depl_1", name: "Rae"))
        XCTAssertEqual(first.pages.first?.entries.first?.entry.title, "Refund a payment", "drawn as the taf's Activity draws it")
        XCTAssertEqual(first.nextCursor, "c2")
        XCTAssertEqual(model.recordHint, Self.hint)
        XCTAssertEqual(model.tafID(cloudID: "depl_1"), model.tafs.first { $0.name == "Rae" }?.id, "a call of Rae's is drawn as Rae's Activity draws it")
        XCTAssertNil(model.tafID(cloudID: "depl_9"), "a taf the phone doesn't list")

        await model.searchRecord("1042", more: true)
        XCTAssertEqual(model.recordSearch?.pages.count, 2)
        XCTAssertNil(model.recordSearch?.nextCursor)
        XCTAssertEqual(model.recordSearch?.pages.last?.end_words, "Nothing older")
        XCTAssertEqual(asked("/v1/record"), ["q=1042", "q=1042&cursor=c2"])

        // A blank box clears the search: the Record shows as before.
        await model.searchRecord("  ")
        XCTAssertNil(model.recordSearch)
    }

    /// What's typed goes as one query value, whatever it holds.
    func testWhatsTypedIsSentAsOneValue() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/record?q=F%2D2026%2F001%26x%3D1"] = (200, #"{"q":"F-2026/001&x=1","entries":[],"groups":[],"none_words":"Nothing matches F-2026/001&x=1"}"#)
        await model.searchRecord("F-2026/001&x=1")
        let search = try XCTUnwrap(model.recordSearch)
        XCTAssertNil(search.error)
        XCTAssertTrue(search.pages.allSatisfy(\.isEmpty))
        XCTAssertEqual(search.pages.last?.none_words, "Nothing matches F-2026/001&x=1", "nothing found, in tafs cloud's words")
    }

    func testACursorTafsCloudRefusesSearchesAgainFromTheTop() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/record?q=1042"] = (200, Self.page1)
        StubProtocol.responses["GET /v1/record?q=1042&cursor=c2"] = (409, #"{"error":"search again"}"#)
        await model.searchRecord("1042")
        await model.searchRecord("1042", more: true)
        XCTAssertEqual(asked("/v1/record"), ["q=1042", "q=1042&cursor=c2", "q=1042"])
        XCTAssertEqual(model.recordSearch?.pages.count, 1)
        XCTAssertNil(model.recordSearch?.error)
    }

    func testAFailedSearchSaysWhyAndKeepsTheQuestion() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/record?q=1042"] = (503, #"{"error":"KIFF's activity couldn't be read","words":"The Record couldn't be read just now."}"#)
        await model.searchRecord("1042")
        XCTAssertEqual(model.recordSearch?.q, "1042")
        XCTAssertEqual(model.recordSearch?.error, "The Record couldn't be read just now.", "tafs cloud's words")
        XCTAssertEqual(model.recordSearch?.loading, false)
    }

    /// An older tafs cloud's page, or one without groups, still reads: every entry as one group.
    func testAPageWithoutGroupsOrWordsStillReads() throws {
        let page = try KIFFClient.decoder.decode(TafsCloud.RecordSearchPage.self, from: Data(#"{"entries":[\#(Self.hit("e1", about: "  "))]}"#.utf8))
        XCTAssertEqual(page.hint, "")
        XCTAssertEqual(page.shownGroups.count, 1)
        XCTAssertEqual(page.shownGroups.first?.title, "")
        XCTAssertNil(page.entries.first?.about, "a blank about opens no case")
        XCTAssertNil(page.next_cursor)
    }

    func testACaseReadsItsStepsOldestFirstAndTheStepThatFailed() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/record/cases/Order 1042"] = (200,
            #"{"about":"Order 1042","steps":[\#(Self.hit("s1", about: "Order 1042")),\#(Self.hit("s2", about: "Order 1042"))],"failed":{"id":"s2","words":"The reply to Mara didn't go.","next":{"kind":"try_later","words":"Try later"}}}"#)
        await model.openRecordCase("Order 1042")
        let sent = try XCTUnwrap(StubProtocol.sent.last?.url?.absoluteString)
        XCTAssertTrue(sent.hasSuffix("/v1/record/cases/Order%201042"), "the case's reference is sent URL-encoded: \(sent)")
        let opened = try XCTUnwrap(model.recordCase)
        XCTAssertEqual(opened.steps.map(\.id), ["s1", "s2"])
        XCTAssertEqual(opened.failed?.words, "The reply to Mara didn't go.")
        XCTAssertEqual(opened.failed?.next?.kind, "try_later")
        model.closeRecordCase()
        XCTAssertNil(model.recordCase)

        // None of theirs is about it: why, in words.
        StubProtocol.responses["GET /v1/record/cases/Order 9999"] = (404, #"{"error":"no such case in the Record"}"#)
        await model.openRecordCase("Order 9999")
        XCTAssertEqual(model.recordCase?.about, "Order 9999", "the sheet stays open, saying why")
        XCTAssertNotNil(model.recordCaseError)
    }

    /// An invoice's reference holds a "/": it goes escaped, as one segment.
    func testACaseReferenceWithASlashIsOneSegment() async throws {
        let model = await kiffModel()
        await model.openRecordCase("F-2026/001")
        let sent = try XCTUnwrap(StubProtocol.sent.last?.url?.absoluteString)
        XCTAssertTrue(sent.hasSuffix("/v1/record/cases/F-2026%2F001"), sent)
    }

    func testTheExportKeepsTafsCloudsAnswerByteForByteAndItsCsv() async throws {
        let model = await kiffModel()
        // Spaced, and with 150.50: a re-encoded copy would differ, so the file is proven to be the answer as it came.
        let body = #"{"from": "2026-11-05", "to": "2026-11-11", "time_zone": "Europe/Paris", "receipts": [{"trace_id": "t1", "sig": "0xab", "amount": 150.50}], "csv": "day,taf\n2026-11-05,Rae\n", "missing": 2}"#
        StubProtocol.responses["GET /v1/record/receipts?from=2026-11-05&to=2026-11-11"] = (200, body)
        let result = await model.exportReceipts(from: "2026-11-05", to: "2026-11-11")
        let files = try XCTUnwrap(result.files, result.why ?? "")
        XCTAssertEqual(files.map(\.lastPathComponent), ["receipts-2026-11-05-to-2026-11-11.json", "receipts-2026-11-05-to-2026-11-11.csv"])
        XCTAssertEqual(try Data(contentsOf: files[0]), Data(body.utf8), "tafs cloud's answer byte for byte: KIFF's receipts as issued")
        XCTAssertEqual(try String(contentsOf: files[1], encoding: .utf8), "day,taf\n2026-11-05,Rae\n")
        XCTAssertEqual(result.missing, 2)
        XCTAssertTrue(files.allSatisfy { $0.path.hasPrefix(AppModel.exportedFiles.path) }, "removed with the opened files at sign-out")

        // Someone who doesn't see every taf: tafs cloud's 403, in words.
        StubProtocol.responses["GET /v1/record/receipts?from=2026-11-05&to=2026-11-11"] = (403, #"{"error":"only those who see every taf's Record can export it"}"#)
        let refused = await model.exportReceipts(from: "2026-11-05", to: "2026-11-11")
        XCTAssertNil(refused.files)
        XCTAssertNotNil(refused.why)
    }

    func testTheExportIsOfferedOnlyToWhoeverSeesEveryTafsRecord() async {
        let manager = await kiffModel()
        XCTAssertTrue(manager.seesEveryTaf)
        let overseer = await kiffModel(access: #"{"use":false,"oversee":true,"manage":false,"groups":[]}"#)
        XCTAssertTrue(overseer.seesEveryTaf)
        let user = await kiffModel(access: #"{"use":true,"oversee":false,"manage":false,"groups":[]}"#)
        XCTAssertFalse(user.seesEveryTaf)
    }

    /// The last 7 days are today and the six before, as the account's calendar days.
    func testTheRangeCountsTodayAsADay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Kiritimati")!
        // 23:30 UTC on 10 Nov is 13:30 on 11 Nov in Kiritimati (UTC+14).
        let now = ISO8601DateFormatter().date(from: "2026-11-10T23:30:00Z")!
        XCTAssertEqual(ExportReceiptsRow.range(7, today: now, calendar: calendar).from, "2026-11-05")
        XCTAssertEqual(ExportReceiptsRow.range(7, today: now, calendar: calendar).to, "2026-11-11")
        XCTAssertEqual(ExportReceiptsRow.range(90, today: now, calendar: calendar).from, "2026-08-14")
        XCTAssertEqual(ExportReceiptsRow.days, [7, 30, 90], "inside tafs cloud's 92")
    }

    /// The sample has no Record across tafs: nothing is searched or sent.
    func testTheSampleSearchesNothing() async {
        StubProtocol.sent = []
        let model = AppModel(signer: StubSigner())
        await model.searchRecord("1042")
        await model.openRecordCase("Order 1042")
        XCTAssertNil(model.recordSearch)
        XCTAssertNil(model.recordCase)
        XCTAssertFalse(model.seesEveryTaf)
        XCTAssertEqual(StubProtocol.sent.count, 0)
    }
}
