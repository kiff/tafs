import Foundation
#if canImport(UIKit)
import UIKit
#endif
import UserNotifications

/// The watch requests this iPhone sent for its watch (docs/watch-app.md, "An answer you can't miss, with the app put away"):
/// their ids alone, never their words, each kept a day, at most `most`. With each, whether the watch read its answer itself
/// and whether it was posted already, so tafs cloud's push and W3's follow post it once at most, and never one the watch showed.
struct WatchAskPushes {
    struct Entry: Codable, Equatable {
        var at: Date
        var seen = false
        var posted = false
        /// tafs cloud's id for the taf whose chat took it, for a request sent from a taf's chat: its answer is the taf's.
        var taf: String? = nil
    }

    static let key = "watch.asks.sent"
    /// A request's answer comes within minutes: a day is ample, and its id goes after.
    static let kept: TimeInterval = 86_400
    static let most = 50

    var defaults: UserDefaults = .standard

    private func all() -> [String: Entry] {
        guard let data = defaults.data(forKey: Self.key) else { return [:] }
        return (try? JSONDecoder().decode([String: Entry].self, from: data)) ?? [:]
    }

    private func save(_ entries: [String: Entry], now: Date) {
        let fresh = entries.filter { now.timeIntervalSince($0.value.at) < Self.kept }
        let newest = fresh.sorted { $0.value.at > $1.value.at }.prefix(Self.most)
        defaults.set(try? JSONEncoder().encode(Dictionary(uniqueKeysWithValues: newest.map { ($0.key, $0.value) })), forKey: Self.key)
    }

    /// The relay sent this request for the watch; `taf`, from that taf's chat.
    func sent(_ id: String, taf: String? = nil, at now: Date = Date()) {
        var entries = all()
        if entries[id] == nil { entries[id] = Entry(at: now, taf: taf) }
        save(entries, now: now)
    }

    /// The request, if this iPhone sent it within the day.
    func entry(_ id: String, now: Date = Date()) -> Entry? {
        all()[id].flatMap { now.timeIntervalSince($0.at) < Self.kept ? $0 : nil }
    }

    /// The watch read the request's finished answer itself.
    func seen(_ id: String, now: Date = Date()) {
        var entries = all()
        guard entries[id] != nil else { return }
        entries[id]?.seen = true
        save(entries, now: now)
    }

    /// Takes the one post of this request's answer: false once it was posted, or the watch read it. A request this iPhone
    /// didn't keep (from before, or past the day) is W3's alone, as before.
    func claimPost(_ id: String, now: Date = Date()) -> Bool {
        var entries = all()
        guard let entry = entries[id] else { return true }
        guard !entry.posted, !entry.seen else { return false }
        entries[id]?.posted = true
        save(entries, now: now)
        return true
    }
}

/// Row 11 on Apple Watch: an answer you can't miss, with the app put away. W3's follow (`CoachFollow`) reads for as long as
/// iOS gives it after a Send; tafs cloud's push for a watch request (`change: watch_ask`, its id alone) wakes the iPhone each
/// time the request changes, however long it takes. The iPhone reads that one request with its own session, and once it has
/// ended, or a call of it waits for the owner, posts it as W3 does: watchOS shows it on the wrist while the iPhone is locked.
/// Only a request this iPhone sent for its watch, never one the watch read itself or is reading, nor with tafs open.
@MainActor
enum WatchAskPush {
    /// The push, handled: reads the request, hands what it read to the watch (`relay`, queued, so the thread on the wrist is
    /// in step even with both apps put away), and posts it as W3's notification. Returns whether it posted.
    @discardableResult
    static func handle(_ id: String, pushes: WatchAskPushes = WatchAskPushes(), read: (String) async -> WatchReply,
                       now: () -> Date = Date.init, phoneInUse: () -> Bool,
                       relay: (WatchPush) -> Void = { _ in },
                       post: (WatchCoachAnswer, WatchCoachTicket) async -> Void) async -> Bool {
        // Nothing is read for a request this iPhone didn't send, or whose answer the watch had or was posted already.
        guard let entry = pushes.entry(id, now: now()), !entry.seen, !entry.posted else { return false }
        let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: id, sent: nil)
        let reply = await read(id)
        // The watch gets every change, tafs open or not: only its notification waits on the rest.
        if reply.outcome == .ok || reply.outcome == .failed { relay(WatchPush(.ask, ask: id, reply: reply, at: now())) }
        guard !phoneInUse(), !watchHas(ticket, now: now()) else { return false }
        // Still at work: the next push says when it changes.
        guard reply.outcome == .ok, reply.answering == false, let answer = reply.coach else { return false }
        guard !watchHas(ticket, now: now()), pushes.claimPost(id, now: now()) else { return false }
        await post(answer, ticket)
        return true
    }

    /// The watch read the answer itself, or is reading now: it shows it, and nothing is posted.
    private static func watchHas(_ ticket: WatchCoachTicket, now: Date) -> Bool {
        let key = CoachFollow.key(ticket)
        if CoachFollow.watchHad.contains(key) { return true }
        guard let last = CoachFollow.watchLastRead[key] else { return false }
        return now.timeIntervalSince(last) <= CoachFollow.watchAway
    }

    /// The app's push: read with the session the app keeps in the Keychain (tafs may not be open, the iPhone locked).
    static func run(_ id: String) async {
        let reading = KeptWatchReading()
        guard reading.signedIn else { return }
        // Whose read this is: a push read under this session never reaches a watch after it ends (`WatchLink.push`).
        let mark = reading.mark
        let taf = WatchAskPushes().entry(id)?.taf
        await handle(id, read: { await reading.readAsk($0) }, phoneInUse: { CoachFollow.phoneActive },
                     relay: { WatchPushRelay.toWatch($0, mark: mark) },
                     post: { answer, ticket in
                         await CoachFollow.notify(answer, ticket: ticket, taf: await reading.tafName(taf))
                     })
    }
}

