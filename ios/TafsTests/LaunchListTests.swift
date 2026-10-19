import Combine
import XCTest
@testable import Tafs

/// The tafs list at launch (#91): one list per refresh, with tafs cloud's states in it,
/// remembered for the next launch, and only ever this account's.
@MainActor
final class LaunchListTests: XCTestCase {
    private static let raeCard = #"{"id":"rae-k3f9-stripe_refund","subject":"rae-k3f9","grants":[],"aggregates":[],"active":true}"#
    private static let demoCard = #"{"id":"support-demo-card","subject":"support-demo","grants":[],"aggregates":[],"active":true}"#
    private static let raePaused = #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"paused"}]}"#

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

    private func serve(cards: [String], cloud: String = raePaused) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let list = #"{"mandates":["# + cards.joined(separator: ",") + "]}"
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, list) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, cloud)
    }

    private func kiffUnreachable() {
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use", "GET /v1/me/card-exceptions?status=held"] {
            StubProtocol.responses[path] = (503, #"{"error":"unavailable"}"#)
        }
    }

    /// The owner's launch: no "working" first and "paused" a moment later. A refresh
    /// shows the list once, with tafs cloud's names and states already in it.
    func testARefreshShowsTheListOnceWithTafsCloudsStates() async throws {
        serve(cards: [Self.raeCard, Self.demoCard])
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")

        var shown: [[Taf]] = []
        let watching = model.$tafs.dropFirst().sink { shown.append($0) }
        await model.refresh()
        watching.cancel()

        XCTAssertEqual(shown.count, 1, "one list per refresh")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }, "tafs cloud's name, never the agent id")
        XCTAssertEqual(rae.state, .paused)
        let demo = try XCTUnwrap(model.tafs.first { $0.name == "support-demo" })
        XCTAssertEqual(demo.state, .idle, "an agent tafs cloud doesn't run isn't working")
        XCTAssertEqual(model.workingCount, 0)
    }

    /// The next launch shows the last list before KIFF answers, here because it can't.
    func testTheNextLaunchShowsTheRememberedList() async throws {
        serve(cards: [Self.raeCard, Self.demoCard])
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
        XCTAssertFalse(next.loadingList)
    }

    /// The remembered list is the account's it was shown for: never another's.
    func testAnotherAccountNeverSeesTheRememberedList() async throws {
        serve(cards: [Self.raeCard])
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
        serve(cards: [Self.raeCard])
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")
        XCTAssertNotNil(defaults.data(forKey: AppModel.listKey))
        await model.signOut()
        XCTAssertNil(defaults.data(forKey: AppModel.listKey))
    }

    /// Until the first list, the headline doesn't say "0 tafs working".
    func testLoadingUntilTheFirstList() async throws {
        serve(cards: [Self.raeCard])
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
        serve(cards: [Self.raeCard])
        kiffUnreachable()
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")
        XCTAssertTrue(model.listUnread)
        XCTAssertFalse(model.loadingList)

        serve(cards: [Self.raeCard])
        await model.refresh()
        XCTAssertFalse(model.listUnread)
        XCTAssertFalse(model.tafs.isEmpty)
    }

    /// A new taf that sorts first doesn't take the others' colours (the owner took the new
    /// Refunds assistant for the old Refunds when it did).
    func testATafKeepsItsColourWhenANewOneSortsBeforeIt() async throws {
        serve(cards: [Self.raeCard])
        let model = model()
        await model.useSession("kiff_dev_x", tenant: "tenant-a")
        let raeColour = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.colorIndex)

        let first = #"{"id":"aaa-card","subject":"aaa-new","grants":[],"aggregates":[],"active":true}"#
        serve(cards: [first, Self.raeCard])
        await model.refresh()
        XCTAssertEqual(model.tafs.first { $0.name == "Rae" }?.colorIndex, raeColour)
        XCTAssertNotEqual(model.tafs.first { $0.name == "aaa-new" }?.colorIndex, raeColour, "the new taf takes a colour no other taf has")
    }
}
