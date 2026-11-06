import SwiftUI

/// Connect apps (A1 of the approved "The knob, end to end"): what tafs cloud offers, the catalog
/// first, searched as the owner types, in tafs cloud's order and words. Each app is connected,
/// to connect, or to sign in again. Connecting opens its approval as a sheet over the list (A3),
/// with KIFF's words for it; the vendor's sign-in comes after the approval, as for any
/// connection. A connection is a taf's: opened from Settings, Connect asks which taf first,
/// unless only one can have it. An app nobody listed goes to Coach.
struct ConnectAppsView: View {
    static let title = "Connect apps"
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
                    Button("Not listed? Ask Coach") { model.openCoach() }
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
            Text("\(app.vendor.isEmpty ? app.title : app.vendor) is connected for one taf, which uses it as its Card allows.")
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
                    Label("Connected", systemImage: "checkmark").labelStyle(.iconOnly).foregroundColor(Theme.ok)
                }
                .accessibilityLabel("Connected. Manage")
                .accessibilityIdentifier("connect.manage." + app.entry)
            } else if app.connected {
                Label("Connected", systemImage: "checkmark").labelStyle(.iconOnly).foregroundColor(Theme.ok)
                    .accessibilityLabel("Connected")
            } else {
                Button(app.needsReconnect ? "Sign in again" : "Connect") { connect(app) }
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
    static let replaceKey = "Replace the key"
    static let signInAgain = "Sign in again"
    static let cancel = "Cancel"
    static func waiting(_ vendor: String) -> String { "Waiting for the sign-in at \(vendor)" }
    static func cancelTitle(_ vendor: String) -> String { "Cancel the sign-in to \(vendor)?" }
    static func cancelBody(_ vendor: String) -> String { "Nothing is connected yet. Connect \(vendor) again from Coach or Connect apps when you're ready." }
}

enum DisconnectWords {
    static let footer = "Disconnecting one removes its tools from that taf only, and from any taf whose Card uses them. Other connections stay."

    /// A name's possessive: "Refunds'", "Chargebacks'"; else "Coach's".
    static func possessive(_ name: String) -> String { name.hasSuffix("s") ? "\(name)'" : "\(name)'s" }

    /// The question, naming the one taf it ends and `alsoStops`, the other tafs whose Card
    /// draws on its tools; the Face ID prompt asks the same.
    static func title(_ vendor: String, taf: String?, alsoStops: [String] = []) -> String {
        let asked = taf.map { "Disconnect \(vendor) from \($0)?" } ?? "Disconnect \(vendor)?"
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
        return (taf.map { "\(possessive($0)) \(vendor) tools stop working." } ?? "No taf uses this \(vendor) connection now. Its tools leave KIFF.")
            + others
            + " Other connections to \(vendor) stay connected. If no other connection uses this sign-in, it's revoked at \(vendor)."
            + " Connecting again asks you to approve and sign in again."
    }
}

/// A connected app's connections: which taf each was set up for (or that its taf was removed),
/// any other taf whose Card covers it, and
/// Disconnect, which removes its tools from KIFF and signs tafs out of the app (Face ID first).
struct AppConnectionsView: View {
    @EnvironmentObject private var model: AppModel
    let app: TafsCloud.ConnectApps.App
    @State private var busy: String?
    /// The connection whose Disconnect waits for the owner's yes.
    @State private var confirming: PlanqueClient.Grant?
    /// The key-based connection taking a new key (gap 13), on the key sheet.
    @State private var replacing: PlanqueClient.Grant?

    var body: some View {
        List {
            Section {
                ForEach(model.connections(of: app)) { g in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.setUpFor(g))
                                .scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                            if let also = model.alsoOnCards(g) {
                                Text(also).scaledFont(13).foregroundColor(Theme.soft)
                            }
                            if let words = g.statusWords, !words.isEmpty {
                                Text(words).scaledFont(13).foregroundColor(Theme.dim)
                            }
                            // Its last failed call, in KIFF's words (gap 13), and how to fix the connection.
                            if let failed = model.lastFailure(g) {
                                Text(failed).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                            }
                            if model.canManageCards {
                                Button(g.takesKey ? ConnectionFixWords.replaceKey : ConnectionFixWords.signInAgain) {
                                    if g.takesKey { replacing = g } else { Task { await model.reconnect(g) } }
                                }
                                .buttonStyle(.borderless)
                                .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                                .disabled(model.connecting != nil || busy != nil)
                                .accessibilityIdentifier("connect.fix." + g.id)
                            }
                            if let why = model.reconnectErrors[g.id] {
                                Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer()
                        if model.canManageCards {
                            Button(busy == g.id ? "Disconnecting…" : "Disconnect", role: .destructive) {
                                confirming = g
                            }
                            .buttonStyle(.borderless)
                            .confirming()
                            .disabled(busy != nil)
                            .accessibilityIdentifier("connect.disconnect." + g.id)
                        }
                    }
                    .frame(minHeight: 44)
                }
            } footer: {
                if model.canManageCards {
                    Text(DisconnectWords.footer)
                }
            }
            if let error = model.lastError, error.hasPrefix("Not disconnected") {
                Text(error).foregroundColor(Theme.no)
            }
        }
        .navigationTitle("Your \(app.title) connections")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $replacing) { g in
            ConnectApprovalSheet(start: .init(grant: g.id, needsKey: true)).environmentObject(model)
        }
        // Asked first, naming the one taf it ends; only then Face ID, in the same words.
        .confirmationDialog(confirming.map { DisconnectWords.title($0.vendor, taf: model.tafOf($0), alsoStops: model.alsoStops($0)) } ?? "",
                            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { g in
            Button("Disconnect", role: .destructive) { disconnect(g) }
            Button("Cancel", role: .cancel) { confirming = nil }
        } message: { g in
            Text(DisconnectWords.message(g.vendor, taf: model.tafOf(g), alsoStops: model.alsoStops(g)))
        }
    }

    private func disconnect(_ g: PlanqueClient.Grant) {
        confirming = nil
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
                            DriveField("The key", text: $key, kind: .secure)
                                .textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never)
                                .padding(12).background(Theme.raised).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .accessibilityIdentifier("connect.key")
                        }
                        if let error = model.lastError {
                            Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                        }
                        ApprovalGate {
                            Button(busy ? "Approving…" : start.needsKey ? "Give the key with \(Biometry.name)" : "Approve with \(Biometry.name)") {
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
            .navigationTitle(grant.map { "Connect \($0.vendor)" } ?? "Connect")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
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
