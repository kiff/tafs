import XCTest
@testable import Tafs

/// The device's check of a KIFF receipt (kiff/tafs#167), against a receipt signed the
/// way KIFF signs and written by the receipts endpoint's encoder, with a test key. Its chain has what a Swift re-encoding would change: escaped <, > and &, a
/// map's sorted keys, and Go's 1e+21.
@MainActor
final class ReceiptCheckTests: XCTestCase {
    static let signed = #"{"schema_version":"0.1","trace_id":"01KTRACE","tenant_id":"t_1","entity_id":"pi_3Q_test","entity_type":"Payment","action_name":"stripe_create_refund","outcome":"allowed","issued_at":"2026-10-21T00:41:03.12Z","causal_chain":[{"sequence":1,"kind":"decision","actor_id":"agt_refunds","occurred_at":"2026-10-21T00:41:03.12Z","message":"held: over \u003c€100\u003e \u0026 more","data_hash":"aa"},{"sequence":2,"kind":"settled","occurred_at":"2026-10-21T00:41:04.12Z","data_hash":"bb","data":{"amount":15000,"answer":"approved_once","big":1e+21,"tool":{"status":"done"}}}],"attestation":{"algorithm":"secp256k1+keccak256","signature":"79917d81e09b24ef196e5b71c72ea1c5cc8951e628b63922072ef59832d0a43e4a0b198bead4ddad1312fe7f1e9b6d41b5f617fe08c5aed59abcd9e5b225a2ce01","wallet_address":"0x2c7536e3605d9c16a7a3d7b1898e529396a65c23","anchor_tx_hash":"","anchored_at":"2026-10-21T00:41:03.12Z"},"risk_score":null,"compliance_map":null,"insurer_envelope":null}"#
    static let testSigner = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"

    func testKeccakMatchesTheStandardVectors() {
        XCTAssertEqual(Hex.encode(Keccak.hash256(Data())), "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
        XCTAssertEqual(Hex.encode(Keccak.hash256(Data("abc".utf8))), "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45")
        // Either side of a block (136 bytes), against Go's sha3.NewLegacyKeccak256.
        XCTAssertEqual(Hex.encode(Keccak.hash256(Data(repeating: 0x61, count: 135))), "34367dc248bbd832f4e3e69dfaac2f92638bd0bbd18f2912ba4ef454919cf446")
        XCTAssertEqual(Hex.encode(Keccak.hash256(Data(repeating: 0x61, count: 136))), "a6c4d403279fe3e0af03729caada8374b5ca54d8065329a3ebcaeb4b60aa386e")
        XCTAssertEqual(Hex.encode(Keccak.hash256(Data(repeating: 0x61, count: 300))), "5b7e0e47a96f32a88b4f14ca177982790807c40e1a105742ba0fc1babe1ef826")
    }

    func testAReceiptKIFFSignedChecksOut() {
        XCTAssertEqual(ReceiptCheck.check(Data(Self.signed.utf8), signer: Self.testSigner), .verified(signer: Self.testSigner))
    }

    func testTheChainIsHashedAsWrittenNotReencoded() throws {
        let chain = try XCTUnwrap(RawJSON.value(of: "causal_chain", in: Data(Self.signed.utf8)))
        let text = try XCTUnwrap(String(data: chain, encoding: .utf8))
        XCTAssertTrue(text.hasPrefix(#"[{"sequence":1,"kind":"decision""#))
        // As Go wrote it, escaped: a decoder and re-encoder would write "<€100> & more".
        XCTAssertTrue(text.contains(#"\u003c€100\u003e \u0026 more"#))
        XCTAssertTrue(text.hasSuffix(#""big":1e+21,"tool":{"status":"done"}}}]"#))
        XCTAssertNil(RawJSON.value(of: "missing", in: Data(Self.signed.utf8)))
    }

    func testAChangedByteInTheChainFails() {
        let changed = Self.signed.replacingOccurrences(of: #""data_hash":"bb""#, with: #""data_hash":"bc""#)
        XCTAssertNotEqual(changed, Self.signed)
        XCTAssertEqual(ReceiptCheck.check(Data(changed.utf8), signer: Self.testSigner), .failed("The signature doesn't match these steps."))
        let amount = Self.signed.replacingOccurrences(of: #""amount":15000"#, with: #""amount":95000"#)
        XCTAssertEqual(ReceiptCheck.check(Data(amount.utf8), signer: Self.testSigner), .failed("The signature doesn't match these steps."))
    }

    func testAReceiptSignedByAnotherKeyIsNotKIFFs() {
        // The test key's receipt is sound, but production pins KIFF's signer.
        XCTAssertEqual(ReceiptCheck.check(Data(Self.signed.utf8)), .failed("It isn't signed by KIFF."))
    }

    func testNotSignedYetIsNotAFailure() {
        let pending = Self.signed.replacing(#/"attestation":\{[^}]*\}/#, with: #""attestation":null"#)
        XCTAssertEqual(ReceiptCheck.check(Data(pending.utf8)), .notSignedYet)
    }

    func testAMalformedSignatureFailsWithoutStoppingTheApp() {
        XCTAssertNil(ReceiptCheck.recoverAddress(digest: Data(repeating: 1, count: 32), signature: Data(repeating: 0, count: 65)))
        XCTAssertNil(ReceiptCheck.recoverAddress(digest: Data(repeating: 1, count: 32), signature: Data(repeating: 0xFF, count: 65)))
        let short = Self.signed.replacing(#/"signature":"[0-9a-f]+"/#, with: #""signature":"00""#)
        XCTAssertEqual(ReceiptCheck.check(Data(short.utf8), signer: Self.testSigner), .failed("The signature is malformed."))
    }
}
