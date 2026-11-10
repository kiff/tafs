import SwiftUI
import UIKit

/// The setup chat (kiff/tafs#50): the owner tells Setup what to connect, and Setup shows
/// its plan, asks, and hands over approvals the owner signs on this phone. A component
/// grants nothing: an approval shows planque's own text and KIFF's draft, and Face ID
/// signs there.
struct SetupChatView: View {
    @EnvironmentObject private var model: AppModel
    /// The taf to improve (kiff/tafs#107): the chat opens a new conversation on it.
    var improving: UUID? = nil
    /// Whether this screen has opened its improvement: once, not each time it appears.
    @State private var openedImprovement = false
    @FocusState private var typing: Bool
    /// When the chat opened: Setup's messages after it are revealed as they arrive.
    @State private var openedAt = Date()
    @State private var revealed: Set<String> = []
    /// Counts each step of a reveal, so the thread keeps the growing answer in view.
    @State private var grown = 0
    /// Whether the bottom of the thread shows, and whether the thread follows what arrives.
    @State private var atBottom = true
    @State private var following = true
    /// Something arrived below while the owner read back: the ↓ button shows a dot.
    @State private var unseen = false
    /// The cards on screen, so the "Needs you" bar shows only when the waiting one isn't.
    @State private var cardsInView: Set<String> = []
    /// Whether the chat has landed where it opens: the waiting card, or the latest.
    @State private var landed = false
    /// As in ChatGPT and Claude (#73): the owner's sent message moves to the top and stays,
    /// and the answer grows below it without the view following, so they never scroll
    /// back up to its start. `pinNext` marks the next message the owner sends.
    @State private var pinNext = false
    @State private var pinned: String?
    /// The words of the pinned message: when tafs cloud's copy takes the place of the one
    /// shown on sending, the pin moves to it, and the view doesn't move (kiff/tafs#206).
    @State private var pinnedWords: String?
    /// The owner has scrolled since the chat opened: a late landing leaves them where they are.
    @State private var touched = false
    /// What the owner just sent, while it waits behind Setup's answer: its bubble is pinned,
    /// then the thread's copy, without the view moving (kiff/tafs#138).
    @State private var pinText: String?
    /// The thread's ids when the owner sent it: the pin goes only to a message that wasn't
    /// there, so the same words sent again never pin the older copy (kiff/tafs#138, item 34).
    @State private var sentBefore: Set<String> = []
    @State private var viewport: CGFloat = 600
    /// The pinned message's turn, as laid out: the room under it is the screen less this (the 845 test).
    @State private var turn = PinnedTurn()
    /// The list of recent conversations, to reopen or archive one (#73, #82).
    @State private var showingChats = false
    /// New conversation on a chat that's already empty: the thread shows its top.
    @State private var toTop = 0
    /// The answer of Coach's being reported, from its menu (kiff/tafs#421).
    @State private var reporting: ReportedAnswer?
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The screen's width, so the "Taking shape" panel shows only where it fits beside the chat.
    @State private var width: CGFloat = 0

