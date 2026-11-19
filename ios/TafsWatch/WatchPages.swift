import SwiftUI

/// Watch v2 (docs/watch-app.md, "Version 2"): off. It's built one step at a time behind this switch, as Wear OS's
/// `WATCH_V2`, so a watch shows v1 whole until every step is in; it turns on, as this one line, when the last of them
/// lands and the owner has tried the build.
enum WatchV2 {
    static let on = false
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
        .onChange(of: needsYou.state, initial: true) { _, state in inbox.heard(state) }
        .onAppear { coach.onAnswer = { answer, message in inbox.answered(answer, message: message) } }
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
            WatchAccountPage()
        }
    }
}

/// Coach's home: the microphone first, named for Coach, which opens the ask (`CoachAsk`: the system's input, Type and Quick
/// reply, then Check before sending); then how many wait in the Inbox (a tap goes there), the line that Coach gets words
/// and never a voice, and the menu at the foot. The last chats come with tafs cloud's chats read.
struct CoachHome: View {
    @ObservedObject var needsYou: NeedsYouModel
    @ObservedObject var coach: CoachModel
    @ObservedObject var inbox: InboxModel
    let openInbox: () -> Void
    let openMenu: () -> Void
    @State private var asking = false

    var body: some View {
        NavigationStack {
            List {
                Button { asking = true } label: {
                    Label(Words.Coach.ask, systemImage: "mic.fill")
                        .font(.headline)
                }
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
        }
        .sheet(isPresented: $asking) {
            CoachAsk(model: coach)
        }
        .task { await needsYou.load() }
    }
}

/// The menu, from the foot of Coach's home: every page by name, and Halt at the very bottom, apart from the rest, which
/// opens Choose which to halt (step v).
struct WatchMenu: View {
    let pages: [WatchPage]
    @ObservedObject var tafs: TafsModel
    let go: (WatchPage) -> Void

    var body: some View {
        // Its own stack, so its title shows over the sheet (qa-kiff's read).
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
                            Text(Words.Halt.now).foregroundStyle(.red)
                        }
                    }
                }
            }
            .navigationTitle(Words.Watch.menu)
        }
        .task { await tafs.load() }
    }
}

/// Account, until the account read is drawn (step vi): what this watch holds and can't do.
struct WatchAccountPage: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                Text(Words.Watch.trust)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(Words.Settings.title)
        }
    }
}
