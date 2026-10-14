import Foundation

/// What starting a taf on KIFF asks for: one Card per connected tool the taf may use,
/// with its limits in the tool's own unit. Kept apart from the views so it is tested.
struct ToolLimit: Identifiable, Equatable {
    var id: String { tool.name }
    let tool: KIFFClient.Connection
    var on: Bool
    /// What the owner typed, in the tool's unit ("150", "150.00"). Empty means no limit.
    var perCall: String = ""
    var perDay: String = ""
    var callsPerDay: String = ""
}

enum TafPlan {
    enum PlanError: Error, Equatable, LocalizedError {
        case noTools
        case badNumber(tool: String, field: String)
        case nothingOverTime(tool: String)

        var errorDescription: String? {
            switch self {
            case .noTools: return "Choose at least one tool the taf may use."
            case let .badNumber(tool, field): return "\(field) for \(tool) isn't a number above zero."
            case let .nothingOverTime(tool): return "Set a total or a number of calls a day for \(tool). A Card with neither limits nothing over time."
            }
        }
    }

    /// A KIFF agent id from the taf's name, with a short random suffix so two tafs
    /// with the same name are still two agents ("Rae" → "rae-k3f9").
    static func agentID(for name: String, suffix: String = randomSuffix()) -> String {
        let folded = name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current).lowercased()
        var slug = ""
        for ch in folded {
            if ch.isLetter || ch.isNumber, ch.isASCII { slug.append(ch) } else if !slug.hasSuffix("-") { slug.append("-") }
        }
        slug = String(slug.trimmingCharacters(in: CharacterSet(charactersIn: "-")).prefix(40))
        return (slug.isEmpty ? "taf" : slug) + "-" + suffix
    }

    static func randomSuffix() -> String {
        let letters = Array("abcdefghjkmnpqrstuvwxyz23456789")
        return String((0..<4).map { _ in letters.randomElement()! })
    }

    /// The Cards, one per tool that is on. Each is `<agent>-<tool>`, as the dashboard names them.
    static func cards(agentID: String, limits: [ToolLimit]) throws -> [KIFFClient.CardRequest] {
        let chosen = limits.filter(\.on)
        guard !chosen.isEmpty else { throw PlanError.noTools }
        return try chosen.map { limit in
            let tool = limit.tool
            var grant = KIFFClient.CardRequest.Grant(action: tool.name)
            var aggregates: [KIFFClient.CardRequest.Aggregate] = []
            if tool.movesAmount, let arg = tool.amountArgument {
                let quantity = "sum(\(arg))"
                let scale = tool.amountUnit == nil ? 0 : (tool.amountScale ?? 0)
                if let perCall = try amount(limit.perCall, scale: scale, tool: tool.name, field: "The limit per call") {
                    grant.perActionMax = [.init(quantity: quantity, max: perCall)]
                }
                if let perDay = try amount(limit.perDay, scale: scale, tool: tool.name, field: "The total a day") {
                    aggregates.append(.init(quantity: quantity, limit: perDay, window: "calendar_day"))
                }
            }
            if let calls = try amount(limit.callsPerDay, scale: 0, tool: tool.name, field: "The number of calls a day") {
                aggregates.append(.init(quantity: "count", limit: calls, window: "calendar_day"))
            }
            guard !aggregates.isEmpty else { throw PlanError.nothingOverTime(tool: tool.name) }
            return KIFFClient.CardRequest(id: "\(agentID)-\(tool.name)", subject: agentID, grants: [grant], aggregates: aggregates)
        }
    }

    /// "150.5" at scale 2 → 15050. Empty → nil. Not a number, zero or below, or more
    /// decimals than the tool's unit has → error.
    static func amount(_ text: String, scale: Int, tool: String, field: String) throws -> Int64? {
        let trimmed = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if trimmed.isEmpty { return nil }
        guard let value = Decimal(string: trimmed, locale: Locale(identifier: "en_US_POSIX")), value > 0 else {
            throw PlanError.badNumber(tool: tool, field: field)
        }
        var scaled = value
        for _ in 0..<scale { scaled *= 10 }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        guard rounded == scaled, rounded > 0, rounded <= Decimal(Int64.max) else { throw PlanError.badNumber(tool: tool, field: field) }
        return NSDecimalNumber(decimal: rounded).int64Value
    }

    /// The tools to suggest for a job, with limits from the job's sample Card. A tool is
    /// suggested when its name carries one of the job's actions ("stripe_refund" for refunds).
    static func suggested(job: String, tools: [KIFFClient.Connection]) -> [ToolLimit] {
        let rules = TafTemplates.template(job)?.rules ?? []
        return tools.map { tool in
            let name = tool.name.lowercased()
            let rule = rules.first { r in !r.never && r.action.split(separator: "_").allSatisfy { name.contains($0) } }
            var limit = ToolLimit(tool: tool, on: rule != nil && !(rule?.alwaysAsk ?? false))
            guard let rule, !rule.alwaysAsk else {
                limit.callsPerDay = "20"
                return limit
            }
            let scale = tool.amountUnit == nil ? 0 : (tool.amountScale ?? 0)
            if tool.movesAmount, tool.amountUnit == rule.perAction?.currency ?? rule.perWindow?.currency {
                limit.perCall = rule.perAction.map { major($0, scale: scale) } ?? ""
                if rule.window == .day { limit.perDay = rule.perWindow.map { major($0, scale: scale) } ?? "" }
            }
            limit.callsPerDay = rule.window == .day ? rule.countPerWindow.map(String.init) ?? "" : ""
            if limit.perDay.isEmpty && limit.callsPerDay.isEmpty { limit.callsPerDay = "20" }
            return limit
        }
    }

    /// A sample rule's money in the tool's decimal places, as text to edit.
    private static func major(_ money: Money, scale: Int) -> String {
        var value = Decimal(money.minor)
        for _ in 0..<money.scale { value /= 10 }
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = scale
        return f.string(from: value as NSDecimalNumber) ?? ""
    }
}
