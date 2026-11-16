import SwiftUI

/// Needs you on the wrist: the count, then each held call in the phone's words (the taf, what
/// it asks with its amount, why it was held, and when it lapses). Refuse is answered from here,
/// through the phone; approving is on the phone.
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
                    HeldCallRow(call: call, answer: model.answers[call.id]) {
                        Task { await model.refuse(call.id) }
                    }
                }
            }
            .refreshable { await model.load() }
        case .unreachable:
            WatchNotice(line: Words.Watch.phoneNotReachable) { await model.load() }
        case .signedOut:
            WatchNotice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .failed(let why):
            WatchNotice(line: why) { await model.load() }
        }
    }
}

/// One held call, as the phone words it: the taf, what it asks, why, and by when. Refuse, once
/// confirmed, and what came of it; never Approve.
struct HeldCallRow: View {
    let call: WatchNeedsYou.Call
    let answer: WatchAnswer?
    let refuse: () -> Void
    @State private var confirming = false

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
            switch answer {
            case nil:
                Button(Words.NeedsYou.refuse, role: .destructive) { confirming = true }
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
                // The watch never approves: an approval is signed on the phone, behind Face ID.
                Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                    .font(.footnote)
                    .padding(.top, 2)
            case .sending:
                ProgressView()
            case .done(let line):
                Text(line)
                    .font(.footnote.weight(.semibold))
            case .failed(let why):
                Text(why)
                    .font(.footnote)
                Button(Words.Common.tryAgain, role: .destructive) { refuse() }
                    .buttonStyle(.bordered)
            }
        }
        .confirmationDialog(Words.NeedsYou.refuse, isPresented: $confirming) {
            Button(Words.NeedsYou.refuse, role: .destructive) { refuse() }
        } message: {
            Text([call.taf, call.what].compactMap { $0 }.joined(separator: "\n"))
        }
    }
}

/// A line from the phone, or about it, with Try again.
struct WatchNotice: View {
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
