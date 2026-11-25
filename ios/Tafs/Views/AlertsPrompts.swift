import SwiftUI

/// What tafs says about alerts, over any screen (the owner's call, row 13; as Android's): notifications blocked in Settings,
/// with the way to tafs's own switch and a check on return; and, once per phone, that its watch is using tafs. Then, after
/// Settings, whether alerts are on now.
struct AlertsPrompts: ViewModifier {
    @EnvironmentObject private var model: AppModel

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: Binding(get: { model.alertsPrompt != nil }, set: { if !$0 { model.alertsPrompt = nil } }),
                   presenting: model.alertsPrompt) { prompt in
                switch prompt {
                case .blocked:
                    Button(Words.Alert.openSettings) { model.goToNotificationSettings() }
                case .watchFirst:
                    Button(Words.NeedsYou.turnAlerts) { Task { await model.turnOnAlerts() } }
                }
                Button(Words.Common.notNow, role: .cancel) {}
            } message: { prompt in
                Text(prompt == .blocked ? model.alertsBlockedBody : Words.Alert.WatchFirst.body)
            }
            .alert(model.alertsRecheck ?? "", isPresented: Binding(get: { model.alertsRecheck != nil }, set: { if !$0 { model.alertsRecheck = nil } })) {
                Button(Words.Common.ok, role: .cancel) {}
            }
    }

    private var title: String {
        model.alertsPrompt == .watchFirst ? Words.Alert.WatchFirst.title : Words.Alert.Blocked.title
    }
}
