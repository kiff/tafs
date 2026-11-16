@testable import TafsWatch

/// A watch with a passcode set: Refuse, Halt and Coach may send.
struct Unlocked: WristLock { var isSecure: Bool { true } }
/// A watch with no passcode: nothing is sent.
struct NoPasscode: WristLock { var isSecure: Bool { false } }
