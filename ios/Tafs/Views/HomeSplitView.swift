import SwiftUI

/// The Tafs tab: on a wide screen (kiff/tafs#85) the tafs in a sidebar beside the open
/// taf; on a compact width, the iPhone's or an iPad's narrow split, the list that pushes.
struct TafsTab: View {
    @Binding var tab: Tab
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            HomeSplitView()
        } else {
            NavigationStack { HomeView(tab: $tab) }
        }
    }
}

/// Whether a sidebar beside this screen shows what's open (kiff/tafs#253): opening a taf or
/// a trial then selects its row, rather than pushing a screen the sidebar doesn't show.
private struct SidebarSelectsKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var sidebarSelects: Bool {
        get { self[SidebarSelectsKey.self] }
        set { self[SidebarSelectsKey.self] = newValue }
    }
}

/// What the sidebar opens beside it.
enum HomeSelection: Hashable {
    case taf(UUID)
    /// A held call, shown on its taf's page.
    case held(UUID, taf: UUID)
    /// Every taf's halt, as the phone's home has it.
    case haltAll
    /// Today across the tafs this person uses or oversees.
    case overview
    /// The tafs' scheduled work to come (kiff/tafs#534), opened from the Overview.
    case calendar
}

/// The sidebar (Theme.raised): the overview, the calls waiting for the owner, and the tafs with a line on each. The selected one opens beside it.
struct HomeSplitView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: HomeSelection?
    /// What the app opened on by itself, and whether the owner has chosen since: until they
    /// do, the first taf in the list is the one open (the 859 test).
    @State private var opened: HomeSelection?
    @State private var chosen = false
    /// A recipe's sheet, opened from the detail pane with no taf.
    @State private var openRecipe: TafsCloud.Recipes.Recipe?

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            NavigationStack {
                switch selection {
                case .taf(let id)?, .held(_, let id)?:
                    if let tafID = model.tafOfTrialChat(id) { TrialScreen(chatID: id, tafID: tafID) } else { TafDetailView(tafID: id) }
                case .haltAll?:
                    HaltScreen(tafID: nil)
                case .overview?:
                    OverviewView { open in withMotion(.easeInOut(duration: 0.25)) { selection = open } }
                case .calendar?:
                    CalendarView { id in withMotion(.easeInOut(duration: 0.25)) { selection = .taf(id) } }
                case nil where model.homeListsRecipes:
                    // No taf yet: the recipes take the pane beside the sidebar, which is
                    // narrow for a headline (docs/ios-app.md).
                    ScrollView {
                        HomeRecipes(recipes: model.recipes ?? TafsCloud.Recipes()) { openRecipe = $0 }
                            .padding(.horizontal, 24).padding(.vertical, 20)
                            .frame(maxWidth: 720, alignment: .leading)
                            .frame(maxWidth: .infinity)
                    }
                    .screenBackground()
                case nil:
                    VStack(spacing: 12) {
                        Mascot(size: 72)
                        Text(model.loadingList ? Words.Home.loading : model.listUnread ? Words.Home.notLoaded
                             : model.tafs.isEmpty ? Words.Home.noTafs : Words.Home.chooseTaf)
                            .scaledFont(16).foregroundColor(Theme.dim)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .screenBackground()
                }
            }
            // A new selection starts its own stack: nothing pushed for the last one stays.
            .id(selection)
            .environment(\.sidebarSelects, true)
        }
        .navigationSplitViewStyle(.balanced)
        .sheet(item: $openRecipe) { recipe in
            RecipeSheet(recipe: recipe, recipes: model.recipes ?? TafsCloud.Recipes(), canHand: model.mayUseCoach) {
                openRecipe = nil
                model.handRecipeToCoach(recipe)
            }
        }
        .onAppear { model.sidebarShown = true }
        .onDisappear { model.sidebarShown = false }
        .onChange(of: selection) { s in
            if s != opened { chosen = true }
        }
        // "See it on…" in Setup selects the taf it made (kiff/tafs#96).
        .onChange(of: model.requestedTaf) { id in
            guard let id else { return }
            // A cross-fade, as iOS moves between a list's items, not a cut (the 755 retest).
            withMotion(.easeInOut(duration: 0.25)) { selection = .taf(id) }
            model.requestedTaf = nil
        }
        // Something open from the start: the first taf in the list, which is the one used
        // last, a call waiting on it first (CI's screenshots open Refunds, as on the phone).
        // While the list settles at launch it follows its first taf, until the owner chooses
        // (the 859 test: the app opened Refunds while Refunds 4 led the list).
        .task(id: model.tafs.map(\.id) + [model.listedTafs.first?.id].compactMap { $0 }) {
            if chosen, let selected = selection {
                switch selected {
                case .taf(let id), .held(_, let id): if model.taf(id) != nil { return }
                case .haltAll, .overview, .calendar: return
                }
            }
            opened = Self.opening(model.listedTafs, screenshot: Screenshots.enabled && !Screenshots.listSettlesOnReturn)
            chosen = false
            selection = opened
        }
    }

    private var haltAllRow: some View {
        let h = model.haltState(nil)
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(Words.Taf.Halt.titleAll).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                if h.state != .running {
                    Text(HaltScreen.headline(h)).scaledFont(13).foregroundColor(Theme.no)
                }
            }
        } icon: {
            Image(systemName: h.state == .running ? "hand.raised" : "hand.raised.fill").foregroundColor(Theme.no)
        }
        .task { await model.refreshHalt(nil) }
    }

    /// The taf the app opens on by itself: the first in the list as it stands, which puts the
    /// one a call waits on first, then the one used last (the 859 test). CI's screenshots open
    /// Refunds, as on the phone.
    static func opening(_ listed: [Taf], screenshot: Bool) -> HomeSelection? {
        let refunds = screenshot ? listed.first(where: { $0.name == TafTemplates.refunds.name })?.id : nil
        return (refunds ?? listed.first?.id).map { .taf($0) }
    }

    private var sidebar: some View {
        // Only the owner's taps write through this binding, so each one is a choice, a tap on
        // the taf already open too: the list settling later doesn't take them away from it.
        List(selection: Binding(get: { selection }, set: { chosen = true; selection = $0 })) {
            if model.mode == .kiff {
                Section {
                    // Today across the tafs, one line each.
                    if !model.tafs.isEmpty {
                        NavigationLink(value: HomeSelection.overview) {
                            Label(Words.Home.Overview.title, systemImage: "square.grid.2x2").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        }
                    }
                    // Every taf at once, from its own screen, as on the phone's home.
                    // The owner looked for it here (kiff/tafs#138).
                    if !model.cloudTafs.isEmpty, model.canManageCards {
                        NavigationLink(value: HomeSelection.haltAll) { haltAllRow.modifier(drives(Words.Taf.Halt.titleAll, .haltAll)) }
                    }
                }
            }
            if !model.held.isEmpty {
                Section {
                    ForEach(model.held) { call in
                        if let taf = model.taf(call.tafID) {
                            NavigationLink(value: HomeSelection.held(call.id, taf: taf.id)) { heldRow(call, taf).modifier(drives(taf.name, .held(call.id, taf: taf.id))) }
                        }
                    }
                } header: { Eyebrow(text: Words.NeedsYou.title, color: Theme.amber) }
            }
            let sections = model.tafSections
            // Theirs, then the ones shared with them (kiff/tafs#306); someone with only shared
            // tafs sees just those.
            if !sections.own.isEmpty || sections.shared.isEmpty {
                Section {
                    ForEach(sections.own) { taf in tafLinks(taf) }
                    if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                        // tafs cloud's line for a Home with no taf; the app's own only when it sent none.
                        Text(model.recipes?.homeWords ?? (model.mayUseCoach ? Words.Home.connectToolSetUpYour : Words.Home.tafsSharedYouShow))
                            .scaledFont(13).foregroundColor(Theme.dim)
                            .listRowBackground(Color.clear)
                    }
                } header: { Eyebrow(text: Words.Home.yourTafs) }
            }
            if !sections.shared.isEmpty {
                Section {
                    ForEach(sections.shared) { taf in tafLinks(taf) }
                } header: { Eyebrow(text: Words.Home.sharedYou) }
            }
            if !model.otherAgents.isEmpty {
                // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                Section {
                    ForEach(model.otherAgents) { taf in
                        NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
                    }
                } header: { Eyebrow(text: Words.Home.otherAgents) }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(Theme.raised.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .safeAreaInset(edge: .bottom) { footer }
        .navigationTitle(Words.App.name)
    }

    /// A new taf starts in Coach mode, a conversation (kiff/tafs#138, item 59). The sample has
    /// no form for one, so its + isn't shown (`canStartTafs`).
    private func startNewTaf() {
        model.startNewTaf()
    }

    /// A taf in the list, and its open trial under it (kiff/tafs#173).
    @ViewBuilder private func tafLinks(_ taf: Taf) -> some View {
        // No tap gesture of its own: one on the row took the list's taps over its icon and
        // name, so only the row's empty end selected it (the 901 test).
        NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf).modifier(drives(taf.name, .taf(taf.id))) }
        if let chat = model.trialChat(of: taf.id), let trying = model.taf(chat) {
            NavigationLink(value: HomeSelection.taf(chat)) { tafRow(trying).modifier(drives(trying.name, .taf(chat))) }
        }
    }

    /// A sidebar row for a drive (docs/drive.md): choosing it is the person's tap on it.
    private func drives(_ words: String, _ choice: HomeSelection) -> Drivable {
        Drivable(words: words, role: .button, id: nil, confirm: false, secret: false, terms: false,
                 actions: [.activate: { chosen = true; selection = choice }])
    }

    private func tafRow(_ taf: Taf) -> some View {
        HStack(spacing: 12) {
            TafAvatar(taf: taf, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                    if let badge = taf.badge {
                    Text(badge).scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.amber).clipShape(Capsule())
                    }
                }
                if let out = model.signedOut(taf.id) {
                    Text(out.signedOutWords).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.amberText).oneLineUnlessLarge()
                } else if let line = taf.healthNow {
                    // Idle and not healthy: tafs cloud's line says why, where it would say the next run.
                    Text(line).scaledFont(13, relativeTo: .footnote).foregroundColor(taf.health?.failing == true ? Theme.no : Theme.amberText).oneLineUnlessLarge()
                } else {
                    Text(taf.now).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("taf-row")
    }

    private func heldRow(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Theme.amber).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                Text(Words.Home.reasonAnswerBy(why: "\(call.reason)", time: "\(call.expiresAt.zonedShortTime)"))
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.soft).oneLineUnlessLarge()
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if model.canStartTafs {
                Button { startNewTaf() } label: { Label(Words.Home.startNewTaf, systemImage: "plus") }
                    .buttonStyle(PrimaryButtonStyle())
            }
            // The account this is, always in sight (the 870 test); its settings are the
            // rail's Account.
            if let account = model.accountLabel {
                Text(account).scaledFont(12).foregroundColor(Theme.dim).lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
            // KIFF can't be reached just now (the 930 test): under the account's name, with
            // the status page; nothing when all is well.
            if let words = model.unreachable { UnreachableLine(words: words, retrying: model.retryingRefresh) }
            // The plan, only when it needs the owner: a trial ended or ending, tasks nearly gone.
            PlanLine()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.raised)
    }
}
