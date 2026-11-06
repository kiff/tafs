import Foundation

/// What a not-done Activity row's next step does (kiff/tafs#476, gap 10), the same as Android's.
/// tafs cloud says which step and its words; the app only finds what the button opens. A kind
/// it doesn't know is its words alone, with no button.
enum NextStepWords {
    enum Action: Equatable {
        /// The connection's vendor signed it out: sign in again, as on its taf's Connected tools.
        case signInAgain(PlanqueClient.Grant)
        /// Connect apps, where the owner connects that app again (a refused key is replaced there).
        case connectApps
        /// Coach on the taf, with these words in the box for the owner to send.
        case askCoach(String)
        /// A line: try later, or a kind this app doesn't know.
        case line
    }

    /// The connection whose tools include `tool`: the taf's own first (its KIFF agent), else
    /// any of the account's. One disconnected or failed is none.
    static func connection(for tool: String?, agent: String?, grants: [PlanqueClient.Grant]) -> PlanqueClient.Grant? {
        guard let tool, !tool.isEmpty else { return nil }
        let live = grants.filter { !["disconnected", "failed"].contains($0.status) && actions($0).contains(tool) }
        return live.first { $0.request.agent == agent } ?? live.first
    }

    /// A grant's tools as KIFF names them, as `AppModel.tafsUsing` reads them.
    private static func actions(_ g: PlanqueClient.Grant) -> Set<String> {
        Set(g.tools ?? g.request.tools.filter(\.tafs).map(\.action))
    }

    /// What `next` does for the taf whose KIFF agent is `agent`; `fallback` is Coach's first
    /// message when tafs cloud sent none.
    static func action(_ next: TafsCloud.NextStep, agent: String?, grants: [PlanqueClient.Grant], fallback: String) -> Action {
        switch next.kind {
        case "reconnect":
            if let g = connection(for: next.tool, agent: agent, grants: grants), g.needsReconnect { return .signInAgain(g) }
            return .connectApps
        case "ask_coach":
            let message = next.message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return .askCoach(message.isEmpty ? fallback : next.message!)
        default:
            return .line
        }
    }
}
