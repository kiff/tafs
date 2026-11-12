import SwiftUI

/// Account › Alerts (the taf layout): what an alert looks like, alerts on this device and
/// their sound, and what alerts you, read-only, as tafs cloud sends them. Haptics last.
struct NotificationSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(Haptics.key) private var haptics = true

    static let title = Words.Settings.alerts

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    LockScreenAlert(title: Words.Settings.chargebacksNeedsYou, line: Words.Settings.disputeOverLimit, loud: true)
                    LockScreenAlert(title: Words.Settings.refundsAnsweredMaria, line: Words.Alert.byEmail, loud: false)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
            }
            // Alerts come from tafs cloud, which a signed-in account has.
            if model.mode == .kiff {
                let alertsOn = model.phoneAlerts?.enabled ?? (model.alertsReady == true)
                Section {
                    Toggle(Words.Alert.onThisDevice(device: "\(ThisDevice.kind)"), isOn: Binding(
                        get: { alertsOn },
                        set: { enabled in Task { if enabled { await model.turnOnAlerts() } else { await model.turnOffAlerts() } } }))
                        .disabled(model.phoneAlertsBusy || model.turningOnAlerts)
                    // No alerts, no sound: off and greyed while alerts are off on this device,
                    // its choice kept for when they're back.
                    Toggle(isOn: Binding(get: { alertsOn && model.alertSound }, set: { model.alertSound = $0 })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Words.Alert.sound)
                            Text(Words.Alert.soundWords).scaledFont(13).foregroundColor(Theme.dim)
                        }
                    }
                    .disabled(!alertsOn)
                } footer: {
                    Text(model.canAnswerHolds == false
                         ? Words.Alert.Foot.cantAnswerHolds
                         : Words.Alert.Foot.tellsYouDevice(device: "\(ThisDevice.kind)"))
                }
                // Drawn only with something in it: an empty heading read as broken.
                if !(model.phoneAlerts?.kinds ?? []).isEmpty || model.phoneAlerts?.answering != nil || model.phoneAlertsError != nil {
                Section(Words.Alert.whatAlerts) {
                    ForEach(model.phoneAlerts?.kinds ?? []) { choice in
                        Toggle(isOn: Binding(get: { choice.enabled }, set: { enabled in Task { await model.setAlertKind(choice.id, enabled: enabled) } })) {
                            VStack(alignment: .leading) { Text(choice.title); Text(choice.words).scaledFont(13).foregroundColor(Theme.dim) }
                        }.disabled(model.phoneAlertsBusy || model.phoneAlerts?.enabled != true)
                    }
                    if let answering = model.phoneAlerts?.answering {
                        NavigationLink { AlertingTafsView() } label: { Text(answering.title) }
                    }
                    if let error = model.phoneAlertsError { Text(error).foregroundColor(Theme.no) }
                }
                }
            }
            Section {
                Toggle(Words.Alert.haptics, isOn: $haptics)
                    .drivable(Words.Alert.haptics, role: .toggle, id: "haptics") { haptics.toggle() }
            } footer: {
                Text(Words.Settings.lightTapsWhenYouSend)
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await model.loadPhoneAlerts() }
    }

    /// One kind of alert, with how it arrives: with sound, always, or silent.
    private func level(_ title: String, sound: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundColor(Theme.ink)
            Label(sound ? Words.Alert.always : Words.Alert.silent, systemImage: sound ? "speaker.wave.2" : "bell.slash")
                .scaledFont(13).foregroundColor(Theme.dim)
        }
        .accessibilityElement(children: .combine)
    }
}

/// An alert as the lock screen shows it: the app's mark, its title and line; a loud one
/// with sound, a silent one without.
private struct LockScreenAlert: View {
    let title: String
    let line: String
    let loud: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Mascot(size: 30)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(title).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                    Spacer()
                    Image(systemName: loud ? "speaker.wave.2.fill" : "bell.slash").scaledFont(12).foregroundColor(Theme.dim)
                }
                Text(line).scaledFont(13).foregroundColor(Theme.soft).lineLimit(2)
            }
        }
        .padding(12)
        .background(Theme.raised)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(loud ? Words.Alert.withSound : Words.Alert.silent): \(title). \(line)")
    }
}

/// The tafs whose answers alert the account's phones: who was answered and through which
/// door, never its words. Each one's Results go to changes it.
private struct AlertingTafsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let tafs = model.phoneAlerts?.answering.tafs ?? []
        List {
            Section {
                ForEach(tafs) { taf in
                    NavigationLink {
                        if let id = model.tafs.first(where: { model.cloudTaf(for: $0.id)?.id == taf.id })?.id { ResultsGoToView(tafID: id) }
                    } label: {
                        HStack(spacing: 10) {
                            Text(taf.name).foregroundColor(Theme.ink)
                        }
                    }
                }
                if tafs.isEmpty {
                    Text(Words.Settings.noTafAlertsPhonesWhen).foregroundColor(Theme.dim)
                }
            } footer: {
                Text(Words.Settings.eachSendsWhoAnsweredThrough)
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Words.Alert.answered)
    }
}