/// The other pushes the watch keeps in step with (docs/watch-app.md, "Kept and pushed"): the Inbox's marks, and a taf whose
/// chat changed. Each is read with the session the app keeps and handed on queued; nothing is posted.
@MainActor
enum WatchPushRelay {
    /// tafs cloud's `watch_inbox`: the marks, read once, for the watch's Inbox.
    static func inbox(read: () async -> WatchReply, now: Date = Date(), relay: (WatchPush) -> Void) async {
        let reply = await read()
        guard reply.outcome == .ok, reply.marks != nil else { return }
        relay(WatchPush(.inbox, reply: reply, at: now))
    }

    /// A taf's chat changed: its id alone, for a chat open or kept on the watch to read again.
    static func chat(_ taf: String?, now: Date = Date(), relay: (WatchPush) -> Void) {
        guard let taf, !taf.isEmpty else { return }
        relay(WatchPush(.chat, taf: taf, at: now))
    }

    /// The held calls last handed to the watch, and under whose mark: the same again isn't sent.
    private static var lastHolds: (mark: String?, holds: WatchHolds)?

    /// The phone's held calls changed, or may have (a hold's push, its alert, the app's own read finding others): Needs you
    /// read with the session the app keeps and handed to the watch as `WatchHolds`, the amount and the call's details left
    /// out (`WatchPush.Kind.needsYou`). Nothing when the read fails, or it's what the watch was last told under this mark.
    static func needsYou(read: () async throws -> WatchNeedsYou, mark: String?, now: Date = Date(),
                         relay: (WatchPush) -> Void) async {
        guard let read = try? await read() else { return }
        let holds = WatchHolds(read)
        if let last = lastHolds, last.mark == mark, last.holds.sameAs(holds) { return }
        lastHolds = (mark, holds)
        relay(WatchPush(.needsYou, holds: holds, at: now))
    }

    /// Forgets what the watch was last told, for the tests. Another account's mark is never the same, so its first read goes.
    static func forgetHolds() {
        lastHolds = nil
        lastHeldIDs = nil
    }

    /// The held calls' ids the app's own read last found.
    private static var lastHeldIDs: Set<String>?

    /// The app read its held calls (`AppModel.onHeldChanged`, each refresh): the watch is told only when the ids differ from
    /// the last read's, so a refresh that finds the same calls reads nothing more. Returns whether it reads.
    @discardableResult
    static func heldChanged(_ ids: [String], read: @escaping @MainActor () async -> Void = { await holds() }) -> Bool {
        let now = Set(ids)
        guard now != lastHeldIDs else { return false }
        lastHeldIDs = now
        Task { await read() }
        return true
    }

    /// The app's own: read with the session the app keeps, for a paired watch with tafs on it.
    static func holds() async {
        let reading = KeptWatchReading()
        guard reading.signedIn, watchListening else { return }
        let mark = reading.mark
        await needsYou(read: { try await reading.needsYou(now: Date()) }, mark: mark, relay: { toWatch($0, mark: mark) })
    }

    static func run(change: String, taf: String?) async {
        let reading = KeptWatchReading()
        guard reading.signedIn, watchListening else { return }
        let mark = reading.mark
        switch change {
        case "watch_inbox": await inbox(read: { await reading.inboxMarks() }, relay: { toWatch($0, mark: mark) })
        // tafs cloud's push for a taf's chat (`taf_chat`, the owner's call 2b: a message from any device, or the taf's run
        // answered), and the changes that reload a taf's chat on the iPhone too (`AppModel.liveChange`).
        case "taf_chat", "taf", "knock": chat(taf, relay: { toWatch($0, mark: mark) })
        default: return
        }
    }

    /// A paired watch has tafs: false on a Mac, which has no watch.
    static var watchListening: Bool {
        #if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
        WatchLink.shared.watchListening
        #else
        false
        #endif
    }

