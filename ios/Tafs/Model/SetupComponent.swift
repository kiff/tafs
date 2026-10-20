import Foundation

/// One thing Setup shows in its chat (contract 1 on kiff/agents#91): its plan, a notice,
/// a question, an approval, a vendor sign-in or the hand-over. A component grants
/// nothing: an approval names planque's grant and KIFF's draft, which the app reads from
/// those services and the phone signs there.
struct SetupComponent: Decodable, Equatable {
    enum Kind: String, Decodable { case steps, notice, confirm, approval, signIn = "sign_in", result }

    struct Step: Decodable, Equatable, Hashable {
        /// pending, active, done, failed or skipped.
        let label: String
        let status: String
    }

    struct Option: Decodable, Equatable, Hashable {
        let value: String
        let label: String
    }

    struct Taf: Decodable, Equatable {
        let agent: String
        let name: String
        /// One of tafs' jobs (refunds, chargebacks, …): what the taf does.
        let job: String?
        /// The taf's KIFF draft, the one the owner confirmed: its key is claimed from it. For
        /// an improvement, the draft revising its Cards, confirmed only at the switch.
        let draft: String?
        /// The tafs id of the taf this is the next version of (kiff/tafs#107): an
        /// improvement, never a new taf.
        var improves: String?
        /// What changed, in the owner's language (an improvement).
        var note: String?
        /// The taf's full new instructions from the owner; nil keeps them (an improvement).
        var notes: String?
    }

    let type: Kind
    let id: String
    let title: String?
    let text: String?
    /// info, success, warning or error.
    let tone: String?
    let steps: [Step]?
    let options: [Option]?
    /// planque's grant id.
    let grant: String?
    /// KIFF's agent draft id: the taf's Cards.
    let draft: String?
    /// The vendor's host, such as mcp.stripe.com.
    let service: String?
    let taf: Taf?
    let tools: [String]?
    /// The card's own button words and note, in the owner's language, as Setup wrote
    /// them (kiff/tafs#75, kiff/agents#112). Only a sign-in and the hand-over carry them;
    /// an approval never does, since the owner's tap grants. The app's English is the
    /// fallback.
    let yes: String?
    let no: String?
    let note: String?

    /// Where a sign-in goes, as the app shows it: from the card's service, never from
    /// Setup's words (kiff/agents#112), so a label can't stand in for the destination.
    var destination: String { service ?? "the vendor's page" }
}

/// A component as the thread holds it: where it first appeared, and its latest version
/// (showing an id again replaces it in place).
struct ShownComponent: Identifiable, Equatable {
    var id: String { component.id }
    let firstAt: Date
    let session: String
    let run: String
    var component: SetupComponent
}

/// Setup's thread: the messages, and the components where they first appeared.
struct SetupThread: Equatable {
    var messages: [TafsCloud.ChatMessage] = []
    var components: [ShownComponent] = []
    /// The session Setup is at work in now, as tafs cloud says (kiff/tafs#58); nil when idle.
    var working: String?

    /// The plan, shown pinned above the thread rather than in it.
    var plan: ShownComponent? { components.last { $0.component.type == .steps } }

    /// The session the thread is in now: the latest one anything happened in.
    var session: String? {
        // A message on its way to tafs cloud has no session yet (kiff/tafs#82).
        let lastMessage = messages.last { $0.session != "pending" }.map { ($0.at, $0.session) }
        let lastShown = components.max { $0.firstAt < $1.firstAt }.map { ($0.firstAt, $0.session) }
        return [lastMessage, lastShown].compactMap { $0 }.max { $0.0 < $1.0 }?.1
    }

    /// The thread in order, the plan left out.
    enum Item: Identifiable, Equatable {
        case message(TafsCloud.ChatMessage)
        case component(ShownComponent)
        var id: String {
            switch self {
            case .message(let m): return "m|" + m.id
            case .component(let c): return "c|" + c.id
            }
        }
        var at: Date {
            switch self {
            case .message(let m): return m.at
            case .component(let c): return c.firstAt
            }
        }
    }