    /// A wide screen (kiff/tafs#85): the conversation, and beside it what it has made so far.
    /// A compact width, the iPhone's or an iPad's narrow split, keeps the chat alone.
    private var shaping: Bool {
        sizeClass == .regular && width >= 780 && model.setupTaf != nil && model.planqueReady
    }

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                if model.setupTaf == nil || !model.planqueReady {
                    intro
                } else {
                    // Beside the chat, the plan is in the panel.
                    if !shaping, let plan = model.setupThread.plan { SetupPlan(shown: plan).padding(.horizontal, 12).padding(.top, 8) }
                    thread
                    if let error = model.setupError {
                        Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 4)
                    }
                    composer
                    DictationBar(dictation: model.dictation)
                }
            }
            if shaping {
                Rectangle().fill(Theme.line).frame(width: 1).ignoresSafeArea()
                SetupShapePanel().frame(width: 420)
            }
        }
        .background(GeometryReader { g in
            Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { width = $0 }
        })
        // Coach mode's own wash (kiff/tafs#138): never a taf's chat.
        .background(Theme.coachWash.ignoresSafeArea())
        .navigationTitle(Words.Coach.title)
        .inlineTitle()
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button(action: newConversation) { Image(systemName: "square.and.pencil") }.accessibilityLabel("New conversation")
                Menu { Button("Previous conversations") { showingChats = true } } label: { Image(systemName: "ellipsis") }.accessibilityLabel("Coach's conversations")
            }
        }
        .setupChrome(wide: sizeClass == .regular)
        .sheet(item: $reporting) { ReportAnswerSheet(answer: $0).environmentObject(model) }
        .sheet(isPresented: $showingChats) {
            SetupChatsList { session in
                showingChats = false
                landed = false
                pinned = nil
                Task { await model.openSetupChat(session) }
            }
            .environmentObject(model)
        }
        .task {
            // Opened on a taf to improve it: a new conversation on that taf, once.
            if let improving, !openedImprovement {
                openedImprovement = true
                model.improveTaf(improving)
            }
            await model.followSetup()
        }
    }

    private func newConversation() {
        Haptics.tap(); pinned = nil
        if model.coachChatIsEmpty { toTop += 1 }
        else { landed = true; following = true; model.newCoachConversation() }
        typing = true
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 14) {
            Spacer()
            Text("Coach mode").scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.coach)
            Text("Name a tool a taf should work with, like your payment provider. You sign in on the tool's own page, and your sign-in is kept sealed: no taf ever sees it. Each connection and each Card waits for \(Biometry.name).")
                .scaledFont(16).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
            if model.tafs.isEmpty {
                // Before the first taf (kiff/tafs#423): finishing it takes approvals.
                Text("Approving is needed to finish your first taf: Coach asks you to approve the tool it connects and the taf's Card on this device.")
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let error = model.setupError ?? model.lastError {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
            }
            Spacer()
            ApprovalGate {
                Button(model.startingSetup ? Words.Common.starting : Words.Coach.start) { Task { _ = await model.startSetup() } }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.startingSetup || model.mode != .kiff)
            }
            Text("Starting asks for \(Biometry.name) once.").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                .frame(maxWidth: .infinity)
        }
        .padding(20)
    }

    private var thread: some View {
        let items = textBeforeCards(model.setupThread.items)
        let thinking = model.setupThinking
        let waiting = model.setupWaiting
        return ScrollViewReader { proxy in
            ScrollView {
                // Not lazy (the 845 test): a lazy stack only estimates rows it hasn't drawn, so
                // a pinned message landed short and a return from another app showed blank rows.
                VStack(alignment: .leading, spacing: 12) {
                    Color.clear.frame(height: 0).id(Self.top)
                    if items.isEmpty && !thinking && model.setupUnsent.isEmpty { starts }
                    // The answer streaming now starts at the first item it recorded: its work block
                    // goes just before it, under what started it, so it folds in place to "Worked
                    // for" when the answer ends, as in a taf's chat (the 870 test).
                    let firstOfRun = model.setupLive == nil ? nil : items.firstIndex { item in
                        if case .message(let m) = item, m.from == .owner { return false }
                        return !model.setupLiveBefore.contains(item.id)
                    }
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        if i == firstOfRun, let live = model.setupLive {
                            LiveWork(live: live).id("work")
                        }
                        // A time between parts of the thread that are far apart, not on every line.
                        if let gap = gapTime(items, i) {
                            Text(gap).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity).padding(.top, 6)
                        }
                        Group {
                            switch item {
                            case .message(let m) where m.from == .owner:
                                VStack(alignment: .trailing, spacing: 4) {
                                    ChatBubble(message: TafsCloud.ChatMessage(at: m.at, from: .owner, kind: m.kind,
                                                                              text: model.setupThread.ownerWords(m.text), session: m.session,
                                                                              attachments: m.attachments), taf: nil)
                                    // Written while a card waited: these words were its answer (`answers`).
                                    if m.answers != nil { AnsweredCardLine() }
                                }
                            case .message(let m):
                                // Streamed answers show as written; only without a stream are they revealed.
                                VStack(alignment: .leading, spacing: 6) {
                                    if let work = m.work { WorkSummary(work: work, coach: true) }
                                    RevealingBubble(message: m, reveal: revealing(m), revealed: $revealed, grown: $grown)
                                        .reportable(m, in: nil, model: model, reporting: $reporting)
                                }
                            case .component(let c):
                                SetupComponentView(shown: c)
                                    .onAppear { cardsInView.insert(c.ref) }
                                    .onDisappear { cardsInView.remove(c.ref) }
                            case .drive(let d):
                                // What Coach did while it drove the app (docs/drive.md), where the drive ended.
                                DriveRecapCard(recap: d)
                            }
                        }
                        .id(item.id)
                        .pinnedTop(item.id == pinned)
                    }
                    // What ended while the owner was away, in one message, a line each (kiff/tafs#210).
                    let away = model.setupThread.coachLines.filter { $0.away == true }
                    if !away.isEmpty {
                        WhileYouWereAway(lines: away).id("while-away")
                    }
                    // How the tasks Coach handed tafs went, until Coach reads them (kiff/tafs#206).
                    ForEach(model.setupThread.coachLines.filter { $0.away != true }) { line in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Image(systemName: "arrow.turn.down.right").scaledFont(11, relativeTo: .caption).foregroundColor(Theme.dim)
                            Text("\(line.taf): \(line.said)").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id("line|" + line.id)
                    }
                    ForEach(model.setupUnsent) { unsent in
                        UnsentBubble(message: unsent, words: model.setupThread.ownerWords(unsent.text), busy: model.setupSending,
                                     retry: { await model.retrySetup(unsent) }, discard: { model.discardUnsent(unsent) },
                                     edit: { if let words = model.editUnsent(unsent) { model.setupDraft = words } })
                            .id(unsent.id.uuidString)
                            .pinnedTop(unsent.id.uuidString == pinned)
                    }
                    // What Coach is doing and thinking, and the steps it took (kiff/tafs#285), in one
                    // block under the owner's message, with the answer streaming below it (the 845
                    // test); the dots while nothing streams yet.
                    if let live = model.setupLive {
                        if firstOfRun == nil { LiveWork(live: live).id("work") }
                    } else if thinking {
                        TypingBubble(taf: nil, fullWidth: true).id("thinking")
                    }
                    if !model.setupLiveText.isEmpty {
                        ChatBubble(message: TafsCloud.ChatMessage(at: Date(), from: .taf, text: model.setupLiveText, session: "live"), taf: nil, fullWidth: true, steady: true).id("live")
                    }
                    if !thinking { ended }
                    // Written to Coach's run while it goes, not read yet, as kiff/agents says (docs/chat.md).
                    if let steering = model.setupThread.steering, !steering.over {
                        ForEach(Array(steering.untaken.enumerated()), id: \.offset) { i, m in
                            VStack(alignment: .trailing, spacing: 4) {
                                ChatBubble(message: TafsCloud.ChatMessage(at: m.at, from: .owner, text: m.text, session: "steered"), taf: nil)
                                    .opacity(0.8)
                                Text("Coach reads this " + (steering.status == "PAUSED" ? "when it carries on" : "at its next step"))
                                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                            }
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .accessibilityElement(children: .combine)
                            .id("steer|\(i)")
                            .pinnedTop("steer|\(i)" == pinned)
                        }
                    }
                    // tafs cloud holds it, and sends it once Coach is done (docs/chat.md).
                    if let queued = model.setupQueued { QueuedBubble(text: queued, who: "this answer").id("queued").pinnedTop(pinned == "queued") }
                    Color.clear.frame(height: 0).threadFoot()
                    // Room under a pinned message, so a short one reaches the top at once (kiff/tafs#243):
                    // the screen less the turn it starts (the 845 test), so the answer grows into it
                    // and nothing moves.
                    if pinned != nil { Color.clear.frame(height: turn.room(viewport)) }
                    Color.clear.frame(height: 1).id(Self.bottom)
                        .onAppear {
                            // A pinned message stays at the top: seeing the bottom under it doesn't make
                            // the thread follow what arrives (kiff/tafs#206).
                            atBottom = true
                            if pinned == nil { following = true }
                            unseen = false
                        }
                        .onDisappear { atBottom = false }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .coordinateSpace(name: PinnedTurn.space)
                .onPreferenceChange(PinnedTopKey.self) { turn.top = $0 }
                .onPreferenceChange(ThreadFootKey.self) { turn.foot = $0 }
            }
            .dismissesKeyboardInteractively()
            .background(GeometryReader { g in
                Color.clear.onAppear { viewport = g.size.height }.onChange(of: g.size.height) { viewport = $0 }
            })
            // The owner scrolling stops the thread following what arrives; reaching the
            // bottom again, or the button, starts it.
            .simultaneousGesture(DragGesture().onChanged { _ in following = false; touched = true })
            .overlay(alignment: .bottom) {
                VStack(spacing: 8) {
                    // The card that needs the owner, when it's out of view (#58).
                    if let waiting, !cardsInView.contains(waiting.ref) {
                        NeedsYouBar(shown: waiting) {
                            following = false
                            withMotion(.easeOut(duration: 0.25)) { proxy.scrollTo("c|" + waiting.ref, anchor: .center) }
                        }
                    }
                    if !atBottom && !following {
                        Button {
                            following = true
                            unseen = false
                            pinned = nil
                            withMotion(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                        } label: {
                            Image(systemName: "arrow.down")
                                .scaledFont(15, weight: .bold).foregroundColor(Theme.ink)
                                .frame(width: 38, height: 38)
                                .background(Theme.panel)
                                .overlay(Circle().stroke(Theme.line))
                                .clipShape(Circle())
                                .shadow(color: Theme.ink.opacity(0.12), radius: 6, y: 2)
                                // Something arrived below while the owner read back.
                                .overlay(alignment: .topTrailing) {
                                    if unseen { Circle().fill(Theme.amber).frame(width: 10, height: 10).offset(x: 1, y: -1) }
                                }
                        }
                        .accessibilityLabel(unseen ? "Jump to the latest, something new" : Words.Chat.jumpToLatest)
                    }
                }
                .padding(.bottom, 10)
            }
            .onChange(of: items.map(\.id)) { ids in
                // The thread as it is now: the captured `items` can be the body's before the
                // change, without the message just sent (the 845 test, as in TafChatView).
                let items = textBeforeCards(model.setupThread.items)
                if let p = pinned, p.hasPrefix("m|"), !ids.contains(p) {
                    if let words = pinnedWords,
                       let copy = items.last(where: { if case .message(let m) = $0 { return m.from == .owner && SetupThread.matchesSent(m.text, words) } else { return false } })?.id {
                        pinned = copy
                    } else {
                        // Gone, with nothing in its place: to the latest, not past the end, blank
                        // (the 859 test, as in TafChatView).
                        pinned = nil
                        following = true
                        withMotion(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                        return
                    }
                }
                // The message the owner just sent is in the thread: it goes to the top and stays
                // (kiff/tafs#138). Matched by its words among the messages that are new since the send, so
                // neither a reply arriving first nor an older copy of the same words is pinned. If its bubble was already pinned while the taf (or Setup) worked,
                // the pin moves to it without animating, so the view doesn't move.
                if let text = pinText, let sent = items.last(where: { if case .message(let m) = $0 { return m.from == .owner && !sentBefore.contains($0.id) && SetupThread.matchesSent(m.text, text) } else { return false } })?.id {
                    let animate = pinNext
                    pinNext = false
                    pinnedWords = text
                    pinText = nil
                    pinned = sent
                    following = false
                    touched = false
                    proxy.pinToTop(sent, animated: animate) { touched }
                    return
                }
                // The thread changed under a pinned message: back at the top unless the owner has
                // scrolled, so the view never ends past the thread's end (the 859 test).
                if let pinned {
                    if !touched { proxy.pinToTop(pinned, animated: false) { touched } }
                    return
                }
                if !following && !ids.isEmpty { unseen = true }
                land(proxy, waiting: waiting)
                follow(proxy)
            }
            .onChange(of: thinking) { _ in follow(proxy) }
            .onChange(of: toTop) { _ in withMotion(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.top, anchor: .top) } }
            .onChange(of: grown) { _ in if following && pinned == nil { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onChange(of: model.setupLive) { _ in if following && pinned == nil { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            // A card's answer starts Coach's next answer: the card goes to the top and stays,
            // with the work and the answer under it, as a typed message does (the 870 test).
            .onChange(of: model.setupAnsweredCard) { card in
                guard let card, items.contains(where: { $0.id == "c|" + card }) else { return }
                pinned = "c|" + card
                pinText = nil
                following = false
                touched = false
                proxy.pinToTop("c|" + card, animated: true) { touched }
            }
            .onChange(of: model.setupQueued) { queued in
                // Waiting behind Setup's answer: its bubble goes to the top at once.
                if pinNext, let text = pinText, let queued, SetupThread.matchesSent(queued, text) {
                    pinNext = false
                    pinned = "queued"
                    touched = false
                    proxy.pinToTop("queued", animated: true) { touched }
                }
            }
            .onChange(of: model.setupUnsent) { unsent in
                // Not sent: its bubble, with Retry, is the one at the top.
                if pinNext, let last = unsent.last {
                    pinNext = false
                    pinText = nil
                    pinned = last.id.uuidString
                    touched = false
                    proxy.pinToTop(last.id, animated: true) { touched }
                    return
                }
                follow(proxy)
            }
            .onAppear { land(proxy, waiting: waiting) }
        }
    }

    private static let bottom = "bottom"
    private static let top = "top"

    private func follow(_ proxy: ScrollViewProxy) {
        guard following, pinned == nil else { return }
        withMotion(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    /// Opening the chat lands at the card waiting for the owner, or at the latest (#58).
    private func land(_ proxy: ScrollViewProxy, waiting: ShownComponent?) {
        // Only once the thread is in: landing on an empty chat would count as landed.
        guard !landed, !model.setupThread.items.isEmpty else { return }
        landed = true
        if waiting != nil { following = false }
        let (target, anchor): (String, UnitPoint) = waiting.map { ("c|" + $0.ref, .center) } ?? (Self.bottom, .bottom)
        // Lazy rows aren't laid out on the first pass after the app comes back, so one
        // scroll lands nowhere (kiff/tafs#138): it lands again once they are, unless the
        // owner has scrolled meanwhile.
        proxy.scrollTo(target, anchor: anchor)
        for delay in [0.05, 0.3] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                if !touched { proxy.scrollTo(target, anchor: anchor) }
            }
        }
    }

    /// The time to show above item `i`: the first, and any after an hour's quiet.
    /// A message of Setup's still being revealed, letter by letter, when there's no stream.
    private func revealing(_ m: TafsCloud.ChatMessage) -> Bool {
        !model.setupStreamWorks && m.from == .taf && m.at > openedAt && !revealed.contains(m.id)
    }

    /// Text first, then its card (kiff/tafs#138): while Setup's reply is still being
    /// written, or its message still revealed, the cards that come with it wait, so the
    /// question or approval never shows above words still arriving.
    private func textBeforeCards(_ items: [SetupThread.Item]) -> [SetupThread.Item] {
        let streaming = !model.setupLiveText.isEmpty
        let lastOwner = items.last { if case .message(let m) = $0 { return m.from == .owner } else { return false } }?.at
        var holding = false
        return items.filter { item in
            switch item {
            case .message(let m):
                if m.from == .owner { holding = false } else if revealing(m) { holding = true }
                // An answer to a card the thread shows reads once: the card folds to what was
                // asked and the answer, so no bubble repeats it (kiff/tafs#138, item 38). One
                // that didn't go through keeps its bubble, which says so.
                if m.from == .owner, let a = SetupThread.Answer(m.text), !a.failed,
                   model.setupThread.components.contains(where: { $0.id == a.id }) {
                    return false
                }
                return true
            case .component(let c):
                if holding { return false }
                if streaming, c.firstAt > (lastOwner ?? .distantPast) { return false }
                return true
            case .drive:
                return true
            }
        }
    }

    private func gapTime(_ items: [SetupThread.Item], _ i: Int) -> String? {
        let at = items[i].at
        if i > 0, at.timeIntervalSince(items[i - 1].at) < 3600 { return nil }
        return AccountTime.calendar.isDateInToday(at) ? at.shortTime : at.shown(date: .abbreviated, time: .shortened)
    }

    /// The empty chat: the question it answers (#82), what Setup does, and ways to start (#58).
    private var starts: some View {
        VStack(alignment: .leading, spacing: 12) {
            startSwitch
            if let taf = model.setupImproving {
                Text("Improve \(taf.name)")
                    .scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("Say what should change, or ask how it's going. Coach mode starts from what \(taf.name) did and what its Card allows.")
                    .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            } else {
                Text(Words.Coach.askAnything)
                    .scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text(Words.Coach.askAnythingLine)
                    .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            if model.setupImproving == nil {
                // tafs cloud's recipes, by category (kiff/tafs#476): a tap puts the words in the
                // message box, to edit and send; it never sends.
                ForEach(model.recipes?.byCategory ?? [], id: \.category.id) { group in
                    Text(group.category.title.uppercased())
                        .scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundColor(Theme.dim)
                        .padding(.top, 6)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(group.recipes) { recipe in recipeRow(recipe) }
                }
            }
            ForEach(model.setupImproving == nil ? [] : Self.improveExamples, id: \.self) { example in
                Button {
                    Haptics.tap()
                    pinNext = true
                    Task { await model.submitSetup(example) }
                } label: {
                    Text(example).scaledFont(15).foregroundColor(Theme.ink).multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(Theme.panel)
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .disabled(model.setupSending)
            }
        }
        .padding(.vertical, 16)
    }

    /// What this conversation is about, as a choice at its top (kiff/tafs#138, item 56): a new
    /// taf, or the taf on screen (or the one being improved). A link under the text read as one
    /// of the suggestions below it. Shown only when there's a taf to choose.
    @ViewBuilder private var startSwitch: some View {
        let improving = model.setupImproving
        let onScreen = model.tafOnScreen.flatMap { id in model.cloudTaf(for: id).map { (id, $0) } }
        if let name = improving?.name ?? onScreen?.1.name {
            HStack(spacing: 0) {
                startChoice("New taf", selected: improving == nil) { model.newSetupChat() }
                startChoice("Improve \(name)", selected: improving != nil) {
                    if let id = onScreen?.0 { model.improveTaf(id) }
                }
            }
            .padding(3)
            .background(Theme.panel)
            .overlay(Capsule().stroke(Theme.line))
            .clipShape(Capsule())
            .accessibilityElement(children: .contain)
            .accessibilityLabel("What this conversation is about")
        }
    }

    private func startChoice(_ title: String, selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button {
            guard !selected else { return }
            Haptics.tap()
            action()
        } label: {
            Text(title).scaledFont(14, weight: .semibold).oneLineUnlessLarge()
                .foregroundColor(selected ? Theme.onFill : Theme.coach)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(selected ? Theme.coach : Color.clear)
                .clipShape(Capsule())
        }
        .buttonStyle(.drivablePlain)
        .disabled(model.setupSending)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// One recipe, as tafs cloud words it: its title, and its line under it.
    private func recipeRow(_ recipe: TafsCloud.Recipes.Recipe) -> some View {
        Button {
            Haptics.tap()
            model.fillRecipe(recipe)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(recipe.title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                    .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if !recipe.line.isEmpty {
                    Text(recipe.line).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                        .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.drivablePlain)
        .disabled(model.setupSending)
        .accessibilityHint("Puts its words in the message box")
    }

    /// Ways to start improving a taf (kiff/tafs#107).
    static let improveExamples = [
        Words.Coach.Improve.howIsItGoing,
        Words.Coach.Improve.whyRefused,
        Words.Coach.Improve.cantFindWork,
    ]

    /// How the last run ended, when the owner should know (#58): they stopped it, or it
    /// failed part-way, with Retry.
    @ViewBuilder private var ended: some View {
        switch model.setupEnded {
        case .stopped?:
            Label(Words.Taf.State.stopped, systemImage: "stop.circle")
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        case .failed?:
            HStack(spacing: 10) {
                Text("This stopped part-way, before it finished.")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button(Words.Chat.retry) { Task { await model.retrySetupRun() } }
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    .disabled(model.setupSending)
            }
            .padding(12)
            .background(Theme.no.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        case nil:
            EmptyView()
        }
    }

    private var composer: some View {
        let key = AppModel.setupFiles
        let ready = model.files(key).contains { $0.ref != nil }
        let uploading = model.filesUploading(key)
        let empty = model.setupDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !ready
        let thinking = model.setupThinking
        // Files go with a message of their own, once Setup is done; and nothing goes while a file
        // is still going up, so a message never leaves one behind (kiff/tafs#209).
        let held = model.holdsSend(key, thinking: thinking)
        return VStack(spacing: 0) {
        FileTray(key: key)
        VStack(alignment: .leading, spacing: 4) {
            ChatField(title: thinking ? Words.Chat.writeNext : Words.Coach.askAnything, text: $model.setupDraft, cursor: { model.dictation.cursor = $0 })
                .focused($typing)
                .scaledFont(16)
                .padding(.horizontal, 14).padding(.vertical, 10)
            HStack(alignment: .center, spacing: 10) {
                if model.filesTarget(key) != nil { AttachButton(key: key) }
                Spacer()
            // Speak instead of typing (kiff/tafs#208): the words wait in the box, unsent.
            MicButton(dictation: model.dictation, text: $model.setupDraft)
            if thinking && empty {
                // While Setup works, Send is Stop: its run is cancelled.
                Button { Task { await model.stopSetup() } } label: {
                    Image(systemName: "stop.fill")
                        .scaledFont(14, weight: .bold)
                        .foregroundColor(Theme.onFill)
                        .frame(width: 40, height: 40)
                        .background(Theme.ink)
                        .clipShape(Circle())
                }
                .accessibilityLabel(Words.Setup.stop)
                .shortcut(.stop)
            } else {
                // While Setup works, what's written waits and is sent once it's done.
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .scaledFont(16, weight: .bold)
                        .foregroundColor(Theme.onFill)
                        .frame(width: 40, height: 40)
                        .background(Theme.coach)
                        .clipShape(Circle())
                        .opacity(empty || held ? 0.4 : 1)
                }
                .disabled(empty || held)
                .shortcut(.send)
                .accessibilityLabel(held && uploading ? "Send once the files are up" : thinking ? "Send when this answer is done" : Words.Common.send)
            }
        }
            }.padding(.horizontal, 8).padding(.bottom, 8)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 12).padding(.vertical, 10)
        }
        .barBackground()
        // Leaving the chat stops dictating, so its words never land in another chat's box.
        .onDisappear { model.dictation.stop() }
        // Ready to type (#82): the keyboard shows when the chat opens. Just after the
        // push, since a field focused while the view still slides in isn't. Not over a
        // card that waits for the owner: that card is what they came back to answer.
        .task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            if model.setupWaiting == nil { typing = true }
        }
        // A card that comes to wait once the thread loads takes the keyboard down,
        // unless the owner has started writing.
        .onChange(of: model.setupWaiting?.id) { id in
            if id != nil, model.setupDraft.isEmpty { typing = false }
        }
    }

    private func send() {
        // Sending stops dictating: what was heard goes with it, as it stands in the box.
        model.dictation.stop()
        let text = model.setupDraft
        model.setupDraft = ""
        // A sent message goes to the top of the view and stays (kiff/tafs#138), even while
        // Setup works: the message waits there, and the answer fills the space below.
        pinNext = true
        // As the model keeps it: trimmed (review on #154).
        pinText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        sentBefore = Set(model.setupThread.items.map(\.id))
        following = false
        // The keyboard goes down once it's sent (kiff/tafs#138): the answer gets the screen.
        typing = false
        Haptics.tap()
        let key = AppModel.setupFiles
        let files = model.takeFiles(key)
        Task {
            if !(await model.submitSetup(text, files: files.compactMap(\.ref))) {
                if model.setupDraft.isEmpty { model.setupDraft = text }
                model.putBackFiles(files, key)
            }
        }
    }
}

/// Setup's recent conversations, newest first, each by what the owner asked (#73, #82):
/// one tap reopens it, and messages go on in it. Swiping left archives it, on tafs cloud,
/// so it stays out of the list on every phone, once the owner confirms (#97); Archived,
/// at the end, lists them to reopen or put back.
struct SetupChatsList: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let open: (String) -> Void
    /// Why the last archive didn't go through.
    @State private var notArchived: String?
    /// The conversation waiting for the owner's yes to archive it.
    @State private var confirming: TafsCloud.Chat?

    var body: some View {
        NavStack {
            List {
                if let notArchived {
                    Text(notArchived).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                        .listRowBackground(Color.clear)
                }
                ForEach(model.setupChats) { chat in
                    Button { open(chat.session) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(chat.title).scaledFont(15).foregroundColor(Theme.ink).lineLimit(2)
                            Text(chat.updated.shown(date: .abbreviated, time: .shortened))
                                .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                        }
                    }
                    .listRowBackground(model.setupChat == chat.session ? Theme.raised : Theme.panel)
                    .swipeActions(edge: .trailing) {
                        Button { confirming = chat } label: { Label(Words.Coach.Chats.archive, systemImage: "archivebox") }
                            .tint(Theme.soft)
                    }
                }
                NavigationLink {
                    ArchivedSetupChats(open: open)
                } label: {
                    Label(Words.Coach.Chats.archived, systemImage: "archivebox").scaledFont(15).foregroundColor(Theme.ink)
                }
                .listRowBackground(Theme.panel)
            }
            .confirmationDialog("Archive this conversation?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                                titleVisibility: .visible, presenting: confirming) { chat in
                Button(Words.Coach.Chats.archive) { archive(chat.session) }
                Button(Words.Common.cancel, role: .cancel) {}
            } message: { _ in
                Text(Words.Coach.Chats.archiveBody)
            }
            .overlay {
                if model.setupChats.isEmpty {
                    Text(Words.Coach.Chats.none).scaledFont(14).foregroundColor(Theme.dim)
                }
            }
            .hiddenScrollBackground()
            .screenBackground()
            .navigationTitle(Words.Coach.Chats.previous)
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Record.Filter.done) { dismiss() }.keyboardShortcut(.cancelAction) } }
            .task { await model.loadSetupChats() }
        }
    }

    private func archive(_ session: String) {
        Haptics.tap()
        notArchived = nil
        Task {
            if !(await model.archiveSetupChat(session)) {
                // Said here, under the sheet the app's alert would wait behind.
                notArchived = model.lastError ?? "Not archived. Try again."
                model.lastError = nil
            }
        }
    }
}

/// Setup's archived conversations (#97): one tap opens it, Put back returns it to the list.
struct ArchivedSetupChats: View {
    @EnvironmentObject private var model: AppModel
    let open: (String) -> Void
    @State private var notPutBack: String?

    var body: some View {
        List {
            if let notPutBack {
                Text(notPutBack).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                    .listRowBackground(Color.clear)
            }
            ForEach(model.archivedSetupChats) { chat in
                Button { open(chat.session) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(chat.title).scaledFont(15).foregroundColor(Theme.ink).lineLimit(2)
                        Text(chat.updated.shown(date: .abbreviated, time: .shortened))
                            .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    }
                }
                .listRowBackground(Theme.panel)
                .swipeActions(edge: .trailing) {
                    Button { putBack(chat.session) } label: { Label(Words.Coach.Chats.putBack, systemImage: "tray.and.arrow.up") }
                        .tint(Theme.soft)
                }
            }
        }
        .overlay {
            if model.archivedSetupChats.isEmpty {
                Text(Words.Coach.Chats.nothingArchived).scaledFont(14).foregroundColor(Theme.dim)
            }
        }
        .hiddenScrollBackground()
        .screenBackground()
        .navigationTitle(Words.Coach.Chats.archived)
        .inlineTitle()
        .task { await model.loadArchivedSetupChats() }
    }

    private func putBack(_ session: String) {
        Haptics.tap()
        notPutBack = nil
        Task {
            if !(await model.unarchiveSetupChat(session)) {
                notPutBack = model.lastError ?? "Not put back. Try again."
                model.lastError = nil
            }
        }
    }
}

