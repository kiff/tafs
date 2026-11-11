import XCTest
@testable import Tafs

/// A taf's health as tafs cloud says it (docs/reliability.md, "Its health"): skipped work is
/// never healthy, and each line that has a fix carries it. The app draws the lines and the fixes
/// tafs cloud decided, and decides nothing.
@MainActor
final class HealthTests: XCTestCase {
    private static let tooLarge = #"""
    {"level":"at_risk","lines":["Its last check found work too big for one run, so nothing ran","Two of its last ten runs failed"],
     "fixes":[{"line":0,"kind":"coach","words":"Narrow it with Coach","say":"Its last check found work too big for one run. Narrow what it reads, so each piece fits one run."}]}
    """#

    private func health(_ json: String) throws -> TafsCloud.Health {
        try KIFFClient.decoder.decode(TafsCloud.Health.self, from: Data(json.utf8))
    }

    /// The work page reads the health with its lines and fixes, and the skipped list says why
    /// in tafs cloud's words, for the new reasons too.
    func testTheWorkReadsItsHealthAndItsFixes() throws {
        let work = try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(#"""
        {"summary":{"status":"idle","line":"Nothing waits"},"requests":[],
         "skipped":[{"due":"2026-11-11T08:00:00.000Z","why":"too_large","words":"Skipped the 09:00 run: its work was too big for one run"},
                    {"due":"2026-11-11T07:00:00.000Z","why":"credits","words":"Skipped the 08:00 run: your account's tafs wait for credits"}],
         "health":\#(Self.tooLarge)}
        """#.utf8))
        let health = try XCTUnwrap(work.health)
        XCTAssertEqual(health.level, "at_risk")
        XCTAssertTrue(health.needsAttention)
        XCTAssertFalse(health.failing)
        XCTAssertEqual(health.fixes(for: 0).map(\.kind), ["coach"])
        XCTAssertEqual(health.fixes(for: 0).first?.say, "Its last check found work too big for one run. Narrow what it reads, so each piece fits one run.")
        XCTAssertEqual(health.fixes(for: 1), [], "a line with no fix has none")
        XCTAssertEqual(work.skipped.map(\.why), ["too_large", "credits"])
        XCTAssertEqual(work.skipped.last?.words, "Skipped the 08:00 run: your account's tafs wait for credits")
    }

    /// Credits open Plan & usage, with tafs cloud's words for who's reading. A fix of a kind the
    /// app doesn't know, a Coach fix with nothing to say, or a fix with no words is no button.
    func testOnlyAFixTheAppCanActOnIsDrawn() throws {
        let h = try health(#"""
        {"level":"failing","lines":["Waiting for credits since 09:30"],
         "fixes":[{"line":0,"kind":"credits","words":"Ask an admin for credits"},
                  {"line":0,"kind":"open","words":"Open it"},
                  {"line":0,"kind":"coach","words":"Narrow it with Coach"},
                  {"line":0,"kind":"credits","words":""},
                  {"kind":"credits","words":"Add credits"},
                  "not a fix"]}
        """#)
        XCTAssertTrue(h.failing)
        XCTAssertEqual(h.fixes(for: 0).map(\.words), ["Ask an admin for credits"])
        XCTAssertEqual(h.fixes.count, 5, "one fix that isn't an object is left out; the rest still read")
    }

    /// Healthy, or a level with no line to say why, draws nothing; a health that isn't one never
    /// fails the read it's on.
    func testHealthyDrawsNothingAndABadHealthNeverFailsTheRead() throws {
        XCTAssertFalse(try health(#"{"level":"healthy","lines":[]}"#).needsAttention)
        XCTAssertFalse(try health(#"{"level":"at_risk","lines":["", "  "]}"#).needsAttention, "no line, nothing to draw")
        XCTAssertFalse(try health(#"{"lines":["x"]}"#).needsAttention, "no level reads as healthy")
        XCTAssertTrue(try health(#"{"level":"worse","lines":["Something new"]}"#).needsAttention, "a level the app doesn't know is drawn as its lines")
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([
            HomeJSON.rae.replacingOccurrences(of: #""waiting":0,"#, with: #""waiting":0,"health":"broken","#),
        ]).utf8))
        XCTAssertEqual(home.tafs.first?.health?.needsAttention, false, "a health that isn't one reads as healthy")
        let older = try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(#"{"summary":{"status":"idle","line":""},"requests":[]}"#.utf8))
        XCTAssertNil(older.health, "an older tafs cloud sends none")
    }

    /// Home's taf carries its health onto the taf the app draws, so its row says why it isn't
    /// healthy in place of what it's doing.
    func testHomeCarriesTheHealthOntoTheTaf() throws {
        let entry = HomeJSON.rae.replacingOccurrences(of: #""waiting":0,"#, with: #""waiting":0,"health":\#(Self.tooLarge),"#)
        XCTAssertNotEqual(entry, HomeJSON.rae, "the fixture took the health")
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([entry]).utf8))
        let taf = KIFFMapping.taf(try XCTUnwrap(home.tafs.first), id: UUID(), owner: "owner")
        XCTAssertEqual(taf.health?.lines.first, "Its last check found work too big for one run, so nothing ran")
        XCTAssertEqual(taf.health?.fixes(for: 0).first?.words, "Narrow it with Coach")
        let plain = KIFFMapping.taf(try XCTUnwrap(KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([HomeJSON.rae]).utf8)).tafs.first),
                                    id: UUID(), owner: "owner")
        XCTAssertNil(plain.health, "no health sent, none drawn: the row shows what it's doing")
    }
}
