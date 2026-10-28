import SwiftUI

/// Settings, Devices: the devices signed in as this person on the account,
/// each with when it was last used, and Sign out for any but this one.
struct DevicesView: View {
    static let title = "Devices"
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
                            Text(device.name ?? "A device").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(Self.lastUsed(device.lastUsed)).scaledFont(13).foregroundColor(Theme.dim)
                        }
                        Spacer()
                        if device.thisDevice {
                            Text("This device").scaledFont(13).foregroundColor(Theme.dim)
                        } else {
                            Button("Sign out") { removing = device }
                                .buttonStyle(.borderless)
                                .foregroundColor(Theme.no)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text("Signing a device out ends its sign-in on this account at once. To sign out this \(ThisDevice.kind), use Sign out of KIFF.")
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await model.refreshDevices() }
        .refreshable { await model.refreshDevices() }
        .confirmationDialog(removing.map { "Sign \($0.name ?? "this device") out?" } ?? "",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button("Sign out", role: .destructive) {
                if let device = removing { Task { await model.removeDevice(device) } }
                removing = nil
            }
        }
    }

    /// "Last used 3 hours ago", or "Not used yet".
    static func lastUsed(_ date: Date?, now: Date = Date()) -> String {
        guard let date else { return "Not used yet" }
        let words = RelativeDateTimeFormatter().localizedString(for: date, relativeTo: now)
        return "Last used \(words)"
    }
}
