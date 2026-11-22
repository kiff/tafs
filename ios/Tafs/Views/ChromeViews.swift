import SwiftUI

/// Re-pairing replaces, never adds ("Your Chrome" 1, docs/chrome.md): which paired Chrome a new one replaces, and the connections
/// that move to it. The pick is the owner's; only a connection that is live on the Chrome replaced moves, with the sites planque
/// holds for it, which the phone's fingerprint then signs for the new Chrome (`ChromePairing.Carried`).
enum ChromeReplace {
    /// What the pick starts on: the Chrome with the offer's profile id; else, when the extension was reinstalled and its id is new,
    /// the one paired Chrome on the same browser and system. Nothing otherwise ("a new Chrome").
    static func preselect(offer: PlanqueClient.ChromeOffer, chromes: [PlanqueClient.Chrome]) -> PlanqueClient.Chrome? {
        if !offer.profileID.isEmpty, let same = chromes.first(where: { $0.profileID == offer.profileID }) { return same }
        let alike = chromes.filter { ($0.profileID ?? "").isEmpty || offer.profileID.isEmpty }
            .filter { !offer.browser.isEmpty && $0.browser == offer.browser && !offer.os.isEmpty && $0.os == offer.os }
        return alike.count == 1 ? alike[0] : nil
    }

    /// The connections that move when `old` is replaced: each live one whose sites **this phone's own signature verifies** for the
    /// Chrome replaced (`chrome_sites_sig` over `{chrome_id, grant, sites}`, by `phoneKey`, its approval key), with those sites, in
    /// planque's order. Never from what planque says: a connection without a verifying signature, one carried here from an earlier
    /// Chrome, or one with no sites moves nothing and ends with the old Chrome, as unpairing does. At most the extension's limits
    /// (50 connections, 100 sites each).
    static func carried(from old: PlanqueClient.Chrome, in grants: [PlanqueClient.Grant], phoneKey: Data) -> [ChromePairing.Carried] {
        ChromeConnections.on(old, in: grants).compactMap { g in
            guard g.connected, let sites = g.request.browser?.sites, !sites.isEmpty, sites.count <= 100, let sig = g.chromeSitesSig, !sig.isEmpty,
                  ChromePairing.sitesSignatureHolds(sig, publicKeyDER: phoneKey, chromeID: old.id, grant: g.id, sites: sites) else { return nil }
            return ChromePairing.Carried(grant: g.id, sites: sites)
        }.prefix(50).map { $0 }
    }

    /// The live connections on `old` that do not move: they end with it.
    static func ending(on old: PlanqueClient.Chrome, in grants: [PlanqueClient.Grant], carried: [ChromePairing.Carried]) -> [PlanqueClient.Grant] {
        ChromeConnections.on(old, in: grants).filter { g in !carried.contains { $0.grant == g.id } }
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
                        let moves = ChromeConnections.sites(of: ChromeConnections.on(old, in: model.grants).filter { g in carried.contains { $0.grant == g.id } })
                        let ends = ChromeConnections.sites(of: ChromeReplace.ending(on: old, in: model.grants, carried: carried))
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
                await model.refreshConnections()
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
