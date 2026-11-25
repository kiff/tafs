import Foundation
import UserNotifications

/// This phone's mark for its watch (`WatchMark`): made new at each sign-in, kept in the Keychain beside the session, gone at
/// sign-out. Every reply and push to the watch carries it, so the watch keeps nothing of one account under another: a sign-in
/// as someone else, or a sign-out, changes the mark, and the watch wipes what it kept before it draws anything.
enum PhoneWatchMark {
    static let account = "watch.mark"

    /// Where the mark is kept: the Keychain, this device only, as the session; a box in a test.
    struct Store: Sendable {
        var load: @Sendable () -> String?
        var save: @Sendable (String) -> Void
        var delete: @Sendable () -> Void

        static var keychain: Store {
            Store(load: { Keychain.load(PhoneWatchMark.account) },
                  save: { Keychain.save($0, for: PhoneWatchMark.account) },
                  delete: { Keychain.delete(PhoneWatchMark.account) })
        }
    }

    /// The mark kept now, if it's one the phone made; never made here.
    static func stored(_ store: Store = .keychain) -> String? {
        store.load().flatMap { WatchMark.isMark($0) ? $0 : nil }
    }

    /// The signed-in session's mark. A session from before marks gets one now: its watch then wipes once, which is the
    /// safe side.
    static func current(_ store: Store = .keychain) -> String {
        if let mark = stored(store) { return mark }
        let mark = WatchMark.make()
        store.save(mark)
        return mark
    }

    /// A new session, whoever signed in: a new mark, never the last one.
    @discardableResult
    static func began(_ store: Store = .keychain) -> String {
        let mark = WatchMark.make()
        store.save(mark)
        return mark
    }

    /// Signed out: no mark, so every reply and push says so.
    static func ended(_ store: Store = .keychain) {
        store.delete()
    }

    /// The account changed on this phone: its alerts go, delivered and waiting, since each names one account's tafs and
    /// answers one account's held calls (the watch shows the phone's alerts, so they go from the wrist too).
    @MainActor
    static func removeAlerts(_ center: UNUserNotificationCenter = .current()) {
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
    }

    /// Tells the watch (`WatchLink.account`); nothing on a Mac.
    @MainActor
    static func tellWatch(_ mark: String?) {
        #if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
        WatchLink.shared.account(mark)
        #endif
    }
}
