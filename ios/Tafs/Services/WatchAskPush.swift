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
    /// The push, handled: reads the request and posts it as W3's notification. Returns whether it posted.
    @discardableResult
    static func handle(_ id: String, pushes: WatchAskPushes = WatchAskPushes(), read: (String) async -> WatchReply,
                       now: () -> Date = Date.init, phoneInUse: () -> Bool,
                       post: (WatchCoachAnswer, WatchCoachTicket) async -> Void) async -> Bool {
        // Nothing is read for a request this iPhone didn't send, or whose answer the watch had or was posted already.
        guard let entry = pushes.entry(id, now: now()), !entry.seen, !entry.posted else { return false }
        let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: id, sent: nil)
        guard !phoneInUse(), !watchHas(ticket, now: now()) else { return false }
        let reply = await read(id)
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
                     post: { answer, ticket in await CoachFollow.notify(answer, ticket: ticket) })
    }
}