    /// In order, as it happened, with one exception (kiff/tafs#58): a component that asks
    /// the owner to act comes after what Setup wrote with it, so its words lead to the
    /// card. That is Setup's messages in the same session after the component, up to the
    /// owner's next message.
    var items: [Item] {
        let placed: [(Item, Date)] = messages.map { (Item.message($0), $0.at) } +
            components.filter { $0.component.type != .steps }.map { c in (Item.component(c), placedAt(c)) }
        return placed.enumerated().sorted { a, b in
            if a.element.1 != b.element.1 { return a.element.1 < b.element.1 }
            return a.offset < b.offset // a message before a component placed at its time
        }.map(\.element.0)
    }

    private static let asking: Set<SetupComponent.Kind> = [.confirm, .approval, .signIn, .result]
    private static let needsOwner: Set<SetupComponent.Kind> = [.confirm, .approval, .signIn]

    private func placedAt(_ c: ShownComponent) -> Date {
        guard Self.asking.contains(c.component.type) else { return c.firstAt }
        let nextOwner = messages.first { $0.from == .owner && $0.at > c.firstAt }?.at ?? .distantFuture
        let after = messages.filter { $0.from == .taf && $0.session == c.session && $0.at >= c.firstAt && $0.at < nextOwner }
        return after.last?.at ?? c.firstAt
    }

    /// The owner's answer to a component (contract 1's "[<type> <id>] <answer>"): its
    /// component's type and id, and the answer.
    struct Answer: Equatable {
        let type: String
        let id: String
        let text: String

        init?(_ message: String) {
            guard message.hasPrefix("["), let close = message.firstIndex(of: "]") else { return nil }
            let head = message[message.index(after: message.startIndex)..<close].split(separator: " ", maxSplits: 1)
            guard head.count == 2, SetupComponent.Kind(rawValue: String(head[0])) != nil else { return nil }
            type = String(head[0])
            id = String(head[1])
            text = message[message.index(after: close)...].trimmingCharacters(in: .whitespaces)
        }

        var failed: Bool { text.hasPrefix("failed") }
        /// Why it failed, as the app wrote it after "failed: ".
        var why: String? {
            guard failed else { return nil }
            let rest = text.dropFirst("failed".count).drop { $0 == ":" || $0 == " " }
            return rest.isEmpty ? nil : String(rest)
        }
    }

    /// The owner's latest answer to a component, as the thread has it.
    func answer(to id: String) -> Answer? {
        messages.lazy.reversed().filter { $0.from == .owner }.compactMap { Answer($0.text) }.first { $0.id == id }
    }

    /// An owner's message in their own words: an answer to a component reads as what
    /// they chose ("Refuse it", "Approve"), never as contract 1's form (kiff/tafs#58).
    func ownerWords(_ text: String) -> String {
        guard let a = Answer(text) else { return text }
        switch SetupComponent.Kind(rawValue: a.type) {
        case .confirm?:
            let options = components.first { $0.id == a.id }?.component.options ?? []
            return options.first { $0.value == a.text }?.label ?? a.text
        case .approval?:
            if a.failed { return "Approve · it didn't go through" }
            return a.text == "declined" ? "Decline" : "Approve"
        case .signIn?:
            if a.failed { return "Sign in · it didn't go through" }
            return a.text == "not now" ? "Not now" : "Signed in"
        default:
            return a.text
        }
    }

    /// Whether a newer approval in the thread asks about the same connection (kiff/tafs#138):
    /// then this one is history. Only the newest card for a connection shows its live state,
    /// so one failure doesn't show on every card that names the connection.
    func replaced(_ id: String) -> Bool {
        guard let shown = components.first(where: { $0.component.id == id }), let grant = shown.component.grant else { return false }
        return components.contains { other in
            other.component.id != id && other.component.type == .approval && other.component.grant == grant && other.firstAt > shown.firstAt
        }
    }

    /// The components that ask the owner to act, in the thread's order: an approval, a
    /// question or a sign-in.
    var asks: [ShownComponent] {
        items.compactMap { item in
            if case .component(let c) = item, Self.needsOwner.contains(c.component.type) { return c }
            return nil
        }
    }

    /// Folds components as the conversation lists them, oldest first: the latest
    /// version of each id, at the time it first appeared.
    static func fold(_ shown: [TafsCloud.Shown]) -> [ShownComponent] {
        var out: [ShownComponent] = []
        for s in shown {
            if let i = out.firstIndex(where: { $0.component.id == s.component.id }) {
                out[i].component = s.component
            } else {
                out.append(ShownComponent(firstAt: s.at, session: s.session, run: s.run, component: s.component))
            }
        }
        return out
    }
}
