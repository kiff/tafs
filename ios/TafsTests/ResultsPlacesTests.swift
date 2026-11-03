import XCTest
@testable import Tafs

/// Setup › Results go to: tafs' own places switch from the live taf's places, never a waiting
/// version's (a review on #447), and always keep one on.
final class ResultsPlacesTests: XCTestCase {
    private func place(_ id: String, _ place: String, on: Bool) -> TafsCloud.Knob.Item {
        TafsCloud.Knob.Item(id: id, place: place, label: place, words: "", on: on)
    }

    /// A waiting version whose results are Alert our phones alone.
    private var pendingKnob: TafsCloud.Knob {
        var knob = TafsCloud.Knob(version: 3, results: [place("r1", "door_reply", on: false), place("r2", "app", on: true),
                                                        place("r3", "owner_email", on: false)])
        knob.pending = .init(version: 4, changes: 1)
        return knob
    }

    func testTheLiveTafsPlacesWinOverAWaitingVersions() {
        let live = ResultsGoToView.liveBuiltIns([.string("door_reply")], knob: pendingKnob)
        XCTAssertEqual(live, ["door_reply"])
        // Email me turned on: the live Reply stays, the draft's Alert our phones isn't sent.
        XCTAssertEqual(ResultsGoToView.places(live ?? [], switching: "owner_email", on: true), ["door_reply", "owner_email"])
    }

    func testAPlaceWithWhatItNeedsCountsByItsName() {
        let live: [JSONValue] = [.object(["place": .string("app")]), .string("owner_email")]
        XCTAssertEqual(ResultsGoToView.liveBuiltIns(live, knob: nil), ["app", "owner_email"])
    }

    func testWithoutTheLiveTafsPlacesAWaitingVersionShowsNothingToSwitch() {
        XCTAssertNil(ResultsGoToView.liveBuiltIns(nil, knob: pendingKnob))
    }

    func testTheDialStandsInOnlyWhenItIsTheLiveVersion() {
        var knob = pendingKnob
        knob.pending = nil
        XCTAssertEqual(ResultsGoToView.liveBuiltIns(nil, knob: knob), ["app"])
    }

    func testTheLastOfTafsPlacesIsNeverSwitchedOff() {
        XCTAssertNil(ResultsGoToView.places(["app"], switching: "app", on: false))
        XCTAssertEqual(ResultsGoToView.places(["app", "owner_email"], switching: "app", on: false), ["owner_email"])
    }
}

/// Results go to's calls on tafs cloud: the places sent, and the work's failures kept for the
/// screen to show.
@MainActor
final class ResultsCallsTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        Keychain.delete(AppModel.planqueAccount)
        super.tearDown()
    }

    private func model() async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: RecordingSigner(),
            kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
            cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
            planque: PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session))
        // The home list carries each taf's live places, as tafs cloud's /v1/home does.
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae.replacingOccurrences(
            of: #""kiff_agent":"rae-k3f9""#, with: #""kiff_agent":"rae-k3f9","results":["door_reply"]"#)]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","results":["door_reply"]}]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":true,"groups":[]}}}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/knob"] = (200, #"{"version":3,"wakes":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        let id = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        await model.refreshPeople()
        XCTAssertTrue(model.manages(id))
        return (model, id)
    }

    func testTheLiveTafsPlacesAreReadAndOnlyThoseSwitchedAreSent() async throws {
        let (model, id) = try await model()
        XCTAssertEqual(ResultsGoToView.liveBuiltIns(model.cloudTaf(for: id)?.results, knob: nil), ["door_reply"])
        StubProtocol.responses["PUT /v1/tafs/depl_1/results"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","results":["door_reply","owner_email"]}}"#)
        let ok = await model.setResultPlaces(id, ["door_reply", "owner_email"])
        XCTAssertTrue(ok, model.lastError ?? "")
        let body = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" }?.httpBody)
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(sent["places"] as? [String], ["door_reply", "owner_email"])
        XCTAssertEqual(ResultsGoToView.liveBuiltIns(model.cloudTaf(for: id)?.results, knob: nil), ["door_reply", "owner_email"])
        model.forgetSession()
    }

    func testAFailedReadOfItsWorkIsKeptForTheScreen() async throws {
        let (model, id) = try await model()
        StubProtocol.responses["GET /v1/tafs/depl_1/work"] = (500, #"{"error":"down"}"#)
        await model.loadWork(id)
        XCTAssertNotNil(model.workErrors[id])
        model.forgetSession()
    }

    func testAFailedSendAgainIsKeptAfterTheWorkIsReadAgain() async throws {
        let (model, id) = try await model()
        StubProtocol.responses["GET /v1/tafs/depl_1/work"] = (200, #"{"summary":{"status":"idle","line":""},"requests":[]}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/requests/run_2/deliveries/door_reply/send"] = (502, #"{"error":"door down"}"#)
        await model.sendAgain(id, run: "run_2")
        XCTAssertEqual(model.workErrors[id]?.hasPrefix("It wasn't sent again"), true, model.workErrors[id] ?? "")
        model.forgetSession()
    }
}
