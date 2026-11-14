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
    /// The app's other screens, as sidebar items on an iPad's full width (the owner's layout).
    case needsYou, record, usage, connectApps, account

    /// The tab each screen is, so the app's tab and the sidebar agree.
    var tab: Tab {
        switch self {
        case .needsYou: return .needsYou
        case .record: return .record
        case .account: return .account
        default: return .tafs
        }
    }

    /// The screen a tab opens in the sidebar; nil for the tafs, which keep what's open.
    static func of(_ tab: Tab) -> HomeSelection? {
        switch tab {
        case .needsYou: return .needsYou
        case .record: return .record
        case .account: return .account
        case .tafs: return nil
        }
    }

    /// A screen of the app, not a taf: the list settling doesn't take it away.
    var isScreen: Bool {
        switch self {
        case .taf, .held: return false
        default: return true
        }
    }
}

/// The sidebar (Theme.raised): the overview, the calls waiting for the owner, and the tafs with a line on each. The selected one opens beside it.
struct HomeSplitView: View {
    @EnvironmentObject private var model: AppModel
    /// While Coach drives the app, its button says so; a tap on it stops the drive.
    @ObservedObject private var drive = DriveSession.shared
    /// The app's tab, when the sidebar is the whole app's (an iPad's full width): kept in step
    /// with the selection, so a notification or a drive that opens a tab opens it here.
    var tab: Binding<Tab>? = nil
    /// Coach's button at the sidebar's foot, for those who may use it.
    var coachAvailable = false
    @State private var selection: HomeSelection?
    /// What the app opened on by itself, and whether the owner has chosen since: until they
    /// do, the first taf in the list is the one open (the 859 test).
    @State private var opened: HomeSelection?
    @State private var chosen = false
    /// A recipe's sheet, opened from the detail pane with no taf, or from the strip in the sidebar.
    @State private var openRecipe: TafsCloud.Recipes.Recipe?
    /// + New's gallery of every ready-made job, and what was chosen in it: acted on once it closes.
    @State private var showingGallery: GalleryMode?
    @State private var galleryChoice: GalleryChoice?

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            // Account brings its own stack; Record gets one, as on the phone. Both are built
            // again on each pick, not kept as a tab is.
            if selection == .account {
                SettingsView()
            } else if selection == .record {
                NavigationStack { RecordView() }
            } else {
            NavigationStack {
                switch selection {
                case .needsYou?:
                    NeedsYouView()
                case .usage?:
                    UsageView()
                case .connectApps?:
                    ConnectAppsView()
                case .account?, .record?:
                    EmptyView()
                case .taf(let id)?, .held(_, let id)?:
                    if let tafID = model.tafOfTrialChat(id) { TrialScreen(chatID: id, tafID: tafID) } else { TafDetailView(tafID: id) }
                case .haltAll?:
                    HaltScreen(tafID: nil)
                case .overview?:
                    // As the whole app's sidebar (an iPad's full width), Calendar is its item: the
                    // Overview doesn't draw a second way to it.
                    OverviewView(showsCalendar: !calendarInSidebar) { open in withMotion(.easeInOut(duration: 0.25)) { selection = open } }
                case .calendar?:
                    CalendarView { id in withMotion(.easeInOut(duration: 0.25)) { selection = .taf(id) } }
                case nil where model.homeListsRecipes:
                    // No taf yet: the recipes take the pane beside the sidebar, which is
                    // narrow for a headline (docs/ios-app.md).
                    ScrollView {
                        HomeRecipes(recipes: model.recipes ?? TafsCloud.Recipes(), logo: { model.cloud.logoURL($0) },
                                    ownWords: model.mayUseCoach ? { model.startNewTaf() } : nil) { openRecipe = $0 }
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
                        // No taf and no job listed: "Say it in your own words" still leads to Coach.
                        if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty, model.mayUseCoach,
                           let own = model.recipes?.homeOwnWords {
                            OwnWordsRow(title: own.title, line: own.line) { model.startNewTaf() }
                                .frame(maxWidth: 420)
                        }
                    }
                    .padding(.horizontal, 24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .screenBackground()
                }
            }
            // A new selection starts its own stack: nothing pushed for the last one stays.
            .id(selection)
            .environment(\.sidebarSelects, true)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .sheet(item: $openRecipe) { recipe in
            RecipeSheet(recipe: recipe, recipes: sheetRecipes(for: recipe), canHand: model.mayUseCoach,
                        sampleLabel: model.gallery?.sampleLabel) {
                openRecipe = nil
                model.handRecipeToCoach(recipe)
            }
        }
        .sheet(item: $showingGallery, onDismiss: actOnGalleryChoice) { mode in
            if let gallery = model.gallery {
                RecipeGallerySheet(gallery: gallery, mode: mode, labels: model.recipes?.labels, logo: { model.cloud.logoURL($0) },
                                   canHand: model.mayUseCoach, choose: { galleryChoice = $0; showingGallery = nil },
                                   close: { showingGallery = nil })
            }
        }
        .onAppear { model.sidebarShown = true }
        .onDisappear { model.sidebarShown = false }
        .onChange(of: selection) { s in
            if s != opened { chosen = true }
            // The app's tab follows: a taf is the Tafs tab, each screen its own.
            if let tab, let s, tab.wrappedValue != s.tab { tab.wrappedValue = s.tab }
        }
        // A tab opened from elsewhere (a notification, a drive) opens its screen here.
        .onChange(of: tab?.wrappedValue) { t in
            guard let t else { return }
            // The tafs (⌘1) from a screen: back to the taf the app opened on. From a taf, nothing moves.
            if t == .tafs {
                guard selection?.tab != .tafs, let back = opened ?? Self.opening(model.listedTafs, screenshot: false) else { return }
                chosen = true
                selection = back
                return
            }
            guard let screen = HomeSelection.of(t), selection != screen else { return }
            chosen = true
            selection = screen
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
                default: return
                }
            }
            // As one sidebar for the whole app, the tafs list is what opens first (the owner's layout); a screenshot
            // that asks for a screen still opens Refunds.
            let onList = tab != nil && !(Screenshots.enabled && Screenshots.screen != nil)
            opened = onList ? .overview : Self.opening(model.listedTafs, screenshot: Screenshots.enabled && !Screenshots.listSettlesOnReturn)
            // `-screen calendar` and `calendar-week` select Calendar, the sidebar's item (Screenshots.calendar).
            if Screenshots.calendar != nil { opened = .calendar }
            chosen = false
            // A tab asked for before the sidebar drew (an alert that opened the app on Needs you, a route, a
            // screenshot's `-tab`) opens its screen: the tafs list only when none was.
            selection = tab.flatMap { HomeSelection.of($0.wrappedValue) } ?? opened
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

    /// The signed-in account's items: KIFF mode, or its screenshot over the sample (`-signedIn`).
    private var signedInLayout: Bool { model.mode == .kiff || Screenshots.signedIn }

    /// Calendar is a sidebar item: the sidebar is the whole app's (`tab` set, an iPad's full
    /// width), signed in. The Overview then has no calendar button of its own.
    private var calendarInSidebar: Bool { tab != nil && signedInLayout }

    private var sidebar: some View {
        // Only the owner's taps write through this binding, so each one is a choice, a tap on
        // the taf already open too: the list settling later doesn't take them away from it.
        // A new item chosen ticks (Haptics).
        List(selection: Binding(get: { selection }, set: { chosen = true; Haptics.select(selection, $0); selection = $0 })) {
            // The app's own mark at the top, as its header (the owner's layout).
            // As one sidebar for the whole app, the mascot and "tafs" is the way to the tafs list (the owner's
            // layout): every taf is there; beside it, only those relevant now.
            if tab != nil {
                NavigationLink(value: HomeSelection.overview) { brand.modifier(drives(Words.App.name, .overview)) }
                    .listRowBackground(Color.clear)
            }
            if tab != nil {
                // The app's screens, each an item, opened beside the sidebar.
                Section {
                    screenItem(.needsYou, Words.NeedsYou.title, "bell", badge: model.needsYouCount)
                    screenItem(.record, Words.Record.title, "list.bullet")
                    if signedInLayout {
                        screenItem(.calendar, Words.Calendar.heading, "calendar")
                        screenItem(.usage, UsageView.title, "chart.bar")
                        screenItem(.connectApps, ConnectAppsView.title, "app.connected.to.app.below.fill")
                    }
                }
            }
            if signedInLayout {
                Section {
                    // Today across the tafs, one line each: as one sidebar for the whole app, the mascot opens it.
                    if tab == nil, !model.tafs.isEmpty {
                        NavigationLink(value: HomeSelection.overview) {
                            Label(Words.Home.Overview.title, systemImage: "square.grid.2x2").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        }
                    }
                    // Every taf at once, from its own screen, as on the phone's home.
                    // The owner looked for it here (kiff/tafs#138).
                    if model.showsAccountHalt {
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
            let all = model.tafSections
            // As one sidebar for the whole app, only the tafs relevant now; all of them are in the list behind the mascot.
            let sections = tab == nil ? all : (own: all.own.filter(model.relevantNow), shared: all.shared.filter(model.relevantNow))
            // Theirs, then the ones shared with them (kiff/tafs#306); someone with only shared
            // tafs sees just those.
            if tab == nil ? (!all.own.isEmpty || all.shared.isEmpty) : (!sections.own.isEmpty || !model.settingUp.isEmpty || model.tafs.isEmpty) {
                Section {
                    ForEach(sections.own) { taf in tafLinks(taf) }
                    // A taf being set up, among the tafs: by its name, with no state (docs/tafs.md).
                    ForEach(model.settingUp) { taf in
                        if let finish = taf.finish, model.canFinishSetup(finish) {
                            Button { Task { await model.finishSetup(finish) } } label: { tafRow(taf) }
                                .buttonStyle(.drivablePlain)
                                .driveWords(taf.name)
                                .accessibilityIdentifier("home.finish-setup")
                        } else {
                            tafRow(taf)
                        }
                        // Discard, beside Finish setting up, for the person whose setup it was.
                        if taf.discard != nil {
                            DiscardSettingUpButton(taf: taf).frame(maxWidth: .infinity, alignment: .trailing)
                        }
                    }
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
            // The jobs, to browse with nothing created: with or without tafs, once tafs cloud
            // sends the row's words.
            if model.mode == .kiff, let words = model.gallery?.explore {
                Section {
                    Button { showingGallery = .browse } label: {
                        Label(words, systemImage: "square.grid.2x2").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    }
                    .buttonStyle(.drivablePlain)
                    .driveWords(words)
                    .accessibilityIdentifier("home.explore")
                }
            }
            // A few jobs the account doesn't run yet, where tafs cloud shows them, in its order.
            if model.mode == .kiff, !model.tafs.isEmpty, let gallery = model.gallery, !gallery.stripRecipes.isEmpty {
                Section {
                    ForEach(gallery.stripRecipes) { recipe in
                        Button { openRecipe = recipe } label: {
                            HStack(spacing: 10) {
                                (RecipeTint.color(gallery.category(of: recipe)) ?? Theme.line)
                                    .frame(width: 4, height: 28).clipShape(Capsule()).accessibilityHidden(true)
                                Text(recipe.lead).scaledFont(14).foregroundColor(Theme.ink).lineLimit(2)
                            }
                        }
                        .buttonStyle(.drivablePlain)
                        .driveWords(recipe.lead)
                    }
                    // The strip shows a few: this row opens them all, in tafs cloud's words for them.
                    if let words = gallery.seeAll {
                        Button { showingGallery = .browse } label: {
                            Text(words).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                        }
                        .buttonStyle(.drivablePlain)
                        .driveWords(words)
                        .accessibilityIdentifier("home.recipe-strip.all")
                    }
                } header: { Eyebrow(text: gallery.strip.title) }
                .accessibilityIdentifier("home.recipe-strip")
            }
            if !model.otherAgents.isEmpty {
                // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                Section {
                    ForEach(model.otherAgents) { taf in
                        if let finish = taf.finish, model.canFinishSetup(finish) {
                            // Its setup stopped partway: Coach's chat where it stopped (docs/tafs.md).
                            Button { Task { await model.finishSetup(finish) } } label: { tafRow(taf) }
                                .buttonStyle(.drivablePlain)
                                .driveWords(taf.name)
                                .accessibilityIdentifier("home.finish-setup")
                        } else {
                            NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
                        }
                    }
                } header: { Eyebrow(text: Words.Home.otherAgents) }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(Theme.raised.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .safeAreaInset(edge: .bottom) { footer }
        .navigationTitle(tab == nil ? Words.App.name : "")
        .navigationBarTitleDisplayMode(tab == nil ? .automatic : .inline)
    }

    /// The mascot and the app's name, the sidebar's header.
    private var brand: some View {
        HStack(spacing: 10) {
            Mascot(size: 34)
            Text(Words.App.name).scaledFont(22, weight: .bold, design: .rounded, relativeTo: .title2).foregroundColor(Theme.ink)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("sidebar.brand")
    }

    /// One of the app's screens as a sidebar item, with its count where it has one.
    private func screenItem(_ screen: HomeSelection, _ title: String, _ icon: String, badge: Int = 0) -> some View {
        NavigationLink(value: screen) {
            // In the drive's registry, as every sidebar row is (docs/drive.md).
            Label(title, systemImage: icon).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                .modifier(drives(title, screen))
        }
        .badge(badge)
        .accessibilityIdentifier("sidebar." + title)
    }

    /// A new taf starts in Coach mode, a conversation (kiff/tafs#138, item 59). The sample has
    /// no form for one, so its + isn't shown (`canStartTafs`).
    private func startNewTaf() {
        // + New opens every ready-made job where tafs cloud sends the gallery; without it,
        // Coach as before.
        if model.gallery != nil { showingGallery = .create } else { model.startNewTaf() }
    }

    /// The recipes a job's sheet draws from: the detail pane's list when the job is on it, else
    /// the gallery's, with `/v1/recipes`' sheet words where the gallery sends none.
    private func sheetRecipes(for recipe: TafsCloud.Recipes.Recipe) -> TafsCloud.Recipes {
        if let recipes = model.recipes, recipes.recipes.contains(where: { $0.id == recipe.id }) { return recipes }
        return model.gallery?.asRecipes(labels: model.recipes?.labels) ?? model.recipes ?? TafsCloud.Recipes()
    }

    /// What was chosen in the gallery, once it has closed: Coach opens over Home.
    private func actOnGalleryChoice() {
        guard let choice = galleryChoice else { return }
        galleryChoice = nil
        switch choice {
        case .recipe(let recipe): model.handRecipeToCoach(recipe)
        case .ownWords: model.startNewTaf()
        }
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
                } else if taf.finish != nil {
                    // Its setup stopped partway: it needs the owner, in tafs cloud's words.
                    Text(taf.now).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.amberText).oneLineUnlessLarge()
                } else if taf.settingUp {
                    // Being set up, by someone else's Coach chat: its name alone, no line.
                    EmptyView()
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
            // KIFF can't be reached just now (the 930 test), with the status page; nothing when
            // all is well.
            if let words = model.unreachable { UnreachableLine(words: words, retrying: model.retryingRefresh) }
            // The plan, only when it needs the owner: a trial ended or ending, tasks nearly gone.
            PlanLine()
            if tab != nil {
                // Coach, then the account just under it: always in sight, never behind "…".
                if coachAvailable { coachButton }
                accountButton
            } else if let account = model.accountLabel {
                // The account this is, always in sight (the 870 test).
                Text(account).scaledFont(12).foregroundColor(Theme.dim).lineLimit(1)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.raised)
    }

    /// Coach at the sidebar's foot: ink fill, as on the phone's bar; a tap while Coach drives stops it.
    private var coachButton: some View {
        // Its own style, so it taps here, as the sidebar's buttons do (Haptics).
        Button { Haptics.tap(); model.openCoach() } label: {
            Label(drive.isOn ? Words.Coach.driving : Words.Coach.title, systemImage: drive.isOn ? "sparkles" : "sparkle")
                .scaledFont(15, weight: .semibold).lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundColor(Theme.onFill)
                .background(drive.isOn ? Theme.amber : Theme.ink)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        // In the drive's registry with no action: Coach can't use it, and the person's tap on it stops the drive.
        .drivable(Words.Coach.title, id: DriveSession.coachButtonID)
        .accessibilityLabel(Words.Coach.coachMode)
        .accessibilityIdentifier("bar.Coach")
    }

    /// The account, who is signed in, at the very foot: it opens Account beside the sidebar.
    private var accountButton: some View {
        Button { chosen = true; selection = .account } label: {
            HStack(spacing: 10) {
                Image(systemName: selection == .account ? "person.crop.circle.fill" : "person.crop.circle")
                    .scaledFont(20).foregroundColor(selection == .account ? Theme.amberText : Theme.soft)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Words.Settings.title).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    if let account = model.accountLabel {
                        Text(account).scaledFont(12).foregroundColor(Theme.dim).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.drivablePlain)
        .driveWords(Words.Settings.title)
        .accessibilityLabel(model.accountLabel.map { Words.Settings.accountNamed(account: "\($0)") } ?? Words.Settings.title)
        .accessibilityIdentifier("bar.Account")
    }
}
