import Foundation
import SwiftUI
import UserNotifications

/// Whose data this watch keeps (`WatchMark`; as Wear OS's account wipe): the phone's mark for its session, heard on every
/// reply and push before anything in it is shown. The watch keeps the last mark it heard. A mark that differs, or none once
/// one was kept (the phone signed out, or a reply that doesn't say whose it is), wipes everything the watch kept: the
/// threads, the Inbox, a message kept for later, the tafs it read, and its alerts. So one person's tafs, threads and words
/// never show, and a message they wrote is never sent, once someone else is signed in on the phone.
///
/// And nothing kept is drawn before this open of the app has heard the phone's mark and it's the kept one (`mayDraw`), as
/// on Wear OS: a watch whose phone changed account while it was away shows only what the phone answers now, never the old
/// copy for a frame. Each time the app is put away, or back after more than two minutes with the wrist lowered, the mark
/// has to be heard again (`phase`, `leave`), so an app still running through a sign-out and a sign-in as someone else on
/// the phone draws nothing of the first account.
@MainActor
final class WatchAccountMark {
    static let shared = WatchAccountMark()

    /// Where the mark is kept: the watch's own defaults, or a box in a test. The mark says nothing about the account.
    struct Store {
        var load: () -> String?
        var save: (String?) -> Void
        /// When the account last changed as the phone said it (`heardAccount`), so an older word can't undo a newer one.
        var loadAt: () -> Date? = { nil }
        var saveAt: (Date) -> Void = { _ in }

        static func defaults(_ defaults: UserDefaults = .standard, key: String = "tafs.watch.mark") -> Store {
            Store(load: { defaults.string(forKey: key) },
                  save: { mark in
                      if let mark { defaults.set(mark, forKey: key) } else { defaults.removeObject(forKey: key) }
                  },
                  loadAt: { defaults.object(forKey: key + ".at") as? Date },
                  saveAt: { defaults.set($0, forKey: key + ".at") })
        }
    }

    private let store: Store
    /// What's kept on disk goes first, whether or not a page is up to forget its own: the threads, the Inbox, a message
    /// kept for later, the tafs read, and the watch's alerts, each of which names one account's tafs.
    private let clearStored: @MainActor () -> Void
    private var wipes: [@MainActor () -> Void] = []
    private var confirms: [@MainActor () -> Void] = []
    /// This open heard the phone's mark, and it's the one kept.
    private(set) var confirmed = false
    /// How many times what was kept went: for the tests.
    private(set) var wiped = 0
    /// The watch's clock, for when a live reply was heard.
    private let now: () -> Date
    /// When the account last changed, or was last heard live: an account change older than this is ignored.
    private var lastChange: Date?

    init(store: Store = .defaults(), clearStored: @escaping @MainActor () -> Void = WatchAccountMark.clearAllStored,
         now: @escaping () -> Date = Date.init) {
        self.store = store
        self.clearStored = clearStored
        self.now = now
        lastChange = store.loadAt()
    }

    /// The app's own files and alerts (`clearStored`).
    static func clearAllStored() {
        WatchThreads.shared.clear()
        WatchCache.shared.clear()
        FileInboxStore().clear()
        FileDraftStore().clear()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }

    /// The mark kept, if any.
    var kept: String? { store.load() }

    /// Whether what's kept may be drawn now. The demo has no phone to hear, and shows its own fixed answers.
    var mayDraw: Bool { confirmed || WatchDemo.on }

    /// The app was put away: until a live reply in the next open carries the kept mark again, nothing kept is drawn. The
    /// phone may change accounts while the watch is away, and the app may go on running meanwhile.
    func leave() { confirmed = false }

    /// How long a lowered wrist keeps the open: raised within it, the screen stays as it was; past it, it's a new open.
    /// watchOS can keep an app inactive on screen for up to an hour, and the phone can change accounts meanwhile.
    nonisolated static let inactiveKeeps: TimeInterval = 2 * 60
    /// When the app went inactive (a lowered wrist), while it still is.
    private var inactiveSince: Date?

    /// The app's phase changed. Returns whether this is a new open, so each page forgets what it showed and reads again
    /// behind `mayDraw`: put away (`.background`), or back on screen after more than `inactiveKeeps` inactive. A glance,
    /// raised within it, keeps the screen and the proof.
    @discardableResult
    func phase(_ phase: ScenePhase) -> Bool {
        switch phase {
        case .background:
            inactiveSince = nil
            leave()
            return true
        case .inactive:
            if inactiveSince == nil { inactiveSince = now() }
            return false
        case .active:
            defer { inactiveSince = nil }
            guard let since = inactiveSince, now().timeIntervalSince(since) > Self.inactiveKeeps else { return false }
            leave()
            return true
        @unknown default:
            return false
        }
    }

    /// What goes when the account changes: each store and page the app keeps adds its own.
    func onWipe(_ wipe: @escaping @MainActor () -> Void) { wipes.append(wipe) }

    /// What can be drawn once the mark is confirmed: the pages take in what they kept.
    func onConfirm(_ confirm: @escaping @MainActor () -> Void) { confirms.append(confirm) }

    /// A live reply's mark, or (`live` false, through `heardAccount`) an account change's, before anything in it is used.
    /// Returns whether it's the kept account's. Only a live reply confirms the mark for this open: an account change may
    /// have waited, so it can wipe and name the new account, but never lets what's kept be drawn.
    @discardableResult
    func heard(_ mark: String?, live: Bool = true) -> Bool {
        // A live reply is the phone's account now: an account change sent before it, handled after, is older.
        if live { noteChange(now()) }
        let kept = store.load()
        guard WatchMark.isMark(mark), let mark else {
            // No mark: signed out on the phone, or a reply that doesn't say whose it is. Once a mark was kept, that's a
            // change of account.
            confirmed = false
            if kept != nil {
                store.save(nil)
                wipe()
            }
            return false
        }
        if mark == kept {
            guard live, !confirmed else { return true }
            confirmed = true
            confirms.forEach { $0() }
            return true
        }
        // Another account's, or the first mark this watch hears (what it kept before marks is no one's it can name): all
        // of it goes first, then the new mark is kept.
        wipe()
        store.save(mark)
        confirmed = live
        return false
    }

    /// An account change the phone sent (`WatchPush.Kind.account`, as a push or as the application context), made at `at`:
    /// used only when it's newer than the last change or live reply heard, since WatchConnectivity orders neither against
    /// the other. It may wipe, never confirm.
    func heardAccount(_ mark: String?, at: Date) {
        if let lastChange, at <= lastChange { return }
        noteChange(at)
        heard(mark, live: false)
    }

    /// Whether a data push (a thread's change, the Inbox's marks) is the kept account's, so it may be used. A data push
    /// never changes the account: one with another mark, or none, may have waited on the watch since before a switch, and
    /// is dropped, with no wipe and nothing kept.
    func ours(_ mark: String?) -> Bool {
        WatchMark.isMark(mark) && mark == store.load()
    }

    private func noteChange(_ at: Date) {
        if let lastChange, lastChange >= at { return }
        lastChange = at
        store.saveAt(at)
    }

    /// The phone's reply, as `PhoneLink` hands it on: no reply (the phone not in reach) says nothing about the account.
    func heard(reply: WatchReply?) {
        guard let reply else { return }
        heard(reply.mark)
    }

    private func wipe() {
        wiped += 1
        clearStored()
        // Then each page forgets what it showed, and its store what it read.
        wipes.forEach { $0() }
    }
}
