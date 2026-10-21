import XCTest
@testable import Tafs

/// A signer that refuses, as when Face ID is cancelled.
struct RefusingSigner: ApprovalSigning {
    struct Cancelled: Error {}
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String { throw Cancelled() }
    func signConfirmed(_ message: Data) throws -> String { throw Cancelled() }
}

@MainActor
final class RunsTests: XCTestCase {
    private func stubbedSession() -> URLSession {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    private func model(signer: ApprovalSigning = StubSigner()) -> AppModel {
        let session = stubbedSession()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        return AppModel(signer: signer, kiff: kiff, cloud: cloud)
    }

    // MARK: Runs

    private let rae = TafsCloud.CloudTaf(id: "depl_1", name: "Rae", job: "refunds", kiffAgent: "rae-k3f9", status: "active", nextRun: nil, lastRun: nil)

    private func count(_ method: String, _ path: String) -> Int {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }.count
    }

    func testRunNowSendsOneRequestHoweverOftenItIsTapped() async {
        let model = model()
        StubProtocol.responses["POST /v1/tafs/depl_1/run"] = (200, #"{"session":"sesn_9"}"#)
        let taf = rae
        // Two taps: two tasks on the main actor, the second before the first's answer.
        let first = Task { await model.runNow(taf) }
        let second = Task { await model.runNow(taf) }
        let (a, b) = (await first.value, await second.value)
        XCTAssertEqual([a, b].compactMap { $0 }, ["sesn_9"])
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/run"), 1)
        XCTAssertTrue(model.runsInFlight.isEmpty)
        _ = await model.runNow(rae)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/run"), 2, "once the first is done, another run can start")
    }

    func testLoadsTheLatestRunsReport() async {
        let model = model()
        StubProtocol.responses["GET /v1/tafs/depl_1/runs"] = (200, #"{"runs":[{"session":"sesn_9","at":"2026-10-14T09:00:00Z","error":null},{"session":"sesn_1","at":"2026-10-14T08:00:00Z","error":null}]}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/runs/sesn_9"] = (200, #"{"session":"sesn_9","report":{"status":"idle","text":"Refunded 3 orders; 1 waits on you."}}"#)
        await model.loadReport(rae)
        XCTAssertEqual(model.reports["depl_1"]?.session, "sesn_9")
        XCTAssertEqual(model.reports["depl_1"]?.text, "Refunded 3 orders; 1 waits on you.")
        XCTAssertEqual(model.reports["depl_1"]?.finished, true)

        StubProtocol.responses["GET /v1/tafs/depl_1/runs"] = (500, #"{"error":"down"}"#)
        await model.loadReport(rae)
        XCTAssertEqual(model.reports["depl_1"]?.text, "Refunded 3 orders; 1 waits on you.", "an error keeps the last report")
        XCTAssertNotNil(model.reports["depl_1"]?.error)
    }
}

