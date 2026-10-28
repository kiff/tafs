import XCTest
@testable import Tafs

/// Signing in with KIFF (kiff/tafs#58): the phone shows a code and polls while the owner
/// approves in the browser, where iOS drops the app's connections.
@MainActor
final class SignInTests: XCTestCase {
    private static let start = #"{"device_code":"dc_1","user_code":"WXYZ-1234","verification_uri":"https://app.kiff.dev/auth/device","verification_uri_complete":"https://app.kiff.dev/auth/device?code=WXYZ-1234","expires_in":600,"interval":5}"#
    private static let token = #"{"access_token":"kiff_dev_new","expires_in":7776000,"tenant":"t_01","subject":"u_1"}"#

    private func model() -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.dropped = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.tokenAccount)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: nil, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: nil, session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: FaceIDSigner(), kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        // What a new session loads.
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        model.signInSlice = 1_000_000
        model.signInMinimumInterval = 0
        return model
    }

    private func start() throws -> KIFFClient.DeviceStart {
        try JSONDecoder().decode(KIFFClient.DeviceStart.self, from: Data(Self.start.utf8))
    }

    private func polls() -> Int {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == "/v1/auth/device/token" }.count
    }

    /// A2: a connection dropped while the owner approves in the browser, or KIFF briefly
    /// failing, doesn't end the sign-in: it polls on until the approval arrives.
    func testADroppedConnectionKeepsPolling() async throws {
        let model = model()
        let poll = "POST /v1/auth/device/token"
        StubProtocol.dropped[poll] = [.networkConnectionLost, .notConnectedToInternet]
        StubProtocol.responses[poll] = (400, #"{"error":"authorization_pending"}"#)
        StubProtocol.thenRespond[poll] = [poll: (503, #"{"error":"unavailable"}"#)]
        // The 503 is served, then the approval.
        let s = try start()
        let finished = Task { try await model.finishSignIn(s, startedAt: Date()) }
        // A poll is counted when it's sent, before its answer is chosen: once the fifth
        // is sent, the fourth was answered 503, so the approval can't pre-empt it.
        while polls() < 5 { try await Task.sleep(nanoseconds: 1_000_000) }
        StubProtocol.responses[poll] = (200, Self.token)
        try await finished.value
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertEqual(model.kiff.token, "kiff_dev_new")
        XCTAssertGreaterThanOrEqual(polls(), 5, "two dropped, one pending, one 503, then the token")
        Keychain.delete(AppModel.tokenAccount)
    }

    /// An expired code still ends it, with the error the owner reads.
    func testAnExpiredCodeEndsIt() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/auth/device/token"] = (400, #"{"error":"expired_token"}"#)
        do {
            try await model.finishSignIn(try start(), startedAt: Date())
            XCTFail("an expired code signs nothing in")
        } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .expired)
        }
        XCTAssertEqual(model.mode, .signedOut)
    }

    /// Back in the foreground, the phone asks KIFF at once rather than after the interval.
    func testComingBackPollsAtOnce() async throws {
        let model = model()
        model.signInMinimumInterval = 60
        StubProtocol.responses["POST /v1/auth/device/token"] = (200, Self.token)
        model.nudgeSignIn()
        let began = Date()
        try await model.finishSignIn(try start(), startedAt: Date())
        XCTAssertLessThan(Date().timeIntervalSince(began), 30, "it didn't wait the minute (a slow CI runner took 9 s)")
        XCTAssertEqual(polls(), 1)
        XCTAssertEqual(model.kiffTenant, "t_01")
        Keychain.delete(AppModel.tokenAccount)
    }

    /// A3: Try again starts a new sign-in, even when the first request after the app came
    /// back meets a connection iOS dropped.
    func testTryAgainGetsANewCode() async throws {
        let model = model()
        let begin = "POST /v1/auth/device/start"
        StubProtocol.dropped[begin] = [.networkConnectionLost]
        StubProtocol.responses[begin] = (200, Self.start)
        let s = try await model.startSignIn()
        XCTAssertEqual(s.userCode, "WXYZ-1234")
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path == "/v1/auth/device/start" }.count, 2, "tried again once")

        // A failure that isn't the network's still ends it, for the owner to see.
        StubProtocol.responses[begin] = (400, #"{"error":"bad public key"}"#)
        do { _ = try await model.startSignIn(); XCTFail("a refusal isn't retried") } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .server(status: 400, message: "bad public key"))
        }
    }
}
