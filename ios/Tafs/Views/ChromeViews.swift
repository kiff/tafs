import SwiftUI

/// The browser connections that run in a Chrome ("Your Chrome", docs/chrome.md), as planque lists them on the Chrome: live ones only,
/// decided there once, never by the app. Unpairing the Chrome ends them, so they are read to the owner before it goes, and listed
/// under the Chrome.
enum ChromeConnections {
    /// The sites of those connections, as the owner reads them: "wikipedia.org, shop.example". Empty when none.
    static func sites(of connections: [PlanqueClient.Chrome.Connection]) -> String {
        var seen: [String] = []
        for site in connections.flatMap(\.sites) where !seen.contains(site) { seen.append(site) }
        return seen.joined(separator: ", ")
    }
}

/// Re-pairing replaces, never adds ("Your Chrome" 1, docs/chrome.md): which paired Chrome a new one replaces, and the connections
/// that move to it. The pick is the owner's; only a connection that is live on the Chrome replaced moves, with the sites planque
/// holds for it, which the phone's fingerprint then signs for the new Chrome (`ChromePairing.Carried`).
/// Which Chrome a browser connection runs in, and where it may move ("Your Chrome" 2 and 4).
enum ChromeSwitch {
    /// The action planque offers on this connection's row, only where its own `PUT …/chrome` would accept the move (one rule, in the
    /// service): the app keeps no status list of its own, and a planque that doesn't send it offers no move.
    static func action(of grant: PlanqueClient.Grant) -> PlanqueClient.Grant.Action? {
        guard grant.request.browser?.driver == "extension" else { return nil }
        return (grant.actions ?? []).first { $0.id == PlanqueClient.Grant.Action.useAnotherChrome && $0.known }
    }

    /// The sites to sign again for the new Chrome: the connection's own, **only if this phone's own signature verifies** them for the
    /// Chrome it uses now (`chrome_sites_sig` over `{chrome_id, grant, sites}`, by `phoneKey`, its approval key). Never from what planque
    /// says: a planque that widened the list would otherwise get the owner's signature over the wider one. Nil for anything else
    /// (no signature, another key, other sites, a connection carried here from an earlier Chrome).
    static func signedSites(of grant: PlanqueClient.Grant, phoneKey: Data) -> [String]? {
        guard let browser = grant.request.browser, browser.driver == "extension", let chrome = browser.chrome, !chrome.isEmpty,
              let sites = browser.sites, !sites.isEmpty, sites.count <= 100, let sig = grant.chromeSitesSig, !sig.isEmpty,
              ChromePairing.sitesSignatureHolds(sig, publicKeyDER: phoneKey, chromeID: chrome, grant: grant.id, sites: sites) else { return nil }
        return sites
    }

    /// The paired Chromes it could move to: every one but the one it uses.
    static func others(for grant: PlanqueClient.Grant, in chromes: [PlanqueClient.Chrome]) -> [PlanqueClient.Chrome] {
        chromes.filter { $0.id != grant.request.browser?.chrome }
    }
}

enum ChromeReplace {
    /// What the pick starts on: the Chrome with the offer's profile id; else, when the extension was reinstalled and its id is new,
    /// the one paired Chrome on the same browser and system. Nothing otherwise ("a new Chrome").
    static func preselect(offer: PlanqueClient.ChromeOffer, chromes: [PlanqueClient.Chrome]) -> PlanqueClient.Chrome? {
        if !offer.profileID.isEmpty, let same = chromes.first(where: { $0.profileID == offer.profileID }) { return same }
        let alike = chromes.filter { ($0.profileID ?? "").isEmpty || offer.profileID.isEmpty }
            .filter { !offer.browser.isEmpty && $0.browser == offer.browser && !offer.os.isEmpty && $0.os == offer.os }
        return alike.count == 1 ? alike[0] : nil
    }

    /// The connections that move when `old` is replaced: each of its live connections (planque's list on the Chrome) whose sites **this
    /// phone's own signature verifies** for the Chrome replaced (`chrome_sites_sig` over `{chrome_id, grant, sites}`, by `phoneKey`, its
    /// approval key, from planque's grant read), with those sites, in planque's order. Never from what planque says: a connection
    /// without a verifying signature, one carried here from an earlier Chrome, or one with no sites moves nothing and ends with the
    /// old Chrome, as unpairing does. A connection planque refuses to carry (pending, failed, ended) is left out. At most the
    /// extension's limits (50 connections, 100 sites each).
    static func carried(from old: PlanqueClient.Chrome, in grants: [PlanqueClient.Grant], phoneKey: Data) -> [ChromePairing.Carried] {
        (old.connections ?? []).compactMap { c in
            guard let g = grants.first(where: { $0.id == c.grant }), !["pending", "failed", "disconnected"].contains(g.status),
                  !c.sites.isEmpty, c.sites.count <= 100, let sig = g.chromeSitesSig, !sig.isEmpty,
                  ChromePairing.sitesSignatureHolds(sig, publicKeyDER: phoneKey, chromeID: old.id, grant: c.grant, sites: c.sites) else { return nil }
            return ChromePairing.Carried(grant: c.grant, sites: c.sites)
        }.prefix(50).map { $0 }
    }

