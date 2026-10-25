import SwiftUI

/// Before a trial starts (kiff/tafs#173): what the version changes, the Card it runs under,
/// and what trying it means for the live taf. One Face ID starts it.
struct TrialStartSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let version: TafsCloud.Version
    let tafID: UUID
    /// Started: its chat, which opens next (kiff/tafs#247).
    var onStarted: (UUID) -> Void = { _ in }
    @State private var draft: KIFFClient.AgentDraft?
    @State private var loaded = false

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Try version \(version.n)\(version.note.map { ": \($0)" } ?? "")")
                        .scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Panel {
                        Eyebrow(text: "While you try it")
                        line("You chat with version \(version.n) in its own window. What it does is real: its calls go through KIFF under the Card below.")
                        line("The live version keeps working: its schedule, Card and budget don't change.")
                        line("The trial's Card counts from zero, so on a day your taf can spend its live Card's limits plus this one's.")
                        line("It ends when you install it or go back, or after 7 days unused. Its chat is then deleted, and Versions keeps one line about it.")
                    }
                    if let draft, draft.cards != nil {
                        Eyebrow(text: "Its Card")
                        DraftCardsView(draft: draft)
                    } else if loaded {
                        Text(model.trialErrors[tafID] ?? "This trial's Card couldn't be read.")
                            .scaledFont(13).foregroundColor(Theme.no)
                    } else {
                        ProgressView().tint(Theme.amber)
                    }
                    if let error = model.trialErrors[tafID], draft != nil {
                        Text(error).scaledFont(13).foregroundColor(Theme.no)
                    }
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle("Try it")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(model.trialBusy.contains(tafID) ? "Starting…" : "Start trying") {
                        guard let shown = draft?.version else { return }
                        Task {
                            // Started: its chat opens at once (kiff/tafs#247); later it opens from
                            // Versions, the taf's header and the menu.
                            if let chat = await model.startTrying(version, of: tafID, shownDraft: shown) {
                                dismiss()
                                onStarted(chat)
                            } else if let changed = model.changedVersionCards[tafID], changed.id == draft?.id {
                                // Changed since it was shown: the new terms, to read again.
                                draft = changed
                            }
                        }
                    }
                    .disabled(draft?.cards == nil || model.trialBusy.contains(tafID))
                }
            }
        }
        .task {
            model.trialErrors[tafID] = nil
            draft = await model.trialCard(version, of: tafID)
            loaded = true
        }
    }

    private func line(_ text: String) -> some View {
        Text(text).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
    }
}

/// A trial, open like a taf (kiff/tafs#173): the version's chat under a bar that says it
/// isn't live, with Go back and Install. The chat is the taf's chat view, on the trial's
/// own chat: one universal chat.
struct TrialScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let chatID: UUID
    let tafID: UUID
    @State private var confirmBack = false
    @State private var confirmInstall = false

    var body: some View {
        VStack(spacing: 0) {
            if let trial = model.openTrials[tafID], let taf = model.taf(tafID) {
                bar(trial, name: taf.name)
                // Before its first message, what this trial is and what to try (kiff/tafs#247).
                if model.chatItems(chatID).isEmpty { guide(trial, name: taf.name) }
                TafChatView(tafID: chatID)
                    .confirmationDialog("Go back to the live version?", isPresented: $confirmBack, titleVisibility: .visible) {
                        Button("Go back", role: .destructive) { Task { if await model.goBackFromTrial(tafID) { dismiss() } } }
                    } message: {
                        Text("Version \(trial.n)'s trial Card stops at once, and this chat is deleted. \(taf.name) keeps working as it is.")
                    }
                    .confirmationDialog("Install version \(trial.n)?", isPresented: $confirmInstall, titleVisibility: .visible) {
                        Button("Install with Face ID") { Task { if await model.installTrial(tafID) { dismiss() } } }
                    } message: {
                        Text("Version \(trial.n) becomes the live version, with its Card. This chat is deleted.")
                    }
            } else {
                Text("This trial has ended.").foregroundColor(Theme.dim).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .screenBackground()
        .navigationTitle(model.taf(chatID)?.name ?? "Trial")
        .inlineTitle()
        .task(id: tafID) { await model.loadTrial(tafID) }
    }

    /// What the trial is, in tafs cloud's words: what the version changes, the task to try
    /// it with, that the live taf keeps working, and when the trial ends.
    private func guide(_ trial: TafsCloud.OpenTrial, name: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let changed = trial.changed {
                Text("What's new: \(changed)").scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            if let tryWith = trial.`try` {
                Text("Try it with: \(tryWith)").scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Give it a task that uses the change, as you would the live \(name).")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            Text(["\(name) keeps working as it is. What this version does is real, under its own Card.",
                  trial.ends.map { "The trial ends \($0.shown(date: .abbreviated, time: .omitted)) if you don't use it." },
                  "Install makes it the live version; Go back ends the trial."].compactMap { $0 }.joined(separator: " "))
                .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bar(_ trial: TafsCloud.OpenTrial, name: String) -> some View {
        let busy = model.trialBusy.contains(tafID)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text("Trying version \(trial.n)").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                Text("not live").scaledFont(11, weight: .bold).foregroundColor(.white)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Theme.amber).clipShape(Capsule())
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                Button("Go back") { confirmBack = true }
                    .buttonStyle(OutlineButtonStyle(color: Theme.no))
                    .disabled(busy)
                Button(busy ? "Working…" : "Install") { confirmInstall = true }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(busy)
            }
            if let error = model.trialErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Theme.amber.opacity(0.08))
    }
}
