import SwiftUI

/// Plan & usage (docs/accounts.md › Credits and plans): its own screen, opened from Settings'
/// Plan card and Home's plan line. The balance first, in tafs cloud's words, with Raise the
/// limit and buying where tafs cloud offers them; then the credits the tafs used (the Credits
/// panel); then members' requests, asking an admin, and who bills the plan. The app adds
/// nothing to tafs cloud's words but their order.
struct UsageView: View {
    @EnvironmentObject private var model: AppModel
    /// Read on this screen: an earlier read isn't shown as now (kiff/tafs#206 5.1).
    @State private var read = false
    @State private var buyingCredits = false
    @State private var asking = false
    @State private var declining: KIFFClient.PackRequest?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read {
                    // In Android's order: the balance and buying, who bills, requests, then credits.
                    BalancePanel(buyCredits: { buyingCredits = true })
                    if let message = model.planMessage {
                        Panel { Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                    }
                    if model.mode == .kiff, let u = model.usage {
                        billingPanel(u)
                        askPanel(u)
                        requestsPanel(u)
                    }
                    CreditsPanel()
                    // KIFF's read failed: buying and requests wait on it, said once.
                    if model.mode == .kiff, model.usage == nil, let why = model.usageError {
                        Panel { Text(why).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true) }
                    }
                } else {
                    // Loading draws the screen at its full width, never a narrow strip (kiff/tafs#245, item 2).
                    Panel { ProgressView().frame(maxWidth: .infinity, alignment: .leading) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Self.title)
        .inlineTitle()
        .task { await model.refreshUsage(); read = true }
        .refreshable { await model.refreshUsage() }
        .sheet(isPresented: $buyingCredits) { BuyCreditsView().environmentObject(model) }
        .sheet(isPresented: $asking) { AskAdminView(raise: model.usage.flatMap(Self.asks)).environmentObject(model) }
        .sheet(item: $declining) { DeclineRequestView(request: $0).environmentObject(model) }
    }

