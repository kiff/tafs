import Foundation

/// The account's time zone on the wrist, as the phone last sent it with Your tafs or a taf's chat (`WatchReply.zone`),
/// so a chat's times and days read as on the phone from the first screen (row 44, C); the watch's own until then, or
/// from a phone too old to send it. Never kept past the app's run, nor past a sign-out: the next account's chat reads
/// in the watch's own zone until its first reply names one, as Wear OS does (`WatchChatTime.use(null)`).
enum WatchAccountZone {
    nonisolated(unsafe) private(set) static var zone: TimeZone = .current

    /// Takes the zone a reply names, when it names one this watch knows.
    static func learn(_ identifier: String?) {
        if let identifier, let given = TimeZone(identifier: identifier) { zone = given }
    }

    /// Back to the watch's own zone: the phone said it's signed out.
    static func forget(own: TimeZone = .current) { zone = own }

    /// Every reply from the phone (`PhoneLink.ask`): signed out forgets the account's zone; any other names it or leaves
    /// it; no reply (the phone not in reach) changes nothing.
    static func heard(_ reply: WatchReply?, own: TimeZone = .current) {
        guard let reply else { return }
        if reply.outcome == .signedOut { forget(own: own) } else { learn(reply.zone) }
    }
}
