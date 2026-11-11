import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    @State private var showingBuyCredits = false
    @State private var showingSolo = false
    @State private var showingExport = false
    @State private var openingFirst = false
    /// The calendar of the tafs' scheduled work (kiff/tafs#534): the phone has no Overview, so it opens from the bar.
    @State private var showingCalendar = false
    /// Halt, opened from a halted taf's chip: the account's when the account is halted (that's
    /// what resumes it), else the taf's.
    @State private var showingHalt = false
    @State private var haltTaf: UUID?
    /// A recipe's sheet, opened from Home with no taf.
    @State private var openRecipe: TafsCloud.Recipes.Recipe?

    private func openHalt(_ tafID: UUID) {
        haltTaf = model.haltState(nil).state == .running ? tafID : nil
        showingHalt = true
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Eyebrow(text: Words.Home.tagline)
                headline
                // KIFF can't be reached just now: said quietly, under the account's name.
                if let words = model.unreachable { UnreachableLine(words: words, retrying: model.retryingRefresh) }
                if model.approvalKeyLost { ApprovalKeyLostLine() }
                // The plan, only when it needs the owner: a trial ended or ending, tasks nearly gone.
                PlanLine()

                if let first = model.held.first, let taf = model.taf(first.tafID) {
                    Button { tab = .needsYou } label: { needsYouStrip(first, taf) }
                        .buttonStyle(.drivablePlain)
                }

                // Theirs, then the ones shared with them (kiff/tafs#306).
                let sections = model.tafSections
                VStack(spacing: 10) {
                    ForEach(sections.own) { taf in tafLinks(taf) }
                }
                if !sections.shared.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: Words.Home.sharedYou)
                        ForEach(sections.shared) { taf in tafLinks(taf) }
                    }
                }

                if !model.otherAgents.isEmpty {
                    // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: Words.Home.otherAgents)
                        ForEach(model.otherAgents) { taf in
                            NavigationLink(value: taf.id) { TafRow(taf: taf, onHalted: { openHalt(taf.id) }) }
                                .buttonStyle(.drivablePlain)
                                .driveWords(taf.name)
                        }
                    }
                }

                if model.mode == .kiff {
                    // Connecting tools is Coach mode's (kiff/tafs#138).
                    Button { model.openCoach() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus").scaledFont(16, weight: .bold).foregroundColor(.white)
                                .frame(width: 36, height: 36).background(Theme.brand).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Words.Home.connectTool).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                                Text(Words.Home.signApproveEachStep).scaledFont(13).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        }
                        .padding(14)
                        .background(Theme.panel)
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                    .buttonStyle(.drivablePlain)
                }

                if model.mode == .kiff, !model.cloudTafs.isEmpty, model.canManageCards {
                    // Every taf at once, from its own screen.
                    HaltRow(tafID: nil, framed: true)
                }

                ForEach(model.unfinishedRemovals) { r in
                    Panel {
                        Text(Words.Home.notFullyRemoved(name: "\(r.name)")).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(Words.Home.noLongerRunsTafsDeletes)
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                }

                if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                    // tafs cloud says what Home shows with no taf: the recipes under its line, or
                    // its empty line (docs/ios-app.md).
                    if let recipes = model.recipes, recipes.homeListsRecipes {
                        HomeRecipes(recipes: recipes) { openRecipe = $0 }
                    } else {
                        Panel {
                            if let words = model.recipes?.homeWords {
                                // tafs cloud's empty line, whoever reads it: no words of the app's.
                                Text(words).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            } else {
                                // Not read yet, or a tafs cloud from before the line: the app's own.
                                Text(Words.Home.noTafs).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                                // Setting one up is Coach's: someone who may not use it reads where shared tafs show.
                                Text(model.mayUseCoach ? Words.Home.Empty.coach : Words.Home.Empty.shared)
                                    .scaledFont(14).foregroundColor(Theme.soft)
                            }
                        }
                    }
                }

                if model.mode == .sample {
                    Button(Words.Sample.playNext) { model.playNextDemoActions() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.soft))
                    // The sample has no form for a new taf (as on Android): one starts in Coach, signed in.
                    Text(Words.Home.sampleFoot)
                        .scaledFont(12)
                        .foregroundColor(Theme.dim)
                } else {
                    Text(Words.Home.footer)
                        .scaledFont(12)
                        .foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .refreshable { await model.refresh() }
        .screenBackground()
        .navigationDestination(isPresented: $showingCalendar) { CalendarView() }
        .navigationDestination(isPresented: $showingHalt) { HaltScreen(tafID: haltTaf) }
        .sheet(item: $openRecipe) { recipe in
            // A recipe no longer listed (the account has a taf now) shows no sheet's worth: the
            // sheet draws what was tapped, and handing it over closes it.
            RecipeSheet(recipe: recipe, recipes: model.recipes ?? TafsCloud.Recipes(), canHand: model.mayUseCoach) {
                openRecipe = nil
                model.handRecipeToCoach(recipe)
            }
        }
        .navigationDestination(for: UUID.self) { id in
            if let tafID = model.tafOfTrialChat(id) { TrialScreen(chatID: id, tafID: tafID) } else { TafDetailView(tafID: id) }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                // The account this is, always in sight (the 870 test); its settings are the
                // bar's Account.
                HStack(spacing: 8) {
                    Mascot(size: 34)
                    if let account = model.accountLabel {
                        Text(account).scaledFont(13, weight: .semibold).foregroundColor(Theme.soft).lineLimit(1)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.mode == .kiff, !model.tafs.isEmpty {
                    Button { showingCalendar = true } label: {
                        Image(systemName: "calendar").scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                            .frame(width: 36, height: 36)
                    }
                    .accessibilityLabel(Words.Calendar.heading)
                    .accessibilityIdentifier("home.calendar")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.canStartTafs {
                    // A new taf starts in Coach mode, a conversation (kiff/tafs#138, item 59).
                    Button { model.startNewTaf() } label: {
                        Image(systemName: "plus")
                            .scaledFont(17, weight: .bold)
                            .foregroundColor(Theme.amberInk)
                            .frame(width: 36, height: 36)
                            .background(Theme.brand)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel(Words.Home.startNewTaf)
                }
            }
        }
        // The purchase screens, for their screenshots (Screenshots.screen).
        .sheet(isPresented: $showingBuyCredits) { BuyCreditsView().environmentObject(model) }
        .sheet(isPresented: $showingSolo) { NavStack { SoloView() }.environmentObject(model) }
        // Export my chats, for its UI test: the sample has no KIFF account to open.
        .sheet(isPresented: $showingExport) {
            NavStack { List { ExportChatsSection() }.navigationTitle(Words.Settings.title) }.environmentObject(model)
        }
        .navigationDestination(isPresented: $openingFirst) {
            if let id = model.tafs.first(where: { $0.name == TafTemplates.refunds.name })?.id ?? model.tafs.first?.id {
                TafDetailView(tafID: id)
            }
        }
        .task(id: model.tafs.count) {
            guard Screenshots.enabled, model.tafs.count >= TafTemplates.all.count else { return }
            try? await Task.sleep(nanoseconds: 500_000_000)
            switch Screenshots.screen {
            case "taf", "chat", "card", "setup", "starts", "results": openingFirst = true
            case "buy-credits": showingBuyCredits = true
            case "solo": showingSolo = true
            case "export": showingExport = true
            default: break
            }
        }
    }

    /// A taf in the list, and its open trial under it (kiff/tafs#173).
    @ViewBuilder private func tafLinks(_ taf: Taf) -> some View {
        NavigationLink(value: taf.id) { TafRow(taf: taf, onHalted: { openHalt(taf.id) }) }
            .buttonStyle(.drivablePlain)
            .driveWords(taf.name)
        if let chat = model.trialChat(of: taf.id), let trying = model.taf(chat) {
            NavigationLink(value: chat) { TafRow(taf: trying) }
                .buttonStyle(.drivablePlain)
                .driveWords(trying.name)
        }
    }

    private var headline: some View {
        let working = model.workingCount
        return VStack(alignment: .leading, spacing: 0) {
            if model.loadingList {
                // Never "0 tafs working" before the tafs are read (#91).
                Text(Words.Home.loading)
                    .foregroundColor(Theme.dim)
            } else if model.listUnread {
                Text(Words.Home.notLoaded)
                    .foregroundColor(Theme.dim)
            } else if !model.homeListsRecipes {
                // With no taf and the recipes listed, tafs cloud's line leads: no "0 tafs
                // working" above them (as on Android).
                Text(Words.Home.working(count: working))
                    .foregroundColor(Theme.ink)
                Text(model.needsYouWords)
                    .foregroundColor(Theme.dim)
            }
        }
        .scaledFont(36, weight: .bold, design: .rounded, relativeTo: .title)
    }

    private func needsYouStrip(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 12) {
            Circle().fill(Theme.amber).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)")
                    .scaledFont(14, weight: .semibold)
                    .foregroundColor(Theme.ink)
                    .oneLineUnlessLarge()
                Text(Words.Home.reasonAnswerBy(why: "\(call.reason)", time: "\(call.expiresAt.zonedShortTime)"))
                    .scaledFont(13)
                    .foregroundColor(Theme.soft)
            }
            Spacer()
            Text(Words.NeedsYou.answer).scaledFont(14, weight: .medium).foregroundColor(Theme.amberText)
        }
        .padding(14)
        .background(Theme.amber.opacity(0.1))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.amber.opacity(0.4)))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct TafRow: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    /// A halted taf's chip opens Halt, where it's resumed ("Halted · resume it from Halt");
    /// the rest of the row opens the taf.
    var onHalted: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                TafAvatar(taf: taf)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        if let badge = taf.badge {
                        Text(badge).scaledFont(11, weight: .bold).foregroundColor(.white)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(Theme.amber).clipShape(Capsule())
                    }
                    }
                    // A connection its vendor signed out says so, in planque's words, in place of
                    // what it's doing: it waits on the owner.
                    if let out = model.signedOut(taf.id) {
                        Text(out.signedOutWords).scaledFont(13).foregroundColor(Theme.amberText).oneLineUnlessLarge()
                    } else if let health = taf.health, health.needsAttention, let line = health.lines.first {
                        // Not healthy: tafs cloud's first line says why, in place of what it's doing.
                        Text(line).scaledFont(13).foregroundColor(health.failing ? Theme.no : Theme.amberText).oneLineUnlessLarge()
                    } else {
                        Text(taf.now).scaledFont(13).foregroundColor(Theme.dim).oneLineUnlessLarge()
                    }
                }
                Spacer(minLength: 8)
                if taf.state == .halted, let onHalted {
                    // Its own tap, over the row's: a borderless button takes it inside the link.
                    Button(action: onHalted) { StateChip(state: taf.state) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(Words.Home.haltedOpenHalt)
                        .accessibilityIdentifier("taf.halted.\(taf.name)")
                } else {
                    StateChip(state: taf.state)
                }
            }
            if let rule = taf.card.rules.first(where: { $0.perWindow != nil }), let limit = rule.perWindow {
                let used = taf.used[rule.action] ?? .zero
                // KIFF couldn't read the use: say so, never show an empty meter.
                let unknown = taf.usageUnknown?.contains(rule.action) == true
                VStack(spacing: 6) {
                    HStack {
                        Text("\(rule.label) · \(limit.formatted) \(rule.window.label)")
                        Spacer()
                        Text(unknown ? Words.Usage.useUnavailable : used.formatted).scaledFont(12, design: .monospaced)
                    }
                    .scaledFont(12)
                    .foregroundColor(Theme.dim)
                    if !unknown {
                        Meter(fraction: Double(used.minor) / Double(max(limit.minor, 1)), color: Theme.tafColor(taf.colorIndex))
                    }
                }
            }
        }
        .padding(16)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("taf-row")
    }
}
