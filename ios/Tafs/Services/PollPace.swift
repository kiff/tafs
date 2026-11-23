import Foundation

/// How fast a screen that repeats a server read slows down while nothing changes, and how its waits are spread (docs/scale.md: a screen
/// learns of a change by a stream or a push where there is one, and polls only as a slow fallback with back-off and jitter).
enum PollPace {
    /// A wait up to a fifth shorter, never longer, so the phones that started together don't read together. `unit` is the fraction
    /// of the wait that stays (0.8 to 1.0); tests pass it, the app draws it.
    static func jittered(_ nanoseconds: UInt64, unit: Double = Double.random(in: 0.8...1.0)) -> UInt64 {
        UInt64(Double(nanoseconds) * min(max(unit, 0.8), 1.0))
    }

    /// A taf's chat, waiting for an answer: the base wait for the first ten reads, twice it to the twentieth, three times after.
    static func chatFactor(afterReads polls: Int) -> UInt64 {
        polls < 10 ? 1 : polls < 20 ? 2 : 3
    }

    /// Setup's chat: the base wait for the first ten reads, then 2, 4 and at most 8 times it as the reads go on.
    static func setupFactor(afterReads polls: Int) -> UInt64 {
        polls < 10 ? 1 : polls < 30 ? 2 : polls < 60 ? 4 : 8
    }

    /// A list read while its screen is open: `base` after a change, doubling while the answers are the same, up to `cap`.
    static func quietWait(after current: UInt64, changed: Bool, base: UInt64, cap: UInt64) -> UInt64 {
        changed ? base : min(max(current, base) * 2, cap)
    }
}