/// A message that didn't go through, kept where it was written (#58, kiff/tafs#476):
/// "Not sent" and why, then Edit (back to the composer) and Retry (sent once). Setup's chat
/// and a taf's share it, in the words Android uses.
struct UnsentBubble: View {
    /// The bubble's own words, the same on Android (ChatWords).
    static let notSent = Words.Setup.notSent, retryWord = Words.Chat.retry, editWord = Words.Setup.edit

    let message: AppModel.UnsentMessage
    /// What the bubble shows: the owner's words for the message.
    let words: String
    let busy: Bool
    let retry: () async -> Void
    let discard: () -> Void
    /// Puts its words back in the composer and takes it out of the thread.
    var edit: (() -> Void)? = nil
    /// The taf the chat is with, when it has one: a send refused for its halt says so and
    /// offers Resume, since Retry can't pass a halt (kiff/tafs#236, item 14).
    var taf: (id: UUID, name: String)? = nil

    /// Whether the send was refused because the taf, or its account, is halted.
    static func halted(_ why: String) -> Bool {
        let w = why.lowercased()
        return w.hasPrefix("halted") || w.contains("is halted")
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ChatBubble(message: TafsCloud.ChatMessage(at: message.at, from: .owner, text: words, session: "unsent"), taf: nil)
                .opacity(0.6)
            if let taf, Self.halted(message.why) {
                HStack(spacing: 6) {
                    Image(systemName: "hand.raised.fill").foregroundColor(Theme.no)
                    Text("\(taf.name) is halted").foregroundColor(Theme.no)
                    Text("·").foregroundColor(Theme.dim)
                    NavigationLink { HaltScreen(tafID: taf.id) } label: { Text(Words.Taf.resume).foregroundColor(Theme.ink).semibold() }.confirming()
                }
                .scaledFont(13, relativeTo: .footnote)
                Text("Nothing reaches it until you resume it. Then send this again.")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "exclamationmark.circle.fill").foregroundColor(Theme.no)
                    Text("\(Self.notSent) · \(message.why)").foregroundColor(Theme.no)
                        .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true)
                }
                .scaledFont(13, relativeTo: .footnote)
                HStack(spacing: 14) {
                    if let edit {
                        Button(Self.editWord, action: edit).foregroundColor(Theme.soft)
                    }
                    Button(Self.retryWord) { Task { await retry() } }
                        .foregroundColor(Theme.ink).semibold()
                        .disabled(busy)
                }
                .scaledFont(14, relativeTo: .footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contextMenu {
            Button(Words.Chat.Answer.copy) { Clipboard.copy(message.text) }
            Button(Words.Chat.delete, role: .destructive, action: discard)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: Text(Self.retryWord)) { Task { await retry() } }
        .accessibilityAction(named: Text(Self.editWord)) { edit?() }
    }
}

