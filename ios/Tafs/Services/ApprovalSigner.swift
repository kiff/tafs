import CryptoKit
import Foundation
import LocalAuthentication

/// Signs an owner's answer. The app uses `ApprovalSigner`; tests use a stub, since
/// a real signature waits for a person at the Face ID prompt.
@MainActor
protocol ApprovalSigning {
    /// The public half of the signing key (P-256, SPKI DER). KIFF binds it to this
    /// phone's session when the owner approves the sign-in in their browser.
    var publicKeyDER: Data { get }
    /// Asks for Face ID (or the passcode), then signs `message`. Returns a base64 DER
    /// ECDSA signature over its SHA-256.
    func sign(_ message: Data, reason: String) async throws -> String
    /// Signs `message` without asking again: only for the requests of a step the owner
    /// has just confirmed with Face ID (starting a taf signs each of its setup calls).
    func signConfirmed(_ message: Data) throws -> String
    /// See the extension: one answer from its notification may count the unlock.
    func allowUnlockReuseOnce()
}

extension ApprovalSigning {
    /// The next Face ID or Touch ID may be the device unlock the owner gave seconds before:
    /// only for an answer chosen on its notification, where unlocking was for that answer
    /// (kiff/tafs#236, item 25). Signers that always ask ignore it.
    func allowUnlockReuseOnce() {}
}

@MainActor
extension ApprovalSigning {
    /// An answer in sample mode, where the phone checks the Card itself.
    func sign(answer: Answer, heldID: String, reason: String) async throws -> String {
        try await sign(Data("tafs.answer.v1|\(heldID)|\(answer.wireName)".utf8), reason: reason)
    }
}

/// Signs the owner's answer to a held call with a key that never leaves the iPhone.
///
/// On a device the key lives in the Secure Enclave. The Simulator has none, so there
/// it falls back to a software key, which is fine for a demo and never for production.
/// The key is kept across launches (the Keychain holds the Secure Enclave's handle, not
/// the key), so the key KIFF registered at sign-in is the one that signs. An answer to
/// KIFF counts only once KIFF verifies the signature against that key; a BFF never
/// vouches for an approval.
@MainActor
final class ApprovalSigner: ApprovalSigning {
    enum SignerError: Error { case notAuthenticated }

    private static let keyAccount = "tafs.signing-key"

    private let enclaveKey: SecureEnclave.P256.Signing.PrivateKey?
    private let softwareKey: P256.Signing.PrivateKey?
    /// Set for one answer chosen on its notification: its unlock, seconds ago, counts.
    private var reuseUnlock = false

    /// How long a device unlock counts for that one answer.
    static let unlockReuse: TimeInterval = 10

    func allowUnlockReuseOnce() { reuseUnlock = true }

    init() {
        let saved = Keychain.load(Self.keyAccount).flatMap { Data(base64Encoded: $0) }
        var enclave: SecureEnclave.P256.Signing.PrivateKey?
        var software: P256.Signing.PrivateKey?
        if SecureEnclave.isAvailable {
            if let saved, let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: saved) {
                enclave = key
            } else if let key = try? SecureEnclave.P256.Signing.PrivateKey() {
                Keychain.save(key.dataRepresentation.base64EncodedString(), for: Self.keyAccount)
                enclave = key
            }
        }
        #if os(macOS)
        // A Mac without a Secure Enclave holds no key at all: it never signs with a
        // software key, and approves on the owner's iPhone (kiff/tafs#85).
        let softwareAllowed = false
        #else
        // The Simulator has no Secure Enclave.
        let softwareAllowed = true
        #endif
        if enclave == nil, softwareAllowed {
            if let saved, let key = try? P256.Signing.PrivateKey(rawRepresentation: saved) {
                software = key
            } else {
                let key = P256.Signing.PrivateKey()
                Keychain.save(key.rawRepresentation.base64EncodedString(), for: Self.keyAccount)
                software = key
            }
        }
        enclaveKey = enclave
        softwareKey = software
    }

    var usesSecureEnclave: Bool { enclaveKey != nil }

    var publicKeyDER: Data {
        enclaveKey?.publicKey.derRepresentation ?? softwareKey?.publicKey.derRepresentation ?? Data()
    }

    func sign(_ message: Data, reason: String) async throws -> String {
        try await authenticate(reason: reason)
        return try signConfirmed(message)
    }

    func signConfirmed(_ message: Data) throws -> String {
        let signature: Data
        if let enclaveKey {
            signature = try enclaveKey.signature(for: message).derRepresentation
        } else if let softwareKey {
            signature = try softwareKey.signature(for: message).derRepresentation
        } else {
            throw SignerError.notAuthenticated
        }
        return signature.base64EncodedString()
    }

    private func authenticate(reason: String) async throws {
        let context = LAContext()
        if reuseUnlock {
            reuseUnlock = false
            context.touchIDAuthenticationAllowableReuseDuration = Self.unlockReuse
        }
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            #if targetEnvironment(simulator)
            return // No Face ID enrolled in the Simulator: let the demo through.
            #else
            throw SignerError.notAuthenticated
            #endif
        }
        let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        if !ok { throw SignerError.notAuthenticated }
    }
}