    /// For someone who doesn't manage billing, on a plan where they can ask: their request
    /// waiting, with Withdraw; else the last answer, and Ask an admin.
    @ViewBuilder private func askPanel(_ u: KIFFClient.Usage) -> some View {
        if !u.managesBilling {
            if let mine = Self.waiting(u) {
                Panel {
                    Eyebrow(text: "Waiting")
                    Text("\(Self.askedLine(mine, in: u)) An admin answers.").scaledFont(15).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Withdraw") { Task { await model.withdrawRequest(mine) } }.buttonStyle(OutlineButtonStyle())
                }
            } else {
                if let answer = Self.lastAnswer(u) {
                    Panel { Text(answer).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                }
                if Self.asks(u) != nil {
                    if model.balance?.balance.waiting == true {
                        Button(Self.askAdmin) { asking = true }.buttonStyle(PrimaryButtonStyle())
                    } else {
                        Button(Self.askAdmin) { asking = true }.buttonStyle(OutlineButtonStyle())
                    }
                }
            }
        }
    }

    /// Members' requests waiting for an answer, for someone who manages billing: a raise is
    /// answered by raising the limit, a pack by buying it for that request.
    @ViewBuilder private func requestsPanel(_ u: KIFFClient.Usage) -> some View {
        let open = Self.openRequests(u)
        if !open.isEmpty {
            Panel(stroke: Theme.amber.opacity(0.5)) {
                Eyebrow(text: "Requests", color: Theme.amber)
                ForEach(open) { r in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(Self.requestLine(r, in: u)).scaledFont(15).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        if !r.reason.isEmpty {
                            Text(r.reason).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                        HStack(spacing: 10) {
                            if r.kind == "raise" {
                                // Raising answers every open request for one: only where tafs cloud offers it.
                                if model.balance?.balance.raise != nil, !model.balanceStale {
                                    ApprovalGate {
                                        Button(Self.raiseLimit) { Task { await model.answerRaise() } }
                                            .buttonStyle(PrimaryButtonStyle())
                                    }
                                }
                            } else if model.balance?.balance.buy?.packs == true,
                                      let pack = u.store?.packs.first(where: { $0.id == r.pack }) {
                                // Approving buys the pack: only while packs are on sale.
                                Button("Approve") { Task { await model.buyPack(pack, answering: r) } }
                                    .buttonStyle(PrimaryButtonStyle())
                                    .confirming()
                                    .disabled(model.purchases.buying != nil)
                            }
                            Button("Decline") { declining = r }.buttonStyle(OutlineButtonStyle())
                        }
                    }
                }
            }
        }
    }

    /// Who bills the plan, in one line; Apple's with Manage. After a move from Solo to
    /// Company, while Solo still renews with Apple, a line says so with Apple's own page to
    /// cancel it: the app never points anywhere else to buy.
    @ViewBuilder private func billingPanel(_ u: KIFFClient.Usage) -> some View {
        if let line = Self.billingLine(u) {
            Panel {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text(line).scaledFont(15).foregroundColor(Theme.ink)
                        Spacer()
                        if Self.managedByApple(u) {
                            Button("Manage") { Task { await Purchases.manageSubscriptions() } }
                                .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                        }
                    }
                    if let renews = Self.soloStillRenews(u) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(renews).scaledFont(14).foregroundColor(Theme.soft)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 8)
                            Button("Cancel Solo") { Task { await Purchases.manageSubscriptions() } }
                                .scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                        }
                    }
                }
            }
        }
    }

    /// The account's screen: its plan, and what the period used.
    static let title = "Plan & usage"
    static let askAdmin = "Ask an admin"
    static let raiseLimit = "Raise the limit"

    /// The colour of tafs cloud's tone: `waiting` in red, `warning` in amber, else calm.
    static func color(_ tone: String, calm: Color = Theme.ink) -> Color {
        switch tone {
        case "waiting": return Theme.no
        case "warning": return Theme.amberText
        default: return calm
        }
    }

    /// "The Solo bought in the App Store renews on 3 November…" after a move to Company; nil
    /// otherwise. It doesn't say "your": the person reading may not be the one who bought it.
    static func soloStillRenews(_ u: KIFFClient.Usage) -> String? {
        guard u.billing?.source == "stripe", let until = u.billing?.appleSoloUntil else { return nil }
        return "Company is billed by KIFF now. The Solo bought in the App Store renews on \(until.shown(date: .long, time: .omitted)) unless it's cancelled, from the Apple account that bought it."
    }

    /// One line on who bills the plan; nil when no plan is billed.
    static func billingLine(_ u: KIFFClient.Usage) -> String? {
        switch u.billing?.source {
        case "apple": return "Billed by Apple"
        case "google": return "Billed by Google Play"
        case "stripe": return "Billed by KIFF"
        case "contract": return "Managed by your company's agreement"
        default: return nil
        }
    }

    static func managedByApple(_ u: KIFFClient.Usage) -> Bool { u.billing?.source == "apple" }

    /// What someone who doesn't manage billing asks an admin for on this plan, as Android does:
    /// a raise of the spend limit on Company (KIFF's `team`), a pack on Solo while packs are on
    /// sale; nothing on others. `true` is a raise.
    static func asks(_ u: KIFFClient.Usage) -> Bool? {
        switch u.plan {
        case "team": return true
        case "solo": return (u.store?.packs.isEmpty ?? true) ? nil : false
        default: return nil
        }
    }

    /// The member's own request still waiting.
    static func waiting(_ u: KIFFClient.Usage) -> KIFFClient.PackRequest? {
        guard !u.managesBilling else { return nil }
        return u.requests?.first { $0.status == "open" }
    }

    /// The member's request while it waits, in the lines both apps share (docs/accounts.md ›
    /// In the apps).
    static func askedLine(_ r: KIFFClient.PackRequest, in u: KIFFClient.Usage) -> String {
        r.kind == "raise" ? "You asked an admin to raise the spend limit." : "You asked an admin for a pack."
    }

    /// The member's latest answered request, in the lines both apps share.
    static func lastAnswer(_ u: KIFFClient.Usage) -> String? {
        guard !u.managesBilling, let r = u.requests?.first else { return nil }
        switch r.status {
        case "approved": return r.kind == "raise" ? "An admin raised the spend limit." : "An admin added a pack."
        case "declined":
            if let note = r.note, !note.isEmpty { return "An admin declined: \(note)" }
            return "An admin declined."
        case "expired": return "Your request ended with the period."
        default: return nil
        }
    }

    /// An open request, as someone who manages billing reads it, in Android's words: "Someone
    /// asks to raise the spend limit.", "Someone asks for Rayo."
    static func requestLine(_ r: KIFFClient.PackRequest, in u: KIFFClient.Usage?) -> String {
        r.kind == "raise" ? "Someone asks to raise the spend limit." : "Someone asks for \(packLine(r.pack, in: u))."
    }

    /// Open requests, for someone who manages billing.
    static func openRequests(_ u: KIFFClient.Usage) -> [KIFFClient.PackRequest] {
        guard u.managesBilling else { return [] }
        return (u.requests ?? []).filter { $0.status == "open" }
    }

    /// A pack's line, as KIFF sells it; "a pack" when it isn't on sale.
    static func packLine(_ id: String?, in u: KIFFClient.Usage?) -> String {
        guard let id else { return "a pack of credits" }
        return u?.store?.packs.first { $0.id == id }?.line ?? id.capitalized
    }
}

