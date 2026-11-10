import SwiftUI

/// Connect apps (A1 of the approved "The knob, end to end"): what tafs cloud offers, the catalog
/// first, searched as the owner types, in tafs cloud's order and words. Each app is connected,
/// to connect, or to sign in again. Connecting opens its approval as a sheet over the list (A3),
/// with KIFF's words for it; the vendor's sign-in comes after the approval, as for any
/// connection. A connection is a taf's: opened from Settings, Connect asks which taf first,
/// unless only one can have it. An app nobody listed goes to Coach.
struct ConnectAppsView: View {
    static let title = Words.Connect.Apps.title
    @EnvironmentObject private var model: AppModel
    /// The taf the apps connect for, when opened from one.
    var taf: UUID?
    @State private var query = ""
    @State private var starting: String?
    @State private var loadingMore = false
    /// The app waiting for its taf to be chosen.
    @State private var choosing: TafsCloud.ConnectApps.App?

    var body: some View {
        List {
            if let apps = model.connectApps {
                ForEach(apps.apps) { app in row(app) }
                if apps.nextCursor != nil {
                    Button(loadingMore ? "Loading…" : "More apps") {
                        loadingMore = true
                        Task { await model.loadMoreConnectApps(); loadingMore = false }
                    }
                    .disabled(loadingMore)
                    .accessibilityIdentifier("connect.more")
                }
                if apps.byKey {
                    Button(Words.Connect.Apps.notListed) { model.openCoach() }
                        .accessibilityIdentifier("connect.askCoach")
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
            if model.connectApps != nil, taf == nil, model.connectableTafs.isEmpty {
                Text("Apps connect for a taf. Make one first, then connect its apps here.")
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
        .navigationTitle(Self.title)
        .searchable(text: $query)
        .task(id: query) {
            // As the owner types, a moment after the last key.
            if !query.isEmpty { try? await Task.sleep(nanoseconds: 300_000_000) }
            guard !Task.isCancelled else { return }
            await model.loadConnectApps(query)
        }
        .confirmationDialog("Which taf is this for?", isPresented: Binding(get: { choosing != nil }, set: { if !$0 { choosing = nil } }),
                            titleVisibility: .visible, presenting: choosing) { app in
            ForEach(model.connectableTafs) { t in
                Button(t.name) { start(app, for: t.id) }
            }
        } message: { app in
            Text(Words.Connect.oneTaf(app: "\(app.vendor.isEmpty ? app.title : app.vendor)"))
        }
        .sheet(item: Binding(get: { model.connectStart.map(StartSheet.init) }, set: { if $0 == nil { model.connectStart = nil } })) { sheet in
            ConnectApprovalSheet(start: sheet.start).environmentObject(model)
        }
    }

    private struct StartSheet: Identifiable {
        let start: TafsCloud.ConnectStart
        var id: String { start.grant }
    }

    private func row(_ app: TafsCloud.ConnectApps.App) -> some View {
        HStack(spacing: 12) {
            Text(app.title).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                .accessibilityIdentifier("connect.title." + app.entry)
            Spacer()
            if app.connected, !model.connections(of: app).isEmpty {
                // Its connections, each with the taf it serves and Disconnect, on a screen of their own.
                NavigationLink { AppConnectionsView(app: app) } label: {
                    Label(Words.Connect.Status.connected, systemImage: "checkmark").labelStyle(.iconOnly).foregroundColor(Theme.ok)
                }
                .accessibilityLabel("Connected. Manage")
                .accessibilityIdentifier("connect.manage." + app.entry)
            } else if app.connected {
                Label(Words.Connect.Status.connected, systemImage: "checkmark").labelStyle(.iconOnly).foregroundColor(Theme.ok)
                    .accessibilityLabel(Words.Connect.Status.connected)
            } else {
                Button(app.needsReconnect ? Words.Connect.signInAgain : Words.Connect.Apps.connect) { connect(app) }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(starting != nil || (taf == nil && model.connectableTafs.isEmpty))
            }
        }
        .frame(minHeight: 44)
    }

    /// The taf first: the one this was opened from, the only one there is, or the owner's pick.
    private func connect(_ app: TafsCloud.ConnectApps.App) {
        let tafs = model.connectableTafs
        if let taf { start(app, for: taf) } else if tafs.count == 1, let only = tafs.first { start(app, for: only.id) } else { choosing = app }
    }

    private func start(_ app: TafsCloud.ConnectApps.App, for tafID: UUID) {
        choosing = nil
        starting = app.entry
        Task {
            await model.startConnect(app, for: tafID)
            starting = nil
        }
    }
}

/// Disconnect's words, the same on every screen and in the Face ID prompt: it names the one taf
/// it ends, and says other tafs' connections to the vendor stay.
/// How a connection is fixed, and a sign-in that never came back cancelled (gap 13), the same
/// as Android's words.
enum ConnectionFixWords {
    static let replaceKey = Words.Connect.replaceKey
    static let signInAgain = Words.Connect.signInAgain
    /// In place of Sign in again on a provider's connection, as planque says it.
    static func throughProvider(_ vendor: String) -> String {
        Words.Connect.throughProvider(vendor: "\(vendor)")
    }
    static let cancel = Words.Common.cancel
    static func waiting(_ vendor: String) -> String { Words.Connect.Status.waitingSignIn(vendor: "\(vendor)") }
    static func cancelTitle(_ vendor: String) -> String { Words.Connect.CancelSignIn.title(vendor: "\(vendor)") }
    static func cancelBody(_ vendor: String) -> String { Words.Connect.CancelSignIn.body(vendor: "\(vendor)") }
}

enum DisconnectWords {
    static let footer = Words.Connect.Apps.disconnectWhy

    /// A name's possessive: "Refunds'", "Chargebacks'"; else "Coach's".
    static func possessive(_ name: String) -> String { name.hasSuffix("s") ? "\(name)'" : "\(name)'s" }

    /// The question, naming the one taf it ends and `alsoStops`, the other tafs whose Card
    /// draws on its tools; the Face ID prompt asks the same.
    static func title(_ vendor: String, taf: String?, alsoStops: [String] = []) -> String {
        let asked = taf.map { Words.Connect.Disconnect.titleFrom(vendor: "\(vendor)", taf: "\($0)") } ?? Words.Connect.Disconnect.title(vendor: "\(vendor)")
        guard !alsoStops.isEmpty else { return asked }
        return "\(asked) \(alsoStops.joined(separator: " and ")) \(alsoStops.count == 1 ? "stops" : "stop") too."
    }

    static func message(_ vendor: String, taf: String?, alsoStops: [String] = []) -> String {
        let others: String
        switch alsoStops.count {
        case 0: others = ""
        case 1: others = " \(possessive(alsoStops[0])) Card also uses these tools, so it stops too."
        default: others = " The Cards of \(alsoStops.joined(separator: " and ")) also use these tools, so they stop too."
        }
        return (taf.map { Words.Connect.Disconnect.toolsStop(owner: "\(possessive($0))", vendor: "\(vendor)") } ?? Words.Connect.Disconnect.noTafUses(vendor: "\(vendor)"))
            + others
            + " Other connections to \(vendor) stay connected. If no other connection uses this sign-in, it's revoked at \(vendor)."
            + " Connecting again asks you to approve and sign in again."
    }
}

/// The words of an app's connections grouped by taf, the same on both apps.
enum ConnectionGroupWords {
    /// The last group: connections whose taf the app can't name and tafs cloud doesn't name as
    /// removed. That may be a taf this person doesn't see, an agent that isn't a taf, or a taf
    /// removed before tafs cloud named them, so the words claim none of them.
    static let others = Words.Connect.otherConnections

    // The connections of tafs tafs cloud names as removed (`removed_tafs`), under their own
    // heading, cleared at once.
    static let fromRemoved = Words.Connect.fromRemoved
    static let clearRemoved = Words.Connect.ClearRemoved.button
    static func clearTitle(_ n: Int) -> String { Words.Connect.ClearRemoved.title(count: n) }
    static let clearBody = Words.Connect.ClearRemoved.body
    static let clear = Words.Connect.ClearRemoved.clear

    /// How clearing went: "Cleared 2. Kept 1 for Chargebacks, whose Card uses it.", and what
    /// couldn't be, to try again. `kept` connections were kept for `keptFor`, the tafs whose
    /// Cards use them, by tafs cloud's names.
    static func cleared(done: Int, kept: Int, keptFor: [String], left: Int) -> String {
        var parts: [String] = []
        if done > 0 { parts.append("Cleared \(done).") }
        if kept > 0 { parts.append(keptLine(kept, distinct(keptFor))) }
        if left > 0 { parts.append("\(left) couldn't be cleared. Try again.") }
        return parts.joined(separator: " ")
    }

    /// A connection a live taf's Card still uses, as tafs cloud says (`used_by`): kept, and for whom.
    static func keptLine(_ kept: Int, _ names: [String]) -> String {
        let who = names.isEmpty ? Words.Connect.Kept.anotherTaf : joined(names)
        return "Kept \(kept) for \(who), whose Card\(names.count > 1 ? "s use" : " uses") \(kept == 1 ? "it" : "them")."
    }

    /// Under a removed taf's connection that Clear keeps: the tafs whose Cards still use it.
    static func usedBy(_ names: [String]) -> String? {
        let n = distinct(names)
        guard !n.isEmpty else { return nil }
        return "Kept: " + joined(n) + (n.count == 1 ? "'s Card uses it" : "'s Cards use it")
    }

    /// The names that aren't blank, each once, in order.
    private static func distinct(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0).inserted }
    }

    /// "A", "A and B", "A, B and C".
    private static func joined(_ names: [String]) -> String {
        names.count == 1 ? names[0] : names.dropLast().joined(separator: ", ") + " and " + (names.last ?? "")
    }
}

/// A connected app's connections, grouped by the taf each was set up for (the owner's ask): a
/// section per taf, those needing attention first, then the rest by name; and last, "Other
/// connections", those with no taf this app can name. Each shows any other taf whose Card
/// covers it, how to fix it, and Disconnect (Cancel while its sign-in waits), which removes its
/// tools from KIFF and signs tafs out of the app (Face ID first).
struct AppConnectionsView: View {
    @EnvironmentObject private var model: AppModel
    let app: TafsCloud.ConnectApps.App
    @State private var busy: String?
    /// The connection whose Disconnect waits for the owner's yes.
    @State private var confirming: PlanqueClient.Grant?
    /// The connection whose waiting sign-in Cancel would end.
    @State private var cancelling: PlanqueClient.Grant?
    /// The key-based connection taking a new key (gap 13), on the key sheet.
    @State private var replacing: PlanqueClient.Grant?
    /// The removed tafs' connections whose Clear waits for the owner's yes.
    @State private var clearing: [String]?

    var body: some View {
        let groups = model.connectionGroups(of: app)
        // What tafs cloud says of each removed taf's connection: only one it lists with nobody
        // using it is cleared. Not listed means no Clear.
        let listed = model.removedListed
        let clearable = groups.removed.filter { listed[$0.id]?.usedBy.isEmpty == true }.map(\.id)
        List {
            ForEach(groups.tafs) { group in
                Section {
                    ForEach(group.grants) { g in row(g) }
                } header: {
                    Text(group.taf)
                }
            }
            // Removed tafs' connections, as tafs cloud names them: no sign-in again and no
            // Disconnect each, one action that clears them.
            if !groups.removed.isEmpty {
                Section {
                    ForEach(groups.removed) { g in removedRow(g, usedBy: listed[g.id]?.usedBy) }
                    if model.canManageCards, !clearable.isEmpty {
                        Button(model.clearingRemoved ? Words.Connect.Apps.clearing : ConnectionGroupWords.clearRemoved, role: .destructive) { clearing = clearable }
                            .buttonStyle(.borderless)
                            .confirming()
                            .disabled(model.clearingRemoved || busy != nil)
                            .accessibilityIdentifier("connect.clear-removed")
                    }
                } header: {
                    Text(ConnectionGroupWords.fromRemoved)
                }
            }
            if let note = model.clearNote {
                Text(note).scaledFont(13).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("connect.clear-note")
            }
            if !groups.others.isEmpty {
                Section {
                    ForEach(groups.others) { g in row(g) }
                } header: {
                    Text(ConnectionGroupWords.others)
                }
            }
            if model.canManageCards {
                Section { } footer: { Text(DisconnectWords.footer) }
            }
            if model.lastErrorFrom == .disconnect, let error = model.lastError {
                Text(error).foregroundColor(Theme.no)
            }
        }
        .navigationTitle(Words.Connect.Apps.yourConnections(app: "\(app.title)"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $replacing) { g in
            ConnectApprovalSheet(start: .init(grant: g.id, needsKey: true)).environmentObject(model)
        }
        // Asked first, naming the one taf it ends; only then Face ID, in the same words.
        .confirmationDialog(confirming.map { DisconnectWords.title($0.vendor, taf: model.tafOf($0), alsoStops: model.alsoStops($0)) } ?? "",
                            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { g in
            Button(Words.Connect.disconnectButton, role: .destructive) { disconnect(g) }
            Button(Words.Common.cancel, role: .cancel) { confirming = nil }
        } message: { g in
            Text(DisconnectWords.message(g.vendor, taf: model.tafOf(g), alsoStops: model.alsoStops(g)))
        }
        // Clearing removed tafs' connections asks first, then one Face ID for all of them.
        .confirmationDialog(ConnectionGroupWords.clearTitle(clearing?.count ?? 0),
                            isPresented: Binding(get: { clearing != nil }, set: { if !$0 { clearing = nil } }),
                            titleVisibility: .visible, presenting: clearing) { ids in
            Button(ConnectionGroupWords.clear, role: .destructive) { clearRemoved(ids) }
            Button(Words.Common.cancel, role: .cancel) { clearing = nil }
        } message: { _ in
            Text(ConnectionGroupWords.clearBody)
        }
        .confirmationDialog(cancelling.map { ConnectionFixWords.cancelTitle($0.vendor) } ?? "",
                            isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
                            titleVisibility: .visible, presenting: cancelling) { g in
            Button(Words.Connect.CancelSignIn.button, role: .destructive) { disconnect(g) }
            Button(Words.Connect.CancelSignIn.keepWaiting, role: .cancel) { cancelling = nil }
        } message: { g in
            Text(ConnectionFixWords.cancelBody(g.vendor))
        }
    }

    /// A connection: its lines, how to fix it, and Disconnect, or Cancel while its sign-in waits.
    private func row(_ g: PlanqueClient.Grant) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                lines(g)
                if model.canManageCards, g.signsInThroughProvider {
                    // A provider's connection isn't signed in again in place: said before any Face ID.
                    Text(ConnectionFixWords.throughProvider(g.vendor)).scaledFont(13).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                } else if model.canManageCards, !g.waitingSignIn {
                    Button(g.takesKey ? ConnectionFixWords.replaceKey : ConnectionFixWords.signInAgain) {
                        if g.takesKey { replacing = g } else { Task { await model.reconnect(g) } }
                    }
                    .buttonStyle(.borderless)
                    .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                    .disabled(model.connecting != nil || busy != nil)
                    .accessibilityIdentifier("connect.fix." + g.id)
                    .confirming()
                }
                if let why = model.reconnectErrors[g.id] {
                    Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer()
            if model.canManageCards {
                Button(busy == g.id ? "Disconnecting…" : g.waitingSignIn ? ConnectionFixWords.cancel : "Disconnect", role: .destructive) {
                    if g.waitingSignIn { cancelling = g } else { confirming = g }
                }
                .buttonStyle(.borderless)
                .confirming()
                .disabled(busy != nil)
                .accessibilityIdentifier("connect.disconnect." + g.id)
            }
        }
        .frame(minHeight: 44)
    }

    /// What a connection is: the vendor's account when planque gives one, else the app's
    /// name; planque's words for its status; the other tafs whose Card uses it; its last failure.
    @ViewBuilder private func lines(_ g: PlanqueClient.Grant) -> some View {
        Text(g.account.flatMap { $0.isEmpty ? nil : $0 } ?? app.title)
            .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
        if let also = model.alsoOnCards(g) {
            Text(also).scaledFont(13).foregroundColor(Theme.soft)
        }
        if let words = g.statusWords, !words.isEmpty {
            Text(words).scaledFont(13).foregroundColor(Theme.dim)
        } else if g.waitingSignIn {
            Text(ConnectionFixWords.waiting(g.vendor)).scaledFont(13).foregroundColor(Theme.dim)
        }
        // Its last failed call, in KIFF's words (gap 13), and how to fix the connection.
        if let failed = model.lastFailure(g) {
            Text(failed).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
        }
    }

    /// A removed taf's connection: what it is, the removed taf it was made for by the name
    /// planque keeps, planque's status words, and the tafs whose Cards still use it.
    private func removedRow(_ g: PlanqueClient.Grant, usedBy: [TafsCloud.ConnectApps.UsedBy]?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(g.account.flatMap { $0.isEmpty ? nil : $0 } ?? app.title)
                .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            if let name = g.agentName, !name.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(name).scaledFont(13).foregroundColor(Theme.soft)
            }
            if let words = g.statusWords, !words.isEmpty {
                Text(words).scaledFont(13).foregroundColor(Theme.dim)
            }
            if let kept = usedBy.flatMap({ ConnectionGroupWords.usedBy($0.map(\.name)) }) {
                Text(kept).scaledFont(13).foregroundColor(Theme.soft)
            }
        }
        .frame(minHeight: 44, alignment: .leading)
    }

    /// Clears the removed tafs' connections (one Face ID), then reads the list again.
    private func clearRemoved(_ ids: [String]) {
        clearing = nil
        Task {
            await model.clearRemovedConnections(ids)
            await model.loadConnectApps()
        }
    }

    private func disconnect(_ g: PlanqueClient.Grant) {
        confirming = nil
        cancelling = nil
        busy = g.id
        Task {
            if await model.disconnect(grantID: g.id) { await model.loadConnectApps() }
            busy = nil
        }
    }

}

/// A connection's approval over the list: what it may do, KIFF's words for it, then Face ID.
/// Approving opens the vendor's sign-in; an API-key vendor takes its key here instead, given
/// with the same Face ID.
struct ConnectApprovalSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let start: TafsCloud.ConnectStart
    @State private var key = ""
    @State private var busy = false

