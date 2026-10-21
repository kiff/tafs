import CryptoKit
import XCTest
@testable import Tafs

/// The Record's facts from KIFF's activity (kiff/tafs#167): how much, on what (KIFF keeps
/// only its hash), a read, the owner's answer and the receipt, from the shapes agreed there.
final class RecordFieldsTests: XCTestCase {
    static let calls = #"""
    {"calls":[
    {"proposal_id":"p_a","action_name":"stripe_create_refund","title":"Refund a payment","actor_id":"agt_r","outcome":"allowed","held":false,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-20T22:38:02Z",
     "amount":{"value":"20.00","unit":"EUR"},"on_hash":"sha256:ON_A",
     "receipt":{"trace_id":"t_a","status":"signed"}},
    {"proposal_id":"p_b","action_name":"stripe_create_refund","title":"Refund a payment","actor_id":"agt_r","outcome":"allowed","held":true,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-21T00:40:12Z",
     "amount":{"value":"150.00","unit":"EUR"},
     "answer":{"decision":"approved_once","by":"owner","signed":true,"device":"iPhone","at":"2026-10-21T00:41:03Z"},
     "receipt":{"trace_id":"t_b","status":"signed"}},
    {"proposal_id":"p_c","action_name":"stripe_create_refund","title":"Refund a payment","actor_id":"agt_r","outcome":"blocked","held":true,
     "created_at":"2026-10-20T22:40:45Z","amount":{"value":"150.00","unit":"EUR"},
     "answer":{"decision":"refused","by":"owner","signed":true,"device":"iPhone","at":"2026-10-20T22:42:26Z"},
     "receipt":{"trace_id":"t_c","status":"signed"}},
    {"proposal_id":"p_d","action_name":"stripe_retrieve_payment_intent","title":"Look up a payment","actor_id":"agt_r","outcome":"allowed","held":false,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-20T22:37:40Z","read_only":true,
     "receipt":{"trace_id":"t_d","status":"pending"}},
    {"proposal_id":"p_old","action_name":"stripe_create_refund","actor_id":"agt_r","outcome":"allowed","held":false,"created_at":"2026-10-19T10:00:00Z"}
    ]}
    """#

    func testTheNewFieldsReachTheRecord() throws {
        struct Response: Decodable { let calls: [KIFFClient.ActivityCall] }
        let calls = try KIFFClient.decoder.decode(Response.self, from: Data(Self.calls.utf8)).calls
        let taf = UUID()
        let record = KIFFMapping.record(calls, ids: ["agt_r": taf])
        XCTAssertEqual(record.count, 5)
        let byID = Dictionary(uniqueKeysWithValues: zip(calls.map(\.proposalID), record))

        let a = try XCTUnwrap(byID["p_a"])
        XCTAssertEqual(a.amount, .init(value: "20.00", unit: "EUR"))
        XCTAssertEqual(a.onHash, "sha256:ON_A")
        XCTAssertNil(a.answer, "within its Card: nobody answered")
        XCTAssertEqual(a.receipt, .init(traceID: "t_a", status: "signed"))

        let b = try XCTUnwrap(byID["p_b"])
        XCTAssertEqual(b.answer?.decision, "approved_once")
        XCTAssertEqual(b.answer?.signed, true)
        XCTAssertEqual(b.answer?.device, "iPhone")
        XCTAssertEqual(b.ownerApproved, true)

        let c = try XCTUnwrap(byID["p_c"])
        XCTAssertEqual(c.kind, .refused)
        XCTAssertEqual(c.answer?.decision, "refused")

        let d = try XCTUnwrap(byID["p_d"])
        XCTAssertEqual(d.readOnly, true)
        XCTAssertNil(d.amount)
        XCTAssertEqual(d.receipt?.status, "pending")

        // A call from before these fields reads as it did.
        let old = try XCTUnwrap(byID["p_old"])
        XCTAssertNil(old.amount)
        XCTAssertNil(old.receipt)
        XCTAssertNil(old.readOnly)
    }

    /// What a call is on is checked against KIFF's hash of it: the value as tafs has it,
    /// as UTF-8, untrimmed and in its own case.
    func testWhatACallIsOnMatchesKIFFsHash() {
        let value = "pi_3USeCjHcvoaKP2f00rWQbstR"
        let hash = "sha256:" + SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertTrue(KIFFMapping.isOn(value, hash: hash))
        XCTAssertFalse(KIFFMapping.isOn(value.lowercased(), hash: hash))
        XCTAssertFalse(KIFFMapping.isOn(value + " ", hash: hash))
        XCTAssertFalse(KIFFMapping.isOn(value, hash: String(hash.dropFirst(7))), "the prefix is part of it")
    }
}
