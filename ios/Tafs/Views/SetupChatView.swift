import SwiftUI
import UIKit

/// The setup chat (kiff/tafs#50): the owner tells Setup what to connect, and Setup shows
/// its plan, asks, and hands over approvals the owner signs on this phone. A component
/// grants nothing: an approval shows planque's own text and KIFF's draft, and Face ID
/// signs there.
struct SetupChatView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draft = ""
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

    var body: some View {
        VStack(spacing: 0) {
            if model.setupTaf == nil || !model.planqueReady {
                intro
            } else {
                if let plan = model.setupThread.plan { SetupPlan(shown: plan).padding(.horizontal, 12).padding(.top, 8) }
                thread
                if let error = model.setupError {
                    Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 4)
                }
                composer
            }
        }
        .screenBackground()
        .navigationTitle("Setup")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.followSetup() }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 14) {
            Spacer()
            Text("Connect your tools").font(Theme.display(30)).foregroundColor(Theme.ink)
            Text("Tell Setup what a taf should work with, like your Stripe. You sign in on the tool's own page; your sign-in stays with planque, never with Setup, tafs or KIFF. Each connection and each Card waits for your Face ID.")
                .scaledFont(16).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
            if let error = model.setupError ?? model.lastError {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
            }
            Spacer()
            Button(model.startingSetup ? "Starting…" : "Start") { Task { _ = await model.startSetup() } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.startingSetup || model.mode != .kiff)
            Text("Starting asks for Face ID once.").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                .frame(maxWidth: .infinity)
        }
        .padding(20)
    }

    private var thread: some View {
        let items = model.setupThread.items
        let thinking = model.setupThinking
        let waiting = model.setupWaiting
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if items.isEmpty && !thinking && model.setupUnsent.isEmpty { starts }
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        // A time between parts of the thread that are far apart, not on every line.
                        if let gap = gapTime(items, i) {
                            Text(gap).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity).padding(.top, 6)
                        }
                        Group {
                            switch item {
                            case .message(let m) where m.from == .owner:
                                ChatBubble(message: TafsCloud.ChatMessage(at: m.at, from: .owner, kind: m.kind,
                                                                          text: model.setupThread.ownerWords(m.text), session: m.session), taf: nil)
                            case .message(let m):
                                // Streamed answers show as written; only without a stream are they revealed.
                                RevealingBubble(message: m, reveal: !model.setupStreamWorks && m.from == .taf && m.at > openedAt && !revealed.contains(m.id),
                                                revealed: $revealed, grown: $grown)
                            case .component(let c):
                                SetupComponentView(shown: c)
                                    .onAppear { cardsInView.insert(c.id) }
                                    .onDisappear { cardsInView.remove(c.id) }
                            }
                        }
                        .id(item.id)
                    }
                    ForEach(model.setupUnsent) { unsent in UnsentBubble(message: unsent).id(unsent.id) }
                    if let live = model.setupLive, !live.text.isEmpty {
                        ChatBubble(message: TafsCloud.ChatMessage(at: Date(), from: .taf, text: live.text, session: "live"), taf: nil, fullWidth: true).id("live")
                    } else if thinking {
                        TypingBubble(taf: nil, fullWidth: true).id("thinking")
                    }
                    if let activity = model.setupLive?.activity {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.mini).tint(Theme.dim)
                            Text(activity).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if !thinking { ended }
                    if let queued = model.setupQueued { QueuedBubble(text: queued) }
                    Color.clear.frame(height: 1).id(Self.bottom)
                        .onAppear { atBottom = true; following = true; unseen = false }
                        .onDisappear { atBottom = false }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            // The owner scrolling stops the thread following what arrives; reaching the
            // bottom again, or the button, starts it.
            .simultaneousGesture(DragGesture().onChanged { _ in following = false })
            .overlay(alignment: .bottom) {
                VStack(spacing: 8) {
                    // The card that needs the owner, when it's out of view (#58).
                    if let waiting, !cardsInView.contains(waiting.id) {
                        NeedsYouBar(shown: waiting) {
                            following = false
                            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("c|" + waiting.id, anchor: .center) }
                        }
                    }
                    if !atBottom && !following {
                        Button {
                            following = true
                            unseen = false
                            withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 15, weight: .bold)).foregroundColor(Theme.ink)
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
                        .accessibilityLabel(unseen ? "Jump to the latest, something new" : "Jump to the latest")
                    }
                }
                .padding(.bottom, 10)
            }
            .onChange(of: items.map(\.id)) { ids in
                if !following && !ids.isEmpty { unseen = true }
                land(proxy, waiting: waiting)
                follow(proxy)
            }
            .onChange(of: thinking) { _ in follow(proxy) }
            .onChange(of: grown) { _ in if following { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onChange(of: model.setupLive) { _ in if following { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onChange(of: model.setupUnsent) { _ in follow(proxy) }
            .onAppear { land(proxy, waiting: waiting) }
        }
    }

    private static let bottom = "bottom"

    private func follow(_ proxy: ScrollViewProxy) {
        guard following else { return }
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    /// Opening the chat lands at the card waiting for the owner, or at the latest (#58).
    private func land(_ proxy: ScrollViewProxy, waiting: ShownComponent?) {
        guard !landed, !model.setupThread.items.isEmpty else {
            if !landed { proxy.scrollTo(Self.bottom, anchor: .bottom) }
            return
        }
        landed = true
        if let waiting {
            following = false
            proxy.scrollTo("c|" + waiting.id, anchor: .center)
        } else {
            proxy.scrollTo(Self.bottom, anchor: .bottom)
        }
    }

    /// The time to show above item `i`: the first, and any after an hour's quiet.
    private func gapTime(_ items: [SetupThread.Item], _ i: Int) -> String? {
        let at = items[i].at
        if i > 0, at.timeIntervalSince(items[i - 1].at) < 3600 { return nil }
        return Calendar.current.isDateInToday(at) ? at.shortTime : at.formatted(date: .abbreviated, time: .shortened)
    }

    /// The empty chat: what Setup does, and ways to start (#58).
    private var starts: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Setup connects your tools and sets up a taf to work with them. Each connection and each Card waits for your approval.")
                .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            ForEach(Self.examples, id: \.self) { example in
                Button {
                    Haptics.tap()
                    following = true
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

    static let examples = [
        "Make sure customers can get their refunds",
        "Answer customer questions about orders",
        "Connect my Stripe",
    ]

    /// How the last run ended, when the owner should know (#58): they stopped it, or it
    /// failed part-way, with Retry.
    @ViewBuilder private var ended: some View {
        switch model.setupEnded {
        case .stopped?:
            Label("Stopped", systemImage: "stop.circle")
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        case .failed?:
            HStack(spacing: 10) {
                Text("Setup stopped part-way, before it finished.")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("Retry") { Task { await model.retrySetupRun() } }
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
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let thinking = model.setupThinking
        return HStack(alignment: .bottom, spacing: 10) {
            TextField(thinking ? "Write the next message" : "Tell Setup what you want done", text: $draft, axis: .vertical)
                .lineLimit(1...6)
                .focused($typing)
                .scaledFont(16)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            if thinking && empty {
                // While Setup works, Send is Stop: its run is cancelled.
                Button { Task { await model.stopSetup() } } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.ink)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Stop")
            } else {
                // While Setup works, what's written waits and is sent once it's done.
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
                .accessibilityLabel(thinking ? "Send when Setup is done" : "Send")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Theme.background.shadow(.drop(color: Theme.ink.opacity(0.06), radius: 6, y: -2)))
    }

    private func send() {
        let text = draft
        draft = ""
        following = true
        Haptics.tap()
        Task { if !(await model.submitSetup(text)), draft.isEmpty { draft = text } }
    }
}

/// A message that didn't reach Setup, kept where it was written (#58): why, and Retry.
struct UnsentBubble: View {
    @EnvironmentObject private var model: AppModel
    let message: AppModel.UnsentMessage

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ChatBubble(message: TafsCloud.ChatMessage(at: message.at, from: .owner, text: model.setupThread.ownerWords(message.text),
                                                      session: "unsent"), taf: nil)
                .opacity(0.6)
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.circle.fill").foregroundColor(Theme.no)
                Text("Not sent").foregroundColor(Theme.no)
                Text("·").foregroundColor(Theme.dim)
                Button("Retry") { Task { await model.retrySetup(message) } }
                    .foregroundColor(Theme.ink).fontWeight(.semibold)
                    .disabled(model.setupSending)
            }
            .scaledFont(13, relativeTo: .footnote)
            Text(message.why).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contextMenu {
            Button("Copy") { UIPasteboard.general.string = message.text }
            Button("Delete", role: .destructive) { model.discardUnsent(message) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Retry") { Task { await model.retrySetup(message) } }
    }
}

/// What the owner wrote while Setup works: sent once it's done, or taken back to edit.
struct QueuedBubble: View {
    @EnvironmentObject private var model: AppModel
    let text: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ChatBubble(message: TafsCloud.ChatMessage(at: Date(), from: .owner, text: text, session: "queued"), taf: nil)
                .opacity(0.6)
            Text("Sends when Setup is done").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contextMenu {
            Button("Don't send", role: .destructive) { _ = model.unqueueSetup() }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A pinned bar for the card waiting for the owner, when it's out of view (#58).
struct NeedsYouBar: View {
    @EnvironmentObject private var model: AppModel
    let shown: ShownComponent
    let go: () -> Void

    var body: some View {
        Button(action: go) {
            HStack(spacing: 10) {
                Circle().fill(Theme.amber).frame(width: 8, height: 8)
                Text(what).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                Spacer(minLength: 4)
                Image(systemName: "chevron.up").font(.system(size: 13, weight: .bold)).foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Theme.panel)
            .overlay(Capsule().stroke(Theme.amber.opacity(0.6)))
            .clipShape(Capsule())
            .shadow(color: Theme.ink.opacity(0.12), radius: 6, y: 2)
        }
        .padding(.horizontal, 16)
        .accessibilityHint("Shows the card")
    }

    private var what: String {
        let c = shown.component
        let vendor = model.grants.first { $0.id == c.grant }?.vendor ?? c.service
        switch c.type {
        case .approval: return "Needs you · approve" + (vendor.map { " \($0)" } ?? "")
        case .signIn: return "Needs you · sign in" + (vendor.map { " at \($0)" } ?? "")
        default: return "Needs you · Setup asks"
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

    var body: some View {
        let steps = shown.component.steps ?? []
        let done = steps.filter { $0.status == "done" }.count
        Panel {
            HStack {
                Eyebrow(text: "Plan · \(shown.component.title ?? "Setup") · \(done) of \(steps.count)")
                Spacer()
                Button("Stop") { Task { await model.stopSetup() } }
                    .scaledFont(13, weight: .semibold, relativeTo: .footnote)
                    .foregroundColor(Theme.no)
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                HStack(spacing: 10) {
                    StepMark(status: step.status)
                    Text(step.label)
                        .scaledFont(14, weight: step.status == "active" ? .semibold : .regular)
                        .foregroundColor(step.status == "pending" || step.status == "skipped" ? Theme.dim : Theme.ink)
                        .strikethrough(step.status == "skipped")
                }
            }
        }
    }
}

private struct StepMark: View {
    let status: String

    var body: some View {
        ZStack {
            switch status {
            case "done":
                Circle().fill(Theme.ok)
                Image(systemName: "checkmark").font(.system(size: 9, weight: .heavy)).foregroundColor(.white)
            case "failed":
                Circle().fill(Theme.no)
                Image(systemName: "xmark").font(.system(size: 9, weight: .heavy)).foregroundColor(.white)
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
    let shown: ShownComponent

    var body: some View {
        let c = shown.component
        switch c.type {
        case .notice: notice(c)
        case .confirm: confirm(c)
        case .approval: SetupApproval(component: c)
        case .signIn: signIn(c)
        case .result: result(c)
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
                OutcomeLine(text: (c.text ?? "Setup's question") + " · " + chosen, tone: Theme.ok)
            } else {
                Text(c.text ?? "").scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                FlowRow(spacing: 8) {
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
                        .disabled(model.answered.contains(c.id) || model.setupSending)
                    }
                }
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
            let answered = model.answered.contains(c.id)
            Panel(stroke: state == .needsYou ? Theme.amber : Theme.ink) {
                Eyebrow(text: state.isFailure ? "Didn't go through · sign in" : "Needs you · sign in",
                        color: state.isFailure ? Theme.no : Theme.amber)
                Text("Sign in at \(vendor)").font(Theme.display(20)).foregroundColor(Theme.ink)
                if case .failed(let why) = state {
                    Text(why.map { "It didn't go through: \($0)" } ?? "It didn't go through.")
                        .scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
                Text("The vendor's own page opens. Setup never sees your password or the key; planque keeps the sign-in.")
                    .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                Button(state == .working ? "Opening the sign-in…" : state.isFailure ? "Try again" : "Open \(c.service ?? "the sign-in")") {
                    Task { await model.signInAgain(c) }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || (answered && !state.isFailure))
                if !state.isFailure {
                    Button("Not now") { Task { await model.answer(c, "not now") } }
                        .scaledFont(14).foregroundColor(Theme.dim)
                        .frame(maxWidth: .infinity)
                        .disabled(busy || answered)
                }
            }
        }
    }

    private func result(_ c: SetupComponent) -> some View {
        Panel(stroke: Theme.ok) {
            Eyebrow(text: "Set up · \(c.taf?.name ?? "")", color: Theme.ok)
            Text(c.text ?? "").scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if let tools = c.tools, !tools.isEmpty {
                Text(tools.joined(separator: ", ")).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.soft)
            }
            if let agent = c.taf?.agent, let tafID = model.taf(forAgent: agent), model.cloudTaf(for: tafID) != nil {
                NavigationLink {
                    TafDetailView(tafID: tafID)
                } label: {
                    Text("See it on \(c.taf?.name ?? "the taf")").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            } else if let error = model.tafStartErrors[c.id] {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                Button("Try again") { Task { await model.startSetupTaf(c) } }
                    .buttonStyle(OutlineButtonStyle())
            } else if c.taf?.job != nil {
                Text("Starting \(c.taf?.name ?? "the taf")…").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
        // The hand-over starts the taf (once): tafs cloud stores it and kiff/agents claims its key.
        .task(id: c.id) { await model.startSetupTaf(c) }
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

    var body: some View {
        let state = model.setupCardState(component)
        let grant = model.grants.first { $0.id == component.grant }
        Group {
            if (state == .done || state == .declined) && !open {
                Button { withAnimation(.easeOut(duration: 0.2)) { open = true } } label: {
                    OutcomeLine(text: outcome(state, grant), tone: state == .done ? Theme.ok : Theme.dim,
                                mark: state == .done ? "checkmark.circle.fill" : "minus.circle.fill", more: true)
                }
                .buttonStyle(.plain)
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

    private func outcome(_ state: AppModel.CardState, _ grant: PlanqueClient.Grant?) -> String {
        let vendor = grant?.vendor ?? component.service ?? "the vendor"
        if state == .declined { return "Declined · \(vendor)" }
        if grant?.connected == true { return "Approved · \(vendor) is connected, keys held by planque" }
        return "Approved · signed in at \(vendor)"
    }

    private func card(_ state: AppModel.CardState, _ grant: PlanqueClient.Grant?) -> some View {
        let draft = component.draft.flatMap { model.setupDrafts[$0] }
        let connected = grant?.connected ?? false
        // Connected first (contract 5): signed in, and Setup reads what the vendor offers.
        let listed = grant?.listed ?? false
        let busy = model.connecting != nil
        let answered = model.answered.contains(component.id)
        let (eyebrow, color): (String, Color) = {
            switch state {
            case .done: return ("Approved", Theme.ok)
            case .declined: return ("Declined", Theme.dim)
            case .working: return ("Approving…", Theme.amber)
            case .failed: return ("Didn't go through", Theme.no)
            case .needsYou: return ("Needs you · approve", Theme.amber)
            }
        }()
        return Panel(stroke: state == .done ? Theme.ok : state.isFailure ? Theme.no : Theme.amber) {
            HStack {
                Eyebrow(text: eyebrow, color: color)
                Spacer()
                if open {
                    Button("Fold") { withAnimation(.easeOut(duration: 0.2)) { open = false } }
                        .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                }
            }
            if let grant {
                if connected {
                    Label("\(grant.vendor) is connected · keys held by planque", systemImage: "checkmark.circle.fill")
                        .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                } else if listed {
                    Label("Signed in at \(grant.vendor) · Setup is reading what it offers", systemImage: "checkmark.circle.fill")
                        .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                } else {
                    // planque's text says when the sign-in is already held (kiff/planque#7).
                    Text(grant.text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    if !grant.digestMatches {
                        Text("planque's request doesn't match what it shows, so it can't be approved.")
                            .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                    }
                }
            } else {
                Text("Reading what planque asks…").scaledFont(14).foregroundColor(Theme.dim)
            }
            if let draft, let cards = draft.cards {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(text: "\(draft.agentID)'s Card")
                    ForEach(Array(cards.flatMap(\.lines).enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.ink)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.raised)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            if case .failed(let why) = state {
                Text(why.map { "It didn't go through: \($0)" } ?? "It didn't go through. Nothing was connected.")
                    .scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            if state == .needsYou || state == .working || state.isFailure {
                HStack(spacing: 10) {
                    Button("Decline") { Task { await model.answer(component, "declined") } }
                        .buttonStyle(OutlineButtonStyle())
                    Button(state == .working ? "Approving…" : state.isFailure ? "Try again" : "Approve") {
                        Task { await model.approve(component) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(grant == nil || (!connected && !(grant?.digestMatches ?? false)) || (connected && draft == nil))
                }
                .disabled(busy || (answered && !state.isFailure))
                Text("Approving asks for Face ID.").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).frame(maxWidth: .infinity)
            }
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
            if more { Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold)).foregroundColor(Theme.dim) }
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
struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: width == .infinity ? x : width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}
