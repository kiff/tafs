import Foundation

/// The watch app against a phone of fixed answers, for the UI tests (`TafsWatchUITests`) that walk the watch's rows a
/// simulator can show (docs/watch-app.md, "The demo phone"), and for anyone checking the watch's screens in the simulator without a signed-in iPhone. On only when the
/// app is launched with `-watch-demo`: the shipped app never takes this path, asks no real phone in it and keeps nothing
/// of it. The watch's own code is all as shipped; only the phone and the lock are the demo's.
///
/// Debug builds only: a release build compiles none of it, so `on` is always false there, and no launch argument can swap
/// the phone or take the passcode away.
enum WatchDemo {
    #if DEBUG
    /// The app was launched for the demo.
    static let on = ProcessInfo.processInfo.arguments.contains("-watch-demo")
    /// The demo phone is out of reach: each ask says the phone isn't in reach (row 30).
    static let unreachable = on && ProcessInfo.processInfo.arguments.contains("-watch-demo-unreachable")
    /// The demo phone is in reach but can't reach tafs cloud: each read fails in the phone's words.
    static let cloudDown = on && ProcessInfo.processInfo.arguments.contains("-watch-demo-cloud-down")
    /// The demo watch has no passcode: Refuse and Halt say to set one, and the Inbox isn't kept.
    static let noPasscode = on && ProcessInfo.processInfo.arguments.contains("-watch-demo-no-passcode")
    /// A taf in Your tafs opens on its page, as it does with taf chat off, rather than on its chat with the page one
    /// vertical page below: a UI test can't turn that page reliably, so it reaches What it does and Halt this way.
    static let tafPage = on && ProcessInfo.processInfo.arguments.contains("-watch-demo-taf-page")
    #else
    static let on = false
    static let tafPage = false
    #endif

    /// The phone the app asks: the demo's, or the real one.
    @MainActor static var phone: PhoneAsking {
        #if DEBUG
        if on { return DemoPhone.shared }
        #endif
        return PhoneLink.shared
    }

    /// The watch's lock: the demo's (a passcode, unless `-watch-demo-no-passcode`), or the watch's own.
    static var lock: WristLock {
        #if DEBUG
        if on { return DemoLock(secure: !noPasscode) }
        #endif
        return DeviceWristLock()
    }

    /// The demo's own folder, emptied at each demo launch, so every run starts as a watch just installed.
    static let folder: URL = {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("watch-demo", isDirectory: true)
        if on {
            try? FileManager.default.removeItem(at: url)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        return url
    }()
}

#if DEBUG
/// The demo watch's lock: a passcode set, or none.
struct DemoLock: WristLock {
    let secure: Bool
    var isSecure: Bool { secure }
}

/// A phone of fixed answers, as a signed-in iPhone would give them: three tafs (one paused, one halted), two held calls,
/// today's calendar, the account, and Coach answering any words. Refuse and Halt change what it answers next, as the real
/// phone's would. Nothing leaves the watch.
@MainActor
final class DemoPhone: PhoneAsking {
    static let shared = DemoPhone()

    private var calls: [WatchNeedsYou.Call] = [
        WatchNeedsYou.Call(id: "hold-refund", taf: "Refunds", what: "Refund order 4417", summary: "Refund €240 to Ana Ruiz",
                           reason: "Over the Card's €200 a refund", answerBy: "18:00"),
        WatchNeedsYou.Call(id: "hold-ship", taf: "Late parcels", what: "Reship parcel 88-2", reason: "Reships wait for you",
                           answerBy: "tomorrow 09:00"),
    ]
    private var tafs: [WatchTaf] = [
        WatchTaf(id: "taf-refunds", name: "Refunds", line: "Answers refund requests", halted: false),
        WatchTaf(id: "taf-parcels", name: "Late parcels", line: "Paused: its schedule is off. It still answers what you send.",
                 halted: false, paused: true),
        WatchTaf(id: "taf-leads", name: "Lead follow-up", line: "Halted", halted: true),
    ]
    private var marks = WatchInboxMarks()
    private var sent: [String: String] = [:]

    func ask(_ request: WatchRequest) async -> WatchReply? { (await reach(request)).reply }

    func reach(_ request: WatchRequest) async -> PhoneReach {
        if WatchDemo.unreachable { return .failed(.notReachable) }
        if WatchDemo.cloudDown, request.kind.isRead { return .reply(WatchReply(.failed, line: Words.Watch.phoneCouldNotReachCloud)) }
        return .reply(answer(request))
    }

