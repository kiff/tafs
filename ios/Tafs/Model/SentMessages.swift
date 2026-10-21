import Foundation

/// What the owner sent that tafs cloud's thread doesn't have yet (kiff/tafs#82), kept by one
/// rule in both chats, Coach mode's and a taf's (kiff/tafs#138, item 48): a reload while the
/// answer is on its way must never take the owner's message off the screen.
///
/// A sent message goes once the thread has it (the same words, written about when it was
/// sent), or after `kept`, when it either has it or the send says why not. One copy takes
/// the place of one sent message, oldest first: two "Sí" sent close together stay two until
/// the thread has both.
enum SentMessages {
    static let kept: TimeInterval = 120

    /// The thread's messages with the sent ones it doesn't have yet, and those still waiting.
    static func merge(_ messages: [TafsCloud.ChatMessage], sent: [TafsCloud.ChatMessage], now: Date)
        -> (messages: [TafsCloud.ChatMessage], waiting: [TafsCloud.ChatMessage]) {
        var taken = Set<Int>()
        let waiting = sent.filter { s in
            if now.timeIntervalSince(s.at) > kept { return false }
            let copy = messages.indices.first { i in
                let m = messages[i]
                return !taken.contains(i) && m.from == .owner && m.text == s.text && m.at >= s.at.addingTimeInterval(-kept)
            }
            guard let copy else { return true }
            taken.insert(copy)
            return false
        }
        return (messages + waiting, waiting)
    }

    /// When a message being sent is stamped: now, but never before the thread's latest, so a
    /// phone clock a little behind tafs cloud's doesn't place it above the answer it follows.
    static func stamp(after latest: Date?, now: Date) -> Date {
        guard let latest else { return now }
        return max(now, latest.addingTimeInterval(0.001))
    }
}
