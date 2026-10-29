import XCTest
@testable import Tafs

/// Alerts that reach a closed app: the phone points the account's KIFF alert target at
/// tafs cloud with a signed setup call, and hands tafs cloud the target's secret and its
/// push token.
@MainActor
final class AlertsTests: XCTestCase {
    private let token = Data([0xab, 0x01, 0xff])

    private func model(signer: ApprovalSigning = StubSigner(), allowed: Bool = true) -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.alertHandoffAccount)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud)
        model.askNotificationPermission = { allowed }
        model.requestPushToken = { [weak model, token] in Task { await model?.pushTokenArrived(token) } }
        return model
    }

    private func signedIn(_ model: AppModel) async {
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    private func sent(_ method: String, _ host: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.host == host && $0.url?.path == path }
    }

    func testTurningOnPointsKIFFAtTafsCloudAndHandsItTheSecret() async throws {
        let signer = RecordingSigner()
        let model = model(signer: signer)
        await signedIn(model)
        StubProtocol.responses["POST /v1/me/alert-targets"] = (201, #"{"id":"at_1","url":"https://cloud.test/v1/kiff/alerts","signing_secret":"whsec_abc"}"#)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":true}"#)

        let on = await model.turnOnAlerts()
        XCTAssertTrue(on)
        XCTAssertEqual(model.alertsReady, true)

        let target = try XCTUnwrap(sent("POST", "api.test", "/v1/me/alert-targets").first)
        XCTAssertEqual(body(target)["url"] as? String, "https://cloud.test/v1/kiff/alerts")
        XCTAssertNotNil(target.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a phone's setup call is signed")
        XCTAssertTrue(signer.signed.contains("tafs.alerts.v1|t1"), "Face ID first")

        let push = try XCTUnwrap(sent("POST", "cloud.test", "/v1/push").last)
        XCTAssertEqual(body(push)["device_token"] as? String, "ab01ff")
        XCTAssertEqual(body(push)["environment"] as? String, AppModel.pushEnvironment)
        let handed = try XCTUnwrap(body(push)["alert_target"] as? [String: Any])
        XCTAssertEqual(handed["id"] as? String, "at_1")
        XCTAssertEqual(handed["signing_secret"] as? String, "whsec_abc")
        XCTAssertNil(model.kiff.setupSigner, "nothing signs afterwards")
    }

    func testNothingIsSentWithoutNotificationsOrFaceID() async {
        let denied = model(allowed: false)
        await signedIn(denied)
        StubProtocol.sent = []
        let off = await denied.turnOnAlerts()
        XCTAssertFalse(off)
        XCTAssertNotNil(denied.lastError)
        XCTAssertTrue(StubProtocol.sent.isEmpty)

        let refused = model(signer: RefusingSigner())
        await signedIn(refused)
        StubProtocol.sent = []
        let stillOff = await refused.turnOnAlerts()
        XCTAssertFalse(stillOff)
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/alert-targets").isEmpty)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/push").filter { body($0)["alert_target"] != nil }.isEmpty)
    }

    func testANewTokenIsHandedOverAgainWithoutTheSecret() async throws {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":false}"#)
        await model.pushTokenArrived(token)
        let push = try XCTUnwrap(sent("POST", "cloud.test", "/v1/push").last)
        XCTAssertEqual(body(push)["device_token"] as? String, "ab01ff")
        XCTAssertNil(body(push)["alert_target"], "a token alone carries no secret")
        XCTAssertEqual(model.alertsReady, false, "tafs cloud has no secret for the account yet: the card stays")
    }

    /// The 901 test: Sound off in Settings goes to tafs cloud at once, with the token; on by default.
    func testSoundOffIsHandedOverWithTheToken() async throws {
        UserDefaults.standard.removeObject(forKey: AppModel.alertSoundKey)
        defer { UserDefaults.standard.removeObject(forKey: AppModel.alertSoundKey) }
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":true}"#)
        await model.pushTokenArrived(token)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/push").last)["sound"] as? Bool, true, "alerts sound unless turned off")
        model.alertSound = false
        await model.syncPush()
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/push").last)["sound"] as? Bool, false)
        XCTAssertFalse(model.alertSound, "kept on this device")
    }

    func testKIFFRefusingTheTargetSaysWhy() async {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/me/alert-targets"] = (403, #"{"error":"phone_alert_url_forbidden","message":"from a phone, alerts go only to an app backend KIFF lists"}"#)
        let on = await model.turnOnAlerts()
        XCTAssertFalse(on)
        XCTAssertEqual(model.lastError, "KIFF doesn't send alerts to tafs yet on this account.")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/push").filter { body($0)["alert_target"] != nil }.isEmpty)
    }

    func testSigningOutStopsThisPhonesAlerts() async {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":true}"#)
        StubProtocol.responses["DELETE /v1/push/ab01ff"] = (200, #"{"removed":true}"#)
        await model.pushTokenArrived(token)
        await model.signOut()
        XCTAssertEqual(sent("DELETE", "cloud.test", "/v1/push/ab01ff").count, 1)
        XCTAssertNil(model.alertsReady)
    }

    /// A sign-out KIFF doesn't confirm keeps this phone's alerts: unregistered with the session
    /// before the logout, registered again at once after it fails.
    func testASignOutKIFFRefusedKeepsThisPhonesAlerts() async {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":true}"#)
        StubProtocol.responses["DELETE /v1/push/ab01ff"] = (200, #"{"removed":true}"#)
        StubProtocol.responses["POST /v1/auth/device/logout"] = (503, #"{"error":"down"}"#)
        await model.pushTokenArrived(token)
        let out = await model.signOut()
        XCTAssertFalse(out)
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertEqual(model.alertsReady, true)
        let order = StubProtocol.sent.compactMap { r -> String? in
            guard let path = r.url?.path, path.hasPrefix("/v1/push") || path == "/v1/auth/device/logout" else { return nil }
            return "\(r.httpMethod ?? "") \(path)"
        }
        XCTAssertEqual(order, ["POST /v1/push", "DELETE /v1/push/ab01ff", "POST /v1/auth/device/logout", "POST /v1/push"])
    }

    /// KIFF replaced the account's target and showed its new secret once; the hand-over to
    /// tafs cloud failed while tafs cloud still held an older secret. The new secret is kept
    /// and handed over again, without a second registration, and alerts don't count as ready
    /// until tafs cloud has it.
    func testASecretTafsCloudDidntTakeIsHandedOverAgain() async throws {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/me/alert-targets"] = (201, #"{"id":"at_2","signing_secret":"whsec_new"}"#)
        StubProtocol.responses["POST /v1/push"] = (502, #"{"error":"busy"}"#)
        let first = await model.turnOnAlerts()
        XCTAssertFalse(first)
        XCTAssertEqual(model.alertsReady, false)

        // tafs cloud is back, and still says alerts are set up: with the older secret.
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":true,"alerts":true}"#)
        await model.syncPush()
        let retried = try XCTUnwrap(sent("POST", "cloud.test", "/v1/push").last)
        let handed = try XCTUnwrap(body(retried)["alert_target"] as? [String: Any], "the kept secret goes with the token")
        XCTAssertEqual(handed["signing_secret"] as? String, "whsec_new")
        XCTAssertEqual(sent("POST", "api.test", "/v1/me/alert-targets").count, 1, "KIFF isn't asked again")
        XCTAssertEqual(model.alertsReady, true)

        // Handed over: the next sync carries the token alone.
        await model.syncPush()
        XCTAssertNil(body(sent("POST", "cloud.test", "/v1/push").last)["alert_target"])
    }

    func testSomeoneWhoCantAnswerIsntOfferedAlerts() async {
        let model = model()
        await signedIn(model)
        StubProtocol.responses["POST /v1/push"] = (200, #"{"device":true,"answers":false,"alerts":true}"#)
        await model.pushTokenArrived(token)
        XCTAssertEqual(model.canAnswerHolds, false)
    }

    // MARK: Tapping an alert

    func testATapOpensNeedsYouEvenWhenItLaunchedTheApp() {
        let push = PushDelegate()
        var opened: [String] = []
        // Cold start: the tap arrives before the app is ready.
        push.open("needs-you")
        XCTAssertEqual(push.pendingOpen, "needs-you")
        push.onOpen = { opened.append($0) }
        XCTAssertEqual(opened, ["needs-you"])
        XCTAssertNil(push.pendingOpen)
        // Running, on another tab.
        push.open("needs-you")
        XCTAssertEqual(opened, ["needs-you", "needs-you"])
    }

    /// A member's request for tasks opens Usage, where it's answered (kiff/tafs#215).
    func testARequestForTasksOpensUsage() {
        let model = model()
        model.open("usage")
        XCTAssertTrue(model.usageRequested)
        XCTAssertNil(model.requestedTab, "Usage opens over the tab that's showing")
    }

    func testTheModelAsksForNeedsYou() {
        let model = model()
        model.open("needs-you")
        XCTAssertEqual(model.requestedTab, .needsYou)
        model.requestedTab = nil
        model.open("somewhere-else")
        XCTAssertNil(model.requestedTab)
    }
}
