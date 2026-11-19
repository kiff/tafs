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
    /// kept on the watch, with read and dismissed, is step (iv). Nil until the phone has answered.
    static func unread(_ state: NeedsYouModel.State) -> Int? {
        if case .loaded(let page) = state { return page.calls.count }
        return nil
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
    @State private var page: WatchPage = .coach
    @State private var menu = false

    var body: some View {
        TabView(selection: $page) {
            ForEach(WatchPage.pages()) { shown in
                pageView(shown).tag(shown)
            }
        }
        .tabViewStyle(.verticalPage)
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
            CoachHome(needsYou: needsYou, coach: coach, openInbox: { page = .inbox }, openMenu: { menu = true })
        case .inbox, .brief:
            // Until the inbox is kept on the watch (step iv), the Inbox is Needs you as v1 reads it, and the brief is drawn
            // in its place there when the brief's switch is on.
            NeedsYouScreen(model: needsYou)
        case .tafs:
            TafsScreen(model: tafs, offersHalt: false)
        case .account:
            WatchAccountPage()
        }
    }
}

/// Coach's home: the microphone first, named for Coach; then how many wait in the Inbox (a tap goes there), the line that
/// Coach gets words and never a voice, and the menu at the foot. Type and Quick reply come with the review (step iii),
/// and the last chats with tafs cloud's chats read.
struct CoachHome: View {
    @ObservedObject var needsYou: NeedsYouModel
    @ObservedObject var coach: CoachModel
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
                if let count = WatchPage.unread(needsYou.state) {
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
            CoachScreen(model: coach)
        }
        .task { await needsYou.load() }
    }
}

/// The menu, from the foot of Coach's home: every page by name, and Halt at the very bottom, apart from the rest. Halt
/// asks once more, for all the tafs, until choosing which to halt comes with step (v).
struct WatchMenu: View {
    let pages: [WatchPage]
    @ObservedObject var tafs: TafsModel
    let go: (WatchPage) -> Void
    @State private var confirming = false

    var body: some View {
        // Its own stack, so its title shows over the sheet (qa-kiff's read).
        NavigationStack {
            List {
                ForEach(pages) { shown in
                    Button(shown.title) { go(shown) }
                }
                if WatchPage.offersHalt(tafs.state) {
                    Section {
                        Button(Words.Halt.allNow, role: .destructive) { confirming = true }
                    }
                }
            }
            .navigationTitle(Words.Watch.menu)
        }
        .task { await tafs.load() }
        .confirmationDialog(Words.HaltAll.title, isPresented: $confirming) {
            Button(Words.Halt.allNow, role: .destructive) {
                // The menu closes on Your tafs, where the halt's answer shows.
                go(.tafs)
                Task { await tafs.halt(nil) }
            }
        } message: {
            Text(Words.Halt.Explain.runningAll)
        }
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