    /// The connections on `old` that do not move: they end with it.
    static func ending(on old: PlanqueClient.Chrome, carried: [ChromePairing.Carried]) -> [PlanqueClient.Chrome.Connection] {
        (old.connections ?? []).filter { c in !carried.contains { $0.grant == c.grant } }
    }

    /// The connections that do move.
    static func moving(from old: PlanqueClient.Chrome, carried: [ChromePairing.Carried]) -> [PlanqueClient.Chrome.Connection] {
        (old.connections ?? []).filter { c in carried.contains { $0.grant == c.grant } }
    }
}

/// Account › Chrome (docs/chrome.md): the Chromes paired to the account, Pair a Chrome, and Unpair. It shows only once
/// planque offers Chromes (`AppModel.chromes` is nil until then): planque's switch decides, never the app.
struct ChromesView: View {
    static let title = Words.Chrome.title
    @EnvironmentObject private var model: AppModel
    @State private var pairing = false
    @State private var removing: PlanqueClient.Chrome?

    var body: some View {
        List {
            if let error = model.chromesError {
                Section { Text(error).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true) }
            }
            Section {
                if (model.chromes ?? []).isEmpty {
                    Text(Words.Chrome.none).scaledFont(15).foregroundColor(Theme.dim)
                }
                ForEach(model.chromes ?? []) { chrome in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chrome.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            if let browser = chrome.browser, let os = chrome.os {
                                Text(Words.Chrome.browserOn(browser: browser, system: os)).scaledFont(13).foregroundColor(Theme.dim)
                            }
                            let used = ChromeConnections.sites(of: chrome.connections ?? [])
                            if !used.isEmpty {
                                Text(Words.Chrome.usedFor(sites: used)).scaledFont(13).foregroundColor(Theme.soft)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer()
                        Button(Words.Chrome.remove) { removing = chrome }
                            .buttonStyle(.borderless)
                            .confirming()
                            .foregroundColor(Theme.no)
                    }
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text(Words.Chrome.footer)
            }
            Section {
                Button { pairing = true } label: { Label(Words.Chrome.pair, systemImage: "globe") }
                    .confirming()
                    .accessibilityIdentifier("chrome.pair")
            } footer: {
                Text(Words.Chrome.profileNote)
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await model.refreshChromes() }
        .refreshable { await model.refreshChromes() }
        .sheet(isPresented: $pairing, onDismiss: { Task { await model.refreshChromes() } }) { PairChromeView() }
        .confirmationDialog(removing.map { Words.Chrome.removeNamed(name: $0.name) } ?? "",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button(Words.Chrome.remove, role: .destructive) {
                if let chrome = removing { Task { await model.removeChrome(chrome) } }
                removing = nil
            }
        } message: {
            // What unpairing ends (Your Chrome 3): the connections run in this Chrome, and come back only by connecting again.
            if let chrome = removing {
                let ends = ChromeConnections.sites(of: chrome.connections ?? [])
                if !ends.isEmpty { Text(Words.Chrome.unpairEnds(sites: ends)) }
            }
        }
    }
}

/// Pairing a Chrome (docs/chrome.md, "Pairing"): the phone shows a code whose last six characters only it knows, the owner
/// types it into Chrome, and the phone shows that Chrome only once its proof holds under that secret. Then the owner names
/// it, and Face ID or Touch ID signs. Nothing is paired from the code alone.
struct PairChromeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pairing: AppModel.ChromePairingInProgress?
    @State private var step: AppModel.ChromePairingStep = .waiting
    @State private var name = ""
    @State private var outcome: String?
    @State private var sending = false
    /// The paired Chrome this one replaces ("Your Chrome" 1), by id; none is "a new Chrome".
    @State private var replacingID: String?

    private var replacing: PlanqueClient.Chrome? { model.chromes?.first { $0.id == replacingID } }

