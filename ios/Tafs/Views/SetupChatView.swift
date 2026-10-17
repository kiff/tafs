import SwiftUI

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

    var body: some View {
        VStack(spacing: 0) {
            if model.setupTaf == nil || !model.planqueReady {
                intro
            } else {
                if let plan = model.setupThread.plan { SetupPlan(shown: plan).padding(.horizontal, 12).padding(.top, 8) }
                thread
                if let error = model.setupError {
                    Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
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
                .font(.system(size: 16)).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
            if let error = model.setupError ?? model.lastError {
                Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
            }
            Spacer()
            Button(model.startingSetup ? "Starting…" : "Start") { Task { _ = await model.startSetup() } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.startingSetup || model.mode != .kiff)
            Text("Starting asks for Face ID once.").font(.system(size: 12)).foregroundColor(Theme.dim)
                .frame(maxWidth: .infinity)
        }
        .padding(20)
    }

    private var thread: some View {
        let items = model.setupThread.items
        let thinking = model.setupThinking
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if items.isEmpty && !thinking {
                        Text("Say what you want done, for example \"Make sure customers can get their refunds\".")
                            .font(.system(size: 14)).foregroundColor(Theme.dim)
                            .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 30)
                    }
                    ForEach(items) { item in
                        Group {
                            switch item {
                            case .message(let m):
                                RevealingBubble(message: m, reveal: m.from == .taf && m.at > openedAt && !revealed.contains(m.id),
                                                revealed: $revealed, grown: $grown)
                            case .component(let c): SetupComponentView(shown: c)
                            }
                        }
                        .id(item.id)
                    }
                    if thinking { TypingBubble(taf: nil).id("thinking") }
                    Color.clear.frame(height: 1).id(Self.bottom)
                        .onAppear { atBottom = true; following = true }
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
                if !atBottom && !following {
                    Button {
                        following = true
                        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 15, weight: .bold)).foregroundColor(Theme.ink)
                            .frame(width: 38, height: 38)
                            .background(Theme.panel)
                            .overlay(Circle().stroke(Theme.line))
                            .clipShape(Circle())
                            .shadow(color: Theme.ink.opacity(0.12), radius: 6, y: 2)
                    }
                    .padding(.bottom, 10)
                    .accessibilityLabel("Jump to the latest")
                }
            }
            .onChange(of: items.map(\.id)) { _ in follow(proxy) }
            .onChange(of: thinking) { _ in follow(proxy) }
            .onChange(of: grown) { _ in if following { proxy.scrollTo(Self.bottom, anchor: .bottom) } }
            .onAppear { proxy.scrollTo(Self.bottom, anchor: .bottom) }
        }
    }

    private static let bottom = "bottom"

    private func follow(_ proxy: ScrollViewProxy) {
        guard following else { return }
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
    }

    private var composer: some View {
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let thinking = model.setupThinking
        return HStack(alignment: .bottom, spacing: 10) {
            TextField("Tell Setup what you want done", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .focused($typing)
                .font(.system(size: 16))
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            if thinking {
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
                Button {
                    let text = draft
                    draft = ""
                    following = true
                    Task { if !(await model.sendSetup(text)) { if draft.isEmpty { draft = text } } }
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Theme.brand)
                        .clipShape(Circle())
                        .opacity(empty || model.setupSending ? 0.4 : 1)
                }
                .disabled(empty || model.setupSending)
                .accessibilityLabel("Send")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Theme.background.shadow(.drop(color: Theme.ink.opacity(0.06), radius: 6, y: -2)))
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
        ChatBubble(message: message, taf: nil, visible: visible)
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
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Theme.no)
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                HStack(spacing: 10) {
                    StepMark(status: step.status)
                    Text(step.label)
                        .font(.system(size: 14, weight: step.status == "active" ? .semibold : .regular))
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
            if let title = c.title { Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(color) }
            Text(c.text ?? "").font(.system(size: 14)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func confirm(_ c: SetupComponent) -> some View {
        let done = model.answered.contains(c.id)
        return VStack(alignment: .leading, spacing: 8) {
            Text(c.text ?? "").font(.system(size: 15)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            FlowRow(spacing: 8) {
                ForEach(c.options ?? [], id: \.self) { option in
                    Button(option.label) { Task { await model.answer(c, option.value) } }
                        .font(.system(size: 14))
                        .foregroundColor(Theme.ink)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(Theme.panel)
                        .overlay(Capsule().stroke(Theme.ink.opacity(0.18)))
                        .clipShape(Capsule())
                        .disabled(done || model.setupSending)
                }
            }
        }
    }

    private func signIn(_ c: SetupComponent) -> some View {
        let done = model.answered.contains(c.id)
        let busy = model.connecting != nil
        return Panel(stroke: Theme.ink) {
            Eyebrow(text: "Needs you · sign in")
            Text("Sign in at \(c.service ?? "the vendor")").font(Theme.display(20)).foregroundColor(Theme.ink)
            Text("The vendor's own page opens. Setup never sees your password or the key; planque keeps the sign-in.")
                .font(.system(size: 14)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            Button("Open \(c.service ?? "the sign-in")") { Task { await model.signInAgain(c) } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(done || busy)
            Button("Not now") { Task { await model.answer(c, "not now") } }
                .font(.system(size: 14)).foregroundColor(Theme.dim)
                .frame(maxWidth: .infinity)
                .disabled(done || busy)
        }
    }

    private func result(_ c: SetupComponent) -> some View {
        Panel(stroke: Theme.ok) {
            Eyebrow(text: "Set up · \(c.taf?.name ?? "")", color: Theme.ok)
            Text(c.text ?? "").font(.system(size: 15)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if let tools = c.tools, !tools.isEmpty {
                Text(tools.joined(separator: ", ")).font(Theme.mono(12)).foregroundColor(Theme.soft)
            }
            if let agent = c.taf?.agent, let tafID = model.taf(forAgent: agent), model.cloudTaf(for: tafID) != nil {
                NavigationLink {
                    TafDetailView(tafID: tafID)
                } label: {
                    Text("See it on \(c.taf?.name ?? "the taf")").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            } else if let error = model.tafStartErrors[c.id] {
                Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
                Button("Try again") { Task { await model.startSetupTaf(c) } }
                    .buttonStyle(OutlineButtonStyle())
            } else if c.taf?.job != nil {
                Text("Starting \(c.taf?.name ?? "the taf")…").font(.system(size: 13)).foregroundColor(Theme.dim)
            }
        }
        // The hand-over starts the taf (once): tafs cloud stores it and kiff/agents claims its key.
        .task(id: c.id) { await model.startSetupTaf(c) }
    }
}

/// An approval (contract 1 and 4): planque's own words for the connection, KIFF's draft
/// for the taf's Cards, and one Face ID for what's waiting.
struct SetupApproval: View {
    @EnvironmentObject private var model: AppModel
    let component: SetupComponent

    var body: some View {
        let grant = model.grants.first { $0.id == component.grant }
        let draft = component.draft.flatMap { model.setupDrafts[$0] }
        let cardsWaiting = draft.map { !$0.confirmed } ?? (component.draft != nil)
        let connected = grant?.connected ?? false
        // Connected first (contract 5): signed in, and Setup reads what the vendor offers.
        let listed = grant?.listed ?? false
        let finished = (connected && !cardsWaiting) || (listed && component.draft == nil)
        let busy = model.connecting != nil
        let answered = model.answered.contains(component.id)
        Panel(stroke: finished ? Theme.ok : Theme.amber) {
            Eyebrow(text: finished ? "Approved" : "Needs you · approve", color: finished ? Theme.ok : Theme.amber)
            if let grant {
                if connected {
                    Label("\(grant.vendor) is connected · keys held by planque", systemImage: "checkmark.circle.fill")
                        .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ok)
                } else if listed {
                    Label("Signed in at \(grant.vendor) · Setup is reading what it offers", systemImage: "checkmark.circle.fill")
                        .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ok)
                } else {
                    Text(grant.text).font(.system(size: 15)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    if grant.signedIn == true {
                        Text("You're already signed in at \(grant.vendor): approving connects these tools, with no second sign-in.")
                            .font(.system(size: 13)).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                    }
                    if !grant.digestMatches {
                        Text("planque's request doesn't match what it shows, so it can't be approved.")
                            .font(.system(size: 13)).foregroundColor(Theme.no)
                    }
                }
            } else {
                Text("Reading what planque asks…").font(.system(size: 14)).foregroundColor(Theme.dim)
            }
            if let draft, let cards = draft.cards {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(text: "\(draft.agentID)'s Card")
                    ForEach(Array(cards.flatMap(\.lines).enumerated()), id: \.offset) { _, line in
                        Text(line).font(Theme.mono(12)).foregroundColor(Theme.ink)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.raised)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            if !finished {
                HStack(spacing: 10) {
                    Button("Decline") { Task { await model.answer(component, "declined") } }
                        .buttonStyle(OutlineButtonStyle())
                    Button(busy && model.connecting == component.grant ? "Approving…" : "Approve") { Task { await model.approve(component) } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(grant == nil || (!connected && !(grant?.digestMatches ?? false)) || (connected && draft == nil))
                }
                .disabled(busy || answered)
                Text("Approving asks for Face ID.").font(.system(size: 12)).foregroundColor(Theme.dim).frame(maxWidth: .infinity)
            }
        }
    }
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
