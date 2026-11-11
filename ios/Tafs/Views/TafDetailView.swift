import SwiftUI

struct TafDetailView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// 0 chat, 1 activity, 2 card, 3 setup (the taf layout). Chat first: talking to a taf is
    /// the main thing.
    @State private var section = !Screenshots.enabled ? 0 : Screenshots.screen == "taf" ? 1 : Screenshots.screen == "card" ? 2
        : ["setup", "starts", "results"].contains(Screenshots.screen ?? "") ? 3 : 0

    /// The Activity tab, where a taf's door requests are answered.
    static let activitySection = 1

    /// `opensOn` is the tab to open on, such as Activity for the taf's door requests; else Chat.
    init(tafID: UUID, opensOn: Int? = nil) {
        self.tafID = tafID
        if let opensOn { _section = State(initialValue: opensOn) }
    }
    /// Activity's filter of its record.
    @State private var activityFilter: ActivityFilter = .all
    @State private var activitySelection = ""
    /// The screenshots open Setup's screens by themselves.
    @State private var openStarts = Screenshots.enabled && Screenshots.screen == "starts"
    @State private var openResults = Screenshots.enabled && Screenshots.screen == "results"
    @State private var confirmRevoke = false
    @State private var confirmRemove = false
    /// What removing it did, shown once it's done.
    @State private var removal: String?
    /// The Card in front of the stack (kiff/tafs#211); nil for the first, the live one.
    @State private var frontCard: String?
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0
    @State private var height: CGFloat = 0
    /// Share, the version and the Card's line folded away while the page scrolls down, so
    /// the chat has the screen; they come back on the way up.
    @State private var folded = false

    /// The side column's width, and the least the chat keeps beside it: the largest iPhone's
    /// width (kiff/tafs#211, the owner's 4A).
    static let sideWidth: CGFloat = 360
    static let chatAtLeast: CGFloat = 430

    /// Under a Card total with no currency: Coach connects the tool again.
    static let reconnect = Words.Connect.reconnectInCoach

    /// Reconnecting is offered to whoever manages the taf and may use Coach, signed in.
    static func offersReconnect(manages: Bool, mayUseCoach: Bool, mode: AppModel.Mode) -> Bool {
        mode == .kiff && manages && mayUseCoach
    }

    /// What a tab of the taf's page draws: its chat, the Activity of a taf on tafs cloud
    /// (`TafActivityList`, with its runs and its work), or a scrolling page (the Card, Setup, and
    /// the sample's Activity).
    enum Pane: Equatable { case chat, activityList, scroll }
    static func pane(section: Int, kiff: Bool, canChat: Bool) -> Pane {
        if section == 0 && canChat { return .chat }
        if section == 1 && kiff { return .activityList }
        return .scroll
    }

    /// A wide screen (kiff/tafs#85): beside the chat or the activity, a second column with
    /// the call waiting for the owner and the Cards. Only in landscape and only when the chat
    /// keeps an iPhone's width beside it (kiff/tafs#211): iPad landscape. A
    /// narrower screen keeps one column, with the Card bar above it.
    private var sideColumn: Bool {
        sizeClass == .regular && section < 2 && width > height
            && width - Self.sideWidth - 32 >= Self.chatAtLeast
    }

    var body: some View {
        if let taf = model.taf(tafID) {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 12) {
                    header(taf)
                    if !folded { headerLine(taf) }
                    Picker(Words.Taf.viewPicker, selection: $section) {
                        if model.canChat(taf.id) { Text(Words.Taf.Section.chat).tag(0) }
                        Text(Words.Taf.Section.activity).tag(1)
                        Text(Words.Taf.Section.card).tag(2)
                        Text(Words.Taf.Section.setup).tag(3)
                    }
                    .pickerStyle(.segmented)
                    // Without the side column, the Cards are one line above the chat or the
                    // activity; it opens the Card tab (kiff/tafs#211).
                    if section < 2 && !sideColumn && !folded, let front = shownFront(taf) {
                        CardBar(card: front, count: taf.shownCards.count, color: Theme.tafColor(taf.colorIndex), revoked: taf.card.revoked) {
                            section = 2
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 4)

                HStack(alignment: .top, spacing: 0) {
                    switch Self.pane(section: section, kiff: model.mode == .kiff, canChat: model.canChat(taf.id)) {
                    case .chat:
                        TafChatView(tafID: taf.id, onScroll: fold)
                    case .activityList:
                        TafActivityList(tafID: taf.id, selection: $activitySelection)
                    case .scroll:
                        ScrollView {
                            VStack(alignment: .leading, spacing: 16) {
                                switch section {
                                case 2: card(taf)
                                case 3: setup(taf)
                                default: activity(taf)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                        .simultaneousGesture(DragGesture().onChanged { drag in
                            if abs(drag.translation.height) > 24 { fold(drag.translation.height < 0) }
                        })
                    }
                    if sideColumn {
                        ScrollView { side(taf).padding(.horizontal, 16).padding(.vertical, 12) }
                            .frame(width: Self.sideWidth)
                    }
                }
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { width = g.size.width; height = g.size.height }
                    .onChange(of: g.size.width) { width = $0 }
                    .onChange(of: g.size.height) { height = $0 }
            })
            .screenBackground()
            .inlineTitle()
            // Coach mode opened from here works on this taf (kiff/tafs#138).
            .onAppear { model.tafOnScreen = tafID }
            .task(id: tafID) { await model.loadTafSetup(tafID) }
            .onDisappear { if model.tafOnScreen == tafID { model.tafOnScreen = nil } }
            .navigationDestination(isPresented: $openStarts) { StartsWhenView(tafID: tafID) }
            .navigationDestination(isPresented: $openResults) { ResultsGoToView(tafID: tafID) }
            .confirmationDialog(Words.Taf.revokeTitle(taf: "\(taf.name)"), isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button(Words.Taf.revokeAndStop, role: .destructive) { Task { await model.revoke(tafID) } }
            } message: {
                Text(Words.Taf.revokeBody)
            }
            .confirmationDialog(Words.Taf.removeTitle(taf: "\(taf.name)"), isPresented: $confirmRemove, titleVisibility: .visible) {
                Button(Words.Taf.Remove.button, role: .destructive) { Task { removal = await model.removeTaf(tafID) } }
            } message: {
                Text(Words.Taf.removeBody)
            }
            .alert(Words.Taf.removeNamed(taf: "\(taf.name)"), isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } })) {
                Button(Words.Common.ok, role: .cancel) {}
            } message: {
                Text(removal ?? "")
            }
        } else {
            LateNote(text: Words.Taf.gone).screenBackground()
        }
    }

    /// The Card in front of the stack: the one tapped, else the first, the live one.
    private func shownFront(_ taf: Taf) -> TafCard? {
        let cards = taf.shownCards
        return cards.first(where: { $0.id == frontCard }) ?? cards.first
    }

    /// One row: avatar, name and state, under the back button. The taf's tools aren't listed:
    /// what it may do is on its Card.
    private func header(_ taf: Taf) -> some View {
        HStack(spacing: 10) {
            TafAvatar(taf: taf, size: 34)
            Text(taf.name).scaledFont(20, weight: .bold, design: .rounded, relativeTo: .title2).foregroundColor(Theme.ink)
                .lineLimit(1)
                .accessibilityIdentifier("taf-page-name")
            Spacer(minLength: 8)
            StateChip(state: taf.state)
        }
    }

    /// Under the header, folded away while the page scrolls down: its version, and who it's
    /// shared with.
    @ViewBuilder
    private func headerLine(_ taf: Taf) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            versionLine(taf)
            // Who it's shared with, and Share: the taf's manager's to see (the 870 test).
            if model.manages(taf.id) { ShareLine(tafID: taf.id) }
        }
        .transition(.opacity)
    }

    /// Folds the header's second line going down, and brings it back going up.
    private func fold(_ down: Bool) {
        guard down != folded else { return }
        withMotion(.easeOut(duration: 0.2)) { folded = down }
    }

    /// The ways into its versions (kiff/tafs#173): the live one, which opens Versions, and an
    /// open trial, to resume.
    @ViewBuilder
    private func versionLine(_ taf: Taf) -> some View {
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil, model.manages(taf.id) {
            HStack(spacing: 10) {
                NavigationLink { VersionsScreen(tafID: taf.id) } label: {
                    let live = model.versions[taf.id]?.first(where: \.live)?.n
                    Text(live.map { Words.Version.liveNumber(version: "\($0)") } ?? Words.Version.title)
                        .scaledFont(13, weight: .semibold)
                }
                if let trial = model.openTrials[taf.id], let chat = model.trialChat(of: taf.id) {
                    OpenTrialLink(chatID: chat, tafID: taf.id) {
                        Text(Words.Taf.tryingResume(n: "\(trial.n)")).scaledFont(13, weight: .semibold).foregroundColor(Theme.amber)
                    }
                }
            }
            .task(id: taf.id) {
                await model.loadVersions(taf.id)
                await model.loadTrial(taf.id)
            }
        }
    }

    /// The Activity of a taf tafs cloud doesn't run (the sample's): what it waits for from the
    /// owner, then what it did, by day. A taf on tafs cloud draws `TafActivityList` instead, with
    /// its runs and its work (`TafDetailView.pane`).
    @ViewBuilder
    private func activity(_ taf: Taf) -> some View {
        // Beside it on a wide screen, in the second column.
        if !sideColumn {
            let held = model.heldCalls(for: taf.id)
            if !held.isEmpty {
                Eyebrow(text: Words.NeedsYou.title, color: Theme.amber)
                ForEach(held) { HeldCallCard(call: $0) }
            }
        }
        HStack(spacing: 6) {
            ForEach(ActivityFilter.allCases, id: \.self) { f in
                Button { activityFilter = f } label: {
                    Text(f.title).scaledFont(13)
                        .foregroundColor(activityFilter == f ? Theme.onFill : Theme.soft)
                        .padding(.horizontal, 11).padding(.vertical, 5)
                        .background(activityFilter == f ? Theme.ink : Theme.panel)
                        .overlay(Capsule().stroke(activityFilter == f ? Theme.ink : Theme.line))
                        .clipShape(Capsule())
                }
                .buttonStyle(.drivablePlain)
                .accessibilityAddTraits(activityFilter == f ? .isSelected : [])
            }
        }
        let entries = model.entries(for: taf.id).filter(activityFilter.keeps)
        if entries.isEmpty {
            Text(activityFilter == .all ? Words.Common.nothingYet : Words.Record.nothingHere).foregroundColor(Theme.dim)
        }
        let calendar = AccountTime.calendar
        let days = Dictionary(grouping: entries) { calendar.startOfDay(for: $0.at) }
        ForEach(days.keys.sorted(by: >), id: \.self) { day in
            let lines = (days[day] ?? []).sorted { $0.at > $1.at }
            Text([RecordView.dayTitle(day), RecordView.spent(lines)].compactMap { $0 }.joined(separator: " · "))
                .scaledFont(11, weight: .medium, design: .monospaced).textCase(.uppercase)
                .foregroundColor(Theme.dim).padding(.top, 6)
            ForEach(lines) { RecordRow(entry: $0) }
        }
    }

    /// Setup: how it runs. The job, what starts it, its connected apps, where results go,
    /// what it remembers, its versions, and removing it.
    @ViewBuilder
    private func setup(_ taf: Taf) -> some View {
        let cloud = model.cloudTaf(for: taf.id)
        let manages = model.manages(taf.id)
        let screenshot = Screenshots.enabled && model.mode == .sample
        ForEach(model.tafSetups[taf.id]?.card_unit_exceptions ?? []) { ask in CardUnitExceptionPanel(ask: ask) }
        Panel {
            Eyebrow(text: Words.Setup.job)
            Text(taf.job).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if model.mode == .kiff, manages, model.mayUseCoach, cloud != nil {
                Button(Words.Setup.improveInCoach) {
                    model.improveTaf(taf.id)
                    model.coachOpen = true
                }
                .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
            }
        }
        if (model.mode == .kiff && cloud != nil) || screenshot {
            PanelLink { StartsWhenView(tafID: taf.id) } label: {
                Label(StartsWhenView.title, systemImage: "clock").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                Spacer()
                Text(Words.Setup.`open`).scaledFont(14).foregroundColor(Theme.soft)
            }
            .accessibilityIdentifier("setup.startsWhen")
        }
        if model.mode == .kiff { ConnectedToolsRing(taf: taf) { activitySelection = $0; section = 1 } }
        else { ConnectionsPanel(tafID: taf.id) }
        if (model.mode == .kiff && cloud != nil) || screenshot {
            PanelLink { ResultsGoToView(tafID: taf.id) } label: {
                Label(ResultsGoToView.title, systemImage: "paperplane").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                Spacer()
                Text(Words.Setup.`open`).scaledFont(14).foregroundColor(Theme.soft)
            }
            .accessibilityIdentifier("setup.resultsGoTo")
        }
        // How it does the job: not part of its Card (kiff/tafs#96).
        if model.mode == .kiff, cloud != nil { MemorySummaryPanel(tafID: taf.id) }
        else if model.mode == .sample { MemoryPanel(tafID: taf.id) }
        if model.mode == .sample, taf.state != .revoked {
            Button(taf.state == .paused ? Words.Taf.resume : Words.Taf.pause) { model.togglePause(taf.id) }
                .buttonStyle(OutlineButtonStyle())
                .confirming()
        }
        if model.mode == .kiff, cloud != nil, manages {
            Button(Words.Setup.removeForGood) { confirmRemove = true }
                .buttonStyle(OutlineButtonStyle(color: Theme.no))
                .confirming()
        }
    }

    /// The second column on a wide screen: what waits for the owner, then the Card.
    private func side(_ taf: Taf) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            let held = model.heldCalls(for: taf.id)
            if !held.isEmpty {
                Eyebrow(text: Words.NeedsYou.title, color: Theme.amber)
                ForEach(held) { HeldCallCard(call: $0) }
            }
            let cards = taf.shownCards
            Eyebrow(text: Words.Card.heading(count: cards.count))
            CardStack(cards: cards, issuedBy: model.issuer(of: taf), color: Theme.tafColor(taf.colorIndex),
                      revoked: taf.card.revoked, front: $frontCard)
            Button(Words.Card.allOf(count: cards.count)) { section = 2 }
                .buttonStyle(OutlineButtonStyle())
        }
    }

    @ViewBuilder
    private func card(_ taf: Taf) -> some View {
        if model.mode == .kiff, let words = model.cloudTaf(for: taf.id)?.timeZoneWords { Text(words).scaledFont(13).foregroundColor(Theme.soft) }
        if model.mode == .kiff, let words = model.cloudTaf(for: taf.id)?.scheduleTimeZoneWords { Text(words).scaledFont(13).foregroundColor(Theme.amberText) }
        let cards = taf.shownCards
        let shown = cards.first(where: { $0.id == frontCard }) ?? cards.first
        CardStack(cards: cards, issuedBy: model.issuer(of: taf), color: Theme.tafColor(taf.colorIndex),
                  revoked: taf.card.revoked, front: $frontCard)
            .frame(maxWidth: .infinity)
        // All of the front Card's limits: the face shows the main one.
        CardLines(rules: shown?.rules ?? [])
        // Its totals for each item apart ("1 call in any 7 days for each invoice id"), as tafs cloud says them.
        ForEach(shown?.eachItem ?? [], id: \.self) { line in
            Text(line).scaledFont(14).foregroundColor(Theme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        // How KIFF counts each of its tools, as the owner approved it: its own screen.
        if let shown, let counted = shown.counted, !counted.isEmpty {
            PanelLink { CountedView(card: shown.title, counted: counted) } label: {
                Label(CountedView.title, systemImage: "list.bullet.rectangle")
                    .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
            }
            .accessibilityIdentifier("card-counted")
        }
        // A vendor's refusal still counts against the Card's totals (kiff/tafs#211, 6.13), in
        // KIFF's words for what's pending: for a live, active Card only, since a test Card's
        // totals aren't what the taf may do for real.
        ForEach(shown.flatMap { $0.countsForReal ? $0.pending : nil } ?? [], id: \.self) { line in
            Text(line)
                .scaledFont(13).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        }
        // Its total has no currency because a tool it grants isn't connected any more: KIFF's
        // words as they are, and, for whoever manages the taf, Coach to connect it again.
        if let words = shown?.unitWords {
            VStack(alignment: .leading, spacing: 4) {
                Text(words)
                    .scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                if Self.offersReconnect(manages: model.manages(taf.id), mayUseCoach: model.mayUseCoach, mode: model.mode) {
                    // A new Coach conversation on this taf, as Android's Improve in Coach.
                    Button(Self.reconnect) {
                        model.improveTaf(taf.id)
                        model.coachOpen = true
                    }
                        .scaledFont(13, weight: .semibold).foregroundColor(Theme.amberText)
                        .accessibilityIdentifier("card-reconnect")
                }
            }
            .accessibilityIdentifier("card-unit-words")
        }
        // Halting is a job of its own too: its row opens its screen.
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil, taf.state != .revoked, model.manages(taf.id) {
            HaltRow(tafID: taf.id, framed: true)
        }
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil, model.manages(taf.id) {
            PanelLink { VersionsScreen(tafID: taf.id) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Label(model.tafSetups[taf.id]?.versions.title ?? Words.Version.changesRow, systemImage: "square.stack.3d.up")
                        .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                    if let summary = model.tafSetups[taf.id]?.versions.summary { Text(summary).scaledFont(13).foregroundColor(Theme.soft) }
                }
            }
        }
        // Its Usage row opens its credits this period, its runs, as on Android.
        if model.mode == .kiff, model.manages(taf.id), let cloud = model.cloudTaf(for: taf.id) {
            PanelLink { TafCreditsView(taf: cloud.id, previous: false).environmentObject(model) } label: {
                Label(Words.Usage.label, systemImage: "chart.bar").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                Spacer()
            }
        }
        if taf.state != .revoked, model.mode == .sample || model.manages(taf.id) {
            Button(Words.Taf.revoke) { confirmRevoke = true }
                .buttonStyle(OutlineButtonStyle(color: Theme.no))
                .confirming()
        }
    }
}

