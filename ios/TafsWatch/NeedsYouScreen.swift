import SwiftUI

/// Needs you on the wrist: the count, then each held call in the phone's words (the taf, what
/// it asks with its amount, why it was held, and when it lapses). Refuse is answered from here,
/// through the phone; approving is on the phone, which Approve on your phone opens at the call.
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

    /// Nothing waiting: the calm check (`AllClearArt`) above the phone's words. Only once the phone has
    /// answered, so it never stands in for loading.
    static func allClear(_ count: Int) -> Bool { count == 0 }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .loaded(let needsYou) where model.brief != nil:
            if let brief = model.brief {
                BriefList(brief: brief, model: model, needsYou: needsYou)
            }
        case .loaded(let read):
            // A held call past its end on the watch's own clock goes, and isn't counted.
            let needsYou = NeedsYouModel.shown(read, now: Date())
            List {
                Section {
                    if Self.allClear(needsYou.count) {
                        AllClearArt().listRowBackground(Color.clear)
                    }
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
                    HeldCallRow(call: call, answer: model.answers[call.id], approve: { Task { await model.approveOnPhone(call.id) } }) {
                        Task { await model.refuse(call.id) }
                    }
                }
            }
            .refreshable { await model.load() }
        case .unreachable(let why):
            WatchNotice(line: why.waiting) { await model.load() }
        case .signedOut:
            WatchNotice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .failed(let why):
            WatchNotice(line: why) { await model.load() }
        }
    }
}

/// One held call, as the phone words it: the taf, what it asks, why, and by when. Refuse, once
/// confirmed, and what came of it; never Approve: Approve on your phone opens the call on the phone.
struct HeldCallRow: View {
    let call: WatchNeedsYou.Call
    let answer: WatchAnswer?
    /// Approve on your phone (`NeedsYouModel.approveOnPhone`): the phone opens this call, to approve behind Face ID there.
    var approve: () -> Void = {}
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
                // The watch never approves: the phone opens this call, and an approval is signed there, behind Face ID.
                Button(action: approve) {
                    Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                        .font(.footnote)
                }
                .buttonStyle(.plain)
                .foregroundStyle(WatchLook.coach)
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
                ProgressView()
                Text(line)
                    .multilineTextAlignment(.center)
            }
        }
        // No Try again (docs/watch-app.md, "Linking tafs"): the watch reads again by itself, sooner at first, then every
        // `WatchRetry.most` seconds while the screen is shown.
        .task {
            await WatchRetry.wait()
            await retry()
        }
    }
}

/// How long a screen waits before it reads again by itself: 3 s, then twice as long each time it's shown again within a
/// minute, at most `most`. A read that answers starts over.
@MainActor
enum WatchRetry {
    static let first: TimeInterval = 3
    static let most: TimeInterval = 30
    private static var next = first
    private static var last = Date.distantPast

    static func delay(now: Date = Date()) -> TimeInterval {
        if now.timeIntervalSince(last) > 60 { next = first }
        let wait = next
        next = min(next * 2, most)
        last = now
        return wait
    }

    static func wait() async {
        try? await Task.sleep(nanoseconds: UInt64(delay() * 1_000_000_000))
    }
}

/// Your brief on the wrist, in Needs you's place (behind `NeedsYouModel.briefOnWatch`): tafs cloud's title and when,
/// each decision with its taf, deadline and why it can't wait, answered on the phone. Refuse only where Needs
/// you's own read lists the held call. Then Done's heading and first cases, the rest, and Needs you beyond it.
struct BriefList: View {
    let brief: WatchBrief
    @ObservedObject var model: NeedsYouModel
    let needsYou: WatchNeedsYou

    var body: some View {
        List {
            Section {
                Text(brief.title).font(.headline)
                if let when = brief.when { Text(when).font(.footnote).foregroundStyle(.secondary) }
            }
            ForEach(brief.decisions) { decision in
                VStack(alignment: .leading, spacing: 2) {
                    if decision.urgent, let urgent = brief.urgent {
                        Text(urgent).font(.footnote.weight(.semibold)).foregroundStyle(.orange)
                    }
                    if let taf = decision.taf { Text(taf).font(.footnote).foregroundStyle(.secondary) }
                    Text(decision.question)
                    if let deadline = decision.deadline { Text(deadline).font(.footnote).foregroundStyle(.secondary) }
                    if let cantWait = decision.cantWait { Text(cantWait).font(.footnote) }
                    if model.refusable(decision), let hold = decision.holdID,
                       let call = needsYou.calls.first(where: { $0.id == hold }) {
                        // The same row as Needs you's: Refuse, confirmed, behind the passcode; never Approve.
                        HeldCallRow(call: call, answer: model.answers[hold], approve: { Task { await model.approveOnPhone(hold) } }) {
                            Task { await model.refuse(hold) }
                        }
                    } else {
                        // Decisions are answered on the phone; the watch never approves.
                        Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                            .font(.footnote)
                            .padding(.top, 2)
                    }
                }
            }
            if let more = brief.more { Text(more).font(.footnote) }
            if let done = brief.done {
                Section(done) {
                    ForEach(brief.doneCases, id: \.self) { Text($0).font(.footnote) }
                }
            }
            if let rest = brief.rest { Text(rest).font(.footnote).foregroundStyle(.secondary) }
            if model.beyondBrief > 0 {
                Text(Words.NeedsYou.count(count: model.beyondBrief)).font(.footnote)
            }
        }
        .refreshable { await model.load() }
    }
}
