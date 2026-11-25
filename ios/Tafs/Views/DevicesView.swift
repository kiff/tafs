import SwiftUI

/// Settings, Devices: the devices signed in as this person on the account,
/// each with when it was last used, and Sign out for any but this one.
struct DevicesView: View {
    static let title = Words.Settings.Devices.title
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var removing: KIFFClient.Device?
    @State private var pairing = false
    @State private var removingPasskey: KIFFClient.Passkey?
    @State private var addingPasskey = false
    /// How the last Add ended, in the view's words; nil for nothing to say (a cancel).
    @State private var passkeyNote: String?

    var body: some View {
        List {
            if let error = model.devicesError {
                Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
            }
            Section {
                ForEach(model.devices) { device in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(device.name ?? Words.Settings.Devices.unnamed).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(Self.lastUsed(device.lastUsed)).scaledFont(13).foregroundColor(Theme.dim)
                        }
                        Spacer()
                        if device.thisDevice {
                            Text(Words.Settings.Devices.thisDeviceTitle).scaledFont(13).foregroundColor(Theme.dim)
                        } else {
                            Button(Words.Settings.Devices.signOut) { removing = device }
                                .buttonStyle(.borderless)
                                .confirming()
                                .foregroundColor(Theme.no)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text(Words.Settings.Devices.noteDevice(device: "\(ThisDevice.kind)"))
            }
            // A computer pairs from here (KIFF's pairing contract §5): the phone approves it, never the computer itself.
            if model.mode == .kiff || Screenshots.enabled {
                Section {
                    Button { pairing = true } label: { Label(Words.Settings.Devices.pair, systemImage: "desktopcomputer") }
                        .confirming()
                        .accessibilityIdentifier("devices.pair")
                }
            }
            if model.mode == .kiff { passkeys }
        }
        .scrollContentBackground(.hidden)
        .pageColumn(PageWidth.reading)
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await model.refreshDevices(); await model.refreshPasskeys() }
        .refreshable { await model.refreshDevices(); await model.refreshPasskeys() }
        // Back on screen after leaving it (another app, the phone locked) or after Add a device: read again, so a
        // device added meanwhile shows without closing Devices and opening it again. As Android's (tafs#1281).
        .onChange(of: scenePhase) { phase in
            if phase == .active { Task { await model.refreshDevices() } }
        }
        .sheet(isPresented: $pairing, onDismiss: { Task { await model.refreshDevices() } }) { PairComputerView() }
        .confirmationDialog(removing.map { $0.name.map { Words.Settings.Devices.signOutNamed(device: $0) } ?? Words.Settings.Devices.signOutThis } ?? "",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button(Words.Settings.Devices.signOut, role: .destructive) {
                if let device = removing { Task { await model.removeDevice(device) } }
                removing = nil
            }
        }
        .confirmationDialog(removingPasskey.map { Words.Settings.Devices.passkeyRemoveNamed(name: Self.passkeyName($0)) } ?? "",
                            isPresented: Binding(get: { removingPasskey != nil }, set: { if !$0 { removingPasskey = nil } }),
                            titleVisibility: .visible) {
            Button(Words.Settings.Devices.passkeyRemove, role: .destructive) {
                if let passkey = removingPasskey {
                    Task { await model.removePasskey(passkey, reason: Words.Settings.Devices.passkeyRemovePrompt(name: Self.passkeyName(passkey))) }
                }
                removingPasskey = nil
            }
        } message: {
            // The last passkey that can approve says so before it goes.
            if let passkey = removingPasskey, AppModel.removesLastPasskey(passkey, among: model.passkeys) {
                Text(Words.Settings.Devices.passkeyRemoveLast)
            }
        }
    }

    /// The account's passkeys (KIFF's pairing contract, "Registering one"): each by its name, Remove for the person's
    /// own, and a computer's own going with its computer. Add makes one on this iPhone, behind Face ID twice (the
    /// platform's, then the approval key's), for an owner or admin on a session that approves. Add is the
    /// iPhone's for now: its words say "this phone".
    @ViewBuilder private var passkeys: some View {
        Section {
            if let error = model.passkeysError {
                Text(error).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(model.passkeys.enumerated()), id: \.offset) { _, passkey in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Self.passkeyName(passkey)).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        if !passkey.removable {
                            Text(Words.Settings.Devices.passkeyWithComputer).scaledFont(13).foregroundColor(Theme.dim)
                        }
                    }
                    Spacer()
                    if passkey.removable, model.canAddPasskey {
                        Button(Words.Settings.Devices.passkeyRemove) { removingPasskey = passkey }
                            .buttonStyle(.borderless)
                            .confirming()
                            .foregroundColor(Theme.no)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if ThisDevice.isPhone {
                if model.canAddPasskey {
                    Button(Words.Settings.Devices.passkeyAdd) { addPasskey() }
                        .disabled(addingPasskey)
                        .confirming()
                        .accessibilityIdentifier("devices.passkey.add")
                } else if let why = Self.cantAdd(role: model.me?.role) {
                    Text(why).scaledFont(13).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
                if let passkeyNote {
                    Text(passkeyNote).scaledFont(13).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
        } header: {
            Text(Words.Settings.Devices.passkeysTitle)
        } footer: {
            if ThisDevice.isPhone { Text(Words.Settings.Devices.passkeyNote) }
        }
    }

    private func addPasskey() {
        addingPasskey = true
        passkeyNote = nil
        // KIFF's name for this iPhone, as Devices lists it, when it's one KIFF takes for a passkey; else "iPhone".
        let name = PasskeyRegistration.name(model.devices.first(where: \.thisDevice)?.name ?? "") ?? ThisDevice.kind
        Task {
            let outcome = await model.addPasskey(name: name, reason: Words.Settings.Devices.passkeyPrompt)
            passkeyNote = Self.words(for: outcome, role: model.me?.role)
            addingPasskey = false
        }
    }

    /// A passkey's name, else a computer's own unnamed one as "Unnamed".
    static func passkeyName(_ passkey: KIFFClient.Passkey) -> String {
        passkey.name ?? Words.Settings.Devices.unnamed
    }

    /// Why this person can't add one: only owners and admins may; else this session doesn't approve.
    static func cantAdd(role: String?) -> String? {
        ["owner", "admin"].contains(role ?? "") ? Words.Settings.Devices.passkeyNeedsApprover : Words.Settings.Devices.passkeyAdminsOnly
    }

    /// What adding one says once it's over; nil for a cancel, which says nothing.
    static func words(for outcome: AppModel.PasskeyOutcome, role: String?) -> String? {
        switch outcome {
        case .added, .alreadyThere: return Words.Settings.Devices.passkeyAdded
        case .cancelled: return nil
        case .tooSlow: return Words.Settings.Devices.passkeyTooSlow
        case .notAllowed: return cantAdd(role: role)
        case .badName: return Words.Settings.Devices.passkeyNotMade(why: Words.Error.ours)
        case .failed(let why): return Words.Settings.Devices.passkeyNotMade(why: why)
        case .notRegistered(let why): return [Words.Settings.Devices.passkeyNotRegistered, why].compactMap { $0 }.joined(separator: "\n")
        }
    }

    /// "Last used 3 hours ago", or "Not used yet".
    static func lastUsed(_ date: Date?, now: Date = Date()) -> String {
        guard let date else { return Words.Settings.Devices.notUsed }
        let words = RelativeDateTimeFormatter().localizedString(for: date, relativeTo: now)
        return Words.Settings.Devices.lastUsed(ago: "\(words)")
    }
}
