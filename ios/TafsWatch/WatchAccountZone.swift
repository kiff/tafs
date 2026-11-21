import Foundation

/// The account's time zone on the wrist, as the phone last sent it with Your tafs or a taf's chat (`WatchReply.zone`),
/// so a chat's times and days read as on the phone from the first screen (row 44, C); the watch's own until then, or
/// from a phone too old to send it. Never kept past the app's run.
enum WatchAccountZone {
    nonisolated(unsafe) private(set) static var zone: TimeZone = .current

    /// Takes the zone a reply names, when it names one this watch knows.
    static func learn(_ identifier: String?) {
        if let identifier, let given = TimeZone(identifier: identifier) { zone = given }
    }
}
