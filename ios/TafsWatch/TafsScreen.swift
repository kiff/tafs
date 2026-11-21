import SwiftUI

/// Your tafs on the wrist: each in one line, with Halt, and Halt all. No Face ID: halting
/// only takes authority away. Resuming stays on the phone, behind Face ID.
struct TafsScreen: View {
    @ObservedObject var model: TafsModel
    /// v1 offers Halt on each taf and Halt all; v2's Tafs page doesn't: halting is in the menu, and with step (v) in a
    /// taf's settings (docs/watch-app.md, "Version 2").
    var offersHalt = true
    /// v2 (step v): each taf opens its page, and its Waiting for you goes to the Inbox.
    var opensPages = false
    var openInbox: () -> Void = {}
    @State private var confirmingAll = false

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(Words.Home.yourTafs)
                .toolbar {
                    if model.updating {
                        // The kept read, or the last one through a short drop, while the phone is asked (row 44, D).
                        ToolbarItem(placement: .topBarTrailing) { UpdatingDot() }
                    }
                }
        }
        .task { await model.load() }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .loaded(let tafs):
            List {
                ForEach(tafs) { taf in
                    if opensPages && WatchTafChat.on {
                        // 1A: a taf opens its chat, with its page one swipe below.
                        NavigationLink {
                            TafChatScreen(taf: taf, chat: model.chat(taf), page: { model.page(taf) }, openInbox: openInbox)
                        } label: {
                            TafRow(taf: taf, answer: nil, offersHalt: false) {}
                        }
                    } else if opensPages {
                        NavigationLink {
                            TafPageScreen(model: model.page(taf), openInbox: openInbox)
                        } label: {
                            TafRow(taf: taf, answer: nil, offersHalt: false) {}
                        }
                    } else {
                        TafRow(taf: taf, answer: model.halts[taf.id], offersHalt: offersHalt) {
                            Task { await model.halt(taf.id) }
                        }
                    }
                }
                if opensPages {
                    // v2 (step vi): today's calendar, at the foot of Your tafs.
                    Section {
                        NavigationLink(Words.Watch.calendarToday) {
                            CalendarDayScreen(model: model.calendarDay())
                        }
                    }
                }
                if offersHalt && !tafs.isEmpty {
                    Section {
                        switch model.halts[TafsModel.all] {
                        case .sending?:
                            ProgressView()
                        case .done(let line)?:
                            Text(line).font(.footnote.weight(.semibold))
                        case .failed(let why)?:
                            Text(why).font(.footnote)
                            haltAllButton
                        case nil:
                            haltAllButton
                        }
                    } footer: {
                        Text(Words.Halt.Explain.runningAll)
                    }
                }
            }
            .refreshable { await model.load() }
            .confirmationDialog(Words.HaltAll.title, isPresented: $confirmingAll) {
                Button(Words.Halt.allNow, role: .destructive) { Task { await model.halt(nil) } }
            } message: {
                Text(Words.Halt.Explain.runningAll)
            }
        case .unreachable:
            WatchNotice(line: Words.Watch.phoneNotReachable) { await model.load() }
        case .signedOut:
            WatchNotice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .failed(let why):
            WatchNotice(line: why) { await model.load() }
        }
    }

    private var haltAllButton: some View {
        Button(Words.Halt.allNow, role: .destructive) { confirmingAll = true }
    }
}

/// A taf, its state in one line, and Halt once confirmed.
private struct TafRow: View {
    let taf: WatchTaf
    let answer: WatchAnswer?
    var offersHalt = true
    let halt: () -> Void
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(taf.name)
                .font(.headline)
            if let line = taf.line {
                Text(line)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            switch answer {
            case .sending?:
                ProgressView()
            case .done(let line)?:
                Text(line).font(.footnote.weight(.semibold))
            case .failed(let why)?:
                Text(why).font(.footnote)
                Button(Words.Common.tryAgain, role: .destructive) { halt() }
                    .buttonStyle(.bordered)
            case nil:
                if !taf.halted && offersHalt {
                    Button(Words.Halt.now, role: .destructive) { confirming = true }
                        .buttonStyle(.bordered)
                        .padding(.top, 4)
                }
            }
        }
        .confirmationDialog(taf.name, isPresented: $confirming) {
            Button(Words.Halt.now, role: .destructive) { halt() }
        } message: {
            Text(Words.Halt.Explain.runningOne)
        }
    }
}

/// The small dot over a page drawn from what the watch kept, or its last read, while the phone is asked: "Loading" to
/// VoiceOver.
struct UpdatingDot: View {
    var body: some View {
        Circle()
            .fill(.secondary)
            .frame(width: 6, height: 6)
            .accessibilityLabel(Words.Common.loading)
    }
}
