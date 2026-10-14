import Foundation

/// The decision for one proposed action.
enum Decision: Equatable {
    case allowed
    case held(reason: String)
    case refused(reason: String)
}

/// Checks a proposed action against a taf's Card and what it has used.
///
/// In production KIFF makes this decision at its gateway, before any call reaches
/// a tool; this copy drives the demo and lets the app explain a decision offline.
enum CardCheck {
    static func decide(_ proposed: ProposedAction, for taf: Taf) -> Decision {
        if taf.card.revoked { return .refused(reason: "Card revoked") }
        guard let rule = taf.card.rules.first(where: { $0.action == proposed.action }) else {
            return .refused(reason: "Not on this taf's Card")
        }
        if rule.never { return .refused(reason: "\(rule.label): never allowed") }
        if rule.alwaysAsk { return .held(reason: "\(rule.label): always asks you") }

        var breaches: [String] = []
        if let perAction = rule.perAction, let amount = proposed.amount, amount > perAction {
            breaches.append("Over \(perAction.formatted) each")
        }
        if let perWindow = rule.perWindow, let amount = proposed.amount {
            let used = taf.used[rule.action] ?? .zero
            if used + amount > perWindow {
                breaches.append("Over \(perWindow.formatted) \(rule.window.label)")
            }
        }
        if let count = rule.countPerWindow, (taf.counted[rule.action] ?? 0) + 1 > count {
            breaches.append("Over \(count) \(rule.window.label)")
        }
        guard !breaches.isEmpty else { return .allowed }

        let reason = breaches.joined(separator: ", ")
        return rule.outside == .hold ? .held(reason: reason) : .refused(reason: reason)
    }

    /// Counts an allowed or approved action against the Card's windows.
    static func draw(_ proposed: ProposedAction, on taf: inout Taf) {
        if let amount = proposed.amount {
            taf.used[proposed.action] = (taf.used[proposed.action] ?? .zero) + amount
        }
        taf.counted[proposed.action] = (taf.counted[proposed.action] ?? 0) + 1
    }
}
