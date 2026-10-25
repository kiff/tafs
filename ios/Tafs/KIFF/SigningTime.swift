import Foundation

/// The time a signed request carries (kiff/tafs#245). planque and KIFF each refuse a signed
/// request they have already seen ("this signed request was already made"), and the time is
/// part of what's signed. Two reads made in the same second, such as a refresh and an
/// approval's own read, were the same message to the second and looked like a replay. Each
/// signed request now carries its own millisecond, later than the last one this app signed.
enum SigningTime {
    // `nonisolated(unsafe)`: read and written only under `lock`.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var last = Date.distantPast
    nonisolated(unsafe) private static let format: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// RFC 3339 with milliseconds, never the same twice in this app.
    static func next(now: Date = Date()) -> String {
        lock.lock()
        defer { lock.unlock() }
        let ms = (now.timeIntervalSince1970 * 1000).rounded(.down)
        let floor = (last.timeIntervalSince1970 * 1000).rounded(.down) + 1
        last = Date(timeIntervalSince1970: max(ms, floor) / 1000)
        return format.string(from: last)
    }
}
