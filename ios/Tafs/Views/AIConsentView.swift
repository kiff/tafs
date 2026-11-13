import SwiftUI

/// The AI-data consent screen (docs/accounts.md, "Your data and the AI"): tafs cloud's words, as
/// sent, then Allow and Not now. Nothing the person writes reaches the model before Allow; Not now
/// leaves the app open, and a turn tafs cloud refuses brings the screen back. Every word is tafs
/// cloud's: the app has none of its own here.
struct AIConsentView: View {
    @EnvironmentObject private var model: AppModel
    let consent: TafsCloud.AIConsent

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "sparkles").scaledFont(28, weight: .semibold).foregroundColor(Theme.amberText)
                        .accessibilityHidden(true)
                    Text(consent.words.title)
                        .scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title2).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("consent.title")
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(consent.words.lines.enumerated()), id: \.offset) { _, line in
                            Text(line).scaledFont(16).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .accessibilityIdentifier("consent.lines")
                    if let why = model.aiConsentError {
                        Text(why).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    }
                    Button(consent.words.allow) { Task { await model.allowAI() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(model.aiConsentBusy)
                        .accessibilityIdentifier("consent.allow")
                    Button(consent.words.notNow) { model.notNowAI() }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(model.aiConsentBusy)
                        .accessibilityIdentifier("consent.not-now")
                }
                .padding(.horizontal, 24).padding(.vertical, 24)
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .screenBackground()
        }
    }
}

/// Account's row for the consent: tafs cloud's words for it once given, with Withdraw; not given,
/// its title, which opens the screen. Nothing from a tafs cloud without the route.
struct AIConsentRow: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let consent = model.aiConsent, consent.drawable {
            if consent.given {
                LabeledContent(consent.words.given, value: "")
                    .accessibilityIdentifier("settings.ai-consent")
                if !consent.words.withdraw.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button(consent.words.withdraw, role: .destructive) { Task { await model.withdrawAI() } }
                        .disabled(model.aiConsentBusy)
                        .accessibilityIdentifier("settings.ai-consent.withdraw")
                }
            } else {
                Button(consent.words.title) { model.consentRequired(consent) }
                    .foregroundColor(Theme.amberText)
                    .accessibilityIdentifier("settings.ai-consent")
            }
            if let why = model.aiConsentError, model.aiConsentAsked == nil {
                Text(why).scaledFont(13).foregroundColor(Theme.no)
            }
        }
    }
}