    /// Hands a push to the watch (`WatchLink.push`) with the mark of the session it was read under; nothing on a Mac.
    static func toWatch(_ push: WatchPush, mark: String?) {
        #if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
        var push = push
        push.mark = mark
        WatchLink.shared.push(push)
        #endif
    }
}

/// View on phone (docs/watch-app.md, "View on phone"): the watch can't bring an app to the front of the iPhone, so the iPhone
/// does what it may. With tafs in front, the chat opens now; otherwise a notification, "From your watch", whose tap opens
/// it. One at a time per chat: a second tap replaces the first.
@MainActor
enum WatchOpen {
    /// Where the app opens (`AppModel.open`): Coach's chat, or the taf's own page by tafs cloud's id.
    static func destination(taf: String?) -> String {
        guard let taf, !taf.isEmpty else { return "coach" }
        return "taf:" + taf
    }

    /// Approve on your phone: the held call's own card in Needs you, with no answer chosen, only when this phone's fresh read
    /// (`listed`, KIFF's held calls under the account signed in now) has it. Answered, lapsed, another account's, or a read
    /// that failed: Needs you, with nothing chosen. The person approves on the phone's card, behind Face ID, as always.
    static func holdDestination(_ hold: String, listed: [String]?) -> String {
        guard let listed, listed.contains(hold) else { return "needs-you" }
        return AlertAnswers.destination(hold: hold)
    }

    static func content(_ destination: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = destination == "coach" ? Words.Coach.title : "tafs"
        content.body = Words.Coach.Chats.fromYourWatch
        content.sound = .default
        content.userInfo = ["open": destination]
        return content
    }

    static func run(_ destination: String) async {
        if CoachFollow.phoneActive, let app = PushDelegate.current {
            app.open(destination)
            return
        }
        let request = UNNotificationRequest(identifier: "watch-open-" + destination, content: content(destination), trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}

/// Ask your phone to sign this watch in (docs/watch-app.md, "Sign out on this watch"; row 18): a watch signed out on itself
/// asks, and this phone's owner answers. The question is a notification, "Sign this watch in as you?", with **Allow**, which
/// iOS lets through only once the phone is unlocked (`.authenticationRequired`), and **Deny**. A tap that only opens tafs
/// answers nothing. Only Allow signs the watch in, by the id it asked with, under this phone's mark now.
@MainActor
enum WatchSignInAsk {
    static let categoryID = "watch-sign-in"
    static let allow = "watch-sign-in.allow"
    static let deny = "watch-sign-in.deny"
    /// The `userInfo` key the watch's id goes under.
    static let idKey = "watch_sign_in"

    static func category() -> UNNotificationCategory {
        let allow = UNNotificationAction(identifier: allow, title: Words.Watch.signInAllow, options: [.authenticationRequired])
        let deny = UNNotificationAction(identifier: deny, title: Words.Watch.signInDeny, options: [.destructive])
        return UNNotificationCategory(identifier: categoryID, actions: [allow, deny], intentIdentifiers: [], options: [])
    }

    /// The question: nothing of the account in it, only the watch's id, so a locked phone's screen shows no more.
    static func content(_ id: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = "tafs"
        content.body = Words.Watch.signInWatchQ
        content.sound = .default
        content.categoryIdentifier = categoryID
        content.userInfo = [idKey: id]
        return content
    }

    /// Asks the owner, one question per ask: a second ask replaces any still waiting.
    static func post(_ id: String) async {
        let center = UNUserNotificationCenter.current()
        let waiting = await center.pendingNotificationRequests().filter { $0.content.categoryIdentifier == categoryID }.map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: waiting)
        let delivered = await center.deliveredNotifications().filter { $0.request.content.categoryIdentifier == categoryID }
        center.removeDeliveredNotifications(withIdentifiers: delivered.map(\.request.identifier))
        let request = UNNotificationRequest(identifier: "watch-sign-in-" + id, content: content(id), trigger: nil)
        try? await center.add(request)
    }

    /// The owner's answer, as the watch takes it: Allow or Deny for that id. Any other action (a tap that opens tafs, a
    /// dismissal) answers nothing, and the watch stays signed out.
    static func answer(action: String, id: String, now: Date = Date()) -> WatchPush? {
        guard WatchVoice.isID(id), action == allow || action == deny else { return nil }
        return WatchPush(.signIn, signIn: WatchSignInAnswer(id: id.lowercased(), allowed: action == allow), at: now)
    }

    /// Hands the answer to the watch with this phone's mark now; nothing while this phone is signed out.
    static func respond(action: String, id: String) {
        let reading = KeptWatchReading()
        guard reading.signedIn, let push = answer(action: action, id: id) else { return }
        WatchPushRelay.toWatch(push, mark: reading.mark)
    }
}
