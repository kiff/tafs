import LocalAuthentication

/// What this device asks for before it signs, in the words the owner knows (kiff/tafs#87):
/// Face ID on most iPhones, Touch ID on many iPads and Macs, else the passcode.
enum Biometry {
    /// "Face ID", "Touch ID" or "your passcode", for "Approve once with …".
    static let name: String = {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "your passcode"
        }
    }()

    /// The SF Symbol for it.
    static let symbol: String = {
        switch name {
        case "Face ID": return "faceid"
        case "Touch ID": return "touchid"
        default: return "lock"
        }
    }()
}

/// Whether this device can approve: the approval key signs only after Face ID or Touch ID, so
/// approving needs one set up and working. Each state has its own words, never one "not set
/// up" for all of them (kiff/tafs#423, "Set up approvals on this device").
enum ApprovalReadiness: Equatable {
    case ready
    /// No face or fingerprint enrolled: the person adds one.
    case noneEnrolled
    /// No passcode on the device, which Face ID and Touch ID need first.
    case noPasscode
    /// Face ID or Touch ID is there but turned off for tafs: its own settings turn it on.
    case offForTafs
    /// Working but not right now: locked out after tries, a Touch ID keyboard not connected.
    case unavailable(lockedOut: Bool)
    /// No Face ID or Touch ID on this device: approvals need another one.
    case unsupported

    static let title = Words.Approval.title

    /// The state, from what `canEvaluatePolicy` said: no error is ready.
    static func from(_ code: LAError.Code?, biometry: LABiometryType) -> ApprovalReadiness {
        guard let code else { return .ready }
        switch code {
        case .biometryNotEnrolled:
            return .noneEnrolled
        case .passcodeNotSet:
            return .noPasscode
        case .biometryNotAvailable:
            // The same code for a device without it and for tafs not allowed to use it.
            return biometry == .none ? .unsupported : .offForTafs
        case .biometryLockout:
            return .unavailable(lockedOut: true)
        default:
            return .unavailable(lockedOut: false)
        }
    }

    /// This device, now. The Simulator has nothing enrolled, and the signer lets its demo
    /// through, so it is ready there too.
    static func current() -> ApprovalReadiness {
        #if targetEnvironment(simulator)
        return .ready
        #else
        let context = LAContext()
        var error: NSError?
        let ok = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        return from(ok ? nil : error.map { LAError.Code(rawValue: $0.code) ?? .biometryNotAvailable } ?? .biometryNotAvailable,
                    biometry: context.biometryType)
        #endif
    }

    var isReady: Bool { self == .ready }

    /// The setup screen's words for the state, `device` "iPhone" or "iPad", `kind` "Face ID"
    /// or "Touch ID" (nil when the device doesn't say).
    func words(device: String, kind: String?) -> String {
        let either = kind ?? "Face ID or Touch ID"
        switch self {
        case .ready:
            return "This \(device) is ready to approve with \(either)."
        case .noneEnrolled:
            return "Approving asks for \(either), and none is set up on this \(device) yet. Set it up in \(Self.path(kind)), then come back."
        case .noPasscode:
            return "This \(device) has no passcode, and \(either) needs one. Set a passcode in \(Self.path(kind)), then add \(either), then come back."
        case .offForTafs:
            return "\(either) is turned off for tafs. Open Settings and turn on \(either) for tafs."
        case .unavailable(lockedOut: true):
            return "\(either) is locked after too many tries. Lock this \(device), unlock it with its passcode, then check again."
        case .unavailable(lockedOut: false):
            return "\(either) can't be used right now. Try again in a moment."
        case .unsupported:
            return "This \(device) has no Face ID or Touch ID, so it can't approve. Approve on another iPhone, iPad or Android phone signed in to your account."
        }
    }

    /// A line for an approval's place when it can't be made here.
    func short(kind: String?) -> String {
        let either = kind ?? "Face ID or Touch ID"
        switch self {
        case .ready: return ""
        case .noneEnrolled: return "Approving needs \(either), and none is set up. Set it up in \(Self.path(kind))."
        case .noPasscode: return "Approving needs a passcode and \(either). Set a passcode in \(Self.path(kind)), then add \(either)."
        case .offForTafs: return "\(either) is turned off for tafs. Turn it on in tafs' settings to approve."
        case .unavailable(lockedOut: true): return "\(either) is locked after too many tries. Unlock this device with its passcode, then try again."
        case .unavailable(lockedOut: false): return "\(either) can't be used right now. Try again in a moment."
        case .unsupported: return "This device can't approve. Approve on another device signed in to your account."
        }
    }

    /// Where the person sets it up, in words: Open Settings opens tafs' own settings, never
    /// this page, so the path is said instead.
    static func path(_ kind: String?) -> String {
        switch kind {
        case "Touch ID": return "Settings → Touch ID & Passcode"
        case "Face ID": return "Settings → Face ID & Passcode"
        default: return "Settings → Face ID & Passcode (Touch ID & Passcode on a device with Touch ID)"
        }
    }

    /// Open Settings helps only here: it opens tafs' own settings, which hold its Face ID
    /// switch. Adding a face, a fingerprint or a passcode isn't there, so those states give
    /// the path in words and no button that can't help.
    var opensSettings: Bool { self == .offForTafs }

    /// Said on the setup screen: a new face or fingerprint ends the approval key.
    static let newBiometricsNote = "Adding a face or fingerprint later ends this device's approval key, so you approve this device again then."

    /// The words for a prompt the person closed, nil for any other error.
    static func cancelled(_ error: Error) -> String? {
        switch (error as? LAError)?.code {
        case .userCancel?, .systemCancel?, .appCancel?, .userFallback?:
            return "Cancelled, so nothing was approved. The request is kept: approve again when you're ready."
        default:
            return nil
        }
    }

    /// Why an approval didn't go through, from the prompt's error: cancelled, not recognised,
    /// or the device's state. The request is kept.
    static func notApproved(_ error: Error, kind: String?) -> String {
        if let words = cancelled(error) { return words }
        let either = kind ?? "Face ID or Touch ID"
        switch (error as? LAError)?.code {
        case .authenticationFailed?:
            return "\(either) didn't recognise you, so nothing was approved. The request is kept: approve again when you're ready."
        default:
            // The device couldn't ask (locked, turned off, none enrolled): the state says why.
            let now = current()
            return now.isReady ? "Nothing was approved. The request is kept: approve again when you're ready."
                               : now.short(kind: kind) + " The request is kept."
        }
    }
}

extension Biometry {
    /// "Face ID" or "Touch ID" when the device has one, for the setup words.
    static var kind: String? { name == "your passcode" ? nil : name }
}
