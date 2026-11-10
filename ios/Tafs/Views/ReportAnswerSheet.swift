import SwiftUI

/// An answer to report, from the chat it's in: a taf's or a trial's by its id, Coach's when nil.
struct ReportedAnswer: Identifiable {
    let message: TafsCloud.ChatMessage
    let chatID: UUID?
    var id: String { message.id }
}

extension View {
    /// The answer's menu item, Report this answer, on an answer the person can report
    /// (kiff/tafs#421, Google Play's rule for content an AI generates); nothing on others.
    func reportable(_ message: TafsCloud.ChatMessage, in chatID: UUID?, model: AppModel, reporting: Binding<ReportedAnswer?>) -> some View {
        contextMenu {
            if model.canReport(message) {
                Button {
                    reporting.wrappedValue = ReportedAnswer(message: message, chatID: chatID)
                } label: {
                    Label(Words.Report.menu, systemImage: "flag")
                }
            }
        }
    }
}

/// Reports an answer Coach or a taf wrote: a reason, an optional note, and Send. Sent, it
/// says a person at KIFF will read it; not sent, it says why and stays open to send again.
struct ReportAnswerSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let answer: ReportedAnswer
    @State private var reason: TafsCloud.ReportReason?
    @State private var note = ""
    @State private var sending = false
    @State private var failed: String?
    @State private var done: AppModel.ReportOutcome?

    /// The longest note tafs cloud takes.
    static let noteLimit = 1000

    var body: some View {
        NavStack {
            Group {
                if let done { thanks(done) } else { form }
            }
            .hiddenScrollBackground()
            .screenBackground()
            .navigationTitle(Words.Report.menu)
            .inlineTitle()
            .toolbar {
                if done == nil {
                    ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(Words.Common.send) { send() }
                            .accessibilityIdentifier("report-send")
                            .disabled(reason == nil || sending || note.count > Self.noteLimit)
                    }
                }
            }
        }
    }

    private var form: some View {
        Form {
            Section {
                ForEach(TafsCloud.ReportReason.allCases) { r in
                    Button {
                        reason = r
                    } label: {
                        HStack {
                            Text(r.title).foregroundColor(Theme.ink)
                            Spacer()
                            if reason == r { Image(systemName: "checkmark").foregroundColor(Theme.amberText) }
                        }
                    }
                    .accessibilityIdentifier("report-reason-\(r.rawValue)")
                    .accessibilityAddTraits(reason == r ? .isSelected : [])
                }
            } header: {
                Text(Words.Report.whatWrong)
            }
            Section {
                ChatField(title: Words.Report.noteHint, text: $note)
                    .accessibilityIdentifier("report-note")
                if note.count > Self.noteLimit {
                    Text(Words.Report.noteMostCharacters).scaledFont(13).foregroundColor(Theme.no)
                }
            } footer: {
                Text(Words.Report.personKiffReadsYourReport)
            }
            if let failed {
                Text(Words.Report.notSentWhy(failed: "\(failed)"))
                    .scaledFont(14).foregroundColor(Theme.no)
                    .accessibilityIdentifier("report-failed")
            }
        }
    }

    private func thanks(_ outcome: AppModel.ReportOutcome) -> some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "checkmark.circle").scaledFont(40).foregroundColor(Theme.amberText)
            Text(outcome == .already ? "You've already reported this answer." : "Thanks. Someone at KIFF will read your report.")
                .scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("report-done")
            Spacer()
            Button(Words.Common.done) { dismiss() }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(24)
    }

    private func send() {
        guard let reason, !sending else { return }
        sending = true
        failed = nil
        Task {
            let outcome = await model.reportAnswer(answer.message, in: answer.chatID, reason: reason, note: note)
            sending = false
            if case .failed(let why) = outcome { failed = why } else { done = outcome }
        }
    }
}
