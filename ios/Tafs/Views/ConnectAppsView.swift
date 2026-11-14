import SwiftUI
import UIKit

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
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// On an iPad's full width, a table: App, How it connects, Whose, Status.
    private var table: Bool { sizeClass == .regular }

    var body: some View {
        List {
            if let apps = model.connectApps {
                // The person's own first, with a ✓, then the rest of the catalog, each in tafs cloud's order.
                let parts = ConnectRow.split(apps.apps)
                if !parts.yours.isEmpty {
                    Section {
                        if table { columns }
                        ForEach(parts.yours) { app in row(app) }
                    } header: { Text(Words.Connect.Apps.yours) }
                }
                if !parts.discover.isEmpty {
                    Section {
                        if table, parts.yours.isEmpty { columns }
                        ForEach(parts.discover) { app in row(app) }
                    } header: { Text(Words.Connect.Apps.discover) }
                }
                if apps.nextCursor != nil {
                    Button(loadingMore ? Words.Common.loading : Words.Connect.moreApps) {
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
                Text(Words.Connect.appsConnectTafMakeOne)
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
        .confirmationDialog(Words.Connect.whichTaf, isPresented: Binding(get: { choosing != nil }, set: { if !$0 { choosing = nil } }),
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

    /// The table's header, on an iPad: the same widths as each row's cells.
    private var columns: some View {
        HStack(spacing: 12) {
            Text(Words.Connect.Apps.columnApp).frame(maxWidth: .infinity, alignment: .leading)
            Text(Words.Connect.Apps.columnHow).frame(width: Self.howWidth, alignment: .leading)
            Text(Words.Connect.Apps.columnWhose).frame(width: Self.whoseWidth, alignment: .leading)
            Text(Words.Connect.Apps.columnStatus).frame(width: Self.statusWidth, alignment: .leading)
        }
        .scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundColor(Theme.dim)
        .accessibilityHidden(true)
    }
    private static let howWidth: CGFloat = 150
    private static let whoseWidth: CGFloat = 130
    private static let statusWidth: CGFloat = 230

    /// One app: its connected apps open their detail (what it can read and do, which tafs use
    /// it, and Disconnect); the rest have a compact Connect.
    @ViewBuilder
    private func row(_ app: TafsCloud.ConnectApps.App) -> some View {
        let said = ConnectRow(app, usedHere: model.connectionGroups(of: app).tafs.map(\.taf))
        if app.connected, !model.connections(of: app).isEmpty {
            NavigationLink { AppConnectionsView(app: app) } label: { cells(app, said) }
                .accessibilityIdentifier("connect.manage." + app.entry)
        } else {
            cells(app, said)
        }
    }

    @ViewBuilder
    private func cells(_ app: TafsCloud.ConnectApps.App, _ said: ConnectRow) -> some View {
        if table {
            HStack(spacing: 12) {
                name(app, said).frame(maxWidth: .infinity, alignment: .leading)
                Text(said.how).scaledFont(14).foregroundColor(Theme.soft).frame(width: Self.howWidth, alignment: .leading)
                Text(said.whose ?? "").scaledFont(14).foregroundColor(Theme.soft).frame(width: Self.whoseWidth, alignment: .leading)
                HStack(spacing: 8) {
                    if let setup = said.setup, !setup.isEmpty {
                        Text(setup).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).lineLimit(2)
                    } else if let status = said.status {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(status).scaledFont(14, weight: .semibold).foregroundColor(app.connected ? Theme.ok : Theme.amberText)
                            if let used = said.usedBy { Text(used).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).lineLimit(2) }
                        }
                    }
                    Spacer(minLength: 4)
                    trailing(app)
                }
                .frame(width: Self.statusWidth, alignment: .leading)
            }
            .frame(minHeight: 52)
        } else {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    name(app, said)
                    // How it connects, whose it is, and where it stands, in one line.
                    Text(said.second).scaledFont(13, relativeTo: .footnote).foregroundColor(app.needsReconnect ? Theme.amberText : Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                trailing(app)
            }
            .frame(minHeight: 52)
        }
    }

    /// The logo, from tafs cloud only, beside the name.
    private func name(_ app: TafsCloud.ConnectApps.App, _ said: ConnectRow) -> some View {
        HStack(spacing: 10) {
            if let url = model.cloud.logoURL(app.logo) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image.resizable().interpolation(.high).scaledToFit().frame(width: 24, height: 24)
                    }
                }
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)
            }
            Text(app.title).scaledFont(16, weight: .semibold).foregroundColor(app.needsSetup ? Theme.soft : Theme.ink)
                .accessibilityIdentifier("connect.title." + app.entry)
        }
    }

    /// A ✓ for a connected app; else a Connect (or Sign in again) as wide as its words, never the
    /// row's: on an iPad a button across the screen reads as a field to type in.
    @ViewBuilder
    private func trailing(_ app: TafsCloud.ConnectApps.App) -> some View {
        if app.needsSetup {
            // Not set up for anyone to connect yet: tafs cloud's words say so, and there's no Connect.
            EmptyView()
        } else if app.connected {
            Image(systemName: "checkmark").foregroundColor(Theme.ok)
                .accessibilityLabel(Words.Connect.Status.connected)
        } else {
            Button { connect(app) } label: {
                Text(app.needsReconnect ? Words.Connect.signInAgain : Words.Connect.Apps.connect).padding(.horizontal, 16)
            }
                .buttonStyle(OutlineButtonStyle())
                .fixedSize(horizontal: true, vertical: false)
                .disabled(starting != nil || (taf == nil && model.connectableTafs.isEmpty))
        }
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

/// What a Connect apps row says, from tafs cloud's fields (the same as Android's): how it connects,
/// whose it is, where it stands, and which tafs use it. Every word but the app's own is tafs
/// cloud's or a shared key.
struct ConnectRow: Equatable {
    let how: String
    let whose: String?
    let status: String?
    let usedBy: String?
    let line: String?

    /// `usedHere`: the tafs this phone's planque read names, until tafs cloud sends `used_by`.
    init(_ app: TafsCloud.ConnectApps.App, usedHere: [String] = []) {
        how = app.takesKey ? Words.Connect.How.key : Words.Connect.How.signIn
        whose = app.whose
        status = app.connected ? Words.Connect.Status.connected : app.needsReconnect ? Words.Connect.signInAgain : nil
        let names = app.usedBy ?? usedHere
        usedBy = app.yours && !names.isEmpty ? Words.Connect.Apps.usedBy(tafs: Words.list(names)) : nil
        line = app.line
        setup = app.needsSetup ? (app.setupWords ?? "") : nil
    }

    /// For an app not set up for anyone to connect yet: tafs cloud's words, in place of Connect.
    var setup: String?

    /// The phone's second line: an app to set up says tafs cloud's words for it; else tafs cloud's
    /// line, as sent; else how, whose, where it stands and who uses it.
    var second: String { setup ?? line ?? [how, whose, status, usedBy].compactMap { $0 }.joined(separator: " · ") }

    /// The person's own first (connected, or to sign in again), then the rest, each in tafs cloud's order.
    static func split(_ apps: [TafsCloud.ConnectApps.App]) -> (yours: [TafsCloud.ConnectApps.App], discover: [TafsCloud.ConnectApps.App]) {
        (apps.filter(\.yours), apps.filter { !$0.yours })
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
    /// What's cancelled, asked first: the request nobody answered, or the sign-in that never came back.
    static func cancelTitle(_ g: PlanqueClient.Grant) -> String {
        g.pendingRequest ? Words.Connect.CancelRequest.title(vendor: "\(g.vendor)") : Words.Connect.CancelSignIn.title(vendor: "\(g.vendor)")
    }
    /// The confirmation's own button for it.
    static func cancelWord(_ g: PlanqueClient.Grant) -> String {
        g.pendingRequest ? Words.Connect.CancelRequest.button : Words.Connect.CancelSignIn.button
    }
    /// The row's word for ending a connection: "Cancel the request" for one nobody answered,
    /// Cancel for a sign-in that waits, else Remove access.
    static func endWord(_ g: PlanqueClient.Grant) -> String {
        g.pendingRequest ? Words.Connect.CancelRequest.button : g.waitingSignIn ? cancel : Words.Connect.disconnectButton
    }
    static func cancelBody(_ vendor: String) -> String { Words.Connect.CancelSignIn.body(vendor: "\(vendor)") }
}

enum DisconnectWords {
    static let footer = Words.Connect.Apps.disconnectWhy


    /// The question, naming the one taf it ends and `alsoStops`, the other tafs whose Card
    /// draws on its tools; the Face ID prompt asks the same.
    static func title(_ vendor: String, taf: String?, alsoStops: [String] = []) -> String {
        let asked = taf.map { Words.Connect.Disconnect.titleFrom(vendor: "\(vendor)", taf: "\($0)") } ?? Words.Connect.Disconnect.title(vendor: "\(vendor)")
        guard !alsoStops.isEmpty else { return asked }
        return asked + " " + Words.Connect.Disconnect.alsoStops(count: alsoStops.count, names: Words.list(alsoStops))
    }

    /// What removing access ends. For a key (`key`), the key is deleted from tafs once no taf uses
    /// it: nothing is revoked at the vendor, which a sign-in's text says.
    static func message(_ vendor: String, taf: String?, alsoStops: [String] = [], key: Bool = false) -> String {
        let others: String
        switch alsoStops.count {
        case 0: others = ""
        case 1: others = " " + Words.Connect.Disconnect.alsoCard(owner: alsoStops[0])
        default: others = " " + Words.Connect.Disconnect.alsoCards(names: Words.list(alsoStops))
        }
        return (taf.map { Words.Connect.Disconnect.toolsStop(owner: $0, vendor: vendor) } ?? Words.Connect.Disconnect.noTafUses(vendor: "\(vendor)"))
            + others
            + (key ? " " + Words.Connect.Disconnect.restKey(vendor: "\(vendor)")
               : Words.Connect.otherConnectionsStayConnectedNo(vendor: "\(vendor)", vendor2: "\(vendor)") + Words.Connect.connectingAgainAsksYouApprove)
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
        if done > 0 { parts.append(Words.Connect.ClearRemoved.cleared(count: done)) }
        if kept > 0 { parts.append(keptLine(kept, distinct(keptFor))) }
        if left > 0 { parts.append(Words.Connect.couldNotClearedTryAgain(left: "\(left)")) }
        return parts.joined(separator: " ")
    }

    /// A connection a live taf's Card still uses, as tafs cloud says (`used_by`): kept, and for whom.
    static func keptLine(_ kept: Int, _ names: [String]) -> String {
        let who = names.isEmpty ? Words.Connect.Kept.anotherTaf : joined(names)
        return names.count > 1 ? Words.Connect.Kept.forCards(count: kept, who: who) : Words.Connect.Kept.forCard(count: kept, who: who)
    }

    /// Under a removed taf's connection that Clear keeps: the tafs whose Cards still use it.
    static func usedBy(_ names: [String]) -> String? {
        let n = distinct(names)
        guard !n.isEmpty else { return nil }
        return n.count == 1 ? Words.Connect.UsedBy.one(name: joined(n)) : Words.Connect.UsedBy.many(names: joined(n))
    }

    /// The names that aren't blank, each once, in order.
    private static func distinct(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0).inserted }
    }

    /// "A", "A and B", "A, B and C".
    private static func joined(_ names: [String]) -> String {
        Words.list(names)
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
                    ForEach(group.grants) { g in entry(g) }
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
                    ForEach(groups.others) { g in entry(g) }
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
            Text(DisconnectWords.message(g.vendor, taf: model.tafOf(g), alsoStops: model.alsoStops(g), key: g.takesKey))
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
        .confirmationDialog(cancelling.map { ConnectionFixWords.cancelTitle($0) } ?? "",
                            isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
                            titleVisibility: .visible, presenting: cancelling) { g in
            Button(ConnectionFixWords.cancelWord(g), role: .destructive) { disconnect(g) }
            Button(Words.Connect.CancelSignIn.keepWaiting, role: .cancel) { cancelling = nil }
        } message: { g in
            Text(ConnectionFixWords.cancelBody(g.vendor))
        }
    }

    /// A connection's row: as planque draws it when it says (its name, the tafs on it, its health), opening
    /// its detail, where its actions are; else the app's own row, as before.
    @ViewBuilder private func entry(_ g: PlanqueClient.Grant) -> some View {
        if g.rowFromPlanque {
            NavigationLink { ConnectionDetailView(grantID: g.id, appTitle: app.title).environmentObject(model) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(g.name ?? app.title).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    if let used = g.usedBy, !used.isEmpty {
                        Text(Words.Connect.Apps.usedBy(tafs: used.joined(separator: ", "))).scaledFont(13).foregroundColor(Theme.soft)
                    }
                    if let health = g.health {
                        Text(health.words).scaledFont(13).foregroundColor(health.ok ? Theme.dim : Theme.amberText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(minHeight: 44, alignment: .leading)
            }
            .accessibilityIdentifier("connect.row." + g.id)
        } else {
            row(g)
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
                } else if model.canManageCards, !g.cancellable {
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
                Button(busy == g.id ? Words.Connect.disconnecting : ConnectionFixWords.endWord(g), role: .destructive) {
                    if g.cancellable { cancelling = g } else { confirming = g }
                }
                .buttonStyle(.borderless)
                .confirming()
                .disabled(busy != nil)
                .accessibilityIdentifier("connect.disconnect." + g.id)
            }
        }
        .frame(minHeight: 44)
    }

    /// What a connection is: planque's name for it (`Grant.name`), else the app's
    /// name; planque's words for its status; the other tafs whose Card uses it; its last failure.
    @ViewBuilder private func lines(_ g: PlanqueClient.Grant) -> some View {
        Text(g.name ?? app.title)
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
            Text(g.name ?? app.title)
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
/// Approving opens the vendor's sign-in. An API-key vendor takes its key here instead, on a
/// sheet that looks and acts protected (docs/secure-connection.md): tafs cloud's words for it,
/// the key pasted with one button and never typed, only its last 4 characters shown, the
/// technical lines under Details, and a receipt once it's connected.
struct ConnectApprovalSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let start: TafsCloud.ConnectStart
    /// Called once the key was given and its receipt read: Coach's card, which opened this
    /// sheet for the key, is then approved as any other.
    var onGiven: (() -> Void)? = nil
    /// Coach's card this sheet was opened from, when it carries the taf's Card as a draft: the
    /// key and that Card can then go under one Face ID (docs/secure-connection.md).
    var card: SetupComponent? = nil
    /// The key and the Card went under one signature: Coach's card is answered already.
    @State private var together = false
    /// The pasted key. It's never drawn: only its last 4 characters are.
    @State private var key = ""
    /// Its last 4 characters, kept for the receipt after the key itself is forgotten.
    @State private var last4 = ""
    @State private var busy = false
    /// tafs cloud's words for the key's sheet, once read.
    @State private var sheet: TafsCloud.ConnectionSheet?
    /// tafs cloud's words were asked for, and came or didn't.
    @State private var sheetAsked = false
    /// The key went in: the sheet shows its receipt.
    @State private var given = false
    @State private var detailsOpen = false
    @State private var disconnecting = false

    private var grant: PlanqueClient.Grant? { model.grants.first { $0.id == start.grant } }
    /// The card's draft as this phone read it from KIFF: what Its Card shows, and the version that's signed.
    private var cardDraft: KIFFClient.AgentDraft? {
        guard let draft = card?.draft.flatMap({ model.setupDrafts[$0] }), !draft.confirmed, draft.cards != nil else { return nil }
        return draft
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let grant {
                        if !start.needsKey {
                            signIn(grant)
                        } else if !sheetAsked {
                            ProgressView().frame(maxWidth: .infinity)
                        } else if let sheet {
                            if !SecureKey.saysTrue(sheet, of: grant) {
                                // The sheet says read only, and the request this phone read isn't: nothing to paste or sign.
                                heading(sheet)
                                Text(Words.Connect.requestDoesNotMatchWhat)
                                    .scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                                    .accessibilityIdentifier("connect.sheet.unchecked")
                            } else if given {
                                receipt(grant, sheet)
                            } else {
                                keySheet(grant, sheet)
                            }
                        } else {
                            plainKeySheet(grant)
                        }
                    } else {
                        Text(Words.Connect.readingRequest).scaledFont(14).foregroundColor(Theme.dim)
                    }
                }
                .padding(20)
            }
            .navigationTitle(start.needsKey && sheet != nil ? "" : grant.map { Words.Coach.connectService(service: "\($0.vendor)") } ?? Words.Connect.Apps.connect)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if !given { Button(Words.Common.cancel) { dismiss() } }
                }
            }
            .interactiveDismissDisabled(given)
            .task(id: start.grant) {
                guard start.needsKey else { return }
                // tafs cloud's words for this grant. One that doesn't serve them yet leaves the plain sheet.
                sheet = await model.connectionSheet(grant: start.grant)
                sheetAsked = true
            }
        }
    }

    // MARK: A sign-in at the vendor: approved here, signed in after

    @ViewBuilder private func signIn(_ grant: PlanqueClient.Grant) -> some View {
        connectionWords(grant)
        mismatch(grant)
        errorLine
        ApprovalGate {
            Button(busy ? Words.Approval.approving : Words.Approval.approveWith(biometry: "\(Biometry.name)")) { approve(grant) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || !ready(grant))
        }
    }

    // MARK: A key, on the protected sheet, in tafs cloud's words

    @ViewBuilder private func keySheet(_ grant: PlanqueClient.Grant, _ sheet: TafsCloud.ConnectionSheet) -> some View {
        heading(sheet)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(sheet.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Image(systemName: SecureKey.icon(line.kind)).foregroundColor(Theme.soft).frame(width: 22).accessibilityHidden(true)
                    Text(line.text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityIdentifier("connect.sheet.lines")
        profileWords(grant)
        mismatch(grant)
        paste(what: sheet.paste.what,
              pasted: SecureKey.filled(sheet.paste.pasted, last4: last4, vendor: sheet.vendor.name),
              cleared: sheet.paste.cleared, next: sheet.paste.next)
        // Its Card, when the setup has one drafted: KIFF's own lines for the draft this phone read.
        // Connect then signs the key and this Card together, when tafs cloud asks for that.
        if let draft = cardDraft {
            VStack(alignment: .leading, spacing: 6) {
                Eyebrow(text: sheet.cardTitle ?? draft.cardTitle)
                ForEach(Array(draft.lines.enumerated()), id: \.offset) { _, line in
                    Text(line).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.raised)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityIdentifier("connect.sheet.card")
        }
        // What the tap agrees to, in sight before it: the one legal line, then Connect, right
        // under the pasted key (and the Card it signs with it), never below the technical lines.
        if let legal = sheet.legal {
            Text(SecureKey.legal(legal)).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                .tint(Theme.amberText).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("connect.sheet.legal")
        }
        errorLine
        ApprovalGate {
            Button(busy ? Words.Approval.approving : sheet.button) { approve(grant) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || !ready(grant))
                .accessibilityIdentifier("connect.sheet.connect")
        }
        DisclosureGroup(isExpanded: $detailsOpen) {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array((sheet.details?.lines ?? []).enumerated()), id: \.offset) { _, line in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(line.label).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                        Text(line.value).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    }
                }
                // The connection's own words, as the connection server keeps them: what its digest covers.
                Text(grant.text).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        } label: {
            Text(sheet.details?.title ?? "").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
        }
        .accessibilityIdentifier("connect.sheet.details")
    }

    /// The vendor's logo and name with a lock, and tafs cloud's title.
    private func heading(_ sheet: TafsCloud.ConnectionSheet) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                if let url = model.cloud.sheetLogoURL(sheet.vendor.logo) {
                    AsyncImage(url: url) { phase in
                        if case .success(let image) = phase {
                            image.resizable().interpolation(.high).scaledToFit().frame(width: 36, height: 36)
                        }
                    }
                    .accessibilityHidden(true)
                }
                Image(systemName: "lock.fill").foregroundColor(Theme.ok).accessibilityHidden(true)
                if let host = sheet.vendor.host, !host.isEmpty {
                    Text(host).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                }
            }
            Text(sheet.title).scaledFont(22, weight: .bold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("connect.sheet.title")
        }
    }

    /// Paste the key: Apple's own paste button, so the clipboard is read only on that tap and
    /// iOS asks nothing more. Never a typing field. Once pasted, only the key's last 4
    /// characters show, and the copy is taken off the clipboard, where other apps could read it.
    @ViewBuilder private func paste(what: String, pasted: String, cleared: String, next: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(what).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if !key.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text(pasted).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                        .accessibilityIdentifier("connect.key.pasted")
                    Text(cleared).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    // What to do next, in tafs cloud's words: the app has none of its own for it.
                    if let next = next?.trimmingCharacters(in: .whitespacesAndNewlines), !next.isEmpty {
                        Text(next).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("connect.key.next")
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.raised).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            PasteButton(payloadType: String.self) { strings in
                Task { @MainActor in take(strings) }
            }
            .disabled(busy)
            .accessibilityIdentifier("connect.key.paste")
        }
    }

    /// The key from a paste, kept for this sheet only, and the clipboard emptied.
    @MainActor private func take(_ pasted: [String]) {
        guard let taken = SecureKey.taken(from: pasted) else { return }
        key = taken
        last4 = SecureKey.last4(of: taken)
        Self.clearClipboard()
    }

    // MARK: The receipt, on the sheet

    @ViewBuilder private func receipt(_ grant: PlanqueClient.Grant, _ sheet: TafsCloud.ConnectionSheet) -> some View {
        heading(sheet)
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.ok).accessibilityHidden(true)
            Text(SecureKey.filled(sheet.receipt?.text ?? Words.Connect.Status.connected, last4: last4, vendor: sheet.vendor.name))
                .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("connect.sheet.receipt")
        }
        errorLine
        Button(Words.Common.done) { finish() }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(busy)
            .accessibilityIdentifier("connect.sheet.done")
        if let words = sheet.receipt?.disconnect, model.canManageCards {
            Button(busy ? Words.Connect.disconnecting : words, role: .destructive) { disconnecting = true }
                .buttonStyle(.borderless)
                .disabled(busy)
                .accessibilityIdentifier("connect.sheet.disconnect")
                // Asked first, naming the one taf it ends; only then Face ID, in the same words.
                .confirmationDialog(DisconnectWords.title(grant.vendor, taf: model.tafOf(grant), alsoStops: model.alsoStops(grant)),
                                    isPresented: $disconnecting, titleVisibility: .visible) {
                    Button(Words.Connect.disconnectButton, role: .destructive) { disconnect(grant) }
                    Button(Words.Common.cancel, role: .cancel) { }
                } message: {
                    Text(DisconnectWords.message(grant.vendor, taf: model.tafOf(grant), alsoStops: model.alsoStops(grant), key: grant.takesKey))
                }
        }
    }

    /// The receipt was read: the key is forgotten, and Coach's card goes on.
    private func finish() {
        last4 = ""
        // Under one signature the card is answered already; else it's approved now, as any other.
        if !together { onGiven?() }
        dismiss()
    }

    /// Disconnect from the receipt: the connection ends, so Coach's card isn't approved with it.
    private func disconnect(_ grant: PlanqueClient.Grant) {
        busy = true
        Task {
            defer { busy = false }
            if await model.disconnect(grantID: grant.id) {
                last4 = ""
                await model.loadConnectApps()
                dismiss()
            }
        }
    }

    // MARK: A key, when tafs cloud's words can't be read: the app's own lines, still pasted

    @ViewBuilder private func plainKeySheet(_ grant: PlanqueClient.Grant) -> some View {
        connectionWords(grant)
        mismatch(grant)
        // Where the key goes, before it's pasted: from this phone to the connection server,
        // which sends it only to the vendor's host, and never to Coach.
        paste(what: Words.Connect.keyGoesTo(host: "\(Self.host(of: grant))"),
              pasted: last4.isEmpty ? "…" : "…" + last4,
              cleared: Words.Connect.keyClipboard)
        errorLine
        ApprovalGate {
            Button(busy ? Words.Approval.approving : Words.Connect.giveKeyWith(biometry: "\(Biometry.name)")) { approve(grant) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || !ready(grant))
        }
    }

    // MARK: Shared

    /// The connection's own words from the connection server, and KIFF's for its profile.
    @ViewBuilder private func connectionWords(_ grant: PlanqueClient.Grant) -> some View {
        Text(grant.text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
        profileWords(grant)
    }

    /// KIFF's words for the connection's profile, never folded away: what KIFF can't check
    /// stays in sight, and Connect waits until they're read.
    @ViewBuilder private func profileWords(_ grant: PlanqueClient.Grant) -> some View {
        if grant.profile != nil {
            ProfileWordsView(words: model.shownWords(for: grant), error: model.profileWordsError[grant.id])
                .task(id: grant.digest) { await model.loadProfileWords(for: grant) }
        }
    }

    @ViewBuilder private func mismatch(_ grant: PlanqueClient.Grant) -> some View {
        if !grant.digestMatches {
            Text(Words.Connect.requestDoesNotMatchWhat)
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
        }
    }

    @ViewBuilder private var errorLine: some View {
        if let error = model.lastError {
            Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
        }
        // What tafs cloud said of the key and its Card together, when that didn't finish.
        if let card, let note = model.cardNotes[card.id] {
            Text(note).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Approve only what's shown: its digest checked, KIFF's words read when it has a profile,
    /// and for a key vendor, a key pasted.
    private func ready(_ grant: PlanqueClient.Grant) -> Bool {
        grant.digestMatches && (grant.profile == nil || model.shownWords(for: grant) != nil)
            && (!start.needsKey || !key.isEmpty)
    }

    private func approve(_ grant: PlanqueClient.Grant) {
        busy = true
        Task {
            defer { busy = false }
            if start.needsKey {
                // The key and its Card under one Face ID, when the card has a Card drafted and tafs
                // cloud asks for it. Not asked: the key goes by itself, as before.
                if let card, let draft = cardDraft {
                    switch await model.approveKeyAndCard(card, shown: grant.digest, shownDraft: draft.version, key: key) {
                    case .done:
                        key = ""
                        together = true
                        if sheet?.receipt != nil { given = true } else { finish() }
                        await model.loadConnectApps()
                        return
                    case .failed:
                        return
                    case .notOffered:
                        break
                    }
                }
                if await model.giveKey(grantID: grant.id, shown: grant.digest, key: key) {
                    // The key is forgotten now. The receipt keeps its last 4 until the sheet closes.
                    key = ""
                    if sheet?.receipt != nil {
                        given = true
                    } else {
                        finish()
                    }
                }
            } else if let done = await model.connect(grantID: grant.id, shown: grant.digest), done.connected || done.listed {
                dismiss()
            }
            await model.loadConnectApps()
        }
    }

    /// The host a key is used with: the vendor's server, without its scheme or path.
    static func host(of grant: PlanqueClient.Grant) -> String {
        URL(string: grant.request.server)?.host ?? grant.request.server
    }

    /// Empties the clipboard after a key was pasted. A test puts its own in.
    @MainActor static var clearClipboard: @MainActor () -> Void = { UIPasteboard.general.items = [] }
}

/// A connection's detail (planque's rows): its health, the tafs on it, and only the actions planque says
/// can work for it now. Remove is here, never beside the row, and asks once more, saying what stops.
struct ConnectionDetailView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let grantID: String
    let appTitle: String
    /// The remove action waiting for the owner's yes.
    @State private var asking: PlanqueClient.Grant.Action?
    /// The key-based connection taking a new key, on the key sheet.
    @State private var replacing: PlanqueClient.Grant?
    @State private var busy = false

    var body: some View {
        let g = model.grants.first { $0.id == grantID }
        List {
            if let g {
                Section {
                    if let health = g.health {
                        Text(health.words).scaledFont(15, weight: .semibold).foregroundColor(health.ok ? Theme.ink : Theme.amberText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let used = g.usedBy, !used.isEmpty {
                        Text(Words.Connect.Apps.usedBy(tafs: used.joined(separator: ", "))).scaledFont(14).foregroundColor(Theme.soft)
                    }
                    if let failed = model.lastFailure(g) {
                        Text(failed).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    }
                }
                let actions = (g.actions ?? []).filter(\.known)
                if model.canManageCards, !actions.isEmpty {
                    Section {
                        ForEach(actions) { action in
                            Button(role: action.removes ? .destructive : nil) { take(action, g) } label: {
                                Text(model.connecting == g.id && !action.removes ? model.connectStep ?? Words.Connect.openingSignIn : action.words)
                            }
                            .disabled(model.connecting != nil || busy)
                            .confirming()
                            .accessibilityIdentifier("connect.action." + action.id)
                        }
                        if let why = model.reconnectErrors[g.id] {
                            Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if model.lastErrorFrom == .disconnect, let error = model.lastError {
                    Text(error).foregroundColor(Theme.no)
                }
            }
        }
        .navigationTitle(g?.name ?? appTitle)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $replacing) { g in
            ConnectApprovalSheet(start: .init(grant: g.id, needsKey: true)).environmentObject(model)
        }
        // One more ask, in planque's words, naming what stops; only then Face ID.
        .confirmationDialog(asking?.words ?? "", isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
                            titleVisibility: .visible, presenting: asking) { action in
            Button(action.words, role: .destructive) { remove() }
            Button(Words.Common.cancel, role: .cancel) { asking = nil }
        } message: { action in
            if let stops = action.stops { Text(stops) }
        }
    }

    private func take(_ action: PlanqueClient.Grant.Action, _ g: PlanqueClient.Grant) {
        if action.removes { asking = action; return }
        if action.how == "key" { replacing = g } else { Task { await model.reconnect(g) } }
    }

    private func remove() {
        asking = nil
        busy = true
        Task {
            if await model.disconnect(grantID: grantID) {
                await model.loadConnectApps()
                dismiss()
            }
            busy = false
        }
    }
}
