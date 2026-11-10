import SwiftUI

/// Settings, Devices: the devices signed in as this person on the account,
/// each with when it was last used, and Sign out for any but this one.
struct DevicesView: View {
    static let title = Words.Settings.Devices.title
    @EnvironmentObject private var model: AppModel
    @State private var removing: KIFFClient.Device?

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
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await model.refreshDevices() }
        .refreshable { await model.refreshDevices() }
        .confirmationDialog(removing.map { $0.name.map { Words.Settings.Devices.signOutNamed(device: $0) } ?? Words.Settings.Devices.signOutThis } ?? "",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button(Words.Settings.Devices.signOut, role: .destructive) {
                if let device = removing { Task { await model.removeDevice(device) } }
                removing = nil
            }
        }
    }

    /// "Last used 3 hours ago", or "Not used yet".
    static func lastUsed(_ date: Date?, now: Date = Date()) -> String {
        guard let date else { return Words.Settings.Devices.notUsed }
        let words = RelativeDateTimeFormatter().localizedString(for: date, relativeTo: now)
        return Words.Settings.Devices.lastUsed(ago: "\(words)")
    }
}
