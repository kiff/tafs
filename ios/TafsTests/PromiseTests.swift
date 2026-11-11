import XCTest
@testable import Tafs

/// 1b: a taf's oldest waiting case and the owner's promise (docs/reliability.md, "Its oldest
/// waiting case"). tafs cloud words the line and decides when it's late; the app draws it, and
/// sets or clears the promise with `PUT /v1/tafs/{id}/promise`, keeping no copy.
@MainActor
final class PromiseTests: XCTestCase {
    private func work(_ json: String) throws -> TafsCloud.TafWork {
        try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(json.utf8))
    }

    func testTheOldestWaitingCaseAndThePromiseRead() throws {
        let w = try work(#"{"summary":{"status":"idle","line":""},"requests":[],"oldest_waiting":{"since":"2026-11-11T08:00:00.000Z","words":"Oldest case waiting 5 h, past your 2 h","late":true},"promise":{"hours":2}}"#)
        XCTAssertEqual(w.oldestWaiting?.words, "Oldest case waiting 5 h, past your 2 h")
        XCTAssertEqual(w.oldestWaiting?.late, true)
        XCTAssertNotNil(w.oldestWaiting?.since)
        XCTAssertEqual(w.promiseHours, 2)
    }

    /// Neither ever fails the read: a line that isn't one, or a promise that isn't, is none.
    func testWhatIsntOneIsNone() throws {
        let older = try work(#"{"summary":{"status":"idle","line":""},"requests":[]}"#)
        XCTAssertNil(older.oldestWaiting, "an older tafs cloud sends none")
        XCTAssertNil(older.promiseHours)
        let odd = try work(#"{"requests":[],"oldest_waiting":"soon","promise":"2"}"#)
        XCTAssertNil(odd.oldestWaiting)
        XCTAssertNil(odd.promiseHours)
        let blank = try work(#"{"requests":[],"oldest_waiting":{"words":"  ","late":"yes"},"promise":{"hours":0}}"#)
        XCTAssertNil(blank.oldestWaiting, "no words, nothing to draw")
        XCTAssertNil(blank.promiseHours)
        let notLate = try work(#"{"requests":[],"oldest_waiting":{"words":"Oldest case waiting 40 min","late":"yes"}}"#)
        XCTAssertEqual(notLate.oldestWaiting?.late, false, "late only when tafs cloud says so")
    }

    /// The choices are Android's, within tafs cloud's range; the row says the promise or Not set.
    func testTheChoicesAndTheRow() {
        XCTAssertEqual(TafsCloud.TafWork.promiseChoices, [1, 2, 4, 8, 24, 48, 168])
        XCTAssertTrue(TafsCloud.TafWork.promiseChoices.allSatisfy { (1...168).contains($0) })
        XCTAssertEqual(PromiseRow.value(nil), Words.Work.answerWithinNone)
        // Whole days read as days, the rest as hours; tafs cloud still gets hours.
        XCTAssertEqual(TafsCloud.TafWork.promiseChoices.map(PromiseRow.label), [
            Words.Work.answerWithinHours(count: 1), Words.Work.answerWithinHours(count: 2),
            Words.Work.answerWithinHours(count: 4), Words.Work.answerWithinHours(count: 8),
            Words.Work.answerWithinDays(count: 1), Words.Work.answerWithinDays(count: 2), Words.Work.answerWithinDays(count: 7),
        ])
        XCTAssertEqual(PromiseRow.value(24), Words.Work.answerWithinDays(count: 1))
        XCTAssertEqual(PromiseRow.value(168), Words.Work.answerWithinDays(count: 7))
        XCTAssertEqual(PromiseRow.value(36), Words.Work.answerWithinHours(count: 36), "not a whole number of days")
        XCTAssertNotEqual(Words.Work.answerWithinDays(count: 7), Words.Work.answerWithinHours(count: 168))
    }

    /// Setting sends whole hours; clearing sends null, never an absent key.
    func testSettingAndClearingThePromise() async throws {
        StubProtocol.responses = ["PUT /v1/tafs/taf-1/promise": (200, #"{"taf":"taf-1","promise":{"hours":24}}"#)]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        try await cloud.setPromise("taf-1", hours: 24)
        StubProtocol.responses["PUT /v1/tafs/taf-1/promise"] = (200, #"{"taf":"taf-1","promise":null}"#)
        try await cloud.setPromise("taf-1", hours: nil)
        let bodies = StubProtocol.sent.filter { $0.httpMethod == "PUT" && $0.url?.path == "/v1/tafs/taf-1/promise" }.compactMap(\.httpBody)
        XCTAssertEqual(bodies.count, 2)
        let set = try XCTUnwrap(JSONSerialization.jsonObject(with: bodies[0]) as? [String: Any])
        XCTAssertEqual(set["hours"] as? Int, 24)
        let cleared = try XCTUnwrap(JSONSerialization.jsonObject(with: bodies[1]) as? [String: Any])
        XCTAssertTrue(cleared.keys.contains("hours"), "the key is sent")
        XCTAssertTrue(cleared["hours"] is NSNull, "as null")
    }
}
