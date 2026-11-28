import XCTest
@testable import Tafs

/// A held purchase (Thune) as the iPhone reads, draws and answers it: tafs's `contracts/purchase.json`, `approval` and
/// `answer`, read here as KIFF's held list serves them.
@MainActor
final class PurchaseApprovalTests: XCTestCase {
    private func contract() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/purchase.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func part(_ name: String, _ key: String) throws -> [String: Any] {
        try XCTUnwrap((try contract()[name] as? [String: Any])?[key] as? [String: Any], "purchase.json has no \(name).\(key)")
    }

    /// A held call carrying `purchase`, in the shape `/v1/me/card-exceptions` returns.
    private func held(_ purchase: Any) throws -> KIFFClient.CardException {
        let one: [String: Any] = ["id": "exc_7f3a", "proposal_id": "tc_1", "subject": "agent_shop", "action": "buy", "entity_id": "",
                                  "status": "held", "breaches": [], "created_at": "2026-11-28T09:14:00Z", "accounting_version": 1,
                                  "purchase": purchase]
        return try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: JSONSerialization.data(withJSONObject: one))
    }

    private func approval(_ change: (inout [String: Any]) -> Void = { _ in }) throws -> [String: Any] {
        var a = try part("approval", "value")
        change(&a)
        return a
    }

    private func words(_ change: @escaping (inout [String: Any]) -> Void) -> (inout [String: Any]) -> Void {
        { a in var w = a["words"] as? [String: Any] ?? [:]; change(&w); a["words"] = w }
    }

    func testTheContractsApprovalReadsAsAPurchaseEveryWordAsSent() throws {
        let call = KIFFMapping.held(try held(try approval()), tafID: UUID())
        let p = try XCTUnwrap(call.purchase, "read as a purchase")
        XCTAssertTrue(p.words.title.hasPrefix("Buy from "), p.words.title)
        XCTAssertFalse(p.words.total.isEmpty)
        XCTAssertNotNil(p.words.totalNote)
        XCTAssertFalse(p.words.lines.isEmpty, "the lines behind Show more")
        XCTAssertFalse(p.words.charges.isEmpty, "the charges, so the open list adds up")
        XCTAssertNotNil(p.words.showMore)
        XCTAssertNotNil(p.words.showLess)
        XCTAssertNotNil(p.words.priceRule)
        XCTAssertNotNil(p.words.grant)
        XCTAssertNotNil(p.words.buysNow)
        XCTAssertNotNil(p.words.nothingUntil)
        XCTAssertEqual(call.purchaseBinding, p.binding)
        XCTAssertNil(call.purchaseUnreadable)
        XCTAssertTrue(call.approvableOnce)
    }

    /// The signed bytes are the contract's vector, byte for byte: kiff.answer.v1's lines, then the case's line.
    func testTheAnswerIsTheContractsVectorByteForByte() throws {
        let vector = try part("answer", "vector")
        let binding = try XCTUnwrap(KIFFMapping.held(try held(try approval()), tafID: UUID()).purchaseBinding)
        let message = KIFFClient.answerMessage(tenant: try XCTUnwrap(vector["tenant"] as? String),
                                               exceptionID: try XCTUnwrap(vector["exception_id"] as? String),
                                               answer: try XCTUnwrap(vector["answer"] as? String), nextStep: nil,
                                               accountingVersion: try XCTUnwrap(vector["hold_version"] as? Int),
                                               signedAt: try XCTUnwrap(vector["signed_at"] as? String), purchase: binding)
        XCTAssertEqual(String(decoding: message, as: UTF8.self), try XCTUnwrap(vector["message"] as? String))
    }

    func testAnyOtherHoldsAnswerIsAsBefore() {
        let message = KIFFClient.answerMessage(tenant: "t1", exceptionID: "exc_1", answer: "reject", nextStep: nil,
                                               accountingVersion: 3, signedAt: "2026-11-28T09:15:09Z")
        XCTAssertEqual(String(decoding: message, as: UTF8.self), "kiff.answer.v1\nt1\nexc_1\nreject\n\n\n3\n2026-11-28T09:15:09Z")
    }

    /// A case that can't be signed as sent is never answered here; words that don't read are refused for the case, never
    /// approved; and neither ever fails the hold.
    func testAnUnreadablePurchaseIsNeverApprovedAndRefusedOnlyForItsCase() throws {
        let caseBroken: [(String, (inout [String: Any]) -> Void)] = [
            ("a colon in the id", { $0["case_id"] = "pc_1:2" }),
            ("a new line in the id", { $0["case_id"] = "pc_1\npurchase:x" }),
            ("an id past 128", { $0["case_id"] = String(repeating: "a", count: 129) }),
            ("a digest that isn't 64 hex", { $0["digest"] = "ABC" }),
            ("version 0", { $0["version"] = 0 }),
        ]
        for (why, change) in caseBroken {
            let call = KIFFMapping.held(try held(try approval(change)), tafID: UUID())
            XCTAssertNil(call.purchase, why)
            XCTAssertNil(call.purchaseBinding, why)
            XCTAssertEqual(call.purchaseUnreadable, true, why)
            XCTAssertFalse(call.approvableOnce, why)
        }
        let wordsBroken: [(String, (inout [String: Any]) -> Void)] = [
            ("no total", words { $0["total"] = " " }),
            ("no prompt", words { $0["prompt"] = nil }),
            ("no delivery", words { $0["delivery"] = nil }),
            ("a line that doesn't read", words { $0["lines"] = [["name": "x"]] }),
        ]
        for (why, change) in wordsBroken {
            let call = KIFFMapping.held(try held(try approval(change)), tafID: UUID())
            XCTAssertNil(call.purchase, why)
            XCTAssertNotNil(call.purchaseBinding, "\(why): refused for its case")
            XCTAssertEqual(call.purchaseUnreadable, true, why)
            XCTAssertFalse(call.approvableOnce, why)
        }
        let long = KIFFMapping.held(try held(try approval { $0["case_id"] = String(repeating: "a", count: 128) }), tafID: UUID())
        XCTAssertNotNil(long.purchase, "an id of 128 reads, as the other apps take it")
    }

    func testDirectionCharactersAreTakenOutOfAPurchasesWords() throws {
        let call = KIFFMapping.held(try held(try approval(words { $0["title"] = "Buy from \u{202E}nemuL eiretepaP" })), tafID: UUID())
        let title = try XCTUnwrap(call.purchase?.words.title)
        XCTAssertFalse(title.unicodeScalars.contains { $0.value == 0x202E }, title)
    }

    /// A saved Needs you reads back with its purchase whole, so the card and the signed line are the same after a relaunch.
    func testASavedPurchaseReadsBackTheSame() throws {
        let call = KIFFMapping.held(try held(try approval()), tafID: UUID())
        let saved = try JSONEncoder().encode(call)
        let read = try JSONDecoder().decode(HeldCall.self, from: saved)
        XCTAssertEqual(read.purchase, call.purchase)
        XCTAssertEqual(read.purchaseBinding?.signedLine, call.purchaseBinding?.signedLine)
    }

    func testAHoldWithNoPurchaseIsAsBefore() throws {
        let one: [String: Any] = ["id": "exc_1", "subject": "agent_shop", "action": "refund", "entity_id": "ord_1",
                                  "status": "held", "breaches": [], "created_at": "2026-11-28T09:14:00Z", "accounting_version": 1]
        let e = try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: JSONSerialization.data(withJSONObject: one))
        let call = KIFFMapping.held(e, tafID: UUID())
        XCTAssertNil(call.purchase)
        XCTAssertNil(call.purchaseBinding)
        XCTAssertNil(call.purchaseUnreadable)
    }
}
