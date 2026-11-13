import Foundation

/// The AI-data consent (docs/accounts.md, "Your data and the AI"): before anything a person writes
/// reaches the model, they allow it once, on a screen that names the provider, what's sent and what
/// never is. tafs cloud keeps the answer per person, writes the words in their language, and refuses
/// every route that would start a model turn for them until it's given (`ai_consent_required`). The
/// app only draws the words and sends the answer.
extension TafsCloud {
    struct AIConsent: Decodable, Equatable, Identifiable {
        struct Words: Decodable, Equatable {
            let title: String
            let lines: [String]
            let allow: String
            let notNow: String
            let withdraw: String
            /// Account's row once it's given ("AI use: allowed").
            let given: String
            enum CodingKeys: String, CodingKey { case title, lines, allow, notNow = "not_now", withdraw, given }
        }
        let given: Bool
        var at: String?
        /// The words' version: a yes is sent with the version it was given to, so an older screen
        /// can't say yes to newer words.
        let version: String
        let words: Words
        var id: String { version }
        /// Drawn only with everything it must say: a title, the lines, Allow and Not now, as on
        /// Android. Anything less is no screen.
        var drawable: Bool {
            [words.title, words.allow, words.notNow].allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty } && !words.lines.isEmpty
        }
    }

    private struct ConsentAnswer: Decodable { let consent: AIConsent }

    func aiConsent() async throws -> AIConsent {
        let r: ConsentAnswer = try await send("GET", "/v1/me/ai-consent")
        return r.consent
    }

    /// The person allows it, for the version of the words they read.
    func allowAI(version: String) async throws -> AIConsent {
        let r: ConsentAnswer = try await send("POST", "/v1/me/ai-consent", body: ["version": version])
        return r.consent
    }

    /// They withdraw it: from then on, nothing they write reaches the model.
    func withdrawAI() async throws -> AIConsent {
        let r: ConsentAnswer = try await send("DELETE", "/v1/me/ai-consent")
        return r.consent
    }
}

extension Notification.Name {
    /// tafs cloud refused a model turn for want of the AI-data consent; the notification's object
    /// is the screen's words (`TafsCloud.AIConsent`).
    static let aiConsentRequired = Notification.Name(TafsCloud.aiConsentRequired)
}