    private var grant: PlanqueClient.Grant? { model.grants.first { $0.id == start.grant } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let grant {
                        Text(grant.text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                        if grant.profile != nil {
                            ProfileWordsView(words: model.shownWords(for: grant), error: model.profileWordsError[grant.id])
                                .task(id: grant.digest) { await model.loadProfileWords(for: grant) }
                        }
                        if !grant.digestMatches {
                            Text("This request doesn't match what it shows, so it can't be approved.")
                                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                        }
                        if start.needsKey {
                            DriveField(Words.Connect.key, text: $key, kind: .secure)
                                .textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never)
                                .padding(12).background(Theme.raised).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .accessibilityIdentifier("connect.key")
                        }
                        if let error = model.lastError {
                            Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                        }
                        ApprovalGate {
                            Button(busy ? Words.Approval.approving : start.needsKey ? Words.Connect.giveKeyWith(biometry: "\(Biometry.name)") : Words.Approval.approveWith(biometry: "\(Biometry.name)")) {
                                approve(grant)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(busy || !ready(grant))
                        }
                    } else {
                        Text("Reading the request…").scaledFont(14).foregroundColor(Theme.dim)
                    }
                }
                .padding(20)
            }
            .navigationTitle(grant.map { "Connect \($0.vendor)" } ?? Words.Connect.Apps.connect)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() } } }
        }
    }

    /// Approve only what's shown: its digest checked, KIFF's words read when it has a profile,
    /// and for a key vendor, a key typed.
    private func ready(_ grant: PlanqueClient.Grant) -> Bool {
        grant.digestMatches && (grant.profile == nil || model.shownWords(for: grant) != nil)
            && (!start.needsKey || !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private func approve(_ grant: PlanqueClient.Grant) {
        busy = true
        Task {
            defer { busy = false }
            if start.needsKey {
                if await model.giveKey(grantID: grant.id, shown: grant.digest, key: key) {
                    key = ""
                    dismiss()
                }
            } else if let done = await model.connect(grantID: grant.id, shown: grant.digest), done.connected || done.listed {
                dismiss()
            }
            await model.loadConnectApps()
        }
    }
}
