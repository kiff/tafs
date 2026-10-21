import CryptoKit
import XCTest
@testable import Tafs

/// The Record's facts from KIFF's activity (kiff/tafs#167): how much, a read, the owner's
/// answer and the receipt, from the shapes agreed there.
final class RecordFieldsTests: XCTestCase {
    static let calls = #"""
    {"calls":[
    {"proposal_id":"p_a","action_name":"stripe_create_refund","title":"Refund a payment","actor_id":"agt_r","outcome":"allowed","held":false,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-20T22:38:02Z",
     "amount":{"value":"20.00","unit":"EUR"},"entity_id":"tc-a","on_arg":"payment","on_hash":"HASH_A",
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

    static let paymentA = "pi_3USeCjHcvoaKP2f008Vgp"
    static let hashA = "sha256:" + SHA256.hash(data: Data(paymentA.utf8)).map { String(format: "%02x", $0) }.joined()

    func testTheNewFieldsReachTheRecord() throws {
        struct Response: Decodable { let calls: [KIFFClient.ActivityCall] }
        let calls = try KIFFClient.decoder.decode(Response.self, from: Data(Self.calls.replacingOccurrences(of: "HASH_A", with: Self.hashA).utf8)).calls
        let taf = UUID()
        let record = KIFFMapping.record(calls, ids: ["agt_r": taf])
        XCTAssertEqual(record.count, 5)
        let byID = Dictionary(uniqueKeysWithValues: zip(calls.map(\.proposalID), record))

        let a = try XCTUnwrap(byID["p_a"])
        XCTAssertEqual(a.amount, .init(value: "20.00", unit: "EUR"))
        XCTAssertEqual(a.entityID, "tc-a")
        XCTAssertEqual(a.onArg, "payment")
        XCTAssertNil(a.onValue, "tafs' value comes from its call_refs, not from KIFF")
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

    /// A line names what it acted on from tafs' own call_ref, by KIFF's key, and only when
    /// the value is the one KIFF hashed (kiff/tafs#167).
    func testALineNamesItsPaymentOnlyWhenKIFFsHashMatches() {
        XCTAssertTrue(KIFFMapping.isOn(Self.paymentA, hash: Self.hashA))
        XCTAssertFalse(KIFFMapping.isOn(Self.paymentA.lowercased(), hash: Self.hashA))
        XCTAssertFalse(KIFFMapping.isOn(Self.paymentA + " ", hash: Self.hashA))
        XCTAssertFalse(KIFFMapping.isOn(Self.paymentA, hash: String(Self.hashA.dropFirst(7))), "the prefix is part of it")

        var line = RecordEntry(tafID: UUID(), kind: .done, title: "Refund a payment", detail: "", at: Date())
        line.entityID = "tc-a"
        line.onHash = Self.hashA
        line.onArg = "payment"
        XCTAssertEqual(KIFFMapping.naming([line], refs: ["tc-a": Self.paymentA]).first?.onValue, Self.paymentA)
        XCTAssertEqual(KIFFMapping.naming([line], refs: ["tc-a": Self.paymentA]).first?.onText, "payment …08Vgp")
        XCTAssertNil(KIFFMapping.naming([line], refs: ["tc-a": "pi_other"]).first?.onValue, "a value KIFF didn't hash is never shown")
        XCTAssertNil(KIFFMapping.naming([line], refs: ["tc-b": Self.paymentA]).first?.onValue, "another call's value")
    }
}