/// What the owner wrote while the chat's agent works: sent once it's done, or not.
struct QueuedBubble: View {
    /// What waits, held by tafs cloud (docs/chat.md, `hold`): the same words on both apps.
    static let heldWords = Words.Chat.held
    let text: String
    /// Who it waits for: "this answer" in the setup chat, or the taf's name.
    let who: String
    /// Takes it back, while the app holds it; nil once tafs cloud does, which says
    /// `heldWords` under it instead.
    var unqueue: (() -> Void)?

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ChatBubble(message: TafsCloud.ChatMessage(at: Date(), from: .owner, text: text, session: "queued"), taf: nil)
                .opacity(0.6)
            Text(unqueue == nil ? Self.heldWords : "Sends when \(who) is done").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contextMenu {
            if let unqueue { Button("Don't send", role: .destructive, action: unqueue) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A pinned bar for what waits for the owner, when it's out of view (#58, #64).
struct NeedsYouPill: View {
    let text: String
    let go: () -> Void

    var body: some View {
        Button(action: go) {
            HStack(spacing: 10) {
                Circle().fill(Theme.amber).frame(width: 8, height: 8)
                Text(text).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                Spacer(minLength: 4)
                Image(systemName: "chevron.up").scaledFont(13, weight: .bold).foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Theme.panel)
            .overlay(Capsule().stroke(Theme.amber.opacity(0.6)))
            .clipShape(Capsule())
            .shadow(color: Theme.ink.opacity(0.12), radius: 6, y: 2)
        }
        .padding(.horizontal, 16)
        .accessibilityHint("Shows it")
    }
}

/// Setup's card waiting for the owner, as a pinned bar.
struct NeedsYouBar: View {
    @EnvironmentObject private var model: AppModel
    let shown: ShownComponent
    let go: () -> Void

    var body: some View { NeedsYouPill(text: what, go: go) }

    private var what: String {
        let c = shown.component
        let vendor = model.grants.first { $0.id == c.grant }?.vendor ?? c.service
        switch c.type {
        case .approval: return "Needs you · approve" + (vendor.map { " \($0)" } ?? "")
        case .signIn: return Words.Coach.SignIn.eyebrow + (vendor.map { " at \($0)" } ?? "")
        case .drive: return "Needs you · let Coach drive"
        default: return "Needs you · a question"
        }
    }
}

/// A message, Setup's revealed as it arrives while the chat is open (kiff/tafs#58): tafs
/// cloud hands over each of Setup's messages whole, and the reveal shows it being read
/// in, not written. Older messages show at once.
struct RevealingBubble: View {
    let message: TafsCloud.ChatMessage
    let reveal: Bool
    @Binding var revealed: Set<String>
    @Binding var grown: Int
    @State private var visible: Int?

    var body: some View {
        ChatBubble(message: message, taf: nil, visible: visible, fullWidth: true)
            .task(id: message.id) {
                guard reveal else { return }
                let total = MarkdownText(message.text).length
                // About 400 characters a second, at most two seconds for a long answer.
                let step = max(8, total / 120)
                var shown = 0
                visible = 0
                while shown < total && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 16_000_000)
                    shown = min(total, shown + step)
                    visible = shown
                    grown &+= 1
                }
                visible = nil
                revealed.insert(message.id)
            }
    }
}

/// The plan, pinned above the thread, with Stop.
struct SetupPlan: View {
    @EnvironmentObject private var model: AppModel
    let shown: ShownComponent
    /// Folded to the step in progress until tapped, so the chat comes first (#73).
    @State private var open = false