/// The balance at the top of Plan & usage (docs/accounts.md › In the apps): tafs cloud's
/// `words` and `detail`, a bar at `meter_pct`, the `warning`, then Raise the limit where
/// `raise` is set, and Solo or packs where `buy` says. Nothing from a tafs cloud without the
/// read; its words when it couldn't be read.
struct BalancePanel: View {
    @EnvironmentObject private var model: AppModel
    let buyCredits: () -> Void
    @State private var raising = false
    @State private var reading = false
    @State private var why: String?

    var body: some View {
        if let b = model.balance {
            let line = b.balance
            Panel(stroke: line.waiting ? Theme.no.opacity(0.5) : line.state == "warning" ? Theme.amber.opacity(0.5) : Theme.line) {
                // A refresh that failed keeps what was read and says why, as the Credits panel does.
                if let failed = model.balanceError {
                    Text(failed).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
                Text(line.words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let pct = line.meterPct {
                    Meter(fraction: Double(pct) / 100, color: UsageView.color(line.state, calm: Theme.ok))
                        .accessibilityHidden(true)
                }
                if let detail = line.detail {
                    Text(detail).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                if let warning = line.warning {
                    Text(warning).scaledFont(14, weight: .semibold).foregroundColor(UsageView.color(line.state, calm: Theme.ink))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if model.balanceStale {
                // The raise went through and the balance couldn't be read since: only a read is offered.
                Panel {
                    Text(Self.readSince).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    Button(reading ? "Reading…" : Self.readAgain) {
                        reading = true
                        Task { await model.readBalance(); reading = false }
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(reading)
                    .accessibilityIdentifier("balance-read-again")
                }
            } else if let raise = line.raise, model.mode == .kiff {
                ApprovalGate {
                    Button {
                        raising = true
                        why = nil
                        Task {
                            let answer = await model.raiseLimit()
                            why = answer?.isEmpty == true ? nil : answer
                            raising = false
                        }
                    } label: {
                        Label(raising ? "Raising…" : raise.words, systemImage: Biometry.symbol)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(raising)
                    .accessibilityIdentifier("raise-limit")
                }
            }
            if let why {
                Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            if model.mode == .kiff, let buy = line.buy {
                if buy.packs, !(model.usage?.store?.packs.isEmpty ?? true) {
                    if line.waiting {
                        Button(Self.buyCredits) { buyCredits() }.buttonStyle(PrimaryButtonStyle())
                    } else {
                        Button(Self.buyCredits) { buyCredits() }.buttonStyle(OutlineButtonStyle())
                    }
                }
                if buy.solo, model.usage?.store != nil {
                    NavigationLink(destination: SoloView().environmentObject(model)) {
                        Panel {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Solo").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                                    Text(SoloView.line).scaledFont(13).foregroundColor(Theme.dim)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").scaledFont(13).foregroundColor(Theme.dim)
                            }
                        }
                    }
                    .buttonStyle(.drivablePlain)
                }
            }
        } else if let failed = model.balanceError {
            Panel { Text(failed).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true) }
        }
    }

    static let buyCredits = "Add credits"
    static let readAgain = "Read it again"
    /// After a raise whose read failed (docs/accounts.md › In the apps).
    static let readSince = "Your tafs can go on. This period's credits couldn't be read since."
}
