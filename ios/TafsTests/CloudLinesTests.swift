import XCTest
@testable import Tafs

/// tafs cloud's lines drawn as they come (docs/api.md, docs/reliability.md): the next run with its
/// day, what went wrong in place of "Working", firings that started no run, the Cards in its order.
@MainActor
final class CloudLinesTests: XCTestCase {
    /// Rae's home entry with tafs cloud's next run and, when given, its work line.
    private func rae(work: String? = nil) throws -> TafsCloud.HomeTaf {
        var entry = HomeJSON.entry(agent: "rae-k3f9", name: "Rae")
            .replacingOccurrences(of: #""next_run":null"#, with: #""next_run":"2026-11-16T07:00:00Z","next_run_words":"Next run Mon 08:00""#)
        if let work { entry = String(entry.dropLast()) + #","work":\#(work)}"# }
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([entry]).utf8))
        return try XCTUnwrap(home.tafs.first)
    }

    /// A bare "Next run at 08:00" read as tomorrow when the run was Monday's: tafs cloud's words,
    /// with the day, are drawn as they are, on the home list and the taf's page.
    func testTheNextRunIsTafsCloudsWordsWithItsDay() throws {
        let h = try rae()
        XCTAssertEqual(h.nextRunWords, "Next run Mon 08:00")
        XCTAssertEqual(h.cloudTaf?.nextRunWords, "Next run Mon 08:00", "the taf's page reads the same words")
        let taf = KIFFMapping.taf(h, id: UUID(), owner: "You")
        XCTAssertEqual(taf.now, "Next run Mon 08:00")
        XCTAssertFalse(taf.attention)
    }

    /// What went wrong last comes before the next run, flagged: its line is drawn, not "Working".
    func testAnAttentionLineTakesTheNextRunsPlace() throws {
        let h = try rae(work: #"{"status":"idle","line":"Its check couldn't read the work list at 08:00","needs_you":0,"attention":true}"#)
        XCTAssertEqual(h.work?.attention, true)
        let taf = KIFFMapping.taf(h, id: UUID(), owner: "You")
        XCTAssertTrue(taf.attention)
        XCTAssertEqual(taf.healthNow, "Its check couldn't read the work list at 08:00")
        XCTAssertEqual(taf.state, .working, "its state is unchanged; only what's drawn for it")
        // A line without the flag is the plain next run.
        let calm = KIFFMapping.taf(try rae(work: #"{"status":"idle","line":"Next run Mon 08:00","needs_you":0}"#), id: UUID(), owner: "You")
        XCTAssertFalse(calm.attention)
        XCTAssertNil(calm.healthNow)
    }

    /// Its runs' first page carries the firings that started no run, a failed Run now among them.
    func testItsRunsCarryTheFiringsThatStartedNoRun() throws {
        let json = #"{"requests":[],"next_cursor":null,"title":"Its runs","more_title":"All its runs","skipped":[{"due":"2026-11-11T21:29:00Z","why":"check_failed","words":"22:29: its check couldn't read the work list, so no run started"}]}"#
        let page = try KIFFClient.decoder.decode(TafsCloud.RunsPage.self, from: Data(json.utf8))
        XCTAssertEqual(page.skipped?.map(\.words), ["22:29: its check couldn't read the work list, so no run started"])
        let none = try KIFFClient.decoder.decode(TafsCloud.RunsPage.self, from: Data(#"{"requests":[],"next_cursor":null,"title":"Its runs","more_title":"All"}"#.utf8))
        XCTAssertNil(none.skipped, "an older tafs cloud sends none")
    }

    /// The Record's title keeps the action's whole title: "Save $800" read as saving the money.
    func testARecordTitleKeepsTheWholeActionTitle() throws {
        var entry = RecordEntry(tafID: UUID(), kind: .done, title: "Save a dispute response", detail: "", at: Date())
        entry.amount = .init(value: "800", unit: "USD")
        let money = try XCTUnwrap(entry.amountText)
        XCTAssertEqual(entry.titleWithAmount, "Save a dispute response · \(money)")
        entry.amount = nil
        XCTAssertEqual(entry.titleWithAmount, "Save a dispute response")
    }
}