    var body: some View {
        let steps = shown.component.steps ?? []
        let done = steps.filter { $0.status == "done" }.count
        let now = SetupPlan.current(steps)
        Panel {
            Button { withMotion(.easeOut(duration: 0.2)) { open.toggle() } } label: {
                HStack(spacing: 10) {
                    if !open, let now { StepMark(status: now.status) }
                    VStack(alignment: .leading, spacing: 2) {
                        Eyebrow(text: "Plan · \(done) of \(steps.count)")
                        if !open {
                            Text(now?.label ?? (shown.component.title ?? Words.Coach.planTitle))
                                .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                        }
                    }
                    Spacer(minLength: 4)
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .scaledFont(12, weight: .bold).foregroundColor(Theme.dim)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.drivablePlain)
            .accessibilityHint(open ? "Folds the plan" : "Shows the whole plan")
            if open {
                ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                    HStack(spacing: 10) {
                        StepMark(status: step.status)
                        // Text's own strikethrough, before the font.
                        Text(step.label)
                            .strikethrough(step.status == "skipped")
                            .scaledFont(14, weight: step.status == "active" ? .semibold : .regular)
                            .foregroundColor(step.status == "pending" || step.status == "skipped" ? Theme.dim : Theme.ink)
                    }
                }
            }
        }
    }

