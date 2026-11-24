import SwiftUI

/// Coach on the wrist: speak (the watch's own dictation, or Scribble), read your words, send;
/// then Coach's answer, short. A card or anything to approve is on the phone.
struct CoachScreen: View {
    @ObservedObject var model: CoachModel
    /// Hear: the answer read aloud on the watch.
    @StateObject private var hear = WatchHear()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    // The system's text input: dictation or Scribble. The words show here before Send.
                    TextField(Words.Coach.ask, text: $model.draft, axis: .vertical)
                    Button(model.state == .sending ? Words.Common.sending : Words.Common.send) {
                        Task { await model.send() }
                    }
                    .disabled(!model.canSend)
                    answer
                }
            }
            .navigationTitle(Words.Coach.title)
        }
    }

    @ViewBuilder private var answer: some View {
        switch model.state {
        case .writing, .sending, .sent, .answering, .deleted:
            EmptyView()
        case .answered(let answer):
            if let answer, !answer.text.isEmpty {
                Text(answer.text)
                HearButton(text: answer.text, hear: hear)
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
        case .failed(let why):
            Text(why)
                .font(.footnote)
        }
    }
}
