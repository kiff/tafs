import CryptoKit
import XCTest
@testable import Tafs

/// KIFF's signed receipt for a purchase as the iPhone reads and checks it (contracts/purchase.json, `receipt`): Ed25519 over
/// the canonical JSON of its `value` without `sig`, against the keys built into the app, never one fetched with it.
final class PurchaseReceiptTests: XCTestCase {
    private func contract() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/purchase.json")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap((root["receipt"] as? [String: Any])?["value"] as? [String: Any])
    }

    private func bytes(_ value: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: value)
    }

    /// The contract's receipt, signed here with `key` under `kid`, as KIFF signs it.
    private func signed(_ key: Curve25519.Signing.PrivateKey, kid: String = "test-1", _ change: (inout [String: Any]) -> Void = { _ in }) throws -> Data {
        var value = try contract()
        value["kid"] = kid
        change(&value)
        value["sig"] = nil
        let canonical = try JCS.canonical(try bytes(value))
        let sig = try key.signature(for: canonical)
        value["sig"] = sig.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return try bytes(value)
    }

    func testTheContractsReceiptReadsWithEveryWordAsSent() throws {
        let r = try XCTUnwrap(PurchaseReceipt.read(try bytes(try contract())))
        XCTAssertEqual(r.caseState, "done")
        XCTAssertEqual(r.words.state, "Bought")
        XCTAssertTrue(r.words.line.hasPrefix("Bought from "), r.words.line)
        XCTAssertEqual(r.words.lines.count, 4, "allowed, card, order and refunds, in the contract's order")
        XCTAssertEqual(r.kid, "kiff-receipts-1")
    }

    func testWhatsSignedIsTheCanonicalValueWithoutItsSig() throws {
        let raw = try bytes(try contract())
        let r = try XCTUnwrap(PurchaseReceipt.read(raw))
        let text = String(decoding: r.signed, as: UTF8.self)
        XCTAssertFalse(text.contains("\"sig\""), "no sig in what's signed")
        XCTAssertTrue(text.contains("\"case_id\"") && text.contains("\"words\""))
        // The same receipt with its keys in another order signs the same bytes.
        let sorted = try JSONSerialization.data(withJSONObject: try contract(), options: [.sortedKeys])
        XCTAssertEqual(try XCTUnwrap(PurchaseReceipt.read(sorted)).signed, r.signed)
    }

    func testSignedByAKeyTheAppHoldsItChecks() throws {
        let key = Curve25519.Signing.PrivateKey()
        let r = try XCTUnwrap(PurchaseReceipt.read(try signed(key)))
        XCTAssertEqual(r.check(caseID: r.caseID, keys: ["test-1": key.publicKey.rawRepresentation]), .verified)
    }

    func testAChangedWordOrStateNoLongerChecks() throws {
        let key = Curve25519.Signing.PrivateKey()
        var value = try XCTUnwrap(JSONSerialization.jsonObject(with: try signed(key)) as? [String: Any])
        var words = try XCTUnwrap(value["words"] as? [String: Any])
        words["state"] = "Refunded"
        value["words"] = words
        let r = try XCTUnwrap(PurchaseReceipt.read(try bytes(value)))
        XCTAssertEqual(r.check(caseID: r.caseID, keys: ["test-1": key.publicKey.rawRepresentation]), .changed)
    }

    func testAKeyTheAppDoesntHoldOrARevokedOneIsNeverChecked() throws {
        let key = Curve25519.Signing.PrivateKey()
        let r = try XCTUnwrap(PurchaseReceipt.read(try signed(key)))
        XCTAssertEqual(r.check(caseID: r.caseID, keys: [:]), .unknownKey)
        XCTAssertEqual(r.check(caseID: r.caseID, keys: ["test-1": key.publicKey.rawRepresentation], revoked: ["test-1"]), .unknownKey)
        XCTAssertEqual(r.check(caseID: r.caseID, keys: ["test-1": Data(repeating: 1, count: 31)]), .unknownKey, "not a 32-byte key")
    }

    func testAReceiptForAnotherCaseIsntThisLinesReceipt() throws {
        let key = Curve25519.Signing.PrivateKey()
        let r = try XCTUnwrap(PurchaseReceipt.read(try signed(key)))
        XCTAssertEqual(r.check(caseID: "pc_other", keys: ["test-1": key.publicKey.rawRepresentation]), .otherCase)
    }

    /// KIFF's first receipt key is built in: `kiff-receipts-1`, 32 bytes. The contract's example carries a placeholder
    /// signature, so under the real key it never reads as checked.
    func testKiffsReceiptKeyIsPinnedAndAPlaceholderNeverChecks() throws {
        XCTAssertEqual(PurchaseReceipt.pinned["kiff-receipts-1"]?.count, 32)
        let r = try XCTUnwrap(PurchaseReceipt.read(try bytes(try contract())))
        XCTAssertEqual(r.check(caseID: r.caseID), .changed)
    }

    func testTheOrderLineIsOrderOrShippingOnAnOlderReceipt() throws {
        func order(_ w: [String: Any]) throws -> String? {
            var value = try contract()
            value["words"] = w
            return PurchaseReceipt.read(try bytes(value))?.words.order
        }
        XCTAssertEqual(try order(["line": "Bought", "state": "Bought", "order": "Ships to Lyon"]), "Ships to Lyon")
        XCTAssertEqual(try order(["line": "Bought", "state": "Bought", "shipping": "Ships to Lyon"]), "Ships to Lyon")
        XCTAssertEqual(try order(["line": "Bought", "state": "Bought", "order": "New", "shipping": "Old"]), "New")
    }

    func testAnythingElseIsntAPurchaseReceipt() throws {
        var other = try contract()
        other["schema"] = "kiff.receipt.v1"
        XCTAssertNil(PurchaseReceipt.read(try bytes(other)))
        var noSig = try contract()
        noSig["sig"] = nil
        XCTAssertNil(PurchaseReceipt.read(try bytes(noSig)))
        var stringVersion = try contract()
        stringVersion["version"] = "1"
        XCTAssertNil(PurchaseReceipt.read(try bytes(stringVersion)))
        XCTAssertNil(PurchaseReceipt.read(Data("not json".utf8)))
    }

    /// A purchase's Record line names its case; one that isn't a case id the apps take names none.
    func testAPurchasesLineNamesItsCase() throws {
        func caseOf(_ id: Any?) throws -> String? {
            var call: [String: Any] = ["proposal_id": "tc_1", "action_name": "buy", "outcome": "allowed", "created_at": "2026-11-28T09:15:00Z"]
            call["case_id"] = id
            return try KIFFClient.decoder.decode(KIFFClient.ActivityCall.self, from: JSONSerialization.data(withJSONObject: call)).caseID
        }
        XCTAssertEqual(try caseOf("pc_01JD8Q7M2K4T9V6X3B5N8R1Y0Z"), "pc_01JD8Q7M2K4T9V6X3B5N8R1Y0Z")
        XCTAssertNil(try caseOf("pc_1/../x"))
        XCTAssertNil(try caseOf(""))
        XCTAssertNil(try caseOf(nil))
    }
}