    /// The step the plan is at: the active one, else the first not yet done, else the last.
    static func current(_ steps: [SetupComponent.Step]) -> SetupComponent.Step? {
        steps.first { $0.status == "active" } ?? steps.first { $0.status == "pending" || $0.status == "failed" } ?? steps.last
    }
}

/// A plan step's mark: done, failed, at work or to do. The plan above the thread and the
/// "Taking shape" panel share it.
struct StepMark: View {
    let status: String

    var body: some View {
        ZStack {
            switch status {
            case "done":
                Circle().fill(Theme.ok)
                Image(systemName: "checkmark").scaledFont(9, weight: .heavy).foregroundColor(Theme.onFill)
            case "failed":
                Circle().fill(Theme.no)
                Image(systemName: "xmark").scaledFont(9, weight: .heavy).foregroundColor(Theme.onFill)
            case "active":
                Circle().stroke(Theme.amber, lineWidth: 2.5)
            default:
                Circle().stroke(Theme.ink.opacity(0.25), lineWidth: 1.5)
            }
        }
        .frame(width: 18, height: 18)
        .accessibilityLabel(status)
    }
}

/// One component in the thread.
struct SetupComponentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    let shown: ShownComponent

    var body: some View {
        let c = shown.component
        switch c.type {
        case .notice: notice(c)
        case .confirm: confirm(c)
        case .questions: QuestionsCard(component: c)
        case .approval: SetupApproval(component: c)
        case .signIn: signIn(c)
        case .result: result(c)
        case .knob: KnobDraftCard(component: c)
        case .drive: DriveCard(shown: shown)
        case .steps: EmptyView()
        }
    }

