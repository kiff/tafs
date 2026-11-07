import XCTest
@testable import Tafs

/// The credits the tafs used (docs/accounts.md › Credits): read only from tafs cloud, in its
/// words, in Plan & usage. The same read as Android's.
@MainActor
final class CreditsTests: XCTestCase {
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

    /// Signed in with KIFF, with Rae as the one taf, run by tafs cloud as depl_1.
    private func kiffModel() async -> (AppModel, UUID) {
        let session = stubbedSession()
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/me/usage"] = (200, PurchasesTests.read)
        await model.useSession("kiff_dev_x")
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id))
    }

    /// The period read as docs/accounts.md › Credits gives it, with lines the app can't read.
    private static let period = #"""
    {"period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z","current":true},
     "credits":1240,"runs":61,"words":"This period: 1,240 credits used · ends on 1 Dec",
     "tafs":[{"taf":"depl_1","credits":820,"runs":41,"words":"Rae · 820 credits · 41 runs"},
             {"taf":"depl_2","credits":"lots","words":"unreadable"},
             {"taf":null,"credits":374,"runs":18,"words":"Removed tafs · 374 credits · 18 runs"}],
     "coach":{"credits":46,"runs":2,"words":"Coach, past its fair use · 46 credits · 2 runs"},
     "not_counted":{"runs":12,"words":"Not counted: 12 runs",
                    "lines":[{"why":"coach","runs":7,"words":"Coach, within its fair use · 7 runs"},{"why":"x"},
                             {"why":"something_new","runs":2,"words":"Something new · 2 runs"}]},
     "note":"Credits will replace tasks. Nothing is charged in credits yet."}
    """#

    /// Plan & usage reads the period's credits below the balance, in tafs cloud's words, in its
    /// order; a line the app can't read is left out, not the read.
    func testThePeriodsCreditsAreReadInTafsCloudsWords() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        await model.refreshUsage()
        let c = try XCTUnwrap(model.credits)
        XCTAssertEqual(c.words, "This period: 1,240 credits used · ends on 1 Dec")
        XCTAssertEqual(c.credits, 1240)
        XCTAssertEqual(c.runs, 61)
        XCTAssertEqual(c.period?.current, true)
        XCTAssertEqual(c.tafs.map(\.words), ["Rae · 820 credits · 41 runs", "Removed tafs · 374 credits · 18 runs"], "tafs cloud's order, the unreadable one left out")
        XCTAssertEqual(c.tafs.map(\.taf), ["depl_1", nil], "the removed tafs' row has no taf, so it doesn't open")
        XCTAssertEqual(c.coach?.words, "Coach, past its fair use · 46 credits · 2 runs")
        XCTAssertEqual(c.notCounted?.words, "Not counted: 12 runs")
        XCTAssertEqual(c.notCounted?.lines.map(\.words), ["Coach, within its fair use · 7 runs", "Something new · 2 runs"], "a reason the app doesn't know shows by its words")
        XCTAssertEqual(c.note, "Credits will replace tasks. Nothing is charged in credits yet.")
        XCTAssertNotNil(model.usage, "KIFF's read, for the store and the requests, as before")

        // The period before is read when it's asked for.
        StubProtocol.responses["GET /v1/usage/credits?period=previous"] = (200,
            #"{"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z","current":false},"credits":980,"runs":50,"words":"Last period: 980 credits used · 1 Oct to 31 Oct","tafs":[],"coach":null}"#)
        await model.readPreviousCredits()
        XCTAssertEqual(model.previousCredits?.words, "Last period: 980 credits used · 1 Oct to 31 Oct")
        XCTAssertEqual(model.previousCredits?.period?.current, false)
        XCTAssertEqual(model.previousCredits?.tafs, [])
        XCTAssertNil(model.previousCredits?.coach, "Coach within its fair use has no row")
    }

    /// A tafs cloud without the read (404) shows nothing, and the rest of Plan & usage reads as
    /// before: no deploy order. A read that fails later keeps what was read.
    func testNoCreditsReadShowsNothingAndTheRestStays() async throws {
        let (model, _) = await kiffModel()
        await model.refreshUsage()
        XCTAssertNil(model.credits)
        XCTAssertNotNil(model.usage)
        XCTAssertNil(model.usageError)

        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        await model.refreshUsage()
        XCTAssertNotNil(model.credits)
        StubProtocol.responses["GET /v1/usage/credits"] = (503, #"{"error":"kiff unavailable","words":"Credits can't be read right now. Try again in a minute."}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.credits?.credits, 1240, "a failed read keeps what was read")
        XCTAssertEqual(model.creditsError, "Credits can't be read right now. Try again in a minute.", "tafs cloud's words, as Android shows them")
        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        await model.refreshUsage()
        XCTAssertNil(model.creditsError, "a read that works clears it")

        // A taf's runs that can't be read say so, in tafs cloud's words.
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1"] = (503, #"{"error":"kiff unavailable","words":"Its runs can't be read right now."}"#)
        await model.readTafCredits("depl_1")
        XCTAssertEqual(model.tafCreditsError[.init(taf: "depl_1", previous: false)], "Its runs can't be read right now.")
    }

    /// A taf's runs, newest first, each in tafs cloud's words; a run opens its request while
    /// `opens` says so, and Earlier runs reads the next page after them.
    func testATafsRunsAreReadPageByPage() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1"] = (200, #"""
        {"period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z","current":true},
         "taf":"depl_1","credits":820,"runs":41,"words":"Rae · 820 credits · 41 runs this period",
         "items":[{"run":"run_2","at":"2026-11-07T08:00:41Z","credits":6,"counted":true,"why":null,"words":"Its schedule · 6 credits","opens":true},
                  {"run":"run_1","at":"2026-11-06T17:12:09Z","credits":0,"counted":false,"why":"trial","words":"A trial of a new version · not counted","opens":false},
                  {"run":"bad"}],
         "next":"cur_1"}
        """#)
        await model.readTafCredits("depl_1")
        let key = AppModel.CreditsKey(taf: "depl_1", previous: false)
        XCTAssertEqual(model.tafCredits[key]?.words, "Rae · 820 credits · 41 runs this period")
        XCTAssertEqual(model.tafCredits[key]?.runs, 41)
        XCTAssertEqual(model.tafCredits[key]?.items.map(\.words), ["Its schedule · 6 credits", "A trial of a new version · not counted"])
        XCTAssertEqual(model.tafCredits[key]?.items.map(\.opens), [true, false], "a trial's row never opens")
        XCTAssertEqual(model.tafCredits[key]?.items.last?.why, "trial")
        XCTAssertNil(model.tafCredits[key]?.items.first?.why)
        XCTAssertNotNil(model.tafID(cloud: "depl_1"), "a run's row opens its request on the taf this app knows")

        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1?after=cur_1"] = (200, #"""
        {"credits":820,"runs":41,"words":"Rae · 820 credits · 41 runs this period",
         "items":[{"run":"run_1","at":"2026-11-06T17:12:09Z","credits":0,"counted":false,"why":"trial","words":"A trial of a new version · not counted","opens":false},
                  {"run":"run_0","at":"2026-11-05T08:00:00Z","credits":4,"counted":true,"why":null,"words":"From its chat · 4 credits","opens":true}],
         "next":null}
        """#)
        await model.readTafCredits("depl_1", more: true)
        XCTAssertEqual(model.tafCredits[key]?.items.map(\.run), ["run_2", "run_1", "run_0"], "added after, each once")
        XCTAssertNil(model.tafCredits[key]?.next)
        let pages = StubProtocol.sent.filter { $0.url?.path == "/v1/usage/credits/tafs/depl_1" }.count
        await model.readTafCredits("depl_1", more: true)
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path == "/v1/usage/credits/tafs/depl_1" }.count, pages, "no next page, nothing read")

        // The period before is its own read.
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1?period=previous"] = (200, #"{"credits":10,"runs":1,"words":"Rae · 10 credits · 1 run last period","items":[]}"#)
        await model.readTafCredits("depl_1", previous: true)
        XCTAssertEqual(model.tafCredits[.init(taf: "depl_1", previous: true)]?.words, "Rae · 10 credits · 1 run last period")
        XCTAssertEqual(model.tafCredits[key]?.items.count, 3, "this period's runs stay")
    }

    /// Signing out leaves no credits read.
    func testSigningOutForgetsTheCredits() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1"] = (200, #"{"credits":820,"runs":41,"words":"Rae · 820 credits · 41 runs this period","items":[]}"#)
        await model.refreshUsage()
        await model.readTafCredits("depl_1")
        XCTAssertNotNil(model.credits)
        await model.signOut()
        XCTAssertNil(model.credits)
        XCTAssertNil(model.previousCredits)
        XCTAssertTrue(model.tafCredits.isEmpty)
    }

    /// The sample shows credits too, in the same words, with the period before.
    func testTheSampleShowsCredits() async {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        await model.refreshUsage()
        XCTAssertEqual(model.credits?.words, "This period: 1,240 credits used · ends on 30 Nov")
        await model.readPreviousCredits()
        XCTAssertEqual(model.previousCredits?.words, "Last period: 2,310 credits used · 1 Oct to 31 Oct")
    }
}
