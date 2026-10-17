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

    var items: [Item] {
        (messages.map(Item.message) + components.filter { $0.component.type != .steps }.map(Item.component))
            .sorted { $0.at < $1.at }
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
