import Foundation

/// The owner's answers to a questions card (kiff/tafs#138, item 57), as one message to the
/// agent: "[questions <id>] {"<question>": {"value": …} | {"values": […]} | {"own": "…"}}".
/// Each answer is a choice of the agent's, several (a multi question), or the owner's own
/// words. The words read back in the thread are the agent's labels and the owner's own.
enum QuestionAnswers {
    enum Answer: Equatable {
        case choice(String)
        case choices([String])
        case own(String)
    }

    /// The message's answer text: JSON, keys sorted, so the same answers send the same words.
    static func encode(_ answers: [String: Answer]) -> String {
        var out: [String: Any] = [:]
        for (id, a) in answers {
            switch a {
            case .choice(let v): out[id] = ["value": v]
            case .choices(let vs): out[id] = ["values": vs]
            case .own(let text): out[id] = ["own": text]
            }
        }
        let data = (try? JSONSerialization.data(withJSONObject: out, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    static func decode(_ text: String) -> [String: Answer]? {
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: [String: Any]] else { return nil }
        var out: [String: Answer] = [:]
        for (id, a) in obj {
            if let v = a["value"] as? String { out[id] = .choice(v) }
            else if let vs = a["values"] as? [String] { out[id] = .choices(vs) }
            else if let own = a["own"] as? String { out[id] = .own(own) }
        }
        return out
    }

    /// The answers in the owner's words, in the card's order: "Test mode · Just one · In your
    /// words: “300 € on weekdays”". Nil when the text isn't answers.
    static func words(_ text: String, questions: [SetupComponent.Question]) -> String? {
        guard let answers = decode(text) else { return nil }
        let order = questions.map(\.id) + answers.keys.filter { id in !questions.contains { $0.id == id } }.sorted()
        let parts: [String] = order.compactMap { id in
            guard let a = answers[id] else { return nil }
            let labels = questions.first { $0.id == id }?.options ?? []
            let label = { (v: String) in labels.first { $0.value == v }?.label ?? v }
            switch a {
            case .choice(let v): return label(v)
            case .choices(let vs): return vs.map(label).joined(separator: ", ")
            case .own(let t): return "In your words: “\(t)”"
            }
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// What the owner has picked on a questions card so far. A question's answer is one of
/// its choices (several, on a multi question) or the owner's own words, never both, as
/// the contract has it: opening "Something else…" clears the picks, and picking a choice
/// closes the own field (kiff/tafs#138, item 57).
struct QuestionSelection: Equatable {
    var picked: [String: Set<String>] = [:]
    var own: [String: String] = [:]
    var ownOpen: Set<String> = []

    mutating func toggleOwn(_ q: SetupComponent.Question) {
        if ownOpen.contains(q.id) { ownOpen.remove(q.id) } else { ownOpen.insert(q.id); picked[q.id] = [] }
    }

    mutating func pick(_ q: SetupComponent.Question, _ value: String) {
        if q.multi == true {
            var set = picked[q.id] ?? []
            if set.contains(value) { set.remove(value) } else { set.insert(value) }
            picked[q.id] = set
        } else {
            picked[q.id] = [value]
        }
        ownOpen.remove(q.id)
    }

    /// The question's answer, if it has one.
    func answer(_ q: SetupComponent.Question) -> QuestionAnswers.Answer? {
        if ownOpen.contains(q.id) {
            let words = (own[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return words.isEmpty ? nil : .own(words)
        }
        let chosen = q.options.map(\.value).filter { picked[q.id]?.contains($0) == true }
        if chosen.isEmpty { return nil }
        return q.multi == true ? .choices(chosen) : .choice(chosen[0])
    }
}
