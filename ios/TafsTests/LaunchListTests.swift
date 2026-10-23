import Combine
import XCTest
@testable import Tafs

/// The tafs list at launch (#91): one list per refresh, made by tafs cloud and shown as it
/// is, remembered for the next launch, and only ever this account's.
@MainActor
final class LaunchListTests: XCTestCase {
    private static let raePaused = HomeJSON.entry(agent: "rae-k3f9", name: "Rae", status: "paused", color: 2)
    private static let demo = HomeJSON.entry(agent: "support-demo", name: "support-demo", id: nil, color: 3)

    private var defaults: UserDefaults!
    private var suite = ""

    override func setUp() {
        super.setUp()
        suite = "tafs.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func model() -> AppModel {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        return AppModel(signer: StubSigner(),
                        kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                        cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
                        remembered: defaults)
    }

    /// tafs cloud's list, and no held calls.
    private func serve(tafs: [String] = [raePaused], others: [String] = [demo]) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list(tafs, others: others))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
    }

    private func kiffUnreachable() {
        for path in ["GET /v1/home", "GET /v1/me/card-exceptions?status=held"] {
            StubProtocol.responses[path] = (503, #"{"error":"unavailable"}"#)
        }
    }

    /// Signing in takes the sign-in screen away, which cancels its task: the first list
    /// still loads, with no pull to refresh (605 retest).
    func testSigningInLoadsTheListThoughTheSignInScreenGoesAway() async throws {
        serve()
        let model = model()
        let signIn = Task { await model.useSession("kiff_dev_x", tenant: "tenant-a") }
        signIn.cancel()
        await signIn.value
        XCTAssertTrue(model.listLoaded)
        XCTAssertEqual(model.tafs.map(\.name), ["Rae"])
    }

    /// A first refresh dropped before its answer (a view that went away, a newer refresh)
    /// read nothing: the list isn't shown as loaded and empty.
    func testACancelledFirstRefreshDoesntShowAnEmptyList() async throws {
        serve()
        let model = model()
        // A launch with nothing remembered, its task gone before KIFF answers.
        let launch = Task { await model.resumeSession("kiff_dev_x", tenant: "tenant-a", keyed: true) }
        launch.cancel()
        await launch.value
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertFalse(model.listLoaded, "nothing was read, so the list isn't shown as empty")
        // The next refresh reads it.
        await model.refresh()
        XCTAssertTrue(model.listLoaded)
        XCTAssertEqual(model.tafs.map(\.name), ["Rae"])
    }

    /// The owner's launch: no "working" first and "paused" a moment later. A refresh
    /// shows tafs cloud's list once, as it is, with other agents listed apart.
    func testARefreshShowsTheListOnceWithTafsCloudsStates() async throws {
        serve()
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")

        var shown: [[Taf]] = []
        let watching = model.$tafs.dropFirst().sink { shown.append($0) }
        await model.refresh()
        watching.cancel()

        XCTAssertEqual(shown.count, 1, "one list per refresh")
        XCTAssertEqual(model.tafs.map(\.name), ["Rae"], "tafs cloud's name, never the agent id")
        let rae = try XCTUnwrap(model.tafs.first)
        XCTAssertEqual(rae.state, .paused)
        XCTAssertEqual(rae.colorIndex, 2, "tafs cloud's colour, kept there")
        let demo = try XCTUnwrap(model.otherAgents.first { $0.name == "support-demo" }, "listed apart")
        XCTAssertEqual(demo.state, .idle, "an agent tafs cloud doesn't run isn't working")
        XCTAssertEqual(model.workingCount, 0)
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/me/mandates" }, "the app reads no Cards itself")
    }

    /// The next launch shows the last list before KIFF answers, here because it can't.
    func testTheNextLaunchShowsTheRememberedList() async throws {
        serve()
        let first = model()
        await first.useSession("kiff_dev_x", tenant: "tenant-a")
        let shownBefore = first.tafs

        kiffUnreachable()
        let next = model()
        XCTAssertTrue(next.tafs.isEmpty)
        // restore()'s KIFF branch, with the session it reads from the Keychain.
        await next.resumeSession("kiff_dev_x", tenant: "tenant-a", keyed: true)
        XCTAssertEqual(next.tafs.map(\.id), shownBefore.map(\.id), "the same tafs, with the same ids")
        XCTAssertEqual(next.tafs.map(\.name), shownBefore.map(\.name))
        XCTAssertEqual(next.tafs.first { $0.name == "Rae" }?.state, .paused)
        XCTAssertEqual(next.tafs.map(\.colorIndex), shownBefore.map(\.colorIndex))
        XCTAssertEqual(next.otherAgents.map(\.name), ["support-demo"])
        XCTAssertFalse(next.loadingList)
    }

    /// The remembered list is the account's it was shown for: never another's.
    func testAnotherAccountNeverSeesTheRememberedList() async throws {
        serve()
        let first = model()
        await first.useSession("kiff_dev_x", tenant: "tenant-a")
        XCTAssertFalse(first.tafs.isEmpty)

        kiffUnreachable()
        let other = model()
        await other.resumeSession("kiff_dev_x", tenant: "tenant-b", keyed: true)
        XCTAssertTrue(other.tafs.isEmpty, "tenant-a's tafs never show for tenant-b")
        // The same session for tenant-a shows them: the empty list above is the tenant's doing.
        let same = model()
        await same.resumeSession("kiff_dev_x", tenant: "tenant-a", keyed: true)
        XCTAssertFalse(same.tafs.isEmpty)
    }

    func testSignOutForgetsTheList() async throws {
        serve()
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")
        XCTAssertNotNil(defaults.data(forKey: AppModel.listKey))
        await model.signOut()
        XCTAssertNil(defaults.data(forKey: AppModel.listKey))
    }

    /// Until the first list, the headline doesn't say "0 tafs working".
    func testLoadingUntilTheFirstList() async throws {
        serve()
        StubProtocol.holdOnce = "GET /v1/me/card-exceptions?status=held"
        let model = model()
        let signingIn = Task { await model.useSession("kiff_dev_x", tenant: "tenant-a") }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
        XCTAssertTrue(model.loadingList)
        StubProtocol.release.signal()
        await signingIn.value
        XCTAssertFalse(model.loadingList)
    }

    /// A first launch where KIFF can't be read and nothing is remembered says so, rather
    /// than "No tafs yet"; the next list read clears it.
    func testAFirstLaunchKIFFCantReadSaysItCouldntLoad() async throws {
        serve()
        kiffUnreachable()
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")
        XCTAssertTrue(model.listUnread)
        XCTAssertFalse(model.loadingList)

        serve()
        await model.refresh()
        XCTAssertFalse(model.listUnread)
        XCTAssertFalse(model.tafs.isEmpty)
    }
}
