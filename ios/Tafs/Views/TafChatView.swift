import SwiftUI

/// A taf's thread: what it reports and asks, and what the owner tells it. A message
/// steers the taf within its job; it can't widen its Card, and KIFF still checks
/// every action it takes. Like Setup's chat (#58, #64): the answer streams as it's
/// written, a held call shows where it was held, and nothing the owner writes is lost.
struct TafChatView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// The owner scrolled the thread: down (true), toward what's newer, or back up. The taf's
    /// page folds its header's second line away going down, so the chat has the screen.
    var onScroll: ((Bool) -> Void)? = nil
    @State private var draft = ""
    @FocusState private var typing: Bool
    /// Whether the bottom of the thread shows, and whether the thread follows what arrives.
    @State private var atBottom = true
    @State private var following = true
    /// Something arrived below while the owner read back: the ↓ button shows a dot.
    @State private var unseen = false
    /// The held calls on screen, so the "Needs you" bar shows only when the waiting one isn't.
    @State private var heldInView: Set<UUID> = []
    @State private var landed = false
    /// As in ChatGPT and Claude (#73): a sent message moves to the top and stays while
    /// the answer grows below it.
    @State private var pinNext = false
    @State private var pinned: String?
    /// The answer being reported, from its menu (kiff/tafs#421).
    @State private var reporting: ReportedAnswer?
    /// The words of the pinned message: when tafs cloud's copy takes the place of the one
    /// shown on sending, the pin moves to it, and the view doesn't move (kiff/tafs#206).
    @State private var pinnedWords: String?
    /// The owner has scrolled since the chat opened: a late landing leaves them where they are.
    @State private var touched = false
    /// What the owner just sent, while it isn't in the thread yet (steering a run, or queued
    /// behind one): its bubble is pinned, then the thread's copy, without the view moving.
    @State private var pinText: String?
    /// The thread's ids when the owner sent it: the pin goes only to a message that wasn't
    /// there, so the same words sent again never pin the older copy (kiff/tafs#138, item 34).
    @State private var sentBefore: Set<String> = []
    @State private var viewport: CGFloat = 600
    /// The pinned message's turn: from its top to the thread's foot, as laid out (the 845 test).
    /// The room under it is the screen less this, so the answer growing below never moves it.
    @State private var turn = PinnedTurn()
    /// The height of what's under the thread: an error, the field, the dictation bar.
    @State private var footHeight: CGFloat = 60
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        VStack(spacing: 0) {
            thread
            VStack(spacing: 0) {
                if let error = model.chatErrors[tafID] {
                    Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 4)
                }
                composer
                #if os(iOS)
                DictationBar(dictation: model.dictation)
                #endif
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { footHeight = g.size.height }.onChange(of: g.size.height) { footHeight = $0 }
            })
        }
        .task(id: tafID) {
            await model.followChat(tafID)
        }
        .sheet(item: $reporting) { ReportAnswerSheet(answer: $0).environmentObject(model) }
    }

    /// Room at the thread's foot for ✦ Coach mode, which floats over the screen's lower right
    /// (kiff/tafs#243): the newest line stops above it. None while the button is hidden: the
    /// keyboard is up, or Coach mode is open.
    private var coachRoom: CGFloat {
        #if os(iOS)
        guard model.signedIn, model.mode == .kiff, !model.coachOpen, !typing else { return 0 }
        return max(0, CoachButton.clearance(wide: sizeClass == .regular) - footHeight)
        #else
        return 0
        #endif
    }

    private var thread: some View {
        let taf = model.taf(tafID)
        let items = model.chatItems(tafID)
        let state = model.chatState(tafID)
        let thinking = model.chatThinking(tafID)
        // The held call waiting, when its card is in the chat: one still waiting for the taf's
        // words isn't, and landing on or jumping to a card that isn't there showed blank space
        // (the 793 retest).
        let waiting = model.chatWaiting(tafID).flatMap { w in items.contains { $0.id == "k|" + AppModel.heldKey(w) } ? w : nil }
        return ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    // Not lazy (the 845 test): a thread is at most 100 messages, and a lazy stack
                    // only estimates the rows it hasn't drawn, so a sent message scrolled to the top
                    // landed short until the run ended, and coming back from another app the rows
                    // went blank. Every row is laid out, so a scroll lands where it says.
                    VStack(alignment: .leading, spacing: 12) {
                        if items.isEmpty && state.unsent.isEmpty {
                            Text("Ask \(taf?.name ?? "it") what it did, or tell it what to do differently. It stays inside its Card. A call it needs you to approve, from a chat or a scheduled run, shows here too.")
                                .scaledFont(14).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 30)
                        }
                        // The answer streaming now starts at its first words in the items; the work
                        // block goes just before them, under the owner's message, so the answer streams
                        // below it and it folds in place when the run ends (the 845 test).
                        let firstWords = items.firstIndex { if case .streaming = $0 { return true }; return false }
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                            if i == firstWords, let live = state.live {
                                LiveWork(live: live).id("work")
                            }
                            // A time between parts of the thread that are far apart, not on every line.
                            if i == 0 || item.at.timeIntervalSince(items[i - 1].at) >= 3600 {
                                Text(AccountTime.calendar.isDateInToday(item.at) ? item.at.shortTime : item.at.shown(date: .abbreviated, time: .shortened))
                                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                    .frame(maxWidth: .infinity).padding(.top, 6)
                            }
                            Group {
                                switch item {
                                case .message(let m) where m.from == .owner && m.kind != .kickoff && m.door == nil:
                                    VStack(alignment: .trailing, spacing: 4) {
                                        ChatBubble(message: m, taf: taf)
                                            .contextMenu { askAgain(m.text) }
                                        // Written while a card waited: these words were its answer (`answers`).
                                        if m.answers != nil { AnsweredCardLine() }
                                    }
                                case .message(let m):
                                    VStack(alignment: .leading, spacing: 6) {
                                        // What it did before writing it, folded (kiff/tafs#285).
                                        if m.from == .taf, let work = m.work { WorkSummary(work: work) }
                                        // The stored answer, apart from the one streaming, which the
                                        // same bubble draws under its own id (ChatPinUITests).
                                        if m.from == .taf {
                                            ChatBubble(message: m, taf: taf, fullWidth: true).accessibilityIdentifier("taf-message")
                                                .reportable(m, in: tafID, model: model, reporting: $reporting)
                                        } else {
                                            ChatBubble(message: m, taf: taf, fullWidth: false)
                                        }
                                    }
                                case .held(let call):
                                    HeldCallCard(call: call)
                                        .onAppear { heldInView.insert(call.id) }
                                        .onDisappear { heldInView.remove(call.id) }
                                case .outcome(let entry):
                                    OutcomeLine(text: Self.outcomeText(entry), tone: Self.outcomeTone(entry.kind), mark: Self.outcomeMark(entry.kind))
                                case .streaming(_, let m):
                                    ChatBubble(message: m, taf: taf, fullWidth: true, steady: true)
                                case .knock(let knock):
                                    // Someone wrote through a door (docs/doors.md): the owner decides here.
                                    if let doors = model.doors(for: tafID) { KnockCard(knock: knock, taf: taf, doors: doors) }
                                }
                            }
                            .id(item.id)
                            .pinnedTop(item.id == pinned)
                        }
                        ForEach(state.unsent) { unsent in
                            UnsentBubble(message: unsent, words: unsent.text, busy: model.chatSending.contains(tafID),
                                         retry: { await model.retryChat(unsent, to: tafID) },
                                         discard: { model.discardUnsentChat(unsent, from: tafID) },
                                         taf: (tafID, taf?.name ?? "This taf"))
                                .id(unsent.id.uuidString)
                                .pinnedTop(unsent.id.uuidString == pinned)
                        }
                        // The answer streaming now is in the items, in the order it came (kiff/tafs#206).
                        // Under it, what the taf is doing and thinking, and the steps it took
                        // (kiff/tafs#285); the dots while nothing streams yet.
                        if let live = state.live {
                            if firstWords == nil { LiveWork(live: live).id("work") }
                        } else if thinking {
                            TypingBubble(taf: taf, fullWidth: true).id("thinking")
                        }
                        // Written to the run while it goes, not read yet, as kiff/agents says (#64).
                        if let steering = state.steering, !steering.over {
                            ForEach(Array(steering.untaken.enumerated()), id: \.offset) { i, m in
                                VStack(alignment: .trailing, spacing: 4) {
                                    ChatBubble(message: TafsCloud.ChatMessage(at: m.at, from: .owner, text: m.text, session: "steered"), taf: nil)
                                        .opacity(0.8)
                                    Text("\(taf?.name ?? "It") reads this " + (steering.status == "PAUSED" ? "when it carries on" : "at its next step"))
                                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                }
                                .frame(maxWidth: .infinity, alignment: .trailing)
                                .accessibilityElement(children: .combine)
                                .id("steer|\(i)")
                                .pinnedTop("steer|\(i)" == pinned)
                            }
                        }
                        if !thinking { ended(taf) }
                        if let queued = state.queued {
                            QueuedBubble(text: queued, who: taf?.name ?? "your taf") { _ = model.unqueueChat(tafID) }
                                .id("queued")
                                .pinnedTop(pinned == "queued")
                        }
                        Color.clear.frame(height: 1).id(Self.bottom)
                            .threadFoot()
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
                    .padding(.top, 12)
                    .padding(.bottom, pinned == nil ? 12 : 0)
                    // Room under a pinned message, so a short one reaches the top at once, not once
                    // the answer has grown below it (kiff/tafs#243): the screen less the turn it
                    // starts (the 845 test), so as the answer grows the room shrinks by as much, and
                    // nothing moves. It stays once the run ends, until the next message is pinned.
                    if pinned != nil { Color.clear.frame(height: turn.room(viewport)) }
                }
                .coordinateSpace(name: PinnedTurn.space)
                .onPreferenceChange(PinnedTopKey.self) { turn.top = $0 }
                .onPreferenceChange(ThreadFootKey.self) { turn.foot = $0 }
                // A readable column on a wide screen (kiff/tafs#102): the owner's words and the
                // taf's sit together, not at the screen's far edges.
                .frame(maxWidth: Self.readable)
                .frame(maxWidth: .infinity)
            }
            .accessibilityIdentifier("chat-thread")
            // The taf's doors are read with its thread, so their knocks sit among the messages.
            .task(id: model.chats[tafID]?.count ?? 0) {
                if model.mode == .kiff, !model.doorsOffered.isEmpty { await model.doors(for: tafID)?.load() }
            }
            // What the pin is doing, for the UI test that keeps the 845 test from coming back.
            .accessibilityValue(pinState)
            .dismissesKeyboardInteractively()
            .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: coachRoom) }
            .background(GeometryReader { g in
                Color.clear.onAppear { viewport = g.size.height }.onChange(of: g.size.height) { viewport = $0 }
            })
            .simultaneousGesture(DragGesture().onChanged { drag in
                following = false
                touched = true
                if abs(drag.translation.height) > 24 { onScroll?(drag.translation.height < 0) }
            })
            .overlay(alignment: .bottom) {
                VStack(spacing: 8) {
                    // The held call waiting for the owner, when it's out of view.
                    if let waiting, !heldInView.contains(waiting.id) {
                        NeedsYouPill(text: "Needs you · " + waiting.proposed.summary) {
                            following = false
                            withMotion(.easeOut(duration: 0.25)) { proxy.scrollTo("k|" + AppModel.heldKey(waiting), anchor: .center) }
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
                                .overlay(alignment: .topTrailing) {
                                    if unseen { Circle().fill(Theme.amber).frame(width: 10, height: 10).offset(x: 1, y: -1) }
                                }
                        }
                        .accessibilityLabel(unseen ? "Jump to the latest, something new" : "Jump to the latest")
                    }
                }
                .padding(.bottom, 10)
            }
            .onChange(of: items.map(\.id)) { ids in
                // The thread as it is now, from the model: the `items` this closure captured can
                // be the body's before the change, without the message just sent, so the pin
                // waited for the next change, the answer, and landed at the run's end (the 845 test).
                let items = model.chatItems(tafID)
                if let p = pinned, p.hasPrefix("m|"), !ids.contains(p) {
                    if let words = pinnedWords,
                       let copy = items.last(where: { if case .message(let m) = $0 { return m.from == .owner && SetupThread.matchesSent(m.text, words) } else { return false } })?.id {
                        pinned = copy
                    } else {
                        // The pinned message is gone and nothing took its place: the chat goes to
                        // the latest rather than stay scrolled past the end of the thread, blank.
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
                // The thread changed under a pinned message (the run ended and tafs cloud's copy
                // took the place of the one shown while it worked, or the reload brought more or
                // fewer messages above it): it's put back at the top, unless the owner has scrolled.
                // Left where it was, the view could end past the thread's end, blank, with only the
                // down arrow (the 859 test).
                if pinned != nil {
                    repin(proxy)
                    return
                }
                if !following && !ids.isEmpty { unseen = true }
                land(proxy, items: ids, waiting: waiting)
                follow(proxy)
            }
            // Sending puts the keyboard down, and the screen grows as the pin scrolls: the pinned
            // message is put back at the top once it has (the 755 retest).
            .onChange(of: viewport) { _ in repin(proxy) }
            .onChange(of: coachRoom) { _ in repin(proxy) }
            .onChange(of: thinking) { _ in follow(proxy) }
            .onChange(of: state.live) { _ in if following && pinned == nil { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onChange(of: state.queued) { _ in pinBubble(proxy) }
            .onChange(of: state.steering?.untaken.count) { _ in pinBubble(proxy) }
            .onChange(of: state.unsent) { unsent in
                // Not sent: its bubble, with Retry, is the one at the top.
                if pinNext, let last = unsent.last {
                    pinNext = false
                    pinText = nil
                    pinned = last.id.uuidString
                    touched = false
                    proxy.pinToTop(last.id.uuidString, animated: true) { touched }
                    return
                }
                follow(proxy)
            }
            .onAppear { land(proxy, items: items.map(\.id), waiting: waiting) }
            // Back from another app, nothing is changed (the 845 test): taking the room under a
            // pinned message away left the view scrolled past the end of a shorter thread, blank,
            // until a reload. The pin and its room, sized to the turn, stay where they were.
        }
    }

    private static let bottom = "bottom"

    /// The pin as laid out, in screenshots mode only: what ChatPinUITests reports on a failure.
    private var pinState: String {
        guard Screenshots.enabled else { return "" }
        let top = turn.top.map { String(Int($0)) } ?? "-"
        let foot = turn.foot.map { String(Int($0)) } ?? "-"
        let room = Int(turn.room(viewport))
        return "pinned \(pinned ?? "none"), turn \(top)…\(foot), room \(room), viewport \(Int(viewport)), following \(following), touched \(touched)"
    }

    /// The bubble of what the owner just sent, while the taf works: steering its run, or
    /// queued behind it. It goes to the top at once (kiff/tafs#138).
    private func pinBubble(_ proxy: ScrollViewProxy) {
        guard pinNext, let text = pinText else { return }
        let state = model.chatState(tafID)
        let id: String?
        if let queued = state.queued, SetupThread.matchesSent(queued, text) {
            id = "queued"
        } else if let untaken = state.steering?.untaken, let i = untaken.lastIndex(where: { SetupThread.matchesSent($0.text, text) }) {
            id = "steer|\(i)"
        } else {
            id = nil
        }
        guard let id else { return }
        pinNext = false
        pinned = id
        touched = false
        proxy.pinToTop(id, animated: true) { touched }
    }

    /// The pinned message back at the top, unless the owner has scrolled since.
    private func repin(_ proxy: ScrollViewProxy) {
        guard let pinned, !touched else { return }
        proxy.pinToTop(pinned, animated: false) { touched }
    }

    private func follow(_ proxy: ScrollViewProxy) {
        guard following, pinned == nil else { return }
        withMotion(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    /// Opening the chat lands at the held call waiting for the owner, or at the latest.
    private func land(_ proxy: ScrollViewProxy, items: [String], waiting: HeldCall?) {
        // Only once the thread is in: landing on an empty chat would count as landed.
        guard !landed, !items.isEmpty else { return }
        landed = true
        // A card among the last few items is in view at the latest: it lands there, at the bottom,
        // which the lazy rows reach reliably. Centring a card near the end landed on rows not
        // laid out yet, blank, with the Needs you pill and the arrow over them (the 793 retest).
        let card = waiting.map { "k|" + AppModel.heldKey($0) }
        let nearEnd = card.flatMap { c in items.lastIndex(of: c) }.map { items.count - $0 <= 3 } ?? true
        if card != nil, !nearEnd { following = false }
        let (target, anchor): (String, UnitPoint) = nearEnd ? (Self.bottom, .bottom) : (card ?? Self.bottom, .center)
        landAgain(proxy, target, anchor)
    }

    /// Coming back to the app rebuilds the chat, and its lazy rows aren't laid out on the
    /// first pass, so one scroll lands nowhere and the chat opens at its top (kiff/tafs#138).
    /// It lands now and again once they're laid out, unless the owner has scrolled meanwhile.
    private func landAgain(_ proxy: ScrollViewProxy, _ target: String, _ anchor: UnitPoint) {
        proxy.scrollTo(target, anchor: anchor)
        // A long thread's rows are laid out over several passes: it lands again as they are,
        // so it opens at the latest and not part-way up (the 755 retest).
        for delay in [0.05, 0.3, 0.6, 1.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                if !touched { proxy.scrollTo(target, anchor: anchor) }
            }
        }
    }

    /// How the last run ended, when the owner should know: it failed part-way, with Retry.
    @ViewBuilder private func ended(_ taf: Taf?) -> some View {
        switch model.chatState(tafID).ended {
        case .failed?:
            HStack(spacing: 10) {
                Text(model.failedWords(tafID, taf: taf?.name ?? "The taf"))
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("Retry") { Task { await model.retryChatRun(tafID) } }
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    .disabled(model.chatSending.contains(tafID))
            }
            .padding(12)
            .background(Theme.no.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        // Stopped needs no line: the chat is simply ready again (the owner's call, 755 retest).
        case .stopped?, nil:
            // Stopped at its own cap, with no words: the chat says why (the 793 retest).
            if let stop = model.chatState(tafID).capped, let words = AppModel.capWords(stop, taf: taf?.name ?? "The taf") {
                Text(words)
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Theme.amber.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    private var composer: some View {
        let key = tafID.uuidString
        let files = model.files(key)
        let ready = files.contains { $0.ref != nil }
        let uploading = model.filesUploading(key)
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !ready
        let thinking = model.chatThinking(tafID) && model.mode == .kiff
        // Files go with a message of their own, once the taf is done; and nothing goes while a file
        // is still going up, so a message never leaves one behind (kiff/tafs#209).
        let held = model.holdsSend(key, thinking: thinking)
        let name = model.taf(tafID)?.name ?? "your taf"
        return VStack(spacing: 0) {
        FileTray(key: key)
        HStack(alignment: .bottom, spacing: 10) {
            #if os(iOS)
            if model.filesTarget(key) != nil { AttachButton(key: key) }
            #endif
            ChatField(title: thinking ? "Write the next message" : "Message \(name)", text: $draft, cursor: { model.dictation.cursor = $0 })
                .accessibilityIdentifier("chat-field")
                .focused($typing)
                .scaledFont(16)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            #if os(iOS)
            // Speak instead of typing (kiff/tafs#208): the words wait in the box, unsent.
            MicButton(dictation: model.dictation, text: $draft)
            #endif
            if thinking && empty {
                // While the taf works, Send is Stop: its run is cancelled.
                Button { Task { await model.stopChat(tafID) } } label: {
                    Image(systemName: "stop.fill")
                        .scaledFont(14, weight: .bold)
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.ink)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Stop")
                .shortcut(.stop)
            } else {
                // While the taf works, what's written goes to its run, which reads it at its
                // next step; or, behind a queued message, waits for it to be done.
                Button { send() } label: {
                    Image(systemName: "arrow.up")
                        .scaledFont(16, weight: .bold)
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.brand)
                        .clipShape(Circle())
                        .opacity(empty || held ? 0.4 : 1)
                }
                .disabled(empty || held)
                .accessibilityIdentifier("chat-send")
                .shortcut(.send)
                .accessibilityLabel(held ? (uploading ? "Send once the files are up" : "Send files once \(name) is done")
                                    : thinking ? "Send to \(name) while it works" : "Send")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        }
        .frame(maxWidth: Self.readable)
        .frame(maxWidth: .infinity)
        .barBackground()
        // Leaving the chat stops dictating, so its words never land in another chat's box.
        .onDisappear { model.dictation.stop() }
    }

    /// The chat's widest column: about 75 characters of text.
    static let readable: CGFloat = 720

    /// A sent message's menu (kiff/tafs#285): send it again, change it first, or copy it.
    @ViewBuilder private func askAgain(_ text: String) -> some View {
        // Its words only: files picked for the next message stay in the tray (kiff/tafs#209).
        Button { draft = text; send(files: false) } label: { Label("Send again", systemImage: "arrow.clockwise") }
        Button { draft = text; typing = true } label: { Label("Edit and send", systemImage: "pencil") }
        Button { Clipboard.copy(text) } label: { Label("Copy", systemImage: "doc.on.doc") }
    }

    /// Sends what's in the box; with `files`, the tray's ready files go with it.
    private func send(files withFiles: Bool = true) {
        // Sending stops dictating: what was heard goes with it, as it stands in the box.
        model.dictation.stop()
        let text = draft
        draft = ""
        // A sent message goes to the top of the view and stays (kiff/tafs#138): the reply,
        // and a card with it, fill the space below without the view moving, even while the
        // taf is still at work.
        pinNext = true
        // The earlier message's pin goes now: the keyboard going down re-pins, and it scrolled
        // back to the earlier message instead of up to this one (the 789 retest).
        pinned = nil
        touched = false
        // As the model keeps it: trimmed (review on #154).
        pinText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        sentBefore = Set(model.chatItems(tafID).map(\.id))
        following = false
        Haptics.tap()
        // The keyboard goes down once it's sent (kiff/tafs#138, the owner's call, which
        // reverses #102): the reply and its card get the screen.
        typing = false
        let key = tafID.uuidString
        let files = withFiles ? model.takeFiles(key) : []
        Task {
            if !(await model.submitChat(text, to: tafID, files: files.compactMap(\.ref))) {
                if draft.isEmpty { draft = text }
                model.putBackFiles(files, key)
            }
        }
    }
}

/// One message. The taf's on the left with its avatar; the owner's on the right in the
/// mascot's colours. A run's kickoff shows as a small note, not a bubble.
struct ChatBubble: View {
    let message: TafsCloud.ChatMessage
    let taf: Taf?
    /// How much of the taf's message shows, while it is revealed; nil shows it all.
    var visible: Int? = nil
    /// The taf's words full width, with no bubble, as Setup's are (#58).
    var fullWidth = false
    /// Words streaming in: shown at an even pace, with formatting still open closed (kiff/tafs#285).
    var steady = false

    var body: some View {
        if let door = message.door, message.kind != .kickoff {
            // A door's message, or its answer: marked with where it came from and how it went back.
            VStack(alignment: message.from == .owner ? .trailing : .leading, spacing: 4) {
                if message.from == .owner { DoorStamp(mark: door, fromOwner: true) }
                bubble
                if message.from == .taf { DoorStamp(mark: door, fromOwner: false) }
            }
        } else {
            bubble
        }
    }

    @ViewBuilder
    private var bubble: some View {
        if message.kind == .kickoff {
            Text("Run started \(message.at.shortTime) · \(message.text)")
                .scaledFont(12).foregroundColor(Theme.dim).lineLimit(2)
                .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 4)
        } else if message.from == .owner {
            HStack {
                Spacer(minLength: 48)
                VStack(alignment: .trailing, spacing: 6) {
                    // The files sent with it (kiff/tafs#209), each opening full size.
                    if !message.attachments.isEmpty {
                        MessageFiles(files: message.attachments, key: taf?.id.uuidString ?? AppModel.setupFiles)
                            .opacity(message.session == "pending" ? 0.7 : 1)
                    }
                    if !message.text.isEmpty {
                        Text(message.text)
                            .accessibilityIdentifier("owner-message")
                            .scaledFont(15).foregroundColor(.white)
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .background(Theme.brand)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .opacity(message.session == "pending" ? 0.7 : 1)
                            // Copied from its menu, with Send again and Edit (kiff/tafs#285), where the chat
                            // gives one; selectable elsewhere.
                            .textSelection(.enabled)
                    }
                }
            }
        } else if fullWidth {
            // Full width, with no bubble, as Claude and ChatGPT write (#58).
            Group {
                if steady { SteadyMarkdown(text: message.text, size: 16) } else { MarkdownText(message.text, visible: visible, size: 16) }
            }
                .foregroundColor(Theme.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 2)
                .textSelection(.enabled)
        } else {
            HStack(alignment: .bottom, spacing: 8) {
                if let taf { TafAvatar(taf: taf, size: 28) } else { Mascot(size: 28) }
                // The taf writes Markdown: drawn as formatted text, never as symbols (#58).
                MarkdownText(message.text, visible: visible)
                    .foregroundColor(Theme.ink)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .textSelection(.enabled)
                Spacer(minLength: 48)
            }
        }
    }
}

/// The taf is working on an answer.
struct TypingBubble: View {
    let taf: Taf?
    var fullWidth = false

    var body: some View {
        if fullWidth {
            // The dots sit where the words will, with no bubble.
            dots.padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement().accessibilityLabel("Answering")
        } else {
            bubble
        }
    }

    private var dots: some View {
        TimelineView(.animation) { context in
            let phase = context.date.timeIntervalSinceReferenceDate * 4
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { i in
                    Circle().fill(Theme.dim).frame(width: 7, height: 7)
                        .opacity(0.35 + 0.65 * max(0, sin(phase - Double(i) * 0.9)))
                }
            }
        }
    }

    private var bubble: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if let taf { TafAvatar(taf: taf, size: 28) } else { Mascot(size: 28) }
            dots
            .padding(.horizontal, 14).padding(.vertical, 14)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            Spacer()
        }
        .accessibilityLabel("\(taf?.name ?? "The taf") is answering")
    }
}

extension TafChatView {
    /// A call the owner answered, in the chat: what became of it (kiff/tafs#115).
    static func outcomeText(_ entry: RecordEntry) -> String {
        switch entry.kind {
        // Why, as the Record says it: a vendor's refusal still counts (kiff/tafs#206 6.13).
        case .notDone: return "\(entry.title): \(entry.detail)"
        case .sending: return "\(entry.title): sent, waiting for the tool"
        case .unknown: return "\(entry.title): outcome unknown"
        // KIFF's own record of a held call, after a restart: what the owner decided (kiff/tafs#138).
        case .refused where !entry.title.hasPrefix("Refused by you"): return "\(entry.title): refused, not sent"
        case .approved where !entry.title.hasPrefix("Approved once by you"): return "\(entry.title): approved by you, done"
        default: return entry.title
        }
    }

    static func outcomeTone(_ kind: RecordEntry.Kind) -> Color {
        switch kind {
        case .approved: return Theme.ok
        case .notDone: return Theme.no
        case .sending, .unknown: return Theme.amber
        default: return Theme.dim
        }
    }

    static func outcomeMark(_ kind: RecordEntry.Kind) -> String {
        switch kind {
        case .approved: return "checkmark.circle.fill"
        case .sending: return "clock.fill"
        case .unknown: return "questionmark.circle.fill"
        default: return "xmark.circle.fill"
        }
    }
}

extension ScrollViewProxy {
    /// Puts a pinned message at the top of a chat (kiff/tafs#138). The space under a pinned
    /// message is laid out only once `pinned` is set, so a scroll in that same pass clamped
    /// to the old bottom and left the view in the empty space under it: the whole chat went
    /// white (the owner's iPad, 21 Oct). It scrolls on the next pass, once that space is
    /// there, and again as the lazy rows settle, unless the owner has scrolled meanwhile.
    @MainActor func pinToTop<ID: Hashable>(_ id: ID, animated: Bool, touched: @escaping () -> Bool) {
        DispatchQueue.main.async {
            if animated {
                withMotion(.easeOut(duration: 0.25)) { self.scrollTo(id, anchor: .top) }
            } else {
                self.scrollTo(id, anchor: .top)
            }
            for delay in [0.1, 0.35, 0.7] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    if !touched() { self.scrollTo(id, anchor: .top) }
                }
            }
        }
    }
}

/// Where a pinned message's turn sits in a chat's thread (the 845 test): its top and the
/// thread's foot, as laid out, in the thread's own space.
struct PinnedTurn: Equatable {
    static let space = "thread"
    var top: CGFloat?
    var foot: CGFloat?

    /// The room under the turn: the screen less the turn, so the pinned message sits at the top
    /// and the answer grows into the room. A whole screen until the turn is measured.
    func room(_ viewport: CGFloat) -> CGFloat {
        guard let top, let foot, foot >= top else { return max(0, viewport) }
        return max(0, viewport - (foot - top))
    }
}

struct PinnedTopKey: PreferenceKey {
    static let defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) { value = nextValue() ?? value }
}

struct ThreadFootKey: PreferenceKey {
    static let defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) { value = nextValue() ?? value }
}

extension View {
    /// Reports this row's top while it's the pinned message.
    func pinnedTop(_ pinned: Bool) -> some View {
        background(GeometryReader { g in
            Color.clear.preference(key: PinnedTopKey.self, value: pinned ? g.frame(in: .named(PinnedTurn.space)).minY : nil)
        })
    }

    /// Reports where the thread's rows end.
    func threadFoot() -> some View {
        background(GeometryReader { g in
            Color.clear.preference(key: ThreadFootKey.self, value: g.frame(in: .named(PinnedTurn.space)).maxY)
        })
    }
}

/// Under an owner's message written while a card waited: their words were the card's answer.
struct AnsweredCardLine: View {
    var body: some View {
        Text("Answered the card above").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
            .frame(maxWidth: .infinity, alignment: .trailing)
    }
}
