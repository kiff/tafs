import SwiftUI

/// Adding tasks: the packs, each with its tasks and how long they last, at the App Store's
/// price. For someone who may add tasks.
struct AddTasksView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        AddTasksContent(purchases: model.purchases, done: { dismiss() }).environmentObject(model)
    }
}

private struct AddTasksContent: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var purchases: Purchases
    let done: () -> Void

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("A pack adds tasks once. Your plan's tasks are used first each period, then packs, oldest first.")
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(model.usage?.store?.packs ?? []) { pack in
                        Panel {
                            HStack(alignment: .center, spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(pack.line).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                                    Text(AddTasksView.lasts(pack)).scaledFont(13).foregroundColor(Theme.dim)
                                }
                                Spacer()
                                Button(purchases.products[pack.appleProduct]?.displayPrice ?? "…") {
                                    Task {
                                        await model.buyPack(pack)
                                        if model.tasksMessage == nil { done() }
                                    }
                                }
                                .scaledFont(15, weight: .semibold)
                                .foregroundColor(Theme.amber)
                                .disabled(purchases.products[pack.appleProduct] == nil || purchases.buying != nil)
                            }
                        }
                    }
                    if let message = model.tasksMessage {
                        Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                    Button("Restore purchases") { Task { await model.restorePurchases() } }
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle("Add tasks")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { done() }.keyboardShortcut(.cancelAction) } }
        }
    }
}

extension AddTasksView {
    /// "They last 12 months."
    static func lasts(_ pack: KIFFClient.Usage.Store.Pack) -> String {
        pack.months == 1 ? "They last 1 month." : "They last \(pack.months) months."
    }
}

/// Asking an admin for a pack, with a reason: for a member who can't add tasks.
struct AskAdminView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pack: String?
    @State private var reason = ""
    @State private var sending = false

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Your account's admins get your request. When one adds the pack, your tafs go on.")
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Panel {
                        Eyebrow(text: "Which pack")
                        ForEach(model.usage?.store?.packs ?? []) { p in
                            Button { pack = p.id } label: {
                                HStack {
                                    Text(p.line).scaledFont(15).foregroundColor(Theme.ink)
                                    Spacer()
                                    Image(systemName: pack == p.id ? "checkmark.circle.fill" : "circle")
                                        .foregroundColor(pack == p.id ? Theme.amber : Theme.dim)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Panel {
                        Eyebrow(text: "Why (optional)")
                        TextField("What the tasks are for", text: $reason)
                            .scaledFont(15)
                    }
                    if let message = model.tasksMessage {
                        Text(message).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    }
                    Button(sending ? "Asking…" : "Ask") {
                        guard let id = pack, let p = model.usage?.store?.packs.first(where: { $0.id == id }) else { return }
                        sending = true
                        Task {
                            let asked = await model.askForPack(p, reason: String(reason.prefix(500)))
                            sending = false
                            if asked { dismiss() }
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(pack == nil || sending)
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle("Ask an admin")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
    }
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
                Text("Decline the request for \(UsageView.packLine(request.pack, in: model.usage))?")
                    .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Panel {
                    Eyebrow(text: "Note (optional)")
                    TextField("Why, for them", text: $note).scaledFont(15)
                }
                if let message = model.tasksMessage {
                    Text(message).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
                Button(sending ? "Declining…" : "Decline") {
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
            .navigationTitle("Decline")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction) } }
        }
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
    @ObservedObject var purchases: Purchases

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let store = model.usage?.store, let product = purchases.products[store.soloProduct] {
                    Panel {
                        Eyebrow(text: "Solo")
                        Text(SoloView.price(product)).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                        if let trial = product.subscription?.freeTrial {
                            Text("Starts with \(trial) free.").scaledFont(15).foregroundColor(Theme.ok)
                        }
                        Text("One person: tafs, and KIFF for your own agents, with tasks included each month.")
                            .scaledFont(15).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button(SoloView.action(product)) { Task { await model.subscribeSolo() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(purchases.buying != nil)
                    Text(SoloView.terms(product)).scaledFont(13).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 16) {
                        Link("Terms of Use", destination: Legal.terms)
                        Link("Privacy Policy", destination: Legal.privacy)
                    }
                    .scaledFont(13)
                    .foregroundColor(Theme.soft)
                } else {
                    Panel { Text("Solo isn't available from the App Store right now.").scaledFont(15).foregroundColor(Theme.soft) }
                }
                if let message = model.tasksMessage {
                    Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                Button("Restore purchases") { Task { await model.restorePurchases() } }
                    .scaledFont(14).foregroundColor(Theme.soft)
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle("Solo")
        .inlineTitle()
    }
}

extension SoloView {
    /// "$9.99 a month".
    static func price(_ p: StoreProduct) -> String {
        "\(p.displayPrice) a \(p.subscription?.period ?? "month")"
    }

    /// The button: the free trial when this person can have it.
    static func action(_ p: StoreProduct) -> String {
        if let trial = p.subscription?.freeTrial { return "Try \(trial) free" }
        return "Subscribe for \(price(p))"
    }

    /// The subscription's terms, shown with every offer.
    static func terms(_ p: StoreProduct) -> String {
        let period = p.subscription?.period ?? "month"
        var lines: [String] = []
        if let trial = p.subscription?.freeTrial {
            lines.append("Free for \(trial), then \(p.displayPrice) a \(period).")
        }
        lines.append("Solo is a subscription of one \(period) at \(p.displayPrice), charged to your Apple account. It renews every \(period) until you cancel.")
        lines.append("Cancel any time, at least a day before it renews, in your Apple account's Subscriptions.")
        return lines.joined(separator: " ")
    }
}
