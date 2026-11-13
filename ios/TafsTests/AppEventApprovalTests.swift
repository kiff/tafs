import CryptoKit
import XCTest
@testable import Tafs

/// The phone signs the event filters, then records only the returned subscription.
@MainActor
final class AppEventApprovalTests: XCTestCase {
    private let knobPath = "/v1/tafs/depl_1/knob"
    private let subscriptionPath = "/v1/accounts/t_01/grants/g_9/triggers"
    private static let needed = #"{"version":7,"wakes":[{"id":"trg_event_1","kind":"app_event","label":"Support inbox","words":"Listen for new tickets in queue one","on":false,"ok":"approve","approval":{"state":"needed","grant":"g_9","event":"NEW_TICKET","config":{"queue":"one","include_archived":false,"filters":{"tags":["paid","urgent"],"minimum":12.5}}}}]}"#
    private static var approved: String {
        needed.replacingOccurrences(of: #""on":false"#, with: #""on":true"#)
            .replacingOccurrences(of: #""ok":"approve","#, with: "")
            .replacingOccurrences(of: #""state":"needed"#, with: #""state":"approved","subscription":"ti_1"#)
    }

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        Keychain.delete(AppModel.planqueAccount)
        super.tearDown()
    }

    private func model() async throws -> (AppModel, UUID, RecordingSigner) {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let signer = RecordingSigner()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
            kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
            cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
            planque: PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":true,"groups":[]}}}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[{"id":"g_9","status":"connected","text":"Connected support inbox","digest":"d","account":"acc_1","events":[],"request":{"server":"https://support.example","agent":"rae-k3f9","tools":[]}}]}"#)
        StubProtocol.responses["GET " + knobPath] = (200, Self.needed)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        model.planqueSetUp(for: "t_01")
        model.planque.deviceID = "dev_phone01"
        model.readApprovalReadiness = { .ready }; model.checkApprovalReadiness()
        let id = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        await model.refreshPeople()
        XCTAssertTrue(model.manages(id))
        await model.loadKnob(id)
        return (model, id, signer)
    }

    private func requests(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    private func item(_ model: AppModel, _ id: UUID) throws -> TafsCloud.Knob.Item {
        try XCTUnwrap(model.knobs[id]?.wakes.first)
    }

    func testApprovalSignsTheWholeFiltersAndRecordsTheServersReceipt() async throws {
        let (model, id, signer) = try await model()
        StubProtocol.responses["POST " + subscriptionPath] = (201, #"{"id":"ti_1"}"#)
        StubProtocol.responses["POST " + knobPath + "/subscriptions"] = (200, Self.approved)
        let approved = await model.approveKnobEvent(id, item: try item(model, id))
        XCTAssertTrue(approved, model.lastError ?? "")
        let request = try XCTUnwrap(requests("POST", subscriptionPath).first)
        let bytes = try XCTUnwrap(request.httpBody)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["account", "slug", "config"])
        XCTAssertEqual(body["account"] as? String, "acc_1")
        XCTAssertEqual(body["slug"] as? String, "NEW_TICKET")
        let filters = try XCTUnwrap(body["config"] as? [String: Any])
        XCTAssertEqual(filters["queue"] as? String, "one")
        XCTAssertEqual(filters["include_archived"] as? Bool, false)
        let nested = try XCTUnwrap(filters["filters"] as? [String: Any])
        XCTAssertEqual(nested["tags"] as? [String], ["paid", "urgent"])
        XCTAssertEqual(nested["minimum"] as? Double, 12.5)
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        XCTAssertTrue(signer.signed.contains { $0.hasPrefix("planque.request.v1\n") && $0.contains(subscriptionPath) && $0.contains(hash) })
        XCTAssertNotNil(request.value(forHTTPHeaderField: "Planque-Device-Signature"))
        let recorded = try XCTUnwrap(requests("POST", knobPath + "/subscriptions").first?.httpBody)
        let record = try XCTUnwrap(JSONSerialization.jsonObject(with: recorded) as? [String: Any])
        XCTAssertEqual(Set(record.keys), ["trigger", "subscription", "version"])
        XCTAssertEqual(record["version"] as? Int, 7)
        XCTAssertEqual(record["subscription"] as? String, "ti_1")
        XCTAssertEqual(model.knobs[id]?.wakes.first?.approval?.state, "approved")
        XCTAssertEqual(signer.signed.filter { $0.hasPrefix("tafs.event.v1|") }.count, 1)
        model.forgetSession()
    }

    func testAFailedRecordRetriesTheReceiptWithoutAnotherSubscriptionOrFaceID() async throws {
        let (model, id, signer) = try await model()
        let shown = try item(model, id)
        StubProtocol.responses["POST " + subscriptionPath] = (201, #"{"id":"ti_1"}"#)
        StubProtocol.responses["POST " + knobPath + "/subscriptions"] = (503, #"{"error":"try again"}"#)
        let first = await model.approveKnobEvent(id, item: shown)
        XCTAssertFalse(first)
        XCTAssertEqual(model.knobs[id]?.wakes.first?.approval?.state, "needed")
        StubProtocol.responses["POST " + knobPath + "/subscriptions"] = (200, Self.approved)
        let retried = await model.approveKnobEvent(id, item: shown)
        XCTAssertTrue(retried, model.lastError ?? "")
        XCTAssertEqual(requests("POST", subscriptionPath).count, 1)
        XCTAssertEqual(requests("POST", knobPath + "/subscriptions").count, 2)
        XCTAssertEqual(signer.signed.filter { $0.hasPrefix("tafs.event.v1|") }.count, 1)
        model.forgetSession()
    }

    func testARecordWhoseAnswerWasLostIsReadAsApprovedWithoutSigningAgain() async throws {
        let (model, id, signer) = try await model()
        let shown = try item(model, id)
        StubProtocol.responses["POST " + subscriptionPath] = (201, #"{"id":"ti_1"}"#)
        StubProtocol.responses["POST " + knobPath + "/subscriptions"] = (503, #"{"error":"answer lost"}"#)
        StubProtocol.thenRespond["POST " + knobPath + "/subscriptions"] = ["GET " + knobPath: (200, Self.approved)]
        let first = await model.approveKnobEvent(id, item: shown)
        XCTAssertFalse(first)
        let retried = await model.approveKnobEvent(id, item: shown)
        XCTAssertTrue(retried)
        XCTAssertEqual(requests("POST", subscriptionPath).count, 1)
        XCTAssertEqual(requests("POST", knobPath + "/subscriptions").count, 1)
        XCTAssertEqual(signer.signed.filter { $0.hasPrefix("tafs.event.v1|") }.count, 1)
        model.forgetSession()
    }

    func testAProviderRefusalKeepsApprovalNeededAndShowsItsWords() async throws {
        let (model, id, _) = try await model()
        StubProtocol.responses["POST " + subscriptionPath] = (403, #"{"error":"This connection cannot listen to this event."}"#)
        let approved = await model.approveKnobEvent(id, item: try item(model, id))
        XCTAssertFalse(approved)
        XCTAssertEqual(model.lastError, PlainError.words(status: 403), "planque's reason never reaches the person as-is")
        XCTAssertEqual(model.knobs[id]?.wakes.first?.approval?.state, "needed")
        XCTAssertTrue(requests("POST", knobPath + "/subscriptions").isEmpty)
        model.forgetSession()
    }

    func testALiveVersionChangeIsRejectedBeforeFaceIDOrSubscribing() async throws {
        let (model, id, signer) = try await model()
        let shown = try item(model, id)
        StubProtocol.responses["GET " + knobPath] = (200, Self.needed.replacingOccurrences(of: #""version":7"#, with: #""version":8"#))
        let approved = await model.approveKnobEvent(id, item: shown)
        XCTAssertFalse(approved)
        XCTAssertTrue(signer.signed.filter { $0.hasPrefix("tafs.event.v1|") }.isEmpty)
        XCTAssertTrue(requests("POST", subscriptionPath).isEmpty)
        model.forgetSession()
    }

    func testAnAccountChangeDuringFaceIDSendsNoSubscription() async throws {
        let (model, id, signer) = try await model()
        let shown = try item(model, id)
        signer.whileSigning = { model.forgetSession() }
        let approved = await model.approveKnobEvent(id, item: shown)
        XCTAssertFalse(approved)
        XCTAssertTrue(requests("POST", subscriptionPath).isEmpty)
        XCTAssertTrue(requests("POST", knobPath + "/subscriptions").isEmpty)
        XCTAssertTrue(model.knobs.isEmpty)
    }
}
