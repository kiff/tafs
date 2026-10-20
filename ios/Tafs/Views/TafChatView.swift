import SwiftUI

/// A taf's thread: what it reports and asks, and what the owner tells it. A message
/// steers the taf within its job; it can't widen its Card, and KIFF still checks
/// every action it takes. Like Setup's chat (#58, #64): the answer streams as it's
/// written, a held call shows where it was held, and nothing the owner writes is lost.
struct TafChatView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
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
    /// What the owner just sent, while it isn't in the thread yet (steering a run, or queued
    /// behind one): its bubble is pinned, then the thread's copy, without the view moving.
    @State private var pinText: String?
    @State private var viewport: CGFloat = 600

    var body: some View {
        VStack(spacing: 0) {
            thread
            if let error = model.chatErrors[tafID] {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 4)
            }
            composer
        }
        .task(id: tafID) { await model.followChat(tafID) }
    }

    private var thread: some View {
        let taf = model.taf(tafID)
        let items = model.chatItems(tafID)
        let state = model.chatState(tafID)
        let thinking = model.chatThinking(tafID)
        let waiting = model.chatWaiting(tafID)
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if items.isEmpty && state.unsent.isEmpty {
                        Text("Ask \(taf?.name ?? "it") what it did, or tell it what to do differently. It stays inside its Card. A call it needs you to approve, from a chat or a scheduled run, shows here too.")
                            .scaledFont(14).foregroundColor(Theme.dim)
                            .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 30)
                    }
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        // A time between parts of the thread that are far apart, not on every line.
                        if i == 0 || item.at.timeIntervalSince(items[i - 1].at) >= 3600 {
                            Text(Calendar.current.isDateInToday(item.at) ? item.at.shortTime : item.at.formatted(date: .abbreviated, time: .shortened))
                                .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity).padding(.top, 6)
                        }
                        Group {
                            switch item {
                            case .message(let m):
                                ChatBubble(message: m, taf: taf, fullWidth: m.from == .taf)
                            case .held(let call):
                                HeldCallCard(call: call)
                                    .onAppear { heldInView.insert(call.id) }
                                    .onDisappear { heldInView.remove(call.id) }
                            case .outcome(let entry):
                                OutcomeLine(text: Self.outcomeText(entry), tone: Self.outcomeTone(entry.kind), mark: Self.outcomeMark(entry.kind))
                            }
                        }
                        .id(item.id)
                    }
                    ForEach(state.unsent) { unsent in
                        UnsentBubble(message: unsent, words: unsent.text, busy: model.chatSending.contains(tafID),
                                     retry: { await model.retryChat(unsent, to: tafID) },
                                     discard: { model.discardUnsentChat(unsent, from: tafID) })
                            .id(unsent.id)
                    }
                    if let live = state.live, !live.text.isEmpty {
                        ChatBubble(message: TafsCloud.ChatMessage(at: Date(), from: .taf, text: live.text, session: "live"), taf: taf, fullWidth: true)
                            .id("live")
                    } else if thinking {
                        TypingBubble(taf: taf, fullWidth: true).id("thinking")
                    }
                    if let activity = state.live?.activity {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.mini).tint(Theme.dim)
                            Text(activity).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        }
                        .accessibilityElement(children: .combine)
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
                        }
                    }
                    if !thinking { ended(taf) }
                    if let queued = state.queued {
                        QueuedBubble(text: queued, who: taf?.name ?? "your taf") { _ = model.unqueueChat(tafID) }
                            .id("queued")
                    }
                    if pinned != nil { Color.clear.frame(height: max(0, viewport * 0.7)) }
                    Color.clear.frame(height: 1).id(Self.bottom)
                        .onAppear { atBottom = true; following = true; unseen = false }
                        .onDisappear { atBottom = false }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                // A readable column on a wide screen (kiff/tafs#102): the owner's words and the
                // taf's sit together, not at the screen's far edges.
                .frame(maxWidth: Self.readable)
                .frame(maxWidth: .infinity)
            }
            .dismissesKeyboardInteractively()
            .background(GeometryReader { g in
                Color.clear.onAppear { viewport = g.size.height }.onChange(of: g.size.height) { viewport = $0 }
            })
            .simultaneousGesture(DragGesture().onChanged { _ in following = false })
            .overlay(alignment: .bottom) {
                VStack(spacing: 8) {
                    // The held call waiting for the owner, when it's out of view.
                    if let waiting, !heldInView.contains(waiting.id) {
                        NeedsYouPill(text: "Needs you · " + waiting.proposed.summary) {
                            following = false
                            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("h|" + waiting.id.uuidString, anchor: .center) }
                        }
                    }
                    if !atBottom && !following {
                        Button {
                            following = true
                            unseen = false
                            pinned = nil
                            withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 15, weight: .bold)).foregroundColor(Theme.ink)
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
                // The message the owner just sent is in the thread: it goes to the top and stays
                // (kiff/tafs#138). Matched by its words, so a reply arriving first doesn't pin an
                // older message. If its bubble was already pinned while the taf (or Setup) worked,
                // the pin moves to it without animating, so the view doesn't move.
                if let text = pinText, let sent = items.last(where: { if case .message(let m) = $0 { return m.from == .owner && m.text == text } else { return false } })?.id {
                    let animate = pinNext
                    pinNext = false
                    pinText = nil
                    pinned = sent
                    following = false
                    if animate {
                        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(sent, anchor: .top) }
                    } else {
                        proxy.scrollTo(sent, anchor: .top)
                    }
                    return
                }
                if !following && !ids.isEmpty && pinned == nil { unseen = true }
                land(proxy, items: ids, waiting: waiting)
                follow(proxy)
            }
            .onChange(of: thinking) { _ in follow(proxy) }
            .onChange(of: state.live) { _ in if following { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onChange(of: state.queued) { _ in pinBubble(proxy) }
            .onChange(of: state.steering?.untaken.count) { _ in pinBubble(proxy) }
            .onChange(of: state.unsent) { unsent in
                // Not sent: its bubble, with Retry, is the one at the top.
                if pinNext, let last = unsent.last {
                    pinNext = false
                    pinText = nil
                    pinned = last.id.uuidString
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(last.id, anchor: .top) }
                    return
                }
                follow(proxy)
            }
            .onAppear { land(proxy, items: items.map(\.id), waiting: waiting) }
        }
    }

    private static let bottom = "bottom"

    /// The bubble of what the owner just sent, while the taf works: steering its run, or
    /// queued behind it. It goes to the top at once (kiff/tafs#138).
    private func pinBubble(_ proxy: ScrollViewProxy) {
        guard pinNext, let text = pinText else { return }
        let id: String?
        if state.queued == text {
            id = "queued"
        } else if let untaken = state.steering?.untaken, let i = untaken.lastIndex(where: { $0.text == text }) {
            id = "steer|\(i)"
        } else {
            id = nil
        }
        guard let id else { return }
        pinNext = false
        pinned = id
        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(id, anchor: .top) }
    }

    private func follow(_ proxy: ScrollViewProxy) {
        guard following else { return }
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    /// Opening the chat lands at the held call waiting for the owner, or at the latest.
    private func land(_ proxy: ScrollViewProxy, items: [String], waiting: HeldCall?) {
        guard !landed else { return }
        guard !items.isEmpty else { proxy.scrollTo(Self.bottom, anchor: .bottom); return }
        landed = true
        if let waiting {
            following = false
            proxy.scrollTo("h|" + waiting.id.uuidString, anchor: .center)
        } else {
            proxy.scrollTo(Self.bottom, anchor: .bottom)
        }
    }

    /// How the last run ended, when the owner should know: they stopped it, or it
    /// failed part-way, with Retry.
    @ViewBuilder private func ended(_ taf: Taf?) -> some View {
        switch model.chatState(tafID).ended {
        case .stopped?:
            Label("Stopped", systemImage: "stop.circle")
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        case .failed?:
            HStack(spacing: 10) {
                Text("\(taf?.name ?? "The taf") stopped part-way, before it finished.")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("Retry") { Task { await model.retryChatRun(tafID) } }
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    .disabled(model.chatSending.contains(tafID))
            }
            .padding(12)
            .background(Theme.no.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        case nil:
            EmptyView()
        }
    }

    private var composer: some View {
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let thinking = model.chatThinking(tafID) && model.mode == .kiff
        let name = model.taf(tafID)?.name ?? "your taf"
        return HStack(alignment: .bottom, spacing: 10) {
            ChatField(title: thinking ? "Write the next message" : "Message \(name)", text: $draft)
                .focused($typing)
                .scaledFont(16)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            if thinking && empty {
                // While the taf works, Send is Stop: its run is cancelled.
                Button { Task { await model.stopChat(tafID) } } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.ink)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Stop")
            } else {
                // While the taf works, what's written goes to its run, which reads it at its
                // next step; or, behind a queued message, waits for it to be done.
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.brand)
                        .clipShape(Circle())
                        .opacity(empty ? 0.4 : 1)
                }
                .disabled(empty)
                .accessibilityLabel(thinking ? "Send to \(name) while it works" : "Send")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: Self.readable)
        .frame(maxWidth: .infinity)
        .barBackground()
    }

    /// The chat's widest column: about 75 characters of text.
    static let readable: CGFloat = 720

    private func send() {
        let text = draft
        draft = ""
        // A sent message goes to the top of the view and stays (kiff/tafs#138): the reply,
        // and a card with it, fill the space below without the view moving, even while the
        // taf is still at work.
        pinNext = true
        pinText = text
        following = false
        Haptics.tap()
        // The keyboard stays for the next message (kiff/tafs#102): the composer changes
        // around the field as the taf starts, which drops its focus.
        typing = true
        Task {
            if !(await model.submitChat(text, to: tafID)), draft.isEmpty { draft = text }
            typing = true
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

    var body: some View {
        if message.kind == .kickoff {
            Text("Run started \(message.at.shortTime) · \(message.text)")
                .font(.system(size: 12)).foregroundColor(Theme.dim).lineLimit(2)
                .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 4)
        } else if message.from == .owner {
            HStack {
                Spacer(minLength: 48)
                Text(message.text)
                    .scaledFont(15).foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.brand)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .opacity(message.session == "pending" ? 0.7 : 1)
                    .textSelection(.enabled)
            }
        } else if fullWidth {
            // Full width, with no bubble, as Claude and ChatGPT write (#58).
            MarkdownText(message.text, visible: visible, size: 16)
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
        case .notDone: return "\(entry.title): not done"
        case .sending: return "\(entry.title): sent, waiting for the tool"
        case .unknown: return "\(entry.title): outcome unknown"
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
