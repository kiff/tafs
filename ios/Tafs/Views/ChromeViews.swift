import SwiftUI

/// The browser connections that run in a Chrome ("Your Chrome", docs/chrome.md): those planque holds for it that haven't ended.
/// Unpairing the Chrome ends them, so they are read to the owner before it goes, and listed under the Chrome.
enum ChromeConnections {
    static func on(_ chrome: PlanqueClient.Chrome, in grants: [PlanqueClient.Grant]) -> [PlanqueClient.Grant] {
        grants.filter { g in
            g.request.browser?.driver == "extension" && g.request.browser?.chrome == chrome.id
                && g.status != "disconnected" && g.status != "failed"
        }
    }

    /// The sites of those connections, as the owner reads them: "wikipedia.org, shop.example". Empty when none.
    static func sites(of connections: [PlanqueClient.Grant]) -> String {
        var seen: [String] = []
        for site in connections.flatMap({ $0.request.browser?.sites ?? [] }) where !seen.contains(site) { seen.append(site) }
        return seen.joined(separator: ", ")
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
                            let used = ChromeConnections.sites(of: ChromeConnections.on(chrome, in: model.grants))
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
        .task { await model.refreshChromes(); await model.refreshConnections() }
        .refreshable { await model.refreshChromes(); await model.refreshConnections() }
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
                let ends = ChromeConnections.sites(of: ChromeConnections.on(chrome, in: model.grants))
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
            case .offered:
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
        if await model.confirmChrome(pairing, offer: offer, name: name) {
            outcome = Words.Chrome.paired(name: named)
        } else if let error = model.chromesError {
            outcome = error
        }
    }
}
