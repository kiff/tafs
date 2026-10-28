import SwiftUI

/// Settings, Notifications & haptics (the 901 test): two switches, each for this device only.
/// Sound off, alerts still arrive, without a sound or a banner; Haptics off, no taps.
struct NotificationSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(Haptics.key) private var haptics = true

    static let title = "Notifications & haptics"

    var body: some View {
        List {
            // Alerts come from tafs cloud, which a signed-in account has.
            if model.mode == .kiff {
                Section {
                    Toggle("Sound", isOn: Binding(get: { model.alertSound }, set: { model.alertSound = $0 }))
                } footer: {
                    Text("Off: alerts still arrive, in Notification Center and Needs you, without a sound or a banner.")
                }
            }
            Section {
                Toggle("Haptics", isOn: $haptics)
            } footer: {
                Text("The light taps when you send, answer or turn a dial.")
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
    }
}
