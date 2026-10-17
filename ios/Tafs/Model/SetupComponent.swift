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
        /// The taf's KIFF draft, the one the owner confirmed: its key is claimed from it.
        let draft: String?
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
        let lastMessage = messages.last.map { ($0.at, $0.session) }
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

    private func placedAt(_ c: ShownComponent) -> Date {
        guard Self.asking.contains(c.component.type) else { return c.firstAt }
        let nextOwner = messages.first { $0.from == .owner && $0.at > c.firstAt }?.at ?? .distantFuture
        let after = messages.filter { $0.from == .taf && $0.session == c.session && $0.at >= c.firstAt && $0.at < nextOwner }
        return after.last?.at ?? c.firstAt
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
