import Foundation

/// How fast a screen that repeats a server read slows down while nothing changes, and how its waits are spread (docs/scale.md: a screen
/// learns of a change by a stream or a push where there is one, and polls only as a slow fallback with back-off and jitter).
enum PollPace {
    /// A wait up to a fifth shorter, never longer, so the phones that started together don't read together. `unit` is the fraction
    /// of the wait that stays (0.8 to 1.0); tests pass it, the app draws it.
    static func jittered(_ nanoseconds: UInt64, unit: Double = Double.random(in: 0.8...1.0)) -> UInt64 {
        UInt64(Double(nanoseconds) * min(max(unit, 0.8), 1.0))
    }

    /// Following a run to its end with no stream (Android 1.22): every 2.5 s for the first minute, 5 s to the third, 10 s to
    /// the sixth, then 15 s. `scale` shortens it in tests.
    static func followDelay(after elapsed: TimeInterval, scale: Double = 1) -> UInt64 {
        let seconds: Double = elapsed < 60 ? 2.5 : elapsed < 180 ? 5 : elapsed < 360 ? 10 : 15
        return UInt64(seconds * scale * 1_000_000_000)
    }

    /// Setup's chat: the base wait for the first ten reads, then 2, 4 and at most 8 times it as the reads go on.
    static func setupFactor(afterReads polls: Int) -> UInt64 {
        polls < 10 ? 1 : polls < 30 ? 2 : polls < 60 ? 4 : 8
    }

    /// Setup's wait before its next read: `base` times `setupFactor`, except while a card waits on its grant, which keeps `base`
    /// (that read is time-boxed: the card says it couldn't be read after a minute, and stops waiting after ten).
    static func setupWait(base: UInt64, afterReads polls: Int, cardWaits: Bool) -> UInt64 {
        cardWaits ? base : base * setupFactor(afterReads: polls)
    }

    /// A list read while its screen is open: `base` after a change, doubling while the answers are the same, up to `cap`.
    static func quietWait(after current: UInt64, changed: Bool, base: UInt64, cap: UInt64) -> UInt64 {
        changed ? base : min(max(current, base) * 2, cap)
    }
}
