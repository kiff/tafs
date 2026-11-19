import SwiftUI

/// v2's taf page (docs/watch-app.md, "Step (v)"), as Wear OS's: one taf as the phone reads it (`taf`, by its id), in memory
/// only. Halt is never on the main screens: it's in the taf's settings, asked once more, and sent only from a watch with a
/// passcode. Resuming asks for Face ID, so it's the phone's.
@MainActor
final class TafPageModel: ObservableObject {
    enum State: Equatable {
        /// Before the phone answers: the taf's name from Your tafs, and "Asking your phone…".
        case asking
        case loaded(WatchTafPage)
        case unreachable
        case signedOut
        /// A phone from before the read: "Update tafs on your phone".
        case updatePhone
        case failed(String)
    }

    /// The taf as Your tafs listed it: its id is what's read and halted, its name what shows before the read.
    let taf: WatchTaf
    @Published private(set) var state: State = .asking
    /// Halt this taf, sent from its settings, and what the phone said came of it.
    @Published private(set) var halt: WatchAnswer?
    private let phone: PhoneAsking
    private let clock: () -> Date
    private let lock: WristLock
    /// The page is on screen: an answer that lands after it's closed is never drawn.
    private var open = true

    init(taf: WatchTaf, phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock()) {
        self.taf = taf
        self.phone = phone
        self.clock = clock
        self.lock = lock
    }

    /// The name on the page: the phone's once read, Your tafs' before.
    var name: String {
        if case .loaded(let page) = state { return page.name }
        return taf.name
    }

    /// Reads the taf's page from the phone.
    func load() async {
        open = true
        if case .loaded = state {} else { state = .asking }
        let reply = await phone.ask(WatchRequest(.taf, made: clock(), taf: taf.id))
        guard open else { return }
        guard let reply else { state = .unreachable; return }
        switch reply.outcome {
        case .ok:
            // Only this taf's page is drawn here.
            if let page = reply.tafPage, page.id == taf.id {
                state = .loaded(page)
            } else {
                state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
            }
        case .signedOut:
            state = .signedOut
        case .failed:
            state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
        case .notYet:
            state = .updatePhone
        case .refused, .stale, .answered:
            state = .unreachable
        }
    }

    /// The page left the screen.
    func close() {
        open = false
    }

    /// Halt this taf, from its settings once confirmed: v1's Halt for it, only from a watch with a passcode. "Halting {taf}…"
    /// until the phone has tafs cloud's word, then the page is read again.
    func haltThis() async {
        guard halt?.isSending != true else { return }
        guard lock.isSecure else { halt = .failed(Words.Watch.needsLock); return }
        halt = .sending
        let reply = await phone.ask(WatchRequest(.halt, made: clock(), taf: taf.id))
        if reply?.outcome == .signedOut { state = .signedOut; halt = nil; return }
        halt = WatchAnswer.from(reply)
        if reply?.outcome == .ok, open { await load() }
    }
}

extension TafsModel {
    /// A taf's page, read through the same phone as Your tafs.
    func page(_ taf: WatchTaf) -> TafPageModel {
        TafPageModel(taf: taf, phone: phone, clock: clock, lock: lock)
    }
}

/// A taf's page: its name and state (Halted in red), What it does, Its Card, Waiting for you (which goes to the Inbox), and,
/// halted, that resuming is on the phone. Then Its settings.
struct TafPageScreen: View {
    @StateObject private var model: TafPageModel
    let openInbox: () -> Void

    init(model: @autoclosure @escaping () -> TafPageModel, openInbox: @escaping () -> Void) {
        _model = StateObject(wrappedValue: model())
        self.openInbox = openInbox
    }

    var body: some View {
        content
            .navigationTitle(model.name)
            .task { await model.load() }
            .onDisappear { model.close() }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .asking:
            List {
                Text(model.name).font(.headline)
                Text(Words.Watch.askingPhone).font(.footnote).foregroundStyle(.secondary)
            }
        case .loaded(let page):
            List {
                Section {
                    Text(page.name).font(.headline)
                    Text(page.state)
                        .font(.footnote)
                        .foregroundStyle(page.halted ? WatchLook.danger : Color.secondary)
                }
                Section(Words.Watch.whatItDoes) {
                    Text(page.job).font(.footnote)
                }
                if !page.cards.isEmpty {
                    Section(Words.Card.itsCard) {
                        ForEach(Array(page.cards.enumerated()), id: \.offset) { _, card in
                            TafPageCard(card: card)
                        }
                    }
                }
                Section(Words.Chat.waitingForYou) {
                    if page.waiting > 0 {
                        Button(Words.Watch.needsYouCount(count: page.waiting), action: openInbox)
                    } else {
                        Text(Words.Watch.nothingWaits).font(.footnote)
                    }
                }
                if page.halted {
                    Section {
                        Text(Words.Halt.Explain.halted).font(.footnote)
                        Label(Words.Watch.resumeOnPhone, systemImage: "iphone").font(.footnote)
                    }
                }
                NavigationLink(Words.Watch.itsSettings) {
                    TafSettingsScreen(model: model)
                }
            }
        case .unreachable:
            WatchNotice(line: Words.Watch.phoneNotReachable) { await model.load() }
        case .signedOut:
            WatchNotice(line: Words.Watch.signInOnPhone) { await model.load() }
        case .updatePhone:
            WatchNotice(line: Words.Watch.updatePhone) { await model.load() }
        case .failed(let why):
            WatchNotice(line: why) { await model.load() }
        }
    }
}

/// One Card as the phone's Card tab draws it: its title, its face's figure and line, and each rule on a line.
private struct TafPageCard: View {
    let card: WatchTafPage.Card

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(card.title).font(.headline)
            if let figure = card.figure { Text(figure).font(.title3) }
            if let words = card.words { Text(words).font(.footnote) }
            ForEach(Array(card.rules.enumerated()), id: \.offset) { _, rule in
                Text(rule).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

/// A taf's settings: "{taf} · settings", and apart at the end Halt this taf, in red, asked once more. Halted, it offers
/// Resume on your phone instead.
struct TafSettingsScreen: View {
    @ObservedObject var model: TafPageModel
    @State private var confirming = false

    private var halted: Bool {
        if case .loaded(let page) = model.state { return page.halted }
        return model.taf.halted
    }

    var body: some View {
        List {
            Text(Words.Watch.settingsOf(taf: model.name)).font(.headline)
            Section {
                switch model.halt {
                case .sending?:
                    Text(Words.Watch.halting(taf: model.name)).font(.footnote)
                case .done(let line)?:
                    Text(line).font(.footnote.weight(.semibold))
                case .failed(let why)?:
                    Text(why).font(.footnote)
                    Button(Words.Common.tryAgain, role: .destructive) { Task { await model.haltThis() } }
                case nil:
                    if halted {
                        Label(Words.Watch.resumeOnPhone, systemImage: "iphone").font(.footnote)
                    } else {
                        Button(Words.Watch.haltThis, role: .destructive) { confirming = true }
                    }
                }
            }
        }
        .navigationTitle(model.name)
        .confirmationDialog(Words.Watch.haltThis, isPresented: $confirming) {
            Button(Words.Halt.now, role: .destructive) { Task { await model.haltThis() } }
        } message: {
            Text(Words.Halt.Explain.runningOne)
        }
    }
}
