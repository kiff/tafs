import SwiftUI

/// Watch v2 (docs/watch-app.md, "Version 2"): on since 19 Nov, once every step was in and the privacy text for Sign out
/// on this watch was live in 8 languages. Built one step at a time behind this switch, as Wear OS's `WATCH_V2`; turned
/// off, a watch shows v1 whole.
enum WatchV2 {
    static let on = true
}

/// v2's pages, one under the other, moved between with the crown or a swipe: Coach is home, since talking to Coach is
/// what the wrist is for.
enum WatchPage: String, CaseIterable, Identifiable {
    case coach, inbox, tafs, brief, account

    var id: String { rawValue }

    /// The pages in order; Brief only with Your brief on the watch (`NeedsYouModel.briefOnWatch`), which waits for the
    /// owner's yes.
    static func pages(briefOn: Bool = NeedsYouModel.briefOnWatch) -> [WatchPage] {
        allCases.filter { $0 != .brief || briefOn }
    }

    /// Each page's name, in the menu and on its header: the phone's own word where the phone has the page.
    var title: String {
        switch self {
        case .coach: return Words.Coach.title
        case .inbox: return Words.Watch.Page.inbox
        case .tafs: return Words.Home.title
        case .brief: return Words.Watch.Page.brief
        case .account: return Words.Settings.title
        }
    }

    /// What waits in the Inbox, for the count on Coach's home: the calls Needs you holds, as v1 reads them. The inbox
    /// kept on the watch, with read and dismissed, is step (iv). Nil, so no line, until the phone has answered and while
    /// nothing waits: the line shows only above none, as on Wear OS and in the mockup.
    static func unread(_ state: NeedsYouModel.State) -> Int? {
        if case .loaded(let page) = state, !page.calls.isEmpty { return page.calls.count }
        return nil
    }

    /// The count on Coach's home with the Inbox kept on the watch (step iv): its unread lines, and no line with none. With no
    /// passcode nothing is kept, and the count is the calls the phone read now, as above.
    static func unread(_ state: NeedsYouModel.State, inboxKept: Bool, unreadLines: Int) -> Int? {
        guard inboxKept else { return unread(state) }
        return unreadLines > 0 ? unreadLines : nil
    }

    /// Whether the menu offers Halt: only while a taf the phone listed still runs.
    static func offersHalt(_ state: TafsModel.State) -> Bool {
        if case .loaded(let tafs) = state { return tafs.contains { !$0.halted } }
        return false
    }
}

/// The v2 watch: the pages, with the menu over them when it's open. Ask and a held call take the whole face, as in v1.
struct WatchPagesView: View {
    @ObservedObject var needsYou: NeedsYouModel
    @ObservedObject var tafs: TafsModel
    @ObservedObject var coach: CoachModel
    @ObservedObject var inbox: InboxModel
    /// Account's Sign out on this watch, once confirmed.
    var signOut: () -> Void = {}
    @State private var page: WatchPage = .coach
    @State private var menu = false

    var body: some View {
        TabView(selection: $page) {
            ForEach(WatchPage.pages()) { shown in
                pageView(shown).tag(shown)
            }
        }
        .tabViewStyle(.verticalPage)
        // What Needs you reads, and each answer Coach gives this watch, are lines of the Inbox (step iv).
        .onChange(of: needsYou.state, initial: true) { _, state in
            inbox.heard(state)
            // Once the phone has answered, the lines read and dismissed on the person's other watches (row 21).
            if case .loaded = state { Task { await inbox.sync() } }
        }
        .onAppear {
            coach.onAnswer = { answer, message in inbox.answered(answer, message: message) }
            // Opened cold by the complication (row 24): Coach's home, which then listens.
            if coach.askPending { page = .coach }
        }
        // The complication was tapped with the app open (row 24): Coach's home, which then listens.
        .onChange(of: coach.askRequests) { _, _ in page = .coach }
        .sheet(isPresented: $menu) {
            WatchMenu(pages: WatchPage.pages(), tafs: tafs) { chosen in
                menu = false
                page = chosen
            }
        }
    }

    @ViewBuilder private func pageView(_ shown: WatchPage) -> some View {
        switch shown {
        case .coach:
            CoachHome(needsYou: needsYou, coach: coach, inbox: inbox, openInbox: { page = .inbox }, openMenu: { menu = true })
        case .inbox:
            InboxScreen(inbox: inbox, needsYou: needsYou)
        case .brief:
            // Your brief as v1 draws it, in Needs you's place, while the brief's switch is on.
            NeedsYouScreen(model: needsYou)
        case .tafs:
            TafsScreen(model: tafs, offersHalt: false, opensPages: true, openInbox: { page = .inbox })
        case .account:
            WatchAccountScreen(model: tafs.accountPage(), signOut: signOut)
        }
    }
}

