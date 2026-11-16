import SwiftUI

/// Needs you on the wrist: the count, then each held call in the phone's words (the taf, what
/// it asks with its amount, why it was held, and when it lapses). Answering is on the phone.
/// Until the phone answers, it says it's loading, never that nothing is waiting.
struct NeedsYouScreen: View {
    @ObservedObject var model: NeedsYouModel

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(Words.NeedsYou.title)
        }
        .task { await model.load() }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .loaded(let needsYou):
            List {
                Section {
                    Text(needsYou.count == 0 ? Words.NeedsYou.nothingWaiting : Words.NeedsYou.count(count: needsYou.count))
                        .font(.headline)
                    if needsYou.count > needsYou.calls.count {
                        Text(Words.Watch.moreOnPhone)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text(Words.Watch.asOf(time: needsYou.at.formatted(date: .omitted, time: .shortened)))
                }
                ForEach(needsYou.calls) { call in
                    HeldCallRow(call: call)
                }
            }
            .refreshable { await model.load() }
        case .unreachable:
            Notice(line: Words.Watch.phoneNotReachable) { await model.load() }
        case .signedOut:
            Notice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .failed(let why):
            Notice(line: why) { await model.load() }
        }
    }
}

/// One held call, as the phone words it: the taf, what it asks, why, and by when.
struct HeldCallRow: View {
    let call: WatchNeedsYou.Call

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(call.taf ?? Words.NeedsYou.aTaf)
                .font(.headline)
            Text(call.what)
            Text(call.reason)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(Words.NeedsYou.answerBy(time: call.answerBy))
                .font(.footnote)
                .foregroundStyle(.secondary)
            // The watch never approves: an approval is signed on the phone, behind Face ID.
            Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                .font(.footnote)
                .padding(.top, 2)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A line from the phone, or about it, with Try again.
private struct Notice: View {
    let line: String
    let retry: () async -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text(line)
                    .multilineTextAlignment(.center)
                Button(Words.Common.tryAgain) { Task { await retry() } }
            }
        }
    }
}
