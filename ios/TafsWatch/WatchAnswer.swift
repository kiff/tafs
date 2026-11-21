import Foundation

/// A Refuse or a Halt sent from the wrist, and what the phone said came of it. "Refused" or
/// "Halted" shows only once the phone has KIFF's or tafs cloud's answer, never before.
enum WatchAnswer: Equatable {
    case sending
    /// Done, or already done elsewhere: the phone's words.
    case done(String)
    /// Not done: why, in the phone's words, or that the phone wasn't in reach.
    case failed(String)

    /// What a reply means for the answer. `notDone` titles a failure ("Not refused").
    static func from(_ reply: WatchReply?, notDone: String? = nil) -> WatchAnswer {
        from(reply.map(PhoneReach.reply) ?? .failed(.notReachable), notDone: notDone)
    }

    /// The same, with why the phone didn't answer in its own words (row 44, D).
    static func from(_ reached: PhoneReach, notDone: String? = nil) -> WatchAnswer {
        let titled = { (line: String) in [notDone, line].compactMap { $0 }.joined(separator: "\n") }
        // Out of reach is a failure like any other: titled too, so the wrist says what didn't happen.
        let reply: WatchReply
        switch reached {
        case .reply(let answered): reply = answered
        case .failed(let why): return .failed(titled(why.line))
        }
        switch reply.outcome {
        case .ok, .answered:
            return .done(reply.line ?? "")
        case .failed:
            return .failed(titled(reply.line ?? Words.Alert.tafsCouldNotReachKiff))
        case .signedOut:
            return .failed(titled(Words.Watch.signInOnPhone))
        case .refused, .stale, .notYet:
            // Dropped by the phone, or a phone too old to answer it: nothing was done.
            return .failed(titled(Words.Watch.phoneNotReachable))
        }
    }

    var isSending: Bool { self == .sending }
}
