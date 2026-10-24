#if os(iOS)
import Foundation
import UserNotifications

/// A hold's notification, filled in on the phone (kiff/tafs#210, part 2). KIFF's alert never
/// carries a call's parameters, so tafs cloud's push says only that a taf needs the owner.
/// The notification service extension reads the hold from KIFF with the session the app
/// keeps, and shows the taf, what it asks (the amount and on what) and when it lapses, in
/// the account's time zone, worded as the app words it. Whatever it can't read leaves the
/// push as tafs cloud sent it. The lock screen's "Show Previews" setting applies as usual.
@MainActor
enum HoldNotification {
    /// The Keychain item holding the KIFF session, as the app keeps it (`AppModel.tokenAccount`).
    static let sessionAccount = "kiff.session"

    struct Words: Equatable {
        let title: String
        let body: String
    }

    /// "Rae needs you" / "Refund order 42, €150.00 · Over €50 each. Answer by 14:05."
    static func words(_ hold: KIFFClient.CardException, taf: String?, zone: TimeZone) -> Words {
        let call = KIFFMapping.held(hold, tafID: UUID())
        let what = [call.proposed.summary, call.proposed.amount?.formatted].compactMap { $0 }.joined(separator: ", ")
        return Words(title: "\(taf ?? "A taf") needs you",
                     body: "\(what) · \(call.reason). Answer by \(time(call.expiresAt, in: zone)).")
    }

    /// A deadline in the account's zone, with the zone's short name when its clock isn't
    /// this device's at that moment ("14:05 CET").
    static func time(_ date: Date, in zone: TimeZone) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        f.timeZone = zone
        let named = zone.secondsFromGMT(for: date) != TimeZone.current.secondsFromGMT(for: date)
        return f.string(from: date) + (named ? zone.abbreviation(for: date).map { " " + $0 } ?? "" : "")
    }

    /// Fills in `content` for the hold it names: the hold from KIFF, its taf from tafs cloud,
    /// the account's zone from KIFF, read at once. Unchanged when the hold isn't readable.
    static func fill(_ content: UNMutableNotificationContent, kiff: KIFFClient, cloud: TafsCloud) async {
        guard let id = content.userInfo["hold_id"] as? String, kiff.token != nil else { return }
        async let held = try? kiff.heldCalls()
        async let tafs = try? cloud.tafs()
        async let account = try? kiff.me()
        guard let hold = await held?.first(where: { $0.id == id }) else { return }
        let taf = await tafs?.first { $0.kiffAgent == hold.subject }?.name
        let zone = await account?.timeZone.flatMap(TimeZone.init(identifier:)) ?? TimeZone(identifier: "UTC")!
        let words = words(hold, taf: taf, zone: zone)
        content.title = words.title
        content.body = words.body
    }

    /// `fill` with the session the app keeps in the Keychain, shared with the extension.
    static func fillKept(_ content: UNMutableNotificationContent) async {
        let token = Keychain.load(sessionAccount)
        let kiff = KIFFClient()
        kiff.token = token
        await fill(content, kiff: kiff, cloud: TafsCloud(token: token))
    }
}
#endif