    private func answer(_ request: WatchRequest) -> WatchReply {
        switch request.kind {
        case .needsYou:
            return WatchReply(.ok, needsYou: WatchNeedsYou(count: calls.count, calls: calls, at: Date()))
        case .tafs:
            return WatchReply(.ok, tafs: tafs, zone: "Europe/Paris")
        case .refuse:
            // As the phone's relay says it: refused, or already answered elsewhere.
            guard let hold = request.hold, calls.contains(where: { $0.id == hold }) else {
                return WatchReply(.answered, line: Words.Watch.alreadyAnswered)
            }
            calls.removeAll { $0.id == hold }
            return WatchReply(.ok, line: Words.Watch.refused)
        case .halt:
            guard let id = request.taf, let i = tafs.firstIndex(where: { $0.id == id }) else { return WatchReply(.refused) }
            tafs[i].halted = true
            tafs[i].line = "Halted"
            return WatchReply(.ok, line: "Halted \(tafs[i].name).")
        case .haltAll:
            for i in tafs.indices { tafs[i].halted = true; tafs[i].line = "Halted" }
            return WatchReply(.ok, line: "Halted all your tafs.")
        case .taf:
            guard let taf = tafs.first(where: { $0.id == request.taf }) else { return WatchReply(.failed) }
            return WatchReply(.ok, tafPage: WatchTafPage(
                id: taf.id, name: taf.name, state: taf.line ?? "", halted: taf.halted, job: "Answers refund requests from the shop.",
                cards: [WatchTafPage.Card(title: "Refunds", figure: "€1,240 of €2,000 today", words: nil,
                                          rules: ["Up to €200 a refund", "Holds anything over that for you"])],
                waiting: calls.filter { $0.taf == taf.name }.count))
        case .calendar:
            return WatchReply(.ok, calendar: WatchCalendarDay(day: request.day ?? "2026-11-24", words: "Today", events: [
                WatchCalendarDay.Event(time: "09:00", taf: "Refunds", words: "Answers the night's refund requests", count: 1),
                WatchCalendarDay.Event(time: "14:00", taf: "Late parcels", words: "Checks late parcels", count: 1),
            ], halted: false))
        case .account:
            return WatchReply(.ok, account: WatchAccount(email: "owner@example.com", account: "Example Shop", plan: "Solo · 1,200 credits left"))
        case .brief:
            return WatchReply(.ok)
        case .coach:
            return WatchReply(.ok, coach: Self.coach(request.text))
        case .coachSend:
            let id = request.message ?? UUID().uuidString.lowercased()
            sent[id] = request.text ?? ""
            let chat = request.taf.map { "taf-chat-\($0)" } ?? WatchCoachTicket.askChat
            return WatchReply(.ok, ticket: WatchCoachTicket(chat: chat, session: id, sent: Self.iso(Date())))
        case .coachRead:
            return WatchReply(.ok, coach: Self.coach(request.ticket.flatMap { sent[$0.session] }), answering: false)
        case .askFull:
            return WatchReply(.ok, coach: WatchCoachAnswer.whole(Self.longAnswer, approveOnPhone: false))
        case .tafChat:
            return WatchReply(.ok, chat: [
                WatchChatLine(id: "c1", mine: true, at: Date().addingTimeInterval(-3600), text: "How did the night go?", more: false),
                WatchChatLine(id: "c2", mine: false, at: Date().addingTimeInterval(-3590),
                              text: "I answered 6 refund requests. One over €200 waits for you.", more: false),
            ], zone: "Europe/Paris")
        case .transcribe:
            return WatchReply(.notYet)
        case .inboxMarks:
            return WatchReply(.ok, marks: marks)
        case .inboxMark:
            if let new = request.marks {
                marks.read = Array(Set(marks.read + new.read)).sorted()
                marks.dismissed = Array(Set(marks.dismissed + new.dismissed)).sorted()
            }
            return WatchReply(.ok, marks: marks)
        case .openOnPhone:
            // The demo has no phone to open.
            return WatchReply(.ok)
        }
    }

    /// Coach's answer to the words sent: one that needs a yes says to approve on the phone, as the real phone's would.
    private static func coach(_ words: String?) -> WatchCoachAnswer {
        let asked = (words ?? "").lowercased()
        if asked.contains("refund") {
            return WatchCoachAnswer(text: "I can refund order 4417, but it's over the Card's €200, so it needs your yes.",
                                    more: false, approveOnPhone: true)
        }
        if asked.contains("more") { return WatchCoachAnswer.cut(longAnswer, more: true, approveOnPhone: false) }
        return WatchCoachAnswer(text: "Two calls wait for you: a refund of €240 and a reship. Everything else ran on its Card.",
                                more: false, approveOnPhone: false)
    }

    private static let longAnswer = String(repeating: "Refunds answered six requests overnight and kept each within its Card. ", count: 8)

    private static func iso(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }
}
#endif
