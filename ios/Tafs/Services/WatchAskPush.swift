import Foundation

/// The watch requests this iPhone sent for its watch (docs/watch-app.md, "An answer you can't miss, with the app put away"):
/// their ids alone, never their words, each kept a day, at most `most`. With each, whether the watch read its answer itself
/// and whether it was posted already, so tafs cloud's push and W3's follow post it once at most, and never one the watch showed.
struct WatchAskPushes {
    struct Entry: Codable, Equatable {
        var at: Date
        var seen = false
        var posted = false
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

    /// The relay sent this request for the watch.
    func sent(_ id: String, at now: Date = Date()) {
        var entries = all()
        if entries[id] == nil { entries[id] = Entry(at: now) }
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
        await handle(id, read: { await reading.readAsk($0) }, phoneInUse: { CoachFollow.phoneActive },
                     relay: { WatchPushRelay.toWatch($0) },
                     post: { answer, ticket in await CoachFollow.notify(answer, ticket: ticket) })
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

    static func run(change: String, taf: String?) async {
        let reading = KeptWatchReading()
        guard reading.signedIn, watchListening else { return }
        switch change {
        case "watch_inbox": await inbox(read: { await reading.inboxMarks() }, relay: toWatch)
        // The changes that reload a taf's chat on the iPhone too (`AppModel.liveChange`).
        case "taf", "knock": chat(taf, relay: toWatch)
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

    /// Hands a push to the watch (`WatchLink.push`); nothing on a Mac.
    static func toWatch(_ push: WatchPush) {
        #if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
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

    static func content(_ destination: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = destination == "coach" ? Words.Coach.title : "tafs"
        content.body = Words.Coach.Chats.fromYourWatch
        content.sound = .default
        content.userInfo = ["open": destination]
        return content
    }

    static func run(_ destination: String) async {
        if CoachFollow.phoneActive, let app = UIApplication.shared.delegate as? PushDelegate {
            app.open(destination)
            return
        }
        let request = UNNotificationRequest(identifier: "watch-open-" + destination, content: content(destination), trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
