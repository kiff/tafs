import SwiftUI

/// v2's ask (docs/watch-app.md, "Step (iii)"), from Coach's home: the person's words from the system's input (dictation,
/// Scribble or the keyboard) or a Quick reply, then Check before sending. Nothing leaves the watch before Send, and then
/// it's v1's send: the same words, the same id on a retry, the same lock.
struct CoachAsk: View {
    @ObservedObject var model: CoachModel
    /// The ask's title: Coach's, or "Message {taf}" in a taf's chat.
    var title = Words.Coach.title
    /// Who answers: Coach, or the taf.
    var answerer = Words.Coach.title
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
        // Closing the ask keeps the conversation and any answer on its way (W4); the app put away forgets it (`leave`).
        .onDisappear { model.closeAsk() }
    }

    /// The words, by voice or by hand: watchOS's own input, which offers dictation first. There's no speech recognizer a
    /// watch app can drive, so the system's input is the microphone, and only its text leaves.
    private var ask: some View {
        List {
            TextField(Words.Watch.type, text: $input, prompt: Text(Words.Watch.typeHint))
                .onSubmit { take() }
            Text(Words.Watch.voiceTextOnly)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .listRowBackground(Color.clear)
            Section(Words.Watch.quickReply) {
                ForEach(CoachModel.quickReplies, id: \.self) { line in
                    Button(line) { model.review(line) }
                }
            }
        }
        .navigationTitle(title)
    }

    /// Check before sending: the words in full, scrolled with the crown, and under them, pinned at the foot in one row,
    /// Delete ✕ · Re-record ↻ · Send →, so Send is never below the words however long they are (W9).
    private var review: some View {
        List {
            Text(verbatim: model.draft)
                .frame(maxWidth: .infinity, alignment: .leading)
            if case .failed(let why) = model.state {
                // Not sent, or not sure it arrived: what waits for you, in amber (W7).
                Text(why).font(.footnote).foregroundStyle(WatchLook.held)
            }
            // The phone didn't answer: Coach's words may be kept on the watch and sent when it's back (row 8), under the
            // same id. Send stays, to try now.
            if model.offersKeep {
                Button {
                    model.keepForLater()
                    dismiss()
                } label: {
                    Label(Words.Watch.keepForLater, systemImage: "tray.and.arrow.down")
                }
            }
        }
        .navigationTitle(model.taf == nil ? Words.Watch.review : title)
        .toolbar {
            ToolbarItemGroup(placement: .bottomBar) {
                // Neither Delete nor Re-record acts while the words are in flight: they may already have reached Coach.
                Button(role: .destructive) { model.delete() } label: {
                    Label(Words.Chat.delete, systemImage: "xmark").labelStyle(.iconOnly)
                }
                .tint(WatchLook.danger)
                .disabled(model.state == .sending)
                // The system's input again; new words take these words' place, and nothing is sent until Send.
                TextFieldLink(prompt: Text(Words.Watch.typeHint)) {
                    Label(Words.Watch.rerecord, systemImage: "arrow.counterclockwise").labelStyle(.iconOnly)
                } onSubmit: { words in
                    model.review(words)
                }
                .disabled(model.state == .sending)
                Button {
                    Task { await model.sendReviewed() }
                } label: {
                    if model.state == .sending {
                        ProgressView().accessibilityLabel(Words.Common.sending)
                    } else {
                        Label(Words.Common.send, systemImage: "arrow.forward").labelStyle(.iconOnly)
                    }
                }
                .tint(WatchLook.send)
                .disabled(!model.canSend)
            }
        }
    }

    /// Sent (W2): the words that left, "Sent" the moment tafs cloud has them, then "Coach is answering" while Coach is at
    /// them, then Coach's answer as v1 draws it, short, with the rest on the phone.
    private var answer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if let asked = model.asked {
                    Text(verbatim: asked)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .multilineTextAlignment(.trailing)
                    Label(Words.Watch.sent, systemImage: "checkmark")
                        .font(.footnote)
                        .foregroundStyle(WatchLook.done)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                if model.state == .answering {
                    HStack(spacing: 6) {
                        ProgressView()
                            .frame(width: 18, height: 18)
                        Text(Words.Chat.tafIsAnswering(taf: answerer))
                            .font(.footnote)
                            .foregroundStyle(WatchLook.coach)
                    }
                    .accessibilityElement(children: .combine)
                }
                if case .answered(let answer) = model.state {
                    if let answer, !answer.text.isEmpty {
                        Text(answer.text)
                    }
                    if answer?.approveOnPhone == true {
                        Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                            .font(.footnote)
                            .foregroundStyle(WatchLook.held)
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
        .navigationTitle(answerer)
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