/// A taf that isn't healthy, as tafs cloud says it (docs/reliability.md, "Its health"): each
/// line, amber when at risk and red when failing, with the fixes tafs cloud decided for it.
/// Coach's fix opens Coach on this taf with tafs cloud's words in the box, nothing sent, for
/// someone who may use Coach and manages the taf. The credits fix opens Plan & usage, where
/// credits are added or asked for. Healthy draws nothing.
struct HealthLines: View {
    @EnvironmentObject private var model: AppModel
    let health: TafsCloud.Health
    let tafID: UUID

    var body: some View {
        if health.needsAttention {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(health.lines.enumerated()), id: \.offset) { index, line in
                    Text(line).scaledFont(14).foregroundColor(health.failing ? Theme.no : Theme.amberText)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(health.fixes(for: index), id: \.self) { fix in
                        if let act = action(fix) {
                            Button(fix.words, action: act)
                                .buttonStyle(OutlineButtonStyle())
                                .accessibilityIdentifier("taf.health.fix.\(fix.kind)")
                        }
                    }
                }
            }
            .accessibilityIdentifier("taf.health")
        }
    }

    /// What a fix does here; nil draws no button.
    private func action(_ fix: TafsCloud.Health.Fix) -> (() -> Void)? {
        switch fix.kind {
        case "coach":
            guard let say = fix.say, !say.isEmpty, model.mayUseCoach, model.manages(tafID) else { return nil }
            return { model.askCoachAbout(tafID, words: say) }
        case "credits":
            return { model.usageRequested = true }
        default:
            return nil
        }
    }
}

