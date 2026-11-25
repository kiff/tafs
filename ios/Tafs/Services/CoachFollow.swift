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
    /// How many times the iPhone reads: the first `quickReads` 1.5 s apart, then 3 s apart, as the watch does: 4 × 1.5 s +
    /// 6 × 3 s = 24 s, inside the ~30 s iOS gives a background task.
    static let reads = 10
    static let quickReads = 4
    /// The follow's length, in seconds.
    static var length: Double { Double(quickReads) * 1.5 + Double(reads - quickReads) * 3 }
    /// The watch reads at most 3 s apart while Coach's screen is open: none for this long means it was put away.
    static let watchAway: TimeInterval = 5

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
                     phoneInUse: { Self.phoneActive }, post: { answer in
                         // A watch request's answer is posted once, by this follow or by tafs cloud's push (`WatchAskPush`).
                         if let id = ticket.askID, !WatchAskPushes().claimPost(id) { return }
                         await notify(answer, ticket: ticket)
                     })
    }

    /// The iPhone is open in front of the person: tafs on screen, where Coach's chat shows the answer as it lands.
    static var phoneActive: Bool {
        #if canImport(UIKit)
        UIApplication.shared.applicationState == .active
        #else
        false
        #endif
    }

    /// Reads Coach's answer up to `reads` times. Once it's finished, posts it only if the watch hasn't read it itself, hasn't
    /// read at all for `watchAway`, and the iPhone isn't open in front of the person. Returns whether it posted.
    @discardableResult
    static func follow(_ ticket: WatchCoachTicket, read: (WatchCoachTicket) async -> WatchReply, wait: () async -> Void,
                       now: () -> Date, phoneInUse: () -> Bool = { false },
                       post: (WatchCoachAnswer) async -> Void) async -> Bool {
        let id = Self.key(ticket)
        // The watch reads from the moment it's told "Sent".
        watchLastRead[id] = now()
        defer {
            watchLastRead[id] = nil
            watchHad.remove(id)
        }
        for index in 0..<reads {
            await wait()
            if index >= quickReads { await wait() }
            if watchHad.contains(id) { return false }
            let reply = await read(ticket)
            guard reply.outcome == .ok, reply.answering == false, let answer = reply.coach else { continue }
            if watchHad.contains(id) { return false }
            guard now().timeIntervalSince(watchLastRead[id] ?? .distantPast) > watchAway else { return false }
            // tafs open on the iPhone: Coach's chat already shows it, so no banner over the answer being read.
            guard !phoneInUse() else { return false }
            await post(answer)
            return true
        }
        return false
    }

    /// "Coach", then its answer with no amount in it (`WatchAmount.strip`, the rule the watch's Inbox keeps: iOS keeps a
    /// notification until it's dismissed, and the text promises no amount in what's kept or sent through alerts); a tap
    /// opens Coach's chat, where the whole answer is.
    static func content(_ answer: WatchCoachAnswer) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = Words.Coach.title
        // An amount taken out leaves the stripped words; otherwise the answer as written (spacing alone takes nothing out).
        let taken = WatchAmount.hasAmount(answer.text)
        let text = taken ? WatchAmount.strip(answer.text) : answer.text.trimmingCharacters(in: .whitespacesAndNewlines)
        var lines = [text].filter { !$0.isEmpty }
        if answer.approveOnPhone {
            lines.append(Words.Watch.approveOnPhone)
        } else if answer.more || lines.isEmpty || taken {
            lines.append(Words.Watch.moreOnPhone)
        }
        content.body = lines.joined(separator: "\n")
        content.sound = .default
        // The owner's call 7, a notification you can't miss: it's the answer to what was just asked from the wrist, so it
        // comes through a Focus, as a hold does (the app's time-sensitive entitlement).
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["open": "coach"]
        return content
    }

    /// Posted under the ticket's own identifier: a second post of the same answer replaces the first.
    static func notify(_ answer: WatchCoachAnswer, ticket: WatchCoachTicket) async {
        let request = UNNotificationRequest(identifier: "coach-reply-" + key(ticket), content: content(answer), trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
