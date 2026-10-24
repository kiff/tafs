import CryptoKit
import XCTest
@testable import Tafs

/// The Record's facts from KIFF's activity (kiff/tafs#167): how much, a read, the owner's
/// answer and the receipt, from the shapes agreed there.
@MainActor
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
     "answer":{"decision":"approved_once","by":"admin","you":true,"signed":true,"device":"iPhone","at":"2026-10-21T00:41:03Z"},
     "receipt":{"trace_id":"t_b","status":"signed"}},
    {"proposal_id":"p_c","action_name":"stripe_create_refund","title":"Refund a payment","actor_id":"agt_r","outcome":"blocked","held":true,
     "created_at":"2026-10-20T22:40:45Z","amount":{"value":"150.00","unit":"EUR"},
     "answer":{"decision":"refused","by":"admin","you":false,"signed":true,"device":"iPhone","at":"2026-10-20T22:42:26Z"},
     "receipt":{"trace_id":"t_c","status":"signed"}},
    {"proposal_id":"p_d","action_name":"stripe_retrieve_payment_intent","title":"Look up a payment","actor_id":"agt_r","outcome":"allowed","held":false,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-20T22:37:40Z","read_only":true,
     "receipt":{"trace_id":"t_d","status":"pending"}},
    {"proposal_id":"p_gw","action_name":"bxkwf7vi_refund_order","actor_id":"agt_r","outcome":"allowed","held":false,
     "tool_outcome":{"status":"done"},"created_at":"2026-10-20T22:36:00Z","amount":{"value":"150"}},
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
        XCTAssertEqual(record.count, 6)
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
        XCTAssertTrue(b.answeredByOwner, "KIFF says the reader answered it")
        XCTAssertEqual(b.decidedLine, "approved by you")

        let c = try XCTUnwrap(byID["p_c"])
        XCTAssertEqual(c.kind, .refused)
        XCTAssertEqual(c.answer?.decision, "refused")
        XCTAssertFalse(c.answeredByOwner, "a role can't say it was the reader")
        XCTAssertEqual(c.decidedLine, "refused by an admin")

        // A gateway call's integer amount has no unit: the row still reads, and so does the page.
        let gw = try XCTUnwrap(byID["p_gw"])
        XCTAssertEqual(gw.amount, .init(value: "150", unit: nil))
        XCTAssertEqual(gw.amountText, "150")
        // In a currency it reads as the app writes money everywhere (kiff/tafs#236, item 16).
        XCTAssertEqual(b.amountText, Money(minor: 15000, currency: "EUR", scale: 2).formatted)
        var whole = b
        whole.amount = .init(value: "80.00", unit: "USD")
        XCTAssertEqual(whole.amountText, Money(minor: 8000, currency: "USD", scale: 2).formatted, "no ,00 on a whole amount")

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

    /// The owner's iPad, 21 Oct (kiff/tafs#138): a refusal read only "Not sent", and an
    /// approved card jumped below the taf's message once answered. KIFF now says why, in the
    /// app's words, and when the call was made, which keeps the card's place.
    func testALineSaysWhyAndKeepsWhereItWasMade() throws {
        let json = #"""
        {"calls":[
        {"proposal_id":"p_u","action_name":"refund","actor_id":"agt_r","outcome":"blocked","held":false,
         "created_at":"2026-10-21T14:36:13Z","made_at":"2026-10-21T14:36:13Z","reasons":["amount_unit_unverified"],
         "reason":"KIFF couldn't check its currency."},
        {"proposal_id":"p_h","action_name":"refund","actor_id":"agt_r","outcome":"allowed","held":true,
         "tool_outcome":{"status":"done"},"created_at":"2026-10-21T17:12:20Z","made_at":"2026-10-21T17:11:30Z"},
        {"proposal_id":"p_x","action_name":"refund","actor_id":"agt_r","outcome":"blocked","held":false,
         "created_at":"2026-10-21T14:00:00Z","reasons":["something_kiff_has_no_words_for"]}
        ]}
        """#
        struct Response: Decodable { let calls: [KIFFClient.ActivityCall] }
        let calls = try KIFFClient.decoder.decode(Response.self, from: Data(json.utf8)).calls
        let record = KIFFMapping.record(calls, ids: ["agt_r": UUID()])
        XCTAssertEqual(record[0].detail, "Not sent · KIFF couldn't check its currency.")
        XCTAssertEqual(record[0].reason, "KIFF couldn't check its currency.")
        XCTAssertEqual(record[2].detail, "Not sent", "no words, no code: just that it wasn't sent")
        XCTAssertNil(record[2].reason)
        let made = try XCTUnwrap(calls[1].madeAt)
        XCTAssertEqual(AppModel.ChatItem.outcome(record[1]).at, made, "an answered call stays where it was held")
        XCTAssertEqual(AppModel.ChatItem.outcome(record[2]).at, record[2].at, "without made_at, its own time")
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

    /// A call the vendor refused still counts (kiff/tafs#206 6.13): the Record and the chat say
    /// so, naming the vendor as connected.
    func testAVendorsRefusalSaysItStillCounts() {
        let refused = KIFFClient.ActivityCall.ToolOutcome(status: "refused_by_tool", message: "charge_already_refunded")
        XCTAssertEqual(KIFFMapping.executed(refused, approved: false, vendor: "Stripe", counts: .money).1,
                       "Stripe refused it. No money moved, but it still counts against today's total.")
        XCTAssertEqual(KIFFMapping.executed(refused, approved: false, vendor: "Stripe", counts: .amount).1,
                       "Stripe refused it. Nothing was done, but it still counts against today's total.")
        XCTAssertEqual(KIFFMapping.executed(refused, approved: false, vendor: nil, counts: .nothing).1,
                       "The tool refused it, and it still counts toward today's limits: charge_already_refunded")
    }

    /// Only an amount in a currency is money (kiff/tafs#207): a gateway's unit can be a count
    /// of anything, and a read moves nothing.
    func testOnlyACurrencyAmountIsMoney() {
        var call = KIFFClient.ActivityCall(proposalID: "p1", actionName: "refund", outcome: "allowed", createdAt: Date(), held: false)
        call.amount = .init(value: "3000", unit: "EUR")
        XCTAssertEqual(KIFFMapping.counted(call), .money)
        call.amount = .init(value: "3000", unit: "usd")
        XCTAssertEqual(KIFFMapping.counted(call), .money)
        call.amount = .init(value: "3", unit: "seats")
        XCTAssertEqual(KIFFMapping.counted(call), .amount)
        call.amount = .init(value: "3", unit: nil)
        XCTAssertEqual(KIFFMapping.counted(call), .amount)
        call.readOnly = true
        XCTAssertEqual(KIFFMapping.counted(call), .nothing)
        call.readOnly = nil
        call.amount = nil
        XCTAssertEqual(KIFFMapping.counted(call), .nothing)
    }


    /// What happened in tafs itself reads as words in Activity (kiff/tafs#236, items 15 and 19).
    @MainActor
    func testEventsInTafsReadAsWords() {
        let at = Date(timeIntervalSince1970: 1_792_000_000)
        func words(_ kind: String, taf: String? = "taf-1", app: String? = nil, from: String? = nil) -> String? {
            AppModel.eventWords(TafsCloud.AccountEvent(at: at, kind: kind, taf: taf, name: "Refunds 3", app: app, from: from))?.0
        }
        XCTAssertEqual(words("halted"), "Halted")
        XCTAssertEqual(words("halted", taf: nil), "Halted with all your tafs")
        XCTAssertEqual(words("resumed"), "Resumed")
        XCTAssertEqual(words("knocked", app: "email", from: "Maya"), "Maya wrote by email")
        XCTAssertEqual(words("paired", app: "email", from: "Maya"), "You paired Maya")
        XCTAssertEqual(words("door_removed", app: "email"), "You removed its email door")
        XCTAssertNil(words("something new"), "a kind this app doesn't know isn't shown")
    }
}