    var body: some View {
        NavigationStack {
            ScrollView {
                content
                    .padding(20)
                    .frame(maxWidth: 560, alignment: .leading)
                    .frame(maxWidth: .infinity)
            }
            .screenBackground()
            .navigationTitle(Words.Chrome.pair)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(outcome == nil ? Words.Common.cancel : Words.Common.done) { dismiss() }
                }
            }
        }
        .task { await run() }
    }

    @ViewBuilder private var content: some View {
        if let outcome {
            Text(outcome).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("chrome.outcome")
        } else if case .offered(let offer) = step {
            VStack(alignment: .leading, spacing: 14) {
                Text(Words.Chrome.offeredTitle).scaledFont(22, weight: .bold, design: .rounded).foregroundColor(Theme.ink)
                Text(Words.Chrome.offeredSays(browser: offer.browser, system: offer.os)).scaledFont(15).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                DriveField(Words.Chrome.nameLabel, text: $name)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("chrome.name")
                if let chromes = model.chromes, !chromes.isEmpty {
                    // Re-pairing replaces, never adds: the pick starts on the Chrome this one most likely replaces.
                    Picker(Words.Chrome.replaceTitle, selection: $replacingID) {
                        Text(Words.Chrome.replaceNone).tag(String?.none)
                        ForEach(chromes) { chrome in Text(chrome.name).tag(Optional(chrome.id)) }
                    }
                    .accessibilityIdentifier("chrome.replace")
                    if let old = replacing {
                        let carried = ChromeReplace.carried(from: old, in: model.grants, phoneKey: model.approvalPublicKey)
                        let moves = ChromeConnections.sites(of: ChromeReplace.moving(from: old, carried: carried))
                        let ends = ChromeConnections.sites(of: ChromeReplace.ending(on: old, carried: carried))
                        if !moves.isEmpty { Text(Words.Chrome.replaceMoves(sites: moves)).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                        Text(Words.Chrome.replaceUnpairs(name: old.name)).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        if !ends.isEmpty { Text(Words.Chrome.unpairEnds(sites: ends)).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                    }
                }
                Text(Words.Chrome.onlyYours).scaledFont(14).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                Button {
                    Task { await confirm(offer) }
                } label: {
                    Text(Words.Chrome.confirm).frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(sending)
                .confirming()
                .accessibilityIdentifier("chrome.confirm")
            }
        } else if let pairing {
            VStack(alignment: .leading, spacing: 14) {
                Text(Words.Chrome.codeTitle).scaledFont(22, weight: .bold, design: .rounded).foregroundColor(Theme.ink)
                Text(pairing.code)
                    .font(.system(size: 34, weight: .bold, design: .monospaced))
                    .foregroundColor(Theme.ink)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("chrome.code")
                // The code is typed or pasted into the computer's Chrome, so it copies in one tap, for a few minutes
                // (`Clipboard.copyPairingCode`), and Universal Clipboard may carry it to the person's own computer.
                Button { Clipboard.copyPairingCode(pairing.code) } label: { Label(Words.Common.copy, systemImage: "doc.on.doc") }
                    .buttonStyle(OutlineButtonStyle())
                    .accessibilityIdentifier("chrome.code.copy")
                Text(Words.Chrome.codeBody).scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                Text(Words.Chrome.onlyYours).scaledFont(14).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    ProgressView()
                    Text(Words.Chrome.waiting).scaledFont(14).foregroundColor(Theme.dim)
                }
            }
        } else if let error = model.chromesError {
            Text(error).scaledFont(15).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
        } else {
            ProgressView()
        }
    }

    /// Opens the pairing, then reads it every 3 seconds until an extension's proof holds, or it ends.
    private func run() async {
        guard pairing == nil, let opened = await model.openChromePairing() else { return }
        pairing = opened
        for _ in 0..<200 {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if Task.isCancelled { return }
            let next = await model.chromePairingStep(opened)
            switch next {
            case .waiting:
                continue
            case .offered(let offer):
                // The Chrome it most likely replaces, and what is live on it, read now so the pick shows what would move.
                await model.refreshChromes()
                replacingID = ChromeReplace.preselect(offer: offer, chromes: model.chromes ?? [])?.id
                step = next
                return
            case .refused:
                outcome = Words.Chrome.refused
                return
            case .expired:
                outcome = Words.Chrome.expired
                return
            }
        }
        outcome = Words.Chrome.expired
    }

    private func confirm(_ offer: PlanqueClient.ChromeOffer) async {
        guard let pairing else { return }
        sending = true
        defer { sending = false }
        let named = ChromePairing.name(name, browser: Words.Chrome.browserOn(browser: offer.browser, system: offer.os))
        if await model.confirmChrome(pairing, offer: offer, name: name, replacing: replacing) {
            outcome = Words.Chrome.paired(name: named)
        } else if let error = model.chromesError {
            outcome = error
        }
    }
}
