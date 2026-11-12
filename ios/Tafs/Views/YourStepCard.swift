import SwiftUI

/// A step a taf handed its owner (docs/api.md › Your step): what, why, the vendor's page when tafs
/// cloud vouched for its link, and "Done" or "Skip it", signed and sent to KIFF. Every word is tafs
/// cloud's; the app adds none. A link it didn't vouch for is no button, only the words.
struct YourStepCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL
    let step: TafsCloud.YourStep
    /// On the taf's own page: the card doesn't name it, and its work is read again once answered.
    var onTaf: UUID? = nil

    var body: some View {
        Panel(stroke: Theme.amber.opacity(0.4)) {
            VStack(alignment: .leading, spacing: 10) {
                if !step.words.title.isEmpty { Eyebrow(text: step.words.title) }
                if onTaf == nil, let name = step.taf?.name, !name.isEmpty {
                    Text(name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                }
                Text(step.what).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if !step.why.isEmpty {
                    Text(step.why).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                if let link = step.openLink {
                    VStack(alignment: .leading, spacing: 4) {
                        Button { openURL(link.url) } label: { Label(link.words, systemImage: "arrow.up.right.square") }
                            .buttonStyle(OutlineButtonStyle())
                        // Where it really goes, as tafs cloud read the link.
                        Text(link.host).scaledFont(12, design: .monospaced).foregroundColor(Theme.dim)
                    }
                }
                if model.canAnswerSteps {
                    HStack(spacing: 8) {
                        Button(step.words.done) { Task { await model.answerYourStep(step, done: true, tafID: onTaf) } }
                            .buttonStyle(PrimaryButtonStyle())
                        Button(step.words.skip) { Task { await model.answerYourStep(step, done: false, tafID: onTaf) } }
                            .buttonStyle(OutlineButtonStyle())
                    }
                    .disabled(model.answeringSteps.contains(step.handoff))
                }
            }
        }
        .accessibilityIdentifier("yourStep.\(step.handoff)")
    }
}
