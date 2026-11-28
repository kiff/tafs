import SwiftUI

/// Buying credits: the packs of credits KIFF sells, which never expire, at the App Store's
/// price. Offered where tafs cloud's balance says so (`buy.packs`).
struct BuyCreditsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        BuyCreditsContent(purchases: model.purchases, done: { dismiss() }).environmentObject(model)
    }

    static let title = Words.Usage.buy
    static let explain = Words.Billing.packAddsCreditsOnceNever
    /// What credits buy, above what packs are for.
    static let creditsBuy = Words.Billing.creditsBuy
}

private struct BuyCreditsContent: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var purchases: Purchases
    let done: () -> Void

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // What credits buy, then what packs are for.
                    Text(BuyCreditsView.creditsBuy)
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(BuyCreditsView.explain)
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(model.offeredPacks) { pack in
                        // The whole row buys, not only its price: one tap target, read as one button
                        // ("500 credits, 12,49 €"), and off until the App Store has priced it.
                        let price = purchases.products[pack.appleProduct]?.displayPrice
                        Button {
                            Task {
                                await model.buyPack(pack)
                                if model.planMessage == nil { done() }
                            }
                        } label: {
                            Panel {
                                HStack(alignment: .center, spacing: 12) {
                                    Text(pack.line).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Spacer()
                                    Text(price ?? "…").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isButton)
                        .confirming()
                        .disabled(price == nil || purchases.busy)
                    }
                    if purchases.confirming {
                        ConfirmingPurchaseLine()
                    } else if let message = model.planMessage {
                        Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                    Button(Words.Usage.restorePurchases) { Task { await model.restorePurchases() } }
                        .scaledFont(14).foregroundColor(Theme.soft)
                        .disabled(purchases.busy)
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle(BuyCreditsView.title)
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.close) { done() }.keyboardShortcut(.cancelAction) } }
        }
    }
}

/// Asking an admin, with a reason, for someone who doesn't manage billing (docs/accounts.md ›
/// The balance): on Company to raise the spend limit, on Solo for a pack.
struct AskAdminView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    /// `true` asks for a raise, `false` for a pack; nil asks for nothing.
    let raise: Bool?
    @State private var pack: String?
    @State private var reason = ""
    @State private var sending = false

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(raise == true ? Self.forRaise : Self.forPack)
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    if raise == false {
                        Panel {
                            Eyebrow(text: Words.Usage.Requests.whichPack)
                            ForEach(model.offeredPacks) { p in
                                Button { pack = p.id } label: {
                                    HStack {
                                        Text(p.line).scaledFont(15).foregroundColor(Theme.ink)
                                        Spacer()
                                        Image(systemName: pack == p.id ? "checkmark.circle.fill" : "circle")
                                            .foregroundColor(pack == p.id ? Theme.amber : Theme.dim)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.drivablePlain)
                            }
                        }
                    }
                    Panel {
                        Eyebrow(text: Words.Usage.Requests.whyOptional)
                        DriveField(Words.Billing.whatCredits, text: $reason)
                            .scaledFont(15)
                    }
                    if let message = model.planMessage {
                        Text(message).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    }
                    Button(sending ? Words.Usage.Requests.asking : Words.Usage.Requests.ask) {
                        guard let raise else { return }
                        let chosen = model.offeredPacks.first { $0.id == pack }
                        guard raise || chosen != nil else { return }
                        sending = true
                        Task {
                            let asked = await model.askAdmin(raise: raise, pack: chosen, reason: String(reason.prefix(500)))
                            sending = false
                            if asked { dismiss() }
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(raise == nil || (raise == false && pack == nil) || sending)
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle(UsageView.askAdmin)
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
    }

    static let forRaise = Words.Billing.Ask.lineRaise
    static let forPack = Words.Billing.Ask.linePack
}

/// Declining a member's request, with a note they'll read.
struct DeclineRequestView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let request: KIFFClient.PackRequest
    @State private var note = ""
    @State private var sending = false

    var body: some View {
        NavStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(DeclineRequestView.question(request, in: model.usage))
                    .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Panel {
                    Eyebrow(text: Words.Usage.Requests.noteOptional)
                    DriveField(Words.Usage.Requests.noteHint, text: $note).scaledFont(15)
                }
                if let message = model.planMessage {
                    Text(message).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
                Button(sending ? Words.Usage.Requests.declining : Words.Coach.Approval.decline) {
                    sending = true
                    Task {
                        let done = await model.declineRequest(request, note: String(note.prefix(500)))
                        sending = false
                        if done { dismiss() }
                    }
                }
                .buttonStyle(OutlineButtonStyle(color: Theme.no))
                .disabled(sending)
                Spacer()
            }
            .padding(16)
            .screenBackground()
            .navigationTitle(Words.Coach.Approval.decline)
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
    }
}

