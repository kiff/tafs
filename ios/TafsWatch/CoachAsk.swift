import SwiftUI

/// v2's ask (docs/watch-app.md, "Step (iii)"), from Coach's home: the person's words from the system's input (dictation,
/// Scribble or the keyboard) or a Quick reply, then Check before sending. Nothing leaves the watch before Send, and then
/// it's v1's send: the same words, the same id on a retry, the same lock.
struct CoachAsk: View {
    @ObservedObject var model: CoachModel
    @Environment(\.dismiss) private var dismiss
    /// What the system's input hands back, before it's checked.
    @State private var input = ""

    var body: some View {
        NavigationStack {
            Group {
                if model.state == .deleted {
                    deleted
                } else if model.reviewing {
                    review
                } else if case .writing = model.state {
                    ask
                } else {
                    answer
                }
            }
        }
        .onDisappear { model.leave() }
    }

    /// The words, by voice or by hand: watchOS's own input, which offers dictation first. There's no speech recognizer a
    /// watch app can drive, so the system's input is the microphone, and only its text leaves.
    private var ask: some View {
        List {
            TextField(Words.Watch.type, text: $input, prompt: Text(Words.Watch.typeHint))
                .onSubmit { take() }
            Text(Words.Watch.askTextOnly)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
            Section(Words.Watch.quickReply) {
                ForEach(CoachModel.quickReplies, id: \.self) { line in
                    Button(line) { model.review(line) }
                }
            }
        }
        .navigationTitle(Words.Coach.title)
    }

    /// Check before sending: the words in full, scrolled with the crown; Send, Re-record and Delete.
    private var review: some View {
        List {
            Text(verbatim: model.draft)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                Task { await model.sendReviewed() }
            } label: {
                Text(model.state == .sending ? Words.Common.sending : Words.Common.send)
                    .frame(maxWidth: .infinity)
            }
            .disabled(!model.canSend)
            if case .failed(let why) = model.state {
                Text(why).font(.footnote)
            }
            // The system's input again; new words take these words' place, and nothing is sent until Send. Neither it nor
            // Delete acts while the words are in flight: they may already have reached Coach.
            TextField(Words.Watch.rerecord, text: $input)
                .onSubmit { take() }
                .disabled(model.state == .sending)
            Button(Words.Chat.delete, role: .destructive) { model.delete() }
                .disabled(model.state == .sending)
        }
        .navigationTitle(Words.Watch.review)
    }

    /// Sent: Coach's answer as v1 draws it, short, with the rest on the phone.
    private var answer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if case .answered(let answer) = model.state {
                    if let answer, !answer.text.isEmpty {
                        Text(answer.text)
                    }
                    if answer?.approveOnPhone == true {
                        Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                            .font(.footnote)
                    }
                    if answer == nil || answer?.more == true {
                        Text(Words.Watch.moreOnPhone)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Words.Coach.title)
    }

    /// "Deleted. Nothing was sent.", which closes by itself.
    private var deleted: some View {
        Text(Words.Watch.deletedNothingSent)
            .multilineTextAlignment(.center)
            .task {
                try? await Task.sleep(for: .seconds(2))
                dismiss()
            }
    }

    private func take() {
        model.review(input)
        input = ""
    }
}
