import XCTest
@testable import Tafs

@MainActor
final class PresentationTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount); Keychain.delete(AppModel.tenantAccount); Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    private func model() async -> (AppModel, UUID) {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: RecordingSigner(), kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session), cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
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
    func testTerminalPagesKeepCloudWordsAndVersionIncludesTheBuild() throws {
        let activity = try KIFFClient.decoder.decode(TafsCloud.ActivityPage.self, from: Data(#"{"entries":[],"groups":[],"filters":[],"filter":"all","revision":"r","end_words":"Nothing older in this view"}"#.utf8))
        XCTAssertNil(activity.next_cursor)
        XCTAssertEqual(activity.end_words, "Nothing older in this view")
        let runs = try KIFFClient.decoder.decode(TafsCloud.RunsPage.self, from: Data(#"{"requests":[],"title":"Its work","more_title":"Show all runs","end_words":"Nothing older in the retained runs"}"#.utf8))
        XCTAssertEqual(runs.end_words, "Nothing older in the retained runs")
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–"
        XCTAssertEqual(SettingsView.versionLabel(), build.map { "\($0) (\(version))" } ?? version)
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
}
