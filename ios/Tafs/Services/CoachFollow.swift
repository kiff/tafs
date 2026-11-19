import Foundation
#if canImport(UIKit)
import UIKit
#endif
import UserNotifications

/// W3 on Apple Watch, the owner's Watch 5 test (docs/watch-app.md, "W3"): Coach's answer you can't miss once the watch app
/// is put away. watchOS stops the watch's reads within seconds of that, and the watch app posts no notifications of its own
/// (the privacy text: its only notifications are the iPhone's). So after a watch Send, the iPhone follows Coach's answer
/// itself, inside the background time iOS gives it. If the watch has stopped reading by the time Coach answers, the iPhone
/// posts it as a local notification, "Coach" and its answer, which watchOS shows on the wrist while the iPhone is locked.
/// If the watch read the answer itself, nothing is posted. An answer past the follow shows only in Coach's chat.
@MainActor
enum CoachFollow {
    /// How many times the iPhone reads, 1.5 s apart: 24 s, inside the ~30 s iOS gives a background task.
    static let reads = 16
    /// The watch reads every 1.5 s while Coach's screen is open: none for this long means it was put away.
    static let watchAway: TimeInterval = 4

    /// When the watch last read each ticket, and the tickets whose answer it read itself.
    private(set) static var watchLastRead: [String: Date] = [:]
    private(set) static var watchHad: Set<String> = []

    static func key(_ ticket: WatchCoachTicket) -> String { [ticket.chat, ticket.session, ticket.sent ?? ""].joined(separator: "/") }

    /// The watch read this ticket now (`coachRead`).
    static func watchReading(_ ticket: WatchCoachTicket, at now: Date = Date()) { watchLastRead[key(ticket)] = now }
    /// The watch read Coach's finished answer itself: nothing to post.
    static func watchHas(_ ticket: WatchCoachTicket) { watchHad.insert(key(ticket)) }

    /// The app's follow: inside a background task, posting with the notification center.
    static func run(_ ticket: WatchCoachTicket, read: @escaping (WatchCoachTicket) async -> WatchReply) async {
        #if canImport(UIKit)
        let task = UIApplication.shared.beginBackgroundTask(withName: "coach-follow")
        defer { if task != .invalid { UIApplication.shared.endBackgroundTask(task) } }
        #endif
        await follow(ticket, read: read, wait: { try? await Task.sleep(nanoseconds: 1_500_000_000) }, now: Date.init,
                     post: { answer in await notify(answer, ticket: ticket) })
    }

    /// Reads Coach's answer up to `reads` times. Once it's finished, posts it only if the watch hasn't read it itself and
    /// hasn't read at all for `watchAway`. Returns whether it posted.
    @discardableResult
    static func follow(_ ticket: WatchCoachTicket, read: (WatchCoachTicket) async -> WatchReply, wait: () async -> Void,
                       now: () -> Date, post: (WatchCoachAnswer) async -> Void) async -> Bool {
        let key = key(ticket)
        // The watch reads from the moment it's told "Sent".
        watchLastRead[key] = now()
        defer {
            watchLastRead[key] = nil
            watchHad.remove(key)
        }
        for _ in 0..<reads {
            await wait()
            if watchHad.contains(key) { return false }
            let reply = await read(ticket)
            guard reply.outcome == .ok, reply.answering == false, let answer = reply.coach else { continue }
            if watchHad.contains(key) { return false }
            guard now().timeIntervalSince(watchLastRead[key] ?? .distantPast) > watchAway else { return false }
            await post(answer)
            return true
        }
        return false
    }

    /// "Coach", then its answer as the wrist shows it; a tap opens Coach's chat.
    static func content(_ answer: WatchCoachAnswer) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = Words.Coach.title
        var lines = [answer.text].filter { !$0.isEmpty }
        if answer.approveOnPhone {
            lines.append(Words.Watch.approveOnPhone)
        } else if answer.more || lines.isEmpty {
            lines.append(Words.Watch.moreOnPhone)
        }
        content.body = lines.joined(separator: "\n")
        content.sound = .default
        content.userInfo = ["open": "coach"]
        return content
    }

    private static func notify(_ answer: WatchCoachAnswer, ticket: WatchCoachTicket) async {
        let request = UNNotificationRequest(identifier: "coach-reply-" + key(ticket), content: content(answer), trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