/// Coach's home: the microphone first, named for Coach, which opens the system's dictation in one tap (`WatchDictation`, row
/// 44, A), then Check before sending (`CoachAsk`; cancelled, the ask with Type and Quick reply); then how many wait in the Inbox (a tap goes there), the line that Coach gets words
/// and never a voice, and the menu at the foot. The last chats come with tafs cloud's chats read.
struct CoachHome: View {
    @ObservedObject var needsYou: NeedsYouModel
    @ObservedObject var coach: CoachModel
    @ObservedObject var inbox: InboxModel
    let openInbox: () -> Void
    let openMenu: () -> Void
    @State private var asking = false
    /// The watch's own recording, behind `WatchVoice.on` (row 44, A): its text then opens Check before sending.
    @State private var recording = false

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            List {
                // W4: Ask Coach as one conversation, oldest first, so the newest turn sits just over the microphone. A header
                // where a day starts and every time in the account's zone, as a taf's chat and Wear OS read (row 44, C).
                // The thread's stored copy first (the owner's call 1, behind `WatchThreads.on`), then this visit's turns.
                let days = coach.history.map(\.at) + coach.turns.map(\.at)
                ForEach(Array(coach.history.enumerated()), id: \.element.id) { i, line in
                    if ChatTime.startsDay(days, at: i, calendar: coach.calendar) { WatchChatDay(date: line.at, calendar: coach.calendar) }
                    TafChatLineRow(line: line, zone: coach.zone) { Task { await coach.openOnPhone() } }
                }
                ForEach(Array(coach.turns.enumerated()), id: \.element.id) { i, turn in
                    if ChatTime.startsDay(days, at: coach.history.count + i, calendar: coach.calendar) {
                        WatchChatDay(date: turn.at, calendar: coach.calendar)
                    }
                    CoachTurnRow(turn: turn, coach: coach, zone: coach.zone)
                }
                // Keep for later (row 8): the message kept on this watch, sent when the phone is in reach again, and Delete.
                if let kept = coach.kept {
                    KeptRow(kept: kept) { coach.deleteKept() }
                }
                // Right under the newest message: the microphone and the keyboard (the owner's call 4).
                ThreadInput(speak: Words.Coach.ask, listen: listen, typed: { words in
                    coach.review(words)
                    if coach.reviewing { asking = true }
                })
                .id(WatchThread.newest)
                if let count = WatchPage.unread(needsYou.state, inboxKept: inbox.kept, unreadLines: inbox.unread) {
                    Button(Words.Watch.inboxUnread(count: count), action: openInbox)
                }
                Text(Words.Watch.voiceTextOnly)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
                Button(Words.Watch.menu, action: openMenu)
            }
            .navigationTitle(Words.Coach.title)
            // The page opens on the microphone, with the conversation above it, and stays on the newest message as it moves.
            .defaultScrollAnchor(.bottom)
            .onChange(of: WatchThread.shape(coach)) { _, _ in proxy.scrollTo(WatchThread.newest, anchor: .bottom) }
            }
        }
        .sheet(isPresented: $asking) {
            CoachAsk(model: coach)
        }
        .sheet(isPresented: $recording, onDismiss: { if coach.reviewing { asking = true } }) {
            VoiceRecordScreen(model: VoiceModel { words in coach.review(words) })
        }
        .task {
            coach.reopen()
            await needsYou.load()
            await coach.afterRead(needsYou.state)
        }
        // The complication (row 24): the same as a tap on the microphone, once per tap, whether Coach's home was already on
        // screen (`onChange`) or appears for it, opened cold or from another page (`onAppear`).
        .onAppear { if coach.takeAskPending() { listen() } }
        .onChange(of: coach.askRequests) { _, _ in if coach.takeAskPending() { listen() } }
    }

    /// The microphone: the watch's own recording when it's on (row 44, A), else the system's dictation in one tap.
    private func listen() {
        if WatchVoice.on, coach.listensFirst {
            recording = true
        } else {
            Task { if await WatchDictation.talk(to: coach) { asking = true } }
        }
    }
}

/// The message kept for later (row 8, as Wear OS's): its words, that it's kept on this watch and goes when the phone is back,
/// and Delete, which sends nothing.
struct KeptRow: View {
    let kept: CoachModel.Kept
    let delete: () -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text(verbatim: kept.text)
                .font(.footnote)
                .lineLimit(4)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .multilineTextAlignment(.trailing)
            Text(Words.Watch.keptToSend)
                .font(.footnote)
                .foregroundStyle(WatchLook.held)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(role: .destructive, action: delete) {
                Label(Words.Chat.delete, systemImage: "xmark")
            }
            .tint(WatchLook.danger)
        }
    }
}

/// One turn of the conversation (W4): when it was said, the person's words on the right, and Coach's side on the left: "Sent"
/// until Coach starts, "Coach is answering" while it's at them, then its answer, which opens whole, with when it came under
/// it. VoiceOver hears whose words each side is.
struct CoachTurnRow: View {
    let turn: CoachModel.Turn
    @ObservedObject var coach: CoachModel
    var zone: TimeZone = .current

