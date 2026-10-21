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
