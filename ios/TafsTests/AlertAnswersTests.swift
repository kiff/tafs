import UserNotifications
import XCTest
@testable import Tafs

/// Approve and Refuse on a hold's notification (kiff/tafs#210): signed answers only. Refuse
/// is signed and sent from the notification; Approve opens the hold and waits for Face ID.
@MainActor
final class AlertAnswersTests: XCTestCase {
    private let heldJSON = """
    {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"refund-agent","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2099-10-14T10:51:00Z",
      "accounting_version":2}]}
    """
    private let now = ISO8601DateFormatter().date(from: "2026-10-14T10:45:00Z")!

    private func kiff(verifies: Bool = true) -> KIFFClient {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        return KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!,
                          token: "dev_token", serverVerifiesSignatures: verifies, session: URLSession(configuration: config))
    }

    private var rejects: [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == "/v1/me/card-exceptions/ce_1/reject" }
    }

    func testApproveOpensTafsAndRefuseDoesNotBothNeedTheDeviceUnlocked() throws {
        let hold = try XCTUnwrap(AlertAnswers.categories().first { $0.identifier == AlertAnswers.holdCategory })
        XCTAssertEqual(hold.actions.map(\.identifier), [AlertAnswers.approve, AlertAnswers.refuse])
        let approve = hold.actions[0], refuse = hold.actions[1]
        XCTAssertTrue(approve.options.contains(.foreground), "Approve is confirmed with Face ID in tafs")
        XCTAssertTrue(approve.options.contains(.authenticationRequired))
        XCTAssertFalse(refuse.options.contains(.foreground), "Refuse is sent from the notification")
        XCTAssertTrue(refuse.options.contains(.authenticationRequired))
        XCTAssertTrue(refuse.options.contains(.destructive))
    }

    func testAHoldsNotificationOpensThatHoldWithTheChosenAnswer() {
        XCTAssertEqual(AlertAnswers.request(from: AlertAnswers.destination(hold: "ce_1")), .init(kiffID: "ce_1", answer: nil))
        XCTAssertEqual(AlertAnswers.request(from: AlertAnswers.destination(hold: "ce_1", answer: "approve")), .init(kiffID: "ce_1", answer: "approve"))
        XCTAssertEqual(AlertAnswers.request(from: "hold:ce_1:something"), .init(kiffID: "ce_1", answer: nil), "only Approve or Refuse is chosen")
        XCTAssertNil(AlertAnswers.request(from: "hold:"))
        XCTAssertNil(AlertAnswers.request(from: "needs-you"))

        let model = AppModel(signer: StubSigner())
        model.open("hold:ce_1:approve")
        XCTAssertEqual(model.requestedTab, .needsYou)
        XCTAssertEqual(model.requestedHold, .init(kiffID: "ce_1", answer: "approve"))
    }

    func testRefuseIsKIFFsSignedMessageAndAsksNoFaceID() async throws {
        let signer = FaceIDSigner()
        let outcome = await NotificationRefuse.refuse(hold: "ce_1", kiff: kiff(), signer: signer, tenant: "t1", keyed: true, now: now)
        XCTAssertEqual(outcome, .refused(left: 0))
        XCTAssertTrue(signer.prompts.isEmpty, "refusing only takes authority away")
        let message = String(decoding: KIFFClient.answerMessage(tenant: "t1", exceptionID: "ce_1", answer: "reject", nextStep: nil,
                                                                 accountingVersion: 2, signedAt: "2026-10-14T10:45:00Z"), as: UTF8.self)
        XCTAssertEqual(signer.quiet, [message])
        let sent = try XCTUnwrap(rejects.first?.httpBody)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: sent) as? [String: Any])
        let signature = try XCTUnwrap(body["signature"] as? [String: Any])
        XCTAssertEqual(signature["signed_at"] as? String, "2026-10-14T10:45:00Z")
        XCTAssertEqual(signature["value"] as? String, "signature")
    }

    func testAnAnsweredHoldIsGoneAndNothingIsSent() async {
        let client = kiff()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        let signer = FaceIDSigner()
        let outcome = await NotificationRefuse.refuse(hold: "ce_1", kiff: client, signer: signer, tenant: "t1", keyed: true, now: now)
        XCTAssertEqual(outcome, .gone)
        XCTAssertTrue(rejects.isEmpty)
        XCTAssertTrue(signer.quiet.isEmpty)
    }

    func testWithoutTheKeyOrKIFFsCheckNothingIsSignedAndTafsOpens() async {
        for (verifies, keyed) in [(true, false), (false, true)] {
            let client = kiff(verifies: verifies)
            let signer = FaceIDSigner()
            let outcome = await NotificationRefuse.refuse(hold: "ce_1", kiff: client, signer: signer, tenant: "t1", keyed: keyed, now: now)
            guard case .needsApp = outcome else { return XCTFail("\(outcome)") }
            XCTAssertTrue(signer.quiet.isEmpty)
            XCTAssertTrue(rejects.isEmpty)
        }
    }

    func testARefusalKIFFDoesNotTakeOpensTafs() async {
        let client = kiff()
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (409, #"{"error":"the hold changed"}"#)
        let outcome = await NotificationRefuse.refuse(hold: "ce_1", kiff: client, signer: FaceIDSigner(), tenant: "t1", keyed: true, now: now)
        XCTAssertEqual(outcome, .needsApp("KIFF didn't take the refusal. Open tafs to answer it."))
    }
}
