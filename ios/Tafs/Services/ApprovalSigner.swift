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
    func allowUnlockReuse()
    /// That answer is over, signed or not: the next one asks again.
    func endUnlockReuse()
    /// The public half of the approval key (P-256, SPKI DER), which signs only what grants
    /// authority (kiff/tafs#233); empty when this device holds none.
    var approvalPublicKeyDER: Data { get }
    /// Asks for Face ID (or the passcode), then signs `message` with the approval key. The
    /// key checks that Face ID itself: code that skipped the prompt can't sign with it.
    func signApproval(_ message: Data, reason: String) async throws -> String
    /// Signs `message` with the approval key under the Face ID that just confirmed this step
    /// (`sign` or `signApproval`), without asking again. Past the step, it throws.
    func signApprovalConfirmed(_ message: Data) throws -> String
}

extension ApprovalSigning {
    /// The next Face ID or Touch ID may be the device unlock the owner gave seconds before:
    /// only for an answer chosen on its notification, where unlocking was for that answer
    /// (kiff/tafs#236, item 25). Signers that always ask ignore it.
    func allowUnlockReuse() {}
    func endUnlockReuse() {}
}

@MainActor
extension ApprovalSigning {
    /// Signers without an approval key sign everything with the one key they have.
    var approvalPublicKeyDER: Data { Data() }
    func signApproval(_ message: Data, reason: String) async throws -> String { try await sign(message, reason: reason) }
    func signApprovalConfirmed(_ message: Data) throws -> String { try signConfirmed(message) }

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
///
/// On an iPhone or iPad it holds two keys (kiff/tafs#233). The **current key** has no access
/// control: it signs what reads or takes authority away (Refuse, Halt all, Setup's and
/// planque's requests), which may run with no one at the screen. The **approval key** signs
/// only what grants authority (Approve, issuing a Card, starting a trial, Resume): in the
/// Secure Enclave its own access control requires Face ID or the passcode, so the key checks
/// the prompt, not the app's code. One confirmed Face ID covers one confirmed step.
@MainActor
final class ApprovalSigner: ApprovalSigning {
    enum SignerError: Error { case notAuthenticated }

    private static let keyAccount = "tafs.signing-key"
    private static let approvalKeyAccount = "tafs.approval-key"

    private let enclaveKey: SecureEnclave.P256.Signing.PrivateKey?
    private let softwareKey: P256.Signing.PrivateKey?
    /// The approval key: in the Secure Enclave, its handle (the Keychain keeps it); or, in
    /// the Simulator, a software key.
    private let approvalHandle: Data?
    private let approvalPublic: Data
    private let softwareApprovalKey: P256.Signing.PrivateKey?
    /// The Face ID that confirmed the current step, and when: the approval key signs under
    /// it, without asking again, until the step is over.
    private var step: (context: LAContext, at: Date)?
    /// How long one confirmed Face ID covers a step's signatures.
    static let stepWindow: TimeInterval = 120
    /// Set for one answer chosen on its notification: its unlock, seconds ago, counts. It
    /// ends with that answer, whether it signed or stopped before (`endUnlockReuse`).
    private(set) var reusesUnlock = false

    /// How long a device unlock counts for that one answer.
    static let unlockReuse: TimeInterval = 10

    func allowUnlockReuse() { reusesUnlock = true }
    func endUnlockReuse() { reusesUnlock = false }

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

        var handle: Data?
        var approvalPublic = Data()
        var softwareApproval: P256.Signing.PrivateKey?
        #if os(iOS)
        let savedApproval = Keychain.load(Self.approvalKeyAccount).flatMap { Data(base64Encoded: $0) }
        if SecureEnclave.isAvailable {
            if let savedApproval, let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: savedApproval) {
                handle = savedApproval
                approvalPublic = key.publicKey.derRepresentation
            } else if let key = Self.newApprovalKey() {
                Keychain.save(key.dataRepresentation.base64EncodedString(), for: Self.approvalKeyAccount)
                handle = key.dataRepresentation
                approvalPublic = key.publicKey.derRepresentation
            }
        } else {
            // The Simulator: a software key, registered the same way.
            let key = savedApproval.flatMap { try? P256.Signing.PrivateKey(rawRepresentation: $0) } ?? {
                let key = P256.Signing.PrivateKey()
                Keychain.save(key.rawRepresentation.base64EncodedString(), for: Self.approvalKeyAccount)
                return key
            }()
            softwareApproval = key
            approvalPublic = key.publicKey.derRepresentation
        }
        #endif
        approvalHandle = handle
        self.approvalPublic = approvalPublic
        softwareApprovalKey = softwareApproval
    }

    #if os(iOS)
    /// A new approval key in the Secure Enclave, usable only after Face ID (the faces enrolled
    /// now) or the passcode, as the prompt offers; none on a phone with no passcode.
    private static func newApprovalKey() -> SecureEnclave.P256.Signing.PrivateKey? {
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                                                           [.privateKeyUsage, .biometryCurrentSet, .or, .devicePasscode], nil)
        else { return nil }
        return try? SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
    }
    #endif

    var approvalPublicKeyDER: Data { approvalPublic }

    var usesSecureEnclave: Bool { enclaveKey != nil }

    var publicKeyDER: Data {
        enclaveKey?.publicKey.derRepresentation ?? softwareKey?.publicKey.derRepresentation ?? Data()
    }

    func sign(_ message: Data, reason: String) async throws -> String {
        try await authenticate(reason: reason)
        return try signConfirmed(message)
    }

    func signApproval(_ message: Data, reason: String) async throws -> String {
        try await authenticate(reason: reason)
        return try signApprovalConfirmed(message)
    }

    func signApprovalConfirmed(_ message: Data) throws -> String {
        guard let step, Date().timeIntervalSince(step.at) < Self.stepWindow else {
            endStep()
            throw SignerError.notAuthenticated
        }
        if let approvalHandle {
            // The key itself checks the Face ID this context holds.
            let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: approvalHandle, authenticationContext: step.context)
            return try key.signature(for: message).derRepresentation.base64EncodedString()
        }
        if let softwareApprovalKey {
            return try softwareApprovalKey.signature(for: message).derRepresentation.base64EncodedString()
        }
        throw SignerError.notAuthenticated
    }

    /// The step is over: its Face ID covers no more signatures.
    private func endStep() {
        step?.context.invalidate()
        step = nil
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
        if reusesUnlock {
            reusesUnlock = false
            context.touchIDAuthenticationAllowableReuseDuration = Self.unlockReuse
        }
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            #if targetEnvironment(simulator)
            endStep()
            step = (context, Date())
            return // No Face ID enrolled in the Simulator: let the demo through.
            #else
            throw SignerError.notAuthenticated
            #endif
        }
        let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        if !ok { throw SignerError.notAuthenticated }
        // This Face ID confirms a new step: the last one's is over.
        endStep()
        step = (context, Date())
    }
}
