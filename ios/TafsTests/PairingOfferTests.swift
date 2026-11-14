import CryptoKit
import XCTest
@testable import Tafs

/// The new phone's side of pairing (KIFF's pairing contract §5.8): the QR it shows reads back as the other
/// phone reads it, with the shared vector's code, and it claims the session once approved.
@MainActor
final class PairingOfferTests: XCTestCase {
    /// The shared vector's keys: the P-256 public keys of private keys 1 and 2.
    private let g = PairingRequest.base64url("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q")!
    private let twoG = PairingRequest.base64url("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q")!
    private let now = PairingRequest.time("2026-11-14T22:00:00Z")!

    private func offer(platform: String = "ios", deviceKind: String? = "iphone") -> PairingOffer {
        PairingOffer.make(publicKey: g, approvalKey: twoG, name: "tafs on iPhone", platform: platform, deviceKind: deviceKind,
                          nonce: Data(0..<32), now: now)
    }

    func testTheQRReadsBackAsTheOtherPhoneReadsIt() throws {
        let shown = offer()
        let read = try PairingRequest.read(shown.qr, now: now).get()
        XCTAssertEqual(shown.checkCode, "H6SJNU", "the shared vector's code")
        XCTAssertEqual(read.checkCode, shown.checkCode, "both screens show the same code")
        XCTAssertEqual(read.platform, "ios")
        XCTAssertEqual(read.kind, "enclave")
        XCTAssertEqual(read.deviceKind, "iphone")
        XCTAssertTrue(read.isPhone)
        XCTAssertEqual(read.nonce, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        XCTAssertEqual(shown.expires.timeIntervalSince(now), PairingOffer.lifetime)
        XCTAssertLessThanOrEqual(PairingOffer.lifetime, 5 * 60, "under §5.1's 5 minutes")

        // The Mac build pairs as a computer: macos, no device_kind.
        let mac = try PairingRequest.read(offer(platform: "macos", deviceKind: nil).qr, now: now).get()
        XCTAssertFalse(mac.isPhone)
        XCTAssertNil(mac.deviceKind)
    }

    func testTheClaimSignsTheNonceAndTheKeysHash() {
        let keyHash = SHA256.hash(data: g).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(String(decoding: offer().claimMessage, as: UTF8.self),
                       "kiff.pair.claim.v1\nAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\n" + keyHash)
    }

    func testTheDeviceKindByPlatform() {
        XCTAssertEqual(PairingOffer.deviceKind(platform: "ios", kiffKind: "iphone"), "iphone")
        XCTAssertEqual(PairingOffer.deviceKind(platform: "ipados", kiffKind: "ipad"), "ipad")
        XCTAssertNil(PairingOffer.deviceKind(platform: "macos", kiffKind: "other"), "a computer sends none")
        XCTAssertEqual(PairingOffer.base64url(Data([0xFB, 0xFF])), "-_8", "base64url, unpadded")
        XCTAssertEqual(PairingOffer.freshNonce().count, 32)
    }

    /// The claim waits while the other phone hasn't approved, then uses the session KIFF hands over.
    func testTheClaimWaitsThenUsesTheSession() async throws {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: FaceIDSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
                             planque: PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session))
        defer { Keychain.delete(AppModel.tokenAccount) }
        model.signInSlice = 1_000_000
        model.pairClaimInterval = 10_000_000
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[]}"#)
        StubProtocol.responses["POST /v1/pair/claim"] = (400, #"{"error":"authorization_pending"}"#)
        StubProtocol.thenRespond["POST /v1/pair/claim"] = ["POST /v1/pair/claim": (200, #"{"access_token":"kiff_dev_paired","token_type":"Bearer","expires_in":3600,"tenant":"t_01","subject":"u_1","device_id":"dev_2"}"#)]

        let shown = PairingOffer.make(publicKey: g, approvalKey: twoG, name: "tafs on iPhone", platform: "ios", deviceKind: "iphone",
                                      nonce: Data(0..<32))
        try await model.finishPairingSignIn(AppModel.PairingStart(offer: shown, proof: "proof"))
        XCTAssertEqual(model.kiffTenant, "t_01")
        let claims = StubProtocol.sent.filter { $0.url?.path == "/v1/pair/claim" }
        XCTAssertGreaterThanOrEqual(claims.count, 2, "pending first, then the session")
        XCTAssertNil(claims.first?.value(forHTTPHeaderField: "Authorization"), "no session yet")
    }
}