/// A taf's runs on tafs cloud: when it runs next (or that it works from its chat), and Run now,
/// with tafs cloud's words when Run now started nothing. Drawn on a cloud taf's Activity.
struct RunsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// Set on the tap itself, before the request starts, so a second tap can't send one.
    @State private var runTapped = false

    var body: some View {
        if let cloud = model.cloudTaf(for: tafID) {
            let inFlight = runTapped || model.runsInFlight.contains(cloud.id)
            Panel {
                Eyebrow(text: Words.Taf.runs)
                if cloud.worksFromChat {
                    // Its tools can't find its work by themselves (kiff/tafs#138): no schedule.
                    Text(Words.Taf.worksFromChat)
                        .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                } else {
                    Text(cloud.paused ? Words.Taf.State.paused : cloud.nextRun.map { Words.Taf.nextRunAt(time: "\($0.shortTime)") } ?? Words.Taf.onItsSchedule)
                        .scaledFont(15).foregroundColor(Theme.ink)
                    Button(inFlight ? Words.Taf.startingRun : Words.Taf.runNow) {
                        guard !runTapped else { return }
                        runTapped = true
                        Task {
                            // The run it started shows among the runs it kept.
                            if await model.runNow(cloud) != nil { await model.loadRunPage(tafID) }
                            runTapped = false
                        }
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(inFlight)
                    // Run now started no run: tafs cloud's words, as they are.
                    if let words = model.runNowWords[cloud.id] {
                        Text(words).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }
}

/// Answer within, for whoever manages the taf: the promise as tafs cloud keeps it, or Not set,
/// and a menu of whole hours to change or clear it. The phones that answer its holds are told
/// when a case waits longer; tafs cloud sends that alert.
struct PromiseRow: View {
    let hours: Int?
    let pick: (Int?) -> Void

    /// What the row says the promise is: its time, or Not set.
    static func value(_ hours: Int?) -> String {
        hours.map(label) ?? Words.Work.answerWithinNone
    }

    /// A promise in words: whole days as days (24 h is "1 day", 168 h "7 days"), anything else in
    /// hours. tafs cloud gets hours all the same.
    static func label(_ hours: Int) -> String {
        hours >= 24 && hours % 24 == 0 ? Words.Work.answerWithinDays(count: hours / 24) : Words.Work.answerWithinHours(count: hours)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Menu {
                Button(Words.Work.answerWithinNone) { if hours != nil { pick(nil) } }
                ForEach(TafsCloud.TafWork.promiseChoices, id: \.self) { h in
                    Button(PromiseRow.label(h)) { if h != hours { pick(h) } }
                }
            } label: {
                HStack(spacing: 8) {
                    Text(Words.Work.answerWithin).scaledFont(15).foregroundColor(Theme.ink)
                    Spacer(minLength: 8)
                    Text(Self.value(hours)).scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                    Image(systemName: "chevron.up.chevron.down").scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityIdentifier("work.answer-within")
            Text(Words.Work.answerWithinLine).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Where the taf's work stands, as tafs cloud says it (docs/reliability.md): its line, then its
/// recent requests and skipped firings, newest first, each in tafs cloud's words. A request
/// needing the owner is amber, a failed one red; a skipped firing isn't a failure, so it's dim.
/// A door reply given up on offers Send again to whoever manages the taf. Under its health, its
/// oldest waiting case and, for whoever manages the taf, Answer within.
struct WorkPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID

    var body: some View {
        let work = model.works[tafID]
        let error = model.workErrors[tafID]
        Group {
            if work != nil || error != nil {
                Panel {
                    Eyebrow(text: Words.Taf.itsWork)
                    // Its health, read with the work, else as Home read it.
                    let health = work?.health ?? model.taf(tafID)?.health
                    // An idle taf's line is its first health line (docs/reliability.md): said once,
                    // with its colour and fix, below.
                    if let line = work?.summary?.line, !line.isEmpty, health?.repeats(workLine: line) != true {
                        Text(line).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    }
                    if let health {
                        HealthLines(health: health, tafID: tafID)
                    }
                    // Its oldest waiting case, in tafs cloud's words, amber once past the promise.
                    if let waiting = work?.oldestWaiting {
                        Text(verbatim: waiting.words)
                            .scaledFont(14, weight: waiting.late ? .semibold : .regular)
                            .foregroundColor(waiting.late ? Theme.amberText : Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("work.oldest-waiting")
                    }
                    if let work, model.mode == .kiff, model.manages(tafID) {
                        PromiseRow(hours: work.promiseHours) { hours in Task { await model.setPromise(tafID, hours: hours) } }
                    }
                    ForEach(work?.rows ?? []) { row in
                        switch row {
                        case .skipped(let s):
                            Text(s.words).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                                .fixedSize(horizontal: false, vertical: true)
                        case .request(let r):
                            request(r)
                        }
                    }
                    if let error {
                        Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .task(id: tafID) { await model.loadWork(tafID) }
    }

    private func request(_ r: TafsCloud.TafWork.Request) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            // A step the taf handed its owner, answered here too, in place of its line: Home's, which
            // carries the vendor's link when tafs cloud vouched for it, else the request's own (never a link).
            if let step = r.yourStep, model.canAnswer(step: step.handoff) {
                YourStepCard(step: model.yourStep(step.handoff) ?? step, onTaf: tafID)
            } else {
                Text(r.line).scaledFont(14)
                    .foregroundColor(r.state == "needs_you" ? Theme.amberText : r.state == "failed" ? Theme.no : Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(r.deliveries.enumerated()), id: \.offset) { _, d in
                Text(d.line).scaledFont(12, relativeTo: .caption).foregroundColor(d.sendAgain ? Theme.amberText : Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
                if d.sendAgain, model.manages(tafID) {
                    let sending = model.sendingAgain.contains(r.id)
                    Button(sending ? Words.Work.sendingAgain : Words.Work.sendAgain) { Task { await model.sendAgain(tafID, run: r.id) } }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(sending)
                }
            }
        }
    }
}

/// Setup's Connected apps: the vendors connected to the taf through planque (#51), whose keys,
/// which tools, what happened last, and Disconnect, which revokes the sign-in at the vendor.
/// One signed in with no actions chosen yet offers Choose actions (Coach); then Connect an
/// app…, for whoever manages the taf.
struct ConnectionsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var confirming: PlanqueClient.Grant?
    /// A sign-in that never came back, whose Cancel waits for the owner's yes.
    @State private var cancelling: PlanqueClient.Grant?

    var body: some View {
        let connections = model.connections(for: tafID)
        let manages = model.manages(tafID)
        // Nothing to show, nothing drawn: someone who only uses the taf sees no connections,
        // and an empty box read as something broken (the 870 test).
        if model.mode == .kiff, !connections.isEmpty || manages {
            Panel {
                Eyebrow(text: Words.Setup.connectedApps)
                ForEach(connections) { grant in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(KIFFMapping.vendorWords(grant.vendor)).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(grant.account.map { Words.Taf.keysKeptSealed + " · " + $0 } ?? Words.Taf.keysKeptSealed)
                                .scaledFont(12).foregroundColor(Theme.dim)
                            if grant.needsReconnect {
                                // Its vendor signed it out: planque's words, and the owner signs in again.
                                Text(grant.signedOutWords)
                                    .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                                    .fixedSize(horizontal: false, vertical: true)
                            } else if grant.connected {
                                // What it may do, in words, not the tools' ids (kiff/tafs#138, item 52).
                                Text(grant.request.tools.filter(\.tafs).map(\.words).joined(separator: " · "))
                                    .scaledFont(12).foregroundColor(Theme.soft)
                                if model.leftOver(grant, of: tafID) {
                                    // Left over after a version moved its Card (kiff/tafs#138). An older version may still
                                    // run on it, so the words don't say to remove it.
                                    Text(Words.Taf.notLiveVersionCardTaf)
                                        .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            } else if grant.waitingSignIn {
                                // Approved, and the sign-in never came back: cancelled, not disconnected.
                                Text(ConnectionFixWords.waiting(grant.vendor))
                                    .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                            } else {
                                // Signed in, not connected (contract 5): no tool is in KIFF yet.
                                Text(grant.listed ? Words.Taf.signedToolsBeingChosen : Words.Taf.signedToolsWaitingYourApproval)
                                    .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                                // None chosen yet: Coach chooses them with the owner.
                                if grant.listed, manages, model.mayUseCoach {
                                    Button(Words.Setup.chooseActions) {
                                        model.improveTaf(tafID)
                                        model.coachOpen = true
                                    }
                                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                                }
                                if let end = grant.heldUntilDate {
                                    Text(Words.Taf.unlessYouApproveToolsSign(time: "\(end.shown(date: .abbreviated, time: .shortened))"))
                                        .scaledFont(12).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            if let last = grant.events.last {
                                Text(last.what).scaledFont(12).foregroundColor(Theme.soft)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer(minLength: 0)
                        Button(grant.waitingSignIn ? ConnectionFixWords.cancel : Words.Connect.disconnectButton) {
                            if grant.waitingSignIn { cancelling = grant } else { confirming = grant }
                        }
                            .confirming()
                            .scaledFont(14, weight: .semibold)
                            .foregroundColor(Theme.no)
                    }
                    if grant.needsReconnect {
                        Button(model.connecting == grant.id ? model.connectStep ?? Words.Connect.openingSignIn : Words.Connect.signInAgain) {
                            Task { await model.reconnect(grant) }
                        }
                        .buttonStyle(OutlineButtonStyle(color: Theme.ink))
                        .disabled(model.connecting != nil)
                        if let why = model.reconnectErrors[grant.id] {
                            Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if manages {
                    NavigationLink(Words.Setup.connectApp) { ConnectAppsView().environmentObject(model) }
                        .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                }
            }
            .confirmationDialog(confirming.map { DisconnectWords.title($0.vendor, taf: model.tafOf($0), alsoStops: model.alsoStops($0)) } ?? "",
                                isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                                titleVisibility: .visible) {
                Button(Words.Connect.disconnectButton, role: .destructive) {
                    if let id = confirming?.id { Task { _ = await model.disconnect(grantID: id) } }
                }
            } message: {
                if let g = confirming { Text(DisconnectWords.message(g.vendor, taf: model.tafOf(g), alsoStops: model.alsoStops(g))) }
            }
            .confirmationDialog(cancelling.map { ConnectionFixWords.cancelTitle($0.vendor) } ?? "",
                                isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
                                titleVisibility: .visible) {
                Button(Words.Connect.CancelSignIn.button, role: .destructive) {
                    if let id = cancelling?.id { Task { _ = await model.disconnect(grantID: id) } }
                }
                Button(Words.Connect.CancelSignIn.keepWaiting, role: .cancel) { cancelling = nil }
            } message: {
                if let g = cancelling { Text(ConnectionFixWords.cancelBody(g.vendor)) }
            }
        }
    }
}

/// A taf's versions, on their own screen.
struct VersionsScreen: View {
    let tafID: UUID

    var body: some View {
        ScrollView {
            VersionsPanel(tafID: tafID).padding(.horizontal, 16).padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Words.Version.title)
        .inlineTitle()
    }
}

/// The taf's versions, like app builds (kiff/tafs#107): newest first, the live one marked.
/// Try it opens a trial of a version beside the live one, under a Card of its own
/// (kiff/tafs#173); Install this version makes it live, forward or back, with one Face ID.
struct VersionsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// The version whose Card the owner reads before Face ID.
    @State private var reading: TafsCloud.Version?
    /// The version whose trial the owner is about to start.
    @State private var starting: TafsCloud.Version?
    /// The trial just started: its chat opens (kiff/tafs#247).
    @State private var opened: UUID?
    @Environment(\.sidebarSelects) private var sidebarSelects

    /// Install this version: the switch begins, with the Card draft tafs cloud prepared (its own,
    /// drafted again, or a restore when going back), which is read first, now → after
    /// (kiff/tafs#107, #122). With no Card to change, one Face ID makes it live.
    private func use(_ v: TafsCloud.Version, redraft: Bool = false, stopBlocking: Bool = false) {
        Task {
            guard let sw = await model.prepareSwitch(v, of: tafID, redraft: redraft, stopBlocking: stopBlocking) else { return }
            if sw.draft == nil { await model.useVersion(v, of: tafID) } else { reading = v }
        }
    }

    var body: some View {
        Panel {
            Eyebrow(text: Words.Version.title)
            Text(Words.Taf.tryVersionFirstYouChat)
                .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            if let sw = model.switches[tafID] { switchUnderWay(sw) }
            if let n = model.staleVersionCards[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                Button(Words.Version.redraft) { use(v, redraft: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
            if let list = model.versions[tafID] {
                if list.isEmpty {
                    Text(Words.Version.firstOnly)
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                ForEach(list.reversed()) { row($0) }
            } else if model.versionErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.versionErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
            if let n = model.blockedInstalls[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                // One step: what's in the way stops (a held call withdrawn, nothing sent), then
                // the install begins (kiff/tafs#206).
                Button(Words.Version.stopAndInstall) { use(v, stopBlocking: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
            if let error = model.trialErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
        }
        .task(id: tafID) {
            await model.loadVersions(tafID)
            await model.loadTrial(tafID)
        }
        .sheet(item: $reading) { v in VersionCardSheet(version: v, tafID: tafID) }
        // Started: its chat opens; beside a sidebar, by selecting its row (kiff/tafs#253).
        .sheet(item: $starting) { v in
            TrialStartSheet(version: v, tafID: tafID) { chat in
                if sidebarSelects || model.sidebarShown { model.requestedTaf = chat } else { opened = chat }
            }
        }
        .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) {
            if let chat = opened { TrialScreen(chatID: chat, tafID: tafID) }
        }
        // Terms that changed between the screen and the tap open again, to read anew.
        .onChange(of: model.changedVersionCards[tafID]) { changed in
            if changed != nil, reading == nil, let v = model.versions[tafID]?.first(where: { model.switchDraft($0, of: tafID) == changed?.id }) { reading = v }
        }
    }

    private func row(_ v: TafsCloud.Version) -> some View {
        let switching = model.switchingVersion[tafID]
        let trial = model.openTrials[tafID]
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(Words.Version.number(version: "\(v.n)")).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                if v.live {
                    Text(Words.Version.liveNow).scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.ok).clipShape(Capsule())
                } else if trial?.n == v.n {
                    Text(Words.Version.trying).scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.amber).clipShape(Capsule())
                }
                Spacer(minLength: 0)
            }
            if let note = v.note, !note.isEmpty {
                Text(note).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            Text(Self.byline(v)).scaledFont(12).foregroundColor(Theme.dim)
            // What each ended trial came to, the one line kept once its chat is gone.
            ForEach(Array((v.trials ?? []).enumerated()), id: \.offset) { _, t in
                Text(Self.tried(t)).scaledFont(12).foregroundColor(Theme.soft)
            }
            HStack(spacing: 8) {
                if !v.live {
                    if trial?.n == v.n, let chat = model.trialChat(of: tafID) {
                        OpenTrialLink(chatID: chat, tafID: tafID) {
                            Text(Words.Version.resumeTrial)
                        }
                        .buttonStyle(OutlineButtonStyle())
                        .confirming()
                    } else {
                        // One trial per taf: another waits for the open one to end.
                        Button(Words.Version.tryIt) { starting = v }
                            .buttonStyle(OutlineButtonStyle())
                            .disabled(trial != nil || model.trialBusy.contains(tafID))
                    }
                    Button(switching == v.n ? Words.Version.installing : Words.Version.installThis) {
                        use(v)
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .confirming()
                    // One switch at a time, and none while a trial is open: Install ends the trial.
                    .disabled(switching != nil || trial != nil || model.switches[tafID].map { $0.n != v.n } ?? false)
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// One ended trial, in a line: "Tried 21 Oct · 3 calls, 1 held · went back".
    static func tried(_ t: TafsCloud.TrialSummary) -> String {
        let counts = [Words.Version.Tried.calls(count: t.calls),
                      t.held > 0 ? Words.Version.Tried.held(count: t.held) : nil,
                      t.refused > 0 ? Words.Version.Tried.refused(count: t.refused) : nil].compactMap { $0 }.joined(separator: ", ")
        let how: String
        switch t.why {
        case "installed": how = Words.Version.Tried.installed
        case "back": how = Words.Version.Tried.wentBack
        case "idle": how = Words.Version.Tried.endedUnused
        default: how = Words.Version.Tried.ended
        }
        return [Words.Version.Tried.on(date: t.started.formatted(date: .abbreviated, time: .omitted)), counts, how].joined(separator: " · ")
    }

    /// A switch that didn't finish: the taf doesn't run until it's finished or cancelled.
    private func switchUnderWay(_ sw: TafsCloud.Switching) -> some View {
        let version = model.versions[tafID]?.first { $0.n == sw.n }
        let working = model.switchingVersion[tafID] != nil
        return VStack(alignment: .leading, spacing: 6) {
            Text(Words.Version.installingNumber(version: "\(sw.n)")).scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
            Text(Words.Version.doesntRunUntil)
                .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                if let version {
                    Button(working ? Words.Version.installing : Words.Version.finish) { use(version) }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(working)
                }
                Button(Words.Common.cancel) { Task { await model.cancelSwitch(tafID) } }
                    .buttonStyle(OutlineButtonStyle(color: Theme.no))
                    .disabled(working)
            }
        }
        .padding(.vertical, 4)
    }

    /// Who made it, in tafs cloud's words, when, and whether it brings a new Card. A version
    /// kiff/agents wrote itself (the first, or an Upgrade's) names no one.
    static func byline(_ v: TafsCloud.Version) -> String {
        return [v.byWords, v.created.shown(date: .abbreviated, time: .shortened), v.draft == nil ? nil : Words.Version.bringsNewCard]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// The Card a version brings, read before Face ID (kiff/tafs#107): each Card it revises as
/// it is now and as it will be, and each new one. Install this version signs exactly what this
/// sheet shows (kiff/tafs#115).
struct VersionCardSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let version: TafsCloud.Version
    let tafID: UUID
    /// Coach's held install this answers (kiff/tafs#206): approved with the same Face ID,
    /// once the version is live.
    var approving: HeldCall? = nil
    @State private var draft: KIFFClient.AgentDraft?
    @State private var loaded = false

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(version.note.map { Words.Version.numberNote(version: "\(version.n)", note: $0) } ?? Words.Version.number(version: "\(version.n)"))
                        .scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    if let draft, draft.cards != nil {
                        DraftCardsView(draft: draft)
                        Text(draft.retires?.isEmpty == false
                             ? Words.Version.CardNote.removesBiometry(biometry: "\(Biometry.name)")
                             : Words.Version.CardNote.issuesBiometry(biometry: "\(Biometry.name)"))
                            .scaledFont(12).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        ApprovalNotice()
                    } else if loaded {
                        Text(model.versionErrors[tafID] ?? Words.Version.cardUnreadable)
                            .scaledFont(13).foregroundColor(Theme.no)
                    } else {
                        ProgressView().tint(Theme.amber)
                    }
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle(Words.Card.itsCard)
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Not now: the switch it began is dropped, and the taf runs as it was.
                    Button(Words.Common.cancel) {
                        dismiss()
                        Task { await model.cancelSwitch(tafID) }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Words.Version.installThis) {
                        let shown = draft?.version
                        dismiss()
                        Task { await model.useVersion(version, of: tafID, shownDraft: shown, approving: approving) }
                    }
                    .confirming()
                    .disabled(draft?.cards == nil || model.approvalBlocked)
                }
            }
        }
        // The switch is begun while this is open: Cancel or Use ends it, never a swipe
        // that would leave the taf not running with nothing on screen to finish it.
        .interactiveDismissDisabled()
        .task {
            // The terms that changed since last shown, when that's why this opened.
            if let changed = model.changedVersionCards[tafID], changed.id == model.switchDraft(version, of: tafID) { draft = changed }
            else { draft = await model.versionCard(version, of: tafID) }
            loaded = true
        }
    }
}

/// The Cards a draft brings, as the owner reads them before Face ID: each Card it revises
/// as it is now and as it will be, each new one, and each it removes.
struct DraftCardsView: View {
    let draft: KIFFClient.AgentDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            let lines = draft.cardLines
            ForEach(Array((draft.cards ?? []).enumerated()), id: \.offset) { i, card in cardView(card, lines: lines[i]) }
            ForEach(draft.retires ?? [], id: \.id) { retiredView($0) }
        }
    }

    /// A Card this version doesn't have: confirming removes it, so what it allows stops.
    private func retiredView(_ card: KIFFClient.AgentDraft.Retired) -> some View {
        Panel {
            Eyebrow(text: Words.Version.removesCard)
            ForEach(card.current?.lines ?? [], id: \.self) {
                Text($0).strikethrough().scaledFont(14).foregroundColor(Theme.soft)
            }
            Text(Words.Version.cantAnyMore).scaledFont(12).foregroundColor(Theme.dim)
        }
    }

    @ViewBuilder
    /// One Card of the version, with its lines as the draft reads them (`cardLines`).
    private func cardView(_ card: KIFFClient.DraftCard, lines: [String]) -> some View {
        Panel {
            if card.isUnchanged {
                // Re-issued as it is (kiff/tafs#138): its terms once, not a now and after.
                Eyebrow(text: Words.Version.unchanged)
                ForEach(lines, id: \.self) { Text($0).scaledFont(14).foregroundColor(Theme.soft) }
            } else if let current = card.current {
                Eyebrow(text: Words.Version.changesCard)
                Text(Words.Version.now).scaledFont(12, weight: .semibold).foregroundColor(Theme.dim)
                ForEach(current.lines, id: \.self) { Text($0).scaledFont(14).foregroundColor(Theme.soft) }
                Text(Words.Version.after).scaledFont(12, weight: .semibold).foregroundColor(Theme.dim)
            } else {
                Eyebrow(text: Words.Version.newCard)
            }
            if !card.isUnchanged {
                ForEach(lines, id: \.self) { Text($0).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink) }
            }
        }
    }
}

/// What the taf remembers between its runs and chats, each note with a Forget button.
struct MemoryPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    var loadAll = false
    /// The note waiting for the owner's yes: forgetting can't be undone (the 755 retest).
    @State private var forgetting: TafsCloud.Note?

    var body: some View {
        Panel {
            Eyebrow(text: Words.Taf.Memory.title)
            if let notes = model.memories[tafID] {
                if notes.isEmpty {
                    Text(Words.Taf.Memory.empty)
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                ForEach(notes) { note in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(note.content).scaledFont(15).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("\(note.title) · \(note.updatedAt.shortTime)").scaledFont(12).foregroundColor(Theme.soft)
                        }
                        Spacer(minLength: 0)
                        Button(Words.Taf.Memory.forget) { forgetting = note }
                            .confirming()
                            .scaledFont(14, weight: .semibold)
                            .foregroundColor(Theme.soft)
                            .frame(minHeight: 44)
                            .accessibilityLabel(Words.Taf.Memory.forgetLabel(note: "\(note.content)"))
                    }
                }
                Text(Words.Taf.Memory.explains)
                    .scaledFont(12).foregroundColor(Theme.soft)
            } else if model.memoryErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.memoryErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
        }
        .task(id: tafID) { if loadAll { await model.loadAllMemory(tafID) } else { await model.loadMemory(tafID) } }
        .confirmationDialog(Words.Taf.Memory.forgetTitle, isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }),
                            titleVisibility: .visible, presenting: forgetting) { note in
            Button(Words.Taf.Memory.forget, role: .destructive) { Task { _ = await model.forget(note, from: tafID) } }
            Button(Words.Common.cancel, role: .cancel) {}
        } message: { note in
            Text(Words.Taf.Memory.forgetBody(note: note.content))
        }
    }
}

/// A Card drawn from its lines, as one Card: a draft's, before KIFF issued it.
struct CardFace: View {
    let name: String
    let rules: [CardRule]
    let issuedBy: String
    let expires: Date?
    let color: Color
    /// What each rule has used this window, as KIFF says: the face shows what's left.
    var used: [String: Money] = [:]
    var revoked = false

    var body: some View {
        TafCardFace(card: TafCard.merged(id: "draft", title: name, rules: rules, expires: expires, active: !revoked, used: used),
                    issuedBy: issuedBy, color: color, revoked: revoked)
    }
}

/// A Card drawn as a card (kiff/tafs#211): a payment card's shape at a set size, never the
/// full width; what's left is the big figure, the other limits small lines under it.
struct TafCardFace: View {
    let card: TafCard
    let issuedBy: String
    let color: Color
    var revoked = false
    @Environment(\.dynamicTypeSize) private var textSize

    /// At most this wide, on the iPhone and the iPad alike.
    static let width: CGFloat = 300
    /// A payment card's proportions, 85.6 by 54 mm.
    static let ratio: CGFloat = 85.6 / 54

    /// A live Card in the taf's colour; one scoped to another environment in grey.
    static func fill(_ card: TafCard, color: Color) -> Color { card.live ? color : Theme.dim }

    var body: some View {
        face
            // At the accessibility text sizes the Card grows to its text, never clipping it
            // (kiff/tafs#210); otherwise it keeps a payment card's shape.
            .aspectRatio(textSize.isAccessibilitySize ? nil : Self.ratio, contentMode: .fit)
            .fixedSize(horizontal: false, vertical: textSize.isAccessibilitySize)
            .background(Self.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
            .overlay(alignment: .topTrailing) {
                Circle().stroke(Color.white.opacity(0.18)).frame(width: 130, height: 130).offset(x: 35, y: -45)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .frame(maxWidth: Self.width)
            // VoiceOver reads its balance first (kiff/tafs#210).
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spoken(card, issuedBy: issuedBy, revoked: revoked))
    }

    /// The Card as VoiceOver reads it: what's left, then its name, its limits, who issued it
    /// and until when.
    static func spoken(_ card: TafCard, issuedBy: String, revoked: Bool) -> String {
        var parts: [String] = []
        if let hero = card.hero { parts.append("\(hero.figure) \(hero.words)") }
        parts.append([card.title, card.environmentLabel].compactMap { $0 }.joined(separator: ", "))
        parts += card.facts
        if card.rules.isEmpty { parts.append(Words.Card.noActions) }
        parts.append(Words.Card.issuedByNamed(who: "\(issuedBy)"))
        let end = card.expires?.formatted(date: .abbreviated, time: .omitted)
        parts.append(revoked ? Words.Card.revoked : !card.active ? end.map { Words.Card.endedOn(date: $0) } ?? Words.Card.ended : end.map { Words.Card.untilDate(date: "\($0)") } ?? Words.Card.noEndDate)
        return parts.map { $0.trimmingCharacters(in: .whitespaces) }.joined(separator: ". ") + "."
    }

    private var face: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(card.title).scaledFont(12, weight: .semibold, design: .monospaced).oneLineUnlessLarge()
                Spacer(minLength: 4)
                if let environment = card.environmentLabel { EnvironmentPill(text: environment) }
            }
            Spacer(minLength: 0)
            if let hero = card.hero {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hero.figure).scaledFont(34, weight: .bold, relativeTo: .title).monospacedDigit()
                        .oneLineUnlessLarge().minimumScaleFactor(0.6)
                    Text(hero.words).scaledFont(12, weight: .medium).opacity(0.9).oneLineUnlessLarge()
                }
            }
            ForEach(card.facts, id: \.self) { fact in
                Text(fact).scaledFont(11, design: .monospaced).opacity(0.92).oneLineUnlessLarge().minimumScaleFactor(0.8)
            }
            if card.rules.isEmpty { Text(Words.Card.noActionsShort).scaledFont(12, design: .monospaced) }
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(Words.Card.issuedBy).scaledFont(10).opacity(0.75)
                    Text(issuedBy).scaledFont(12, weight: .semibold).oneLineUnlessLarge()
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    // A Card with no end says so, never a far-off date (kiff/tafs#96).
                    Text(revoked ? Words.Card.revoked : !card.active ? Words.Card.ended : card.expires == nil ? Words.Card.ends : Words.Card.until)
                        .scaledFont(10).opacity(0.75)
                    Text(card.expires?.shown(date: .abbreviated, time: .omitted) ?? Words.Card.noEndDate)
                        .scaledFont(12, weight: .semibold)
                }
            }
        }
        .foregroundColor(.white)
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: textSize.isAccessibilitySize ? nil : .infinity, alignment: .topLeading)
    }
}

/// A taf's Cards in one line (kiff/tafs#211): the front Card's balance, its name, its
/// environment and how many Cards there are. Tapping it opens the Card tab.
struct CardBar: View {
    let card: TafCard
    let count: Int
    let color: Color
    var revoked = false
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(TafCardFace.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
                    .frame(width: 46, height: 29)
                VStack(alignment: .leading, spacing: 1) {
                    Text(card.hero.map { "\($0.figure) \($0.words)" } ?? card.title)
                        .scaledFont(15, weight: .semibold).monospacedDigit().foregroundColor(Theme.ink)
                        .oneLineUnlessLarge().minimumScaleFactor(0.8)
                    Text(details).scaledFont(12).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.drivablePlain)
        .accessibilityHint(Words.Taf.showsCards)
    }

    /// "Refunds · 3 Cards": the Card's name, its environment when it isn't production, and the
    /// others.
    private var details: String {
        var parts = [card.title]
        if let environment = card.environmentLabel { parts.append(environment.capitalized) }
        if count > 1 { parts.append(Words.Card.count(count: count)) }
        return parts.joined(separator: " · ")
    }
}

/// Every action a Card covers, with its limits and what happens outside them: what the
/// owner reads in full on the Card tab and wherever they sign for a Card, since the face
/// shows only its main limit (kiff/tafs#211).
struct CardLines: View {
    let rules: [CardRule]

    var body: some View {
        Panel {
            ForEach(rules) { rule in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(rule.outside == .hold ? Words.Card.outsideHeld : Words.Card.outsideRefused)
                            .scaledFont(12).foregroundColor(Theme.dim)
                    }
                    Spacer()
                    Text(rule.summary).scaledFont(13, design: .monospaced)
                        .foregroundColor(rule.never ? Theme.no : Theme.ink)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
    }
}

/// The environment a Card is scoped to, when it isn't production.
struct EnvironmentPill: View {
    let text: String
    var body: some View {
        Text(text).tracking(0.6).scaledFont(10, weight: .semibold, design: .monospaced)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.white.opacity(0.22)).clipShape(Capsule())
    }
}

/// A taf's Cards as a wallet stack (kiff/tafs#211): the Card in front whole, the others
/// behind it showing their top edge, each a shade darker, so their number and names read at once. Tapping one
/// brings it forward.
struct CardStack: View {
    let cards: [TafCard]
    let issuedBy: String
    let color: Color
    var revoked = false
    /// The Card in front; nil for the first, the live one.
    @Binding var front: String?

    /// How much of a Card behind shows.
    private static let edge: CGFloat = 40

    /// How much darker a Card `depth` places behind the front one is drawn: none in front,
    /// then deeper, never hiding its edge.
    static func shade(depth: Int) -> Double { depth <= 0 ? 0 : min(0.12 + 0.08 * Double(depth - 1), 0.32) }

    var body: some View {
        let shown = cards.first(where: { $0.id == front }) ?? cards.first
        // The furthest back on top, as in a wallet: the reverse of the order they're listed in.
        let behind = cards.filter { $0.id != shown?.id }.reversed()
        let ordered = Array(behind) + (shown.map { [$0] } ?? [])
        WalletLayout {
            ForEach(Array(ordered.enumerated()), id: \.element.id) { index, card in
                Button {
                    withMotion(.spring(response: 0.35, dampingFraction: 0.85)) { front = card.id }
                } label: {
                    TafCardFace(card: card, issuedBy: issuedBy, color: color, revoked: revoked)
                        // Each Card behind a shade darker, so Cards of the same colour read as separate.
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(Color.black.opacity(Self.shade(depth: ordered.count - 1 - index))))
                        .shadow(color: .black.opacity(0.16), radius: 4, y: -2)
                }.buttonStyle(.drivablePlain).accessibilityLabel(Words.Taf.showCard(title: "\(card.title)"))
            }
        }
        .frame(maxWidth: TafCardFace.width)
    }
}

/// Card › How this is counted: for each tool the Card covers with a profile the owner
/// approved, KIFF's words for how it's counted, the same lines approved, under the tool's
/// title. The app adds no words of its own.
struct CountedView: View {
    static let title = Words.Card.howCounted
    let card: String
    let counted: [CardCounted]

    var body: some View {
        List {
            ForEach(counted, id: \.tool) { tool in
                Section(tool.title) {
                    ForEach(Array(tool.lines.enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .navigationTitle(Self.title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("counted.\(card)")
    }
}

/// Activity's filter of a taf's record: All, Done, Held, Refused.
enum ActivityFilter: CaseIterable {
    case all, done, held, refused

    var title: String {
        switch self {
        case .all: return Words.Record.Filter.all
        case .done: return Words.Taf.Halt.partHeld
        case .held: return Words.Record.Filter.held
        case .refused: return Words.Record.Filter.refused
        }
    }

    func keeps(_ e: RecordEntry) -> Bool {
        switch self {
        case .all: return true
        case .done: return e.kind == .done || e.kind == .approved
        case .held: return e.kind == .held
        case .refused: return e.kind == .refused || e.kind == .notDone || e.kind == .revoked
        }
    }
}

/// Measures full card faces, including larger text, then exposes a band of each back card.
private struct WalletLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let widths = subviews.map { $0.sizeThatFits(proposal).width }
        let heights = subviews.enumerated().map { CGFloat($0.offset) * 26 + $0.element.sizeThatFits(proposal).height }
        return CGSize(width: widths.max() ?? 0, height: heights.max() ?? 0)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (index, view) in subviews.enumerated() {
            view.place(at: CGPoint(x: bounds.minX, y: bounds.minY + CGFloat(index) * 26), anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: nil))
        }
    }
}