    private func notice(_ c: SetupComponent) -> some View {
        let color: Color = c.tone == "error" ? Theme.no : c.tone == "warning" ? Theme.amber : c.tone == "success" ? Theme.ok : Theme.soft
        return VStack(alignment: .leading, spacing: 4) {
            if let title = c.title { Text(title).scaledFont(14, weight: .semibold).foregroundColor(color) }
            Text(c.text ?? "").scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func confirm(_ c: SetupComponent) -> some View {
        let answer = model.setupThread.answer(to: c.id)
        let chosen = answer.map { a in c.options?.first { $0.value == a.text }?.label ?? a.text }
        return VStack(alignment: .leading, spacing: 8) {
            if let chosen {
                // Answered: one line, with what was chosen (#58).
                OutcomeLine(text: (c.text ?? "The question") + " · " + chosen, tone: Theme.ok)
            } else {
                Text(c.text ?? "").scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                WrapRow(spacing: 8) {
                    ForEach(c.options ?? [], id: \.self) { option in
                        Button(option.label) {
                            Haptics.tap()
                            Task { await model.answer(c, option.value) }
                        }
                        .scaledFont(14)
                        .foregroundColor(Theme.ink)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(Theme.panel)
                        .overlay(Capsule().stroke(Theme.ink.opacity(0.18)))
                        .clipShape(Capsule())
                        // Setup at work doesn't stop a tap: the answer waits for it (kiff/tafs#236, item 2).
                        .disabled(model.isAnswered(c))
                        .opacity(model.isAnswered(c) ? 0.45 : 1)
                    }
                }
                CardNote(id: c.id)
            }
        }
    }

    @ViewBuilder private func signIn(_ c: SetupComponent) -> some View {
        let state = model.setupCardState(c)
        let vendor = c.service ?? "the vendor"
        switch state {
        case .done:
            OutcomeLine(text: "Signed in at \(vendor)", tone: Theme.ok)
        case .declined:
            OutcomeLine(text: "Not now: no sign-in at \(vendor)", tone: Theme.dim, mark: "minus.circle.fill")
        default:
            let busy = model.connecting != nil
            let answered = model.isAnswered(c)
            let signedOut = model.grants.first { $0.id == c.grant && $0.needsReconnect }
            Panel(stroke: state == .needsYou ? Theme.amber : Theme.ink) {
                Eyebrow(text: state.isFailure ? "Didn't go through · sign in" : Words.Coach.SignIn.eyebrow,
                        color: state.isFailure ? Theme.no : Theme.amber)
                // A connection its vendor signed out reads planque's words for it (needs_reconnect).
                Text(signedOut?.signedOutWords ?? "Sign in at \(vendor)").scaledFont(20, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                if case .failed(let why) = state {
                    Text(why.map { "It didn't go through: \($0)" } ?? "It didn't go through.")
                        .scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
                Text(c.note ?? Words.Coach.signInNote)
                    .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                Button(state == .working ? model.connectStep ?? "Opening the sign-in…" : state.isFailure ? "Try again" : signedOut != nil ? "Sign in again" : c.yes ?? "Open \(c.service ?? "the sign-in")") {
                    Task { await model.signInAgain(c) }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || (answered && !state.isFailure))
                // Where it goes is always the app's own words, whatever the button says.
                Text("Opens \(c.destination)")
                    .scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.dim)
                    .frame(maxWidth: .infinity)
                if !state.isFailure {
                    Button(c.no ?? Words.Coach.notNow) { Task { await model.answer(c, "not now") } }
                        .scaledFont(14).foregroundColor(Theme.dim)
                        .frame(maxWidth: .infinity)
                        .disabled(busy || answered)
                }
                CardNote(id: c.id)
            }
        }
    }

    @ViewBuilder
    private func result(_ c: SetupComponent) -> some View {
        if c.taf?.improves != nil { improvement(c) } else { handOver(c) }
    }

    /// An improvement's result (kiff/tafs#107): the taf's next version, which tafs cloud makes
    /// and answers the card with, to try and switch to from its Versions. Nothing goes live.
    /// The card is whole from the start: its button is there while the version is made, and
    /// says what it opens, the version, never the taf (kiff/tafs#138, item 46).
    private func improvement(_ c: SetupComponent) -> some View {
        let tafID = model.improvedTaf(c)
        let name = tafID.flatMap { model.cloudTaf(for: $0) }?.name ?? c.taf?.name ?? ""
        return Panel(stroke: Theme.ok) {
            Eyebrow(text: "New version · \(c.taf?.name ?? "")", color: Theme.ok)
            Text(c.text ?? "").scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if let note = c.taf?.note, !note.isEmpty {
                Text(note).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            switch model.setupThread.improvement(shown.ref) {
            case .made(let n):
                if let tafID {
                    NavigationLink {
                        VersionsScreen(tafID: tafID)
                    } label: {
                        Text("See version \(n)").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                } else {
                    Text("Version \(n) is made.").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                }
            case .failed(let why):
                // tafs cloud told Coach, which hands it over again: no Try again here.
                Text(SetupThread.notImproved(name, why)).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                Text(Words.Coach.coachKnows)
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            case .preparing:
                Button {} label: {
                    HStack(spacing: 8) {
                        ProgressView().tint(.white)
                        Text(Words.Coach.Result.preparing)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(true)
            }
        }
    }

    private func handOver(_ c: SetupComponent) -> some View {
        // The name the taf got, once started: tafs cloud gives a taken name the first free
        // "<name> 2", "<name> 3"…, so Setup's may name another taf (kiff/tafs#236, item 5).
        let started = c.taf.flatMap { model.taf(forAgent: $0.agent) }.flatMap { model.cloudTaf(for: $0) }
        let name = started?.name ?? c.taf?.name ?? ""
        let renamed = started != nil && started?.name != c.taf?.name
        let open = renamed ? "Open \(name)" : c.yes ?? "See it on \(name)"
        return Panel(stroke: Theme.ok) {
            Eyebrow(text: "Set up · \(name)", color: Theme.ok)
            Text(c.text ?? "").scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if let tools = c.tools, !tools.isEmpty {
                // In words, as the approval named them (kiff/tafs#236, item 7).
                Text(tools.map { model.toolTitle($0) ?? KIFFMapping.toolWords($0) }.joined(separator: " · "))
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            switch model.setupThread.handOver(shown.ref, listed: started != nil) {
            case .setUp:
                if let agent = c.taf?.agent, let tafID = model.taf(forAgent: agent), model.cloudTaf(for: tafID) != nil {
                    if sizeClass == .regular {
                        // Beside the sidebar: select it there, so the list says which taf is open.
                        Button { model.requestedTaf = tafID; model.coachOpen = false } label: {
                            Text(open).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    } else {
                        NavigationLink {
                            TafDetailView(tafID: tafID)
                        } label: {
                            Text(open).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                } else {
                    // Set up, and the list is being read again for it.
                    Text("Setting up \(name)…").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                }
            case .failed(let why):
                // tafs cloud couldn't set it up, and told Coach, which hands it over again: no
                // Try again here, the next result is the one to follow.
                Text("\(name) wasn't set up: \(why.trimmingCharacters(in: CharacterSet(charactersIn: ". "))).")
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                Text(Words.Coach.coachKnows)
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            case .settingUp:
                Text("Setting up \(name)…").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
    }
}

/// An approval (contract 1 and 4): planque's own words for the connection, KIFF's draft
/// for the taf's Cards, and one Face ID for what's waiting. Its state shows (#58): needs
/// you, approving, approved, declined, or didn't go through with why and Try again. An
/// answered one folds to a line, which opens again on a tap.
struct SetupApproval: View {
    @EnvironmentObject private var model: AppModel
    let component: SetupComponent
    @State private var open = false
    /// Whether this phone is approving it: only then does finishing tap (not a refresh
    /// that finds it approved elsewhere).
    @State private var approvingHere = false

    /// A newer approval in the thread names the same connection (kiff/tafs#138).
    private var replaced: Bool { model.setupThread.replaced(component.id) }

    /// What a replaced card says, folded: what the owner did with it, if anything.
    private var replacedLine: String {
        let answer = model.setupThread.answer(to: component.id)
        // What happened, then where to look (kiff/tafs#138, item 54).
        if let a = answer, !a.failed, a.text.hasPrefix("approved") { return "Approved · Setup asked for more below" }
        if answer?.text == "declined" { return "Declined · Setup asked again below" }
        return "Setup changed this request: see below"
    }

    var body: some View {
        let state = model.setupCardState(component)
        let grant = model.grants.first { $0.id == component.grant }
        Group {
            if replaced && !open {
                // A newer request for this connection is below (kiff/tafs#138): this one is
                // history, and never shows that connection's live state or its error.
                Button { withMotion(.easeOut(duration: 0.2)) { open = true } } label: {
                    OutcomeLine(text: replacedLine, tone: Theme.dim, mark: "arrow.down.circle.fill", more: true)
                }
                .buttonStyle(.drivablePlain)
                .accessibilityHint("Shows the earlier request")
            } else if (state == .done || state == .declined) && !open {
                Button { withMotion(.easeOut(duration: 0.2)) { open = true } } label: {
                    OutcomeLine(text: outcome(state, grant), tone: state == .done ? Theme.ok : Theme.dim,
                                mark: state == .done ? "checkmark.circle.fill" : "minus.circle.fill", more: true)
                }
                .buttonStyle(.drivablePlain)
                .accessibilityHint("Shows the approval")
            } else {
                card(state, grant)
            }
        }
        .onChange(of: state) { now in
            switch now {
            case .working: approvingHere = true
            case .done:
                if approvingHere { Haptics.success() }
                approvingHere = false
            case .declined, .failed: approvingHere = false
            case .needsYou: break // between the approval and the grant's refresh
            }
        }
    }

    /// An answered approval, in one line: the answer, then what it was. Coach's title for the
    /// card comes first, so several in a row each say what was approved.
    private func outcome(_ state: AppModel.CardState, _ grant: PlanqueClient.Grant?) -> String {
        // Signed earlier, not by this tap: said as that.
        if state == .done, model.setupThread.answer(to: component.id)?.text.hasPrefix("already approved") == true {
            let title = component.title?.trimmingCharacters(in: .whitespaces)
            return "\(AppModel.alreadyApproved) · \(title.flatMap { $0.isEmpty ? nil : $0 } ?? (grant?.vendor ?? component.service).map(KIFFMapping.vendorWords) ?? "the vendor")"
        }
        if let title = component.title?.trimmingCharacters(in: .whitespaces), !title.isEmpty {
            return state == .declined ? "Declined · \(title)" : "Approved · \(title)"
        }
        let vendor = (grant?.vendor ?? component.service).map(KIFFMapping.vendorWords) ?? "the vendor"
        // An approval with the taf's Card is the Card's (kiff/tafs#94), not the tool's again.
        if let draft = component.draft.flatMap({ model.setupDrafts[$0] }), draft.noTools != true {
            return state == .declined ? "Declined · \(draft.cardTitle)" : "Approved · \(draft.cardTitle)"
        }
        if state == .declined { return "Declined · \(vendor)" }
        if grant?.connected == true { return "Approved · \(vendor) is connected, keys kept sealed" }
        return "Approved · signed in to \(vendor)"
    }

    private func card(_ state: AppModel.CardState, _ grant: PlanqueClient.Grant?) -> some View {
        let draft = component.draft.flatMap { model.setupDrafts[$0] }
        let connected = grant?.connected ?? false
        // Connected first (contract 5): signed in, and Setup reads what the vendor offers.
        let listed = grant?.listed ?? false
        let busy = model.connecting != nil
        let answered = model.isAnswered(component)
        let (eyebrow, color): (String, Color) = {
            if replaced { return ("Earlier request", Theme.dim) }
            switch state {
            case .done: return ("Approved", Theme.ok)
            case .declined: return ("Declined", Theme.dim)
            case .working: return (Words.Approval.approving, Theme.amber)
            case .failed: return ("Didn't go through", Theme.no)
            case .needsYou: return ("Needs you · approve", Theme.amber)
            }
        }()
        return Panel(stroke: replaced ? Theme.line : state == .done ? Theme.ok : state.isFailure ? Theme.no : Theme.amber) {
            HStack {
                Eyebrow(text: eyebrow, color: color)
                Spacer()
                if open {
                    Button("Fold") { withMotion(.easeOut(duration: 0.2)) { open = false } }
                        .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                }
            }
            if let grant {
                if connected {
                    Label("\(grant.vendor) is connected · keys kept sealed", systemImage: "checkmark.circle.fill")
                        .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                } else if listed {
                    Label("Signed in at \(grant.vendor) · reading what it offers", systemImage: "checkmark.circle.fill")
                        .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                } else {
                    // planque's text says when the sign-in is already held (kiff/planque#7).
                    Text(grant.text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                        // An open approval's terms are the person's to read: blanked for a drive (docs/drive.md).
                        .driveTerms(grant.vendor)
                    // The exact terms, for the owner who wants them (kiff/tafs#138, item 52).
                    if let terms = grant.terms, !terms.isEmpty {
                        DisclosureGroup {
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(Array(terms.enumerated()), id: \.offset) { _, line in Text(line) }
                            }
                            .scaledFont(11.5, design: .monospaced).foregroundColor(Theme.dim).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .driveTerms(grant.vendor)
                            .padding(.top, 6)
                        } label: {
                            Text("Details").scaledFont(13, weight: .semibold, relativeTo: .footnote).foregroundColor(Theme.blue)
                        }
                    }
                    if grant.profile != nil {
                        ProfileWordsView(words: model.shownWords(for: grant), error: model.profileWordsError[grant.id])
                            .task(id: grant.digest) { await model.loadProfileWords(for: grant) }
                    }
                    if !grant.digestMatches {
                        Text("This request doesn't match what it shows, so it can't be approved.")
                            .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                    }
                }
            } else {
                Text("Reading the request…").scaledFont(14).foregroundColor(Theme.dim)
            }
            if let draft, draft.cards != nil {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(text: draft.cardTitle)
                    ForEach(Array(draft.lines.enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.ink)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.raised)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            if replaced {
                // Opened to read again: its words only. The newer request below is the one to act on.
                Text("A newer request for this connection is below.")
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            } else if case .failed(let why) = state {
                Text(why.map { "It didn't go through: \($0)" } ?? "It didn't go through. Nothing was connected.")
                    .scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            if !replaced, state.isFailure && model.requestRefused(component) {
                // Sent again unchanged, it would fail the same way (kiff/tafs#138).
                Text("Coach mode has to change this request before you can approve it.")
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            } else if !replaced, state == .needsYou || state == .working || state.isFailure {
                HStack(spacing: 10) {
                    Button(Words.Coach.Approval.decline) { Task { await model.answer(component, "declined") } }
                        .buttonStyle(OutlineButtonStyle())
                    ApprovalGate {
                        Button(state == .working ? "Approving…" : state.isFailure ? "Try again" : "Approve") {
                            Task { await model.approve(component, shown: grant?.digest, shownDraft: draft?.version) }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(grant == nil || (!connected && !(grant?.digestMatches ?? false)) || (connected && draft == nil)
                                  || (!connected && grant.map { $0.profile != nil && model.shownWords(for: $0) == nil } ?? false))
                    }
                }
                .disabled(busy || (answered && !state.isFailure))
                CardNote(id: component.id)
                Text("Approving asks for \(Biometry.name).").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).frame(maxWidth: .infinity)
            }
        }
    }
}

/// Why a card's tap ended without reaching Setup, or that its answer waits for Setup
/// (kiff/tafs#236): a tap always shows something.
struct CardNote: View {
    @EnvironmentObject private var model: AppModel
    let id: String

    var body: some View {
        if let note = model.cardNotes[id] {
            Label(note, systemImage: "info.circle")
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// An answered card, folded to one line with its outcome (#58).
struct OutcomeLine: View {
    let text: String
    var tone: Color = Theme.ok
    var mark = "checkmark.circle.fill"
    /// Whether a tap opens the card again.
    var more = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: mark).foregroundColor(tone)
            Text(text).foregroundColor(Theme.soft).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if more { Image(systemName: "chevron.down").scaledFont(11, weight: .bold).foregroundColor(Theme.dim) }
        }
        .scaledFont(14)
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised.opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

extension AppModel.CardState {
    var isFailure: Bool { if case .failed = self { return true } else { return false } }
}

/// Buttons that wrap onto as many lines as they need.
@available(iOS 16, *)
struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for s in subviews {
            let size = Self.size(of: s, within: width)
            if x > 0 && x + size.width > width { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: width == .infinity ? x : width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = Self.size(of: s, within: bounds.width)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }

    /// A button at its own width, but never wider than the row: a long one wraps its lines
    /// inside the card instead of running off its edge (kiff/tafs#236, item 32).
    private static func size(of s: LayoutSubview, within width: CGFloat) -> CGSize {
        let ideal = s.sizeThatFits(.unspecified)
        guard width.isFinite, ideal.width > width else { return ideal }
        return s.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }
}

private extension View {
    /// The chrome around Setup's chat: no tab bar, and its own navigation bar on a wide screen.
    @ViewBuilder func setupChrome(wide: Bool) -> some View {
        self
            // A focused task (#82): the app's tabs stay out of it.
            .toolbar(.hidden, for: .tabBar)
            // Its own bar on a wide screen (kiff/tafs#96): the chat doesn't run under the title,
            // and the conversations button sits in the bar, not over the panel.
            .toolbarBackground(wide ? Visibility.visible : .automatic, for: .navigationBar)
            .toolbarBackground(Theme.background, for: .navigationBar)
    }
}

/// Coach's one message for what ended while the owner was away (kiff/tafs#210): each
/// taf's line in one sentence, read by VoiceOver as one element.
struct WhileYouWereAway: View {
    let lines: [TafsCloud.CoachLine]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(Words.Coach.away).scaledFont(14, weight: .semibold, relativeTo: .subheadline).foregroundColor(Theme.ink)
            ForEach(lines) { line in
                Text("\(line.taf): \(line.said)").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// KIFF's words for a connection's profile: each line verbatim, in order. What
/// KIFF can't check, probation and an unpinned vendor are in amber. Until they're read and
/// checked, the card says so and Approve stays off.
struct ProfileWordsView: View {
    let words: ProfileWords?
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Eyebrow(text: "KIFF counts it this way")
            if let words {
                ForEach(Array(words.lines.enumerated()), id: \.offset) { _, line in
                    Text(line.text)
                        .scaledFont(13, relativeTo: .footnote)
                        .foregroundColor(line.caution ? Theme.amberText : Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else if let error {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Reading KIFF's words…").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Coach's dial card (K4): the dial version Coach drafted for a taf, drawn from tafs cloud's
/// words for it, ring by ring (`GET …/knob`'s `draft`), never Coach's. Confirm with Face ID
/// is the usual switch to that version; Change goes back to Coach. A draft replaced since says so.
struct KnobDraftCard: View {
    @EnvironmentObject private var model: AppModel
    let component: SetupComponent
    @State private var reading: TafsCloud.Version?
    @State private var busy = false

    private var tafID: UUID? { component.tafID.flatMap { model.tafID(cloud: $0) } }
    private var draft: TafsCloud.Knob.Draft? { tafID.flatMap { model.knobs[$0]?.draft } }
    private var live: Int? { tafID.flatMap { model.knobs[$0]?.version } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let tafID, let taf = model.taf(tafID) {
                Eyebrow(text: taf.name + " will work like this")
            }
            if let version = component.version, let live, live >= version {
                Text(Words.Knob.live).scaledFont(14).foregroundColor(Theme.ok)
            } else if let draft, draft.version == component.version {
                if !draft.groups.isEmpty {
                    ForEach(draft.groups) { group in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(group.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                            lines(group.rings)
                        }
                    }
                } else if !draft.rings.isEmpty {
                    lines(draft.rings)
                } else {
                    ForEach(Array(draft.words.enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(15).foregroundColor(Theme.ink)
                    }
                }
                if let tafID, model.manages(tafID) {
                    HStack(spacing: 8) {
                        Button(busy ? "Confirming…" : "Confirm with \(Biometry.name)") { confirm(tafID, draft.version) }
                            .buttonStyle(PrimaryButtonStyle()).disabled(busy)
                        Button(Words.Knob.change) { model.openCoach() }.buttonStyle(OutlineButtonStyle())
                    }
                    Text(Words.Knob.oneConfirmation).scaledFont(12).foregroundColor(Theme.dim)
                }
            } else if draft != nil || live != nil {
                Text(Words.Knob.replaced).scaledFont(14).foregroundColor(Theme.dim)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .padding(14)
        .background(Theme.raised)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .task(id: component.version) { if let tafID { await model.loadKnob(tafID) } }
        .sheet(item: $reading) { v in
            if let tafID { VersionCardSheet(version: v, tafID: tafID).environmentObject(model) }
        }
    }

    private func lines(_ rings: [(TafsCloud.Knob.Ring, [String])]) -> some View {
        ForEach(Array(rings.enumerated()), id: \.offset) { _, ring in
            VStack(alignment: .leading, spacing: 2) {
                Text(ring.0.title).scaledFont(11, weight: .semibold).foregroundColor(TafKnobView.color(ring.0))
                ForEach(Array(ring.1.enumerated()), id: \.offset) { _, line in
                    Text(line).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// The usual switch: the version's Card read first when it changes one, then Face ID.
    private func confirm(_ tafID: UUID, _ n: Int) {
        busy = true
        Task {
            defer { busy = false }
            await model.loadVersions(tafID)
            guard let v = model.versions[tafID]?.first(where: { $0.n == n }),
                  let sw = await model.prepareSwitch(v, of: tafID) else { return }
            if sw.draft == nil { _ = await model.useVersion(v, of: tafID) } else { reading = v }
            await model.loadKnob(tafID)
        }
    }
}
