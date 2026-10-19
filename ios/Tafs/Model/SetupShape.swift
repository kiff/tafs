import Foundation

/// What Setup's conversation has made so far, for the "Taking shape" panel beside it on
/// a wide screen (kiff/tafs#85): the taf's name, the plan, the tools it connects and the
/// Card it drafts. Read only from what the app already holds: the thread, planque's
/// grants and KIFF's drafts. Nothing here asks a server for more.
struct SetupShape: Equatable {
    struct Step: Equatable {
        enum State: Equatable { case done, needsYou, now, toDo, failed, skipped }
        let label: String
        let state: State
    }

    /// A vendor the conversation connects, from planque's grant.
    struct Tool: Equatable, Identifiable {
        enum State: Equatable { case connected, signedIn, waiting, failed(String?) }
        /// planque's grant id.
        let id: String
        let vendor: String
        let state: State
        /// The tools the taf calls through it, once connected.
        let actions: [String]
    }

    /// The taf's Cards as KIFF drafted them: a draft until the owner confirms it.
    struct Card: Equatable {
        let agent: String
        /// What the owner reads it as: "Refunds assistant's Card".
        var title = ""
        let lines: [String]
        let confirmed: Bool
    }

    /// The taf's name, once Setup hands it over.
    var name: String?
    /// The plan's title, such as "Connect Stripe".
    var title: String?
    var steps: [Step] = []
    var tools: [Tool] = []
    var card: Card?

    var isEmpty: Bool { name == nil && steps.isEmpty && tools.isEmpty && card == nil }

    /// `waiting` is the card waiting for the owner; `states` each card's state, by id, as
    /// `AppModel.setupCardState` says.
    init(thread: SetupThread, grants: [PlanqueClient.Grant], drafts: [String: KIFFClient.AgentDraft],
         waiting: String?, states: [String: AppModel.CardState]) {
        let shown = thread.components.map(\.component)
        name = shown.last { $0.type == .result && $0.taf != nil }?.taf?.name

        // The plan, as Setup keeps it. Its step at work waits for the owner when a card does.
        if let plan = thread.plan?.component {
            title = plan.title
            var waited = false
            steps = (plan.steps ?? []).map { step in
                switch step.status {
                case "done": return Step(label: step.label, state: .done)
                case "failed": return Step(label: step.label, state: .failed)
                case "skipped": return Step(label: step.label, state: .skipped)
                case "active":
                    let needsYou = waiting != nil && !waited
                    waited = waited || needsYou
                    return Step(label: step.label, state: needsYou ? .needsYou : .now)
                default: return Step(label: step.label, state: .toDo)
                }
            }
        } else {
            // No plan shown: what Setup asked of the owner, and where each stands.
            steps = thread.asks.map { ask in
                let c = ask.component
                let vendor = grants.first { $0.id == c.grant }?.vendor ?? c.service
                let label: String
                switch c.type {
                case .approval: label = "Approve " + (vendor ?? "the connection")
                case .signIn: label = "Sign in at " + (vendor ?? "the vendor")
                default: label = c.text ?? c.title ?? "Answer"
                }
                if ask.id == waiting { return Step(label: label, state: .needsYou) }
                if c.type == .confirm {
                    // Answered, or written past: the conversation went on without it.
                    return Step(label: label, state: thread.answer(to: c.id) != nil ? .done : .skipped)
                }
                switch states[c.id] ?? .needsYou {
                case .done: return Step(label: label, state: .done)
                case .declined: return Step(label: label, state: .skipped)
                case .failed: return Step(label: label, state: .failed)
                case .working: return Step(label: label, state: .now)
                case .needsYou: return Step(label: label, state: .toDo)
                }
            }
        }

        // The grants the conversation's cards name, in the order they came, each once.
        var seen: Set<String> = []
        tools = shown.compactMap { (c: SetupComponent) -> Tool? in
            guard let id = c.grant, !seen.contains(id), let grant = grants.first(where: { $0.id == id }) else { return nil }
            seen.insert(id)
            let state: Tool.State
            if grant.connected {
                state = .connected
            } else if grant.status == "failed" || grant.status == "disconnected" {
                state = .failed(grant.reason)
            } else if grant.holdsSignIn {
                state = .signedIn
            } else {
                state = .waiting
            }
            return Tool(id: id, vendor: grant.vendor, state: state, actions: grant.connected ? grant.request.tools.map(\.action) : [])
        }

        // The latest draft an approval names that KIFF has shown the app.
        let named = shown.filter { $0.type == .approval }.compactMap { c in c.draft.flatMap { id in drafts[id] } }
        if let draft = named.last(where: { $0.noTools != true }) {
            card = Card(agent: draft.agentID, title: draft.cardTitle, lines: (draft.cards ?? []).flatMap(\.lines), confirmed: draft.confirmed)
        }
    }
}

extension AppModel {
    /// Setup's conversation so far, for the "Taking shape" panel (kiff/tafs#85).
    var setupShape: SetupShape {
        let states = Dictionary(setupThread.asks.map { ($0.id, setupCardState($0.component)) }, uniquingKeysWith: { a, _ in a })
        return SetupShape(thread: setupThread, grants: grants, drafts: setupDrafts, waiting: setupWaiting?.id, states: states)
    }
}
