import XCTest
@testable import Tafs

/// The credits the tafs used (pricing phase 1, docs/accounts.md): read only from tafs cloud,
/// in its words, in Plan & usage and on a taf's page. The same read as Android's.
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
        StubProtocol.responses["GET /v1/me/usage/tasks"] = (200, #"{"items":[],"next":null}"#)
        await model.useSession("kiff_dev_x")
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id))
    }

    private static let period = #"""
    {"period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},
     "credits":1240,"words":"This month: 1,240 credits used · starts again on 1 Dec",
     "tafs":[{"taf":"depl_1","credits":820,"runs":41,"words":"Rae · 820 credits · 41 runs"},
             {"taf":"depl_2","credits":"lots","words":"unreadable"},
             {"taf":"depl_3","credits":420,"runs":28,"words":"Invoices · 420 credits · 28 runs"}],
     "not_counted":{"runs":12,"words":"Not counted: 12 runs",
                    "lines":[{"why":"refused","runs":3,"words":"Refused by KIFF · 3 runs"},{"why":"x"}]},
     "note":"Coach and setup aren't counted."}
    """#

    /// Plan & usage reads the period's credits beside the tasks, in tafs cloud's words, in its
    /// order; a line the app can't read is left out, not the read.
    func testThePeriodsCreditsAreReadInTafsCloudsWords() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        await model.refreshUsage()
        let c = try XCTUnwrap(model.credits)
        XCTAssertEqual(c.words, "This month: 1,240 credits used · starts again on 1 Dec")
        XCTAssertEqual(c.credits, 1240)
        XCTAssertEqual(c.tafs.map(\.words), ["Rae · 820 credits · 41 runs", "Invoices · 420 credits · 28 runs"], "tafs cloud's order, the unreadable one left out")
        XCTAssertEqual(c.notCounted?.words, "Not counted: 12 runs")
        XCTAssertEqual(c.notCounted?.lines.map(\.words), ["Refused by KIFF · 3 runs"])
        XCTAssertEqual(c.note, "Coach and setup aren't counted.")
        XCTAssertNotNil(model.usage, "the tasks are read as before")

        // The period before is read when it's asked for.
        StubProtocol.responses["GET /v1/usage/credits?period=previous"] = (200, #"{"credits":2310,"words":"October: 2,310 credits used"}"#)
        await model.readPreviousCredits()
        XCTAssertEqual(model.previousCredits?.words, "October: 2,310 credits used")
        XCTAssertEqual(model.previousCredits?.tafs, [])
    }

    /// A tafs cloud without the read (404) shows nothing, and Plan & usage reads as before: no
    /// deploy order. A read that fails later keeps what was read.
    func testNoCreditsReadShowsNothingAndTheTasksStay() async throws {
        let (model, _) = await kiffModel()
        await model.refreshUsage()
        XCTAssertNil(model.credits)
        XCTAssertNotNil(model.usage)
        XCTAssertNil(model.usageError)

        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        await model.refreshUsage()
        XCTAssertNotNil(model.credits)
        StubProtocol.responses["GET /v1/usage/credits"] = (503, #"{"error":"down"}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.credits?.credits, 1240, "a failed read keeps what was read")
    }

    /// A taf's runs, newest first, each in tafs cloud's words; a run with a Record entry opens
    /// it, and Earlier runs reads the next page after them.
    func testATafsRunsAreReadPageByPage() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1"] = (200, #"""
        {"credits":820,"words":"Rae · 820 credits this month",
         "runs":[{"run":"run_2","at":"2026-11-07T08:00:00Z","credits":6,"counted":true,"words":"On schedule · 6 credits","record":"call_9"},
                 {"run":"run_1","at":"2026-11-06T08:00:00Z","credits":0,"counted":false,"words":"Your chat · not counted: refused by KIFF"},
                 {"run":"bad"}],
         "next":"cur_1"}
        """#)
        await model.readTafCredits("depl_1")
        let key = AppModel.CreditsKey(taf: "depl_1", previous: false)
        XCTAssertEqual(model.tafCredits[key]?.words, "Rae · 820 credits this month")
        XCTAssertEqual(model.tafCredits[key]?.runs.map(\.words), ["On schedule · 6 credits", "Your chat · not counted: refused by KIFF"])
        XCTAssertEqual(model.tafCredits[key]?.runs.first?.record, "call_9")
        XCTAssertNil(model.tafCredits[key]?.runs.last?.record, "a chat that only answered opens nothing")

        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1?after=cur_1"] = (200, #"""
        {"credits":820,"words":"Rae · 820 credits this month",
         "runs":[{"run":"run_1","at":"2026-11-06T08:00:00Z","credits":0,"counted":false,"words":"Your chat · not counted: refused by KIFF"},
                 {"run":"run_0","at":"2026-11-05T08:00:00Z","credits":4,"counted":true,"words":"On schedule · 4 credits"}],
         "next":null}
        """#)
        await model.readTafCredits("depl_1", more: true)
        XCTAssertEqual(model.tafCredits[key]?.runs.map(\.run), ["run_2", "run_1", "run_0"], "added after, each once")
        XCTAssertNil(model.tafCredits[key]?.next)
        let pages = StubProtocol.sent.filter { $0.url?.path == "/v1/usage/credits/tafs/depl_1" }.count
        await model.readTafCredits("depl_1", more: true)
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path == "/v1/usage/credits/tafs/depl_1" }.count, pages, "no next page, nothing read")

        // The period before is its own read.
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1?period=previous"] = (200, #"{"credits":10,"words":"Rae · 10 credits in October","runs":[]}"#)
        await model.readTafCredits("depl_1", previous: true)
        XCTAssertEqual(model.tafCredits[.init(taf: "depl_1", previous: true)]?.words, "Rae · 10 credits in October")
        XCTAssertEqual(model.tafCredits[key]?.runs.count, 3, "this period's runs stay")
    }

    /// Signing out leaves no credits read.
    func testSigningOutForgetsTheCredits() async throws {
        let (model, _) = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits"] = (200, Self.period)
        StubProtocol.responses["GET /v1/usage/credits/tafs/depl_1"] = (200, #"{"credits":820,"words":"Rae · 820 credits this month"}"#)
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
        XCTAssertEqual(model.credits?.words, "This month: 1,240 credits used")
        await model.readPreviousCredits()
        XCTAssertEqual(model.previousCredits?.words, "Last month: 2,310 credits used")
    }
}