extension DeclineRequestView {
    /// "Decline the request to raise the spend limit?", "Decline the request for Rayo?"
    static func question(_ r: KIFFClient.PackRequest, in u: KIFFClient.Usage?) -> String {
        r.kind == "raise" ? Words.Billing.Request.declineRaiseTitle : Words.Billing.Request.declinePackTitle(pack: "\(UsageView.packLine(r.pack, in: u))")
    }
}

/// Solo, through the App Store: for an account with no plan billed elsewhere. The price is
/// the App Store's, and every offer shows its terms: how long a period is, its price, that it
/// renews until cancelled, how to cancel, and the Terms and Privacy Policy.
struct SoloView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        SoloContent(purchases: model.purchases).environmentObject(model)
    }
}

private struct SoloContent: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var purchases: Purchases

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let store = model.usage?.store, let product = purchases.products[store.soloProduct] {
                    Panel {
                        Eyebrow(text: Words.Usage.solo)
                        Text(SoloView.price(product)).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                        if let trial = product.subscription?.freeTrial {
                            Text(Words.Billing.startsFree(trial: "\(trial)")).scaledFont(15).foregroundColor(Theme.ok)
                        }
                        Text(SoloView.about)
                            .scaledFont(15).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    // Once KIFF reads Solo, back to Plan & usage, which says Solo is on; else this page stays, with why.
                    Button(SoloView.action(product)) { Task { if await model.subscribeSolo() { dismiss() } } }
                        .buttonStyle(PrimaryButtonStyle())
                        .confirming()
                        .disabled(purchases.busy)
                    Text(SoloView.terms(product)).scaledFont(13).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 16) {
                        Link(Legal.label(Words.Billing.termsUse), destination: Legal.terms)
                        Link(Legal.label(Words.Settings.privacyPolicy), destination: Legal.privacy)
                    }
                    .scaledFont(13)
                    .foregroundColor(Theme.soft)
                } else {
                    Panel { Text(Words.Billing.soloNotAvailableAppStore).scaledFont(15).foregroundColor(Theme.soft) }
                }
                if purchases.confirming {
                    ConfirmingPurchaseLine()
                } else if let message = model.planMessage {
                    Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                Button(Words.Usage.restorePurchases) { Task { await model.restorePurchases() } }
                    .scaledFont(14).foregroundColor(Theme.soft)
                    .disabled(purchases.busy)
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle(Words.Usage.solo)
        .inlineTitle()
    }
}

extension SoloView {
    /// Under Solo's row in Plan & usage.
    static let line = Words.Billing.tafsKiffOnePersonEvery
    static let about = Words.Billing.onePersonTafsKiffYour

    /// "$9.99 a month".
    static func price(_ p: StoreProduct) -> String {
        Words.Billing.pricePer(price: p.displayPrice, period: p.subscription?.period ?? Words.Usage.month)
    }

    /// The button: the free trial when this person can have it.
    static func action(_ p: StoreProduct) -> String {
        if let trial = p.subscription?.freeTrial { return Words.Billing.tryFree(trial: "\(trial)") }
        return Words.Billing.Solo.subscribeFor(price: "\(price(p))")
    }

    /// The subscription's terms, shown with every offer.
    static func terms(_ p: StoreProduct) -> String {
        let period = p.subscription?.period ?? Words.Usage.month
        var lines: [String] = []
        if let trial = p.subscription?.freeTrial {
            lines.append(Words.Billing.Solo.freeThen(trial: "\(trial)", price: "\(p.displayPrice)", period: "\(period)"))
        }
        lines.append(Words.Billing.soloSubscriptionOneChargedYour(period: "\(period)", price: "\(p.displayPrice)", period2: "\(period)"))
        lines.append(Words.Billing.cancelAnyTimeLeastDay)
        return lines.joined(separator: " ")
    }
}

/// "Confirming the purchase…", from the App Store's confirmation until KIFF has it (kiff/tafs#2189): a spinner and the
/// shared words, in place of the plan's message, while every purchase control waits.
struct ConfirmingPurchaseLine: View {
    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
            Text(Words.Billing.confirmingPurchase).scaledFont(14).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("confirming-purchase")
    }
}
