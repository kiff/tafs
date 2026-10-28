import XCTest
@testable import Tafs

/// The 930 test: a refresh the app makes by itself, back from the background on a connection
/// the system just closed, never shows "Not done". It says so quietly, tries again by itself,
/// and the line goes once a refresh gets through.
@MainActor
final class QuietRefreshTests: XCTestCase {
    // A drop left behind would fail the next test's sign-in.
    override func tearDown() {
        StubProtocol.dropped = [:]
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func signedIn() async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.dropped = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
        return model
    }

    func testALostConnectionIsSaidQuietlyAndTriedAgain() async throws {
        let model = await signedIn()
        model.lastError = nil
        StubProtocol.dropped["GET /v1/home"] = [.networkConnectionLost]
        await model.refresh()
        XCTAssertNil(model.lastError, "no alert for a refresh the app made by itself")
        XCTAssertEqual(model.unreachable, "Can't reach KIFF")

        // The retry a moment later gets through, and the line goes.
        let end = Date().addingTimeInterval(8)
        while model.unreachable != nil, Date() < end { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertNil(model.unreachable, "the retry got through")
        XCTAssertNil(model.lastError)
    }

    func testSigningOutClearsTheLine() async {
        let model = await signedIn()
        StubProtocol.dropped["GET /v1/home"] = [.notConnectedToInternet]
        await model.refresh()
        XCTAssertNotNil(model.unreachable)
        await model.signOut()
        XCTAssertNil(model.unreachable)
    }

    /// The words are true to what failed: the network, or KIFF answering with an error.
    func testTheWordsSayWhatFailed() {
        XCTAssertEqual(AppModel.refreshWords(URLError(.networkConnectionLost)), "Can't reach KIFF")
        XCTAssertEqual(AppModel.refreshWords(URLError(.notConnectedToInternet)), "Can't reach KIFF")
        XCTAssertEqual(AppModel.refreshWords(URLError(.badServerResponse)), "Couldn't load from KIFF")
        XCTAssertEqual(AppModel.refreshWords(KIFFClient.KIFFError.expired), "Couldn't load from KIFF")
        // A few tries, then none until the person comes back or pulls.
        XCTAssertEqual(AppModel.retryWaits, [2, 10, 30, 60, 120])
    }
}