    var body: some View {
        VStack(spacing: 4) {
            MineBubble(said: turn.said, at: turn.at, zone: zone, lineLimit: 4)
            coachSide
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .listRowBackground(Color.clear)
    }

    @ViewBuilder private var coachSide: some View {
        switch turn.answer {
        case .sent:
            // The ✓ under the bubble says it went; nothing more until Coach starts.
            EmptyView()
        case .answering:
            HStack(spacing: 4) {
                ProgressView().frame(width: 14, height: 14)
                Text(Words.Chat.tafIsAnswering(taf: Words.Coach.title)).font(.caption2).foregroundStyle(WatchLook.coach)
            }
            .accessibilityElement(children: .combine)
        case .answered(let answer):
            VStack(alignment: .leading, spacing: 4) {
            NavigationLink {
                CoachTurnScreen(turn: turn, answer: answer, coach: coach)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(CoachTurnRow.line(answer))
                        .font(.footnote)
                        .lineLimit(5)
                    // When the answer came (row 44, C), small, under it, as a taf's chat and Wear OS show it.
                    if let at = answer?.at {
                        Text(ChatTime.time(at, zone: zone)).font(.caption2).monospacedDigit().opacity(0.75)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .foregroundStyle(WatchLook.onCoachGround)
                .background(WatchLook.coachGround, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(CoachTurnRow.spoken(answer, zone: zone))
                // The rest on the phone, or a call to approve there: View on phone, inline (the owner's call 5).
                if CoachTurnRow.onPhone(answer) { ViewOnPhone { Task { await coach.openOnPhone() } } }
            }
        }
    }

    /// Whether part of the answer is on the phone only: an answer still to come, one to approve there.
    static func onPhone(_ answer: WatchCoachAnswer?) -> Bool {
        guard let answer else { return true }
        return answer.approveOnPhone || (answer.text.isEmpty)
    }

    /// What VoiceOver hears of Coach's side: whose words, the words, and when they came.
    static func spoken(_ answer: WatchCoachAnswer?, zone: TimeZone) -> String {
        let said = Words.Coach.title + ": " + line(answer)
        guard let at = answer?.at else { return said }
        return said + ", " + ChatTime.time(at, zone: zone)
    }

    /// Coach's side once answered: its words, or, with none, where the rest is.
    static func line(_ answer: WatchCoachAnswer?) -> String {
        guard let answer else { return Words.Watch.moreOnPhone }
        if !answer.text.isEmpty { return answer.text }
        return answer.approveOnPhone ? Words.Watch.approveOnPhone : Words.Watch.moreOnPhone
    }
}

/// A turn's answer whole, as the ask's own answer view draws it.
struct CoachTurnScreen: View {
    let turn: CoachModel.Turn
    let answer: WatchCoachAnswer?
    @ObservedObject var coach: CoachModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: turn.said)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                // The whole answer from the preview (the owner's call 8): a cut answer is read whole on the tap.
                if let text = TafTurnRow.shown(answer, whole: coach.whole[turn.id]), !text.isEmpty {
                    Text(verbatim: text)
                }
                if answer?.approveOnPhone == true {
                    Label(Words.Watch.approveOnPhone, systemImage: "iphone").font(.footnote).foregroundStyle(WatchLook.held)
                }
                switch TafTurnRow.foot(answer, whole: coach.whole[turn.id]) {
                case .reading?, .readMore?:
                    ProgressView()
                case .onPhone?:
                    Text(Words.Watch.moreOnPhone).font(.footnote).foregroundStyle(.secondary)
                case nil:
                    EmptyView()
                }
                if TafTurnRow.foot(answer, whole: coach.whole[turn.id]) == .onPhone || answer?.approveOnPhone == true {
                    ViewOnPhone { Task { await coach.openOnPhone() } }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Words.Coach.title)
        .task { if answer?.more == true { await coach.readMore(turn.id) } }
    }
}

/// The menu, from the foot of Coach's home: every page by name, and Halt at the very bottom, apart from the rest, which
/// opens Choose which to halt (step v).
struct WatchMenu: View {
    let pages: [WatchPage]
    @ObservedObject var tafs: TafsModel
    let go: (WatchPage) -> Void

    var body: some View {
        // Its own stack, so its title shows over the sheet.
        NavigationStack {
            List {
                ForEach(pages) { shown in
                    Button(shown.title) { go(shown) }
                }
                if WatchPage.offersHalt(tafs.state) {
                    Section {
                        NavigationLink {
                            HaltPickScreen(tafs: tafs)
                        } label: {
                            Text(Words.Halt.now).foregroundStyle(WatchLook.danger)
                        }
                    }
                }
            }
            .navigationTitle(Words.Watch.menu)
        }
        .task { await tafs.load() }
    }
}
