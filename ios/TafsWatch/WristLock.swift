import LocalAuthentication

/// Whether this watch has a passcode. Refuse, Halt, Halt all and Coach send only then, as on
/// Wear OS (`isDeviceSecure && !isDeviceLocked`): each is the owner acting on their account,
/// and a watch with no passcode can be anyone's wrist. A locked watch doesn't show the app at
/// all, so a passcode set is what's left to check.
protocol WristLock {
    var isSecure: Bool { get }
}

/// The watch's own answer: `.deviceOwnerAuthentication` can be evaluated only when a passcode is
/// set (`passcodeNotSet` otherwise). Asking shows nothing.
struct DeviceWristLock: WristLock {
    var isSecure: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) }
}
