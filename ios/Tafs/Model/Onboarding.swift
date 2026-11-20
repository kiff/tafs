import Foundation

/// The first run after one sign-in (docs/onboarding.md): the welcome with one tick for the terms, KIFF's sign-in in the
/// system's own sheet, three cards on how tafs works, and the first Home. Off until the end-to-end test on real devices
/// with the owner: while it's off, the welcome and the sign-in are as they ship.
enum Onboarding {
    static let on = false
}

/// The welcome's one tick: the person agrees to KIFF's Terms and the tafs Terms before Continue. Privacy is linked
/// beside it, never ticked, and the AI consent is its own screen (`AIConsentView`).
struct ConsentTick: Equatable {
    private(set) var agreed = false

    mutating func toggle() { agreed.toggle() }

    /// Continue opens the sign-in only once the terms are agreed to. Trying the sample data needs no account, so it
    /// never waits on the tick.
    var canContinue: Bool { agreed }
}
