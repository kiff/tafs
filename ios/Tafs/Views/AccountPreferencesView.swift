import SwiftUI

struct ThemePicker: View {
    @AppStorage(ThemePreference.key) private var choice = ThemePreference.system.rawValue
    var body: some View {
        Picker(Words.Settings.themeTitle, selection: $choice) {
            ForEach(ThemePreference.allCases) { theme in Label(theme.title, systemImage: theme.icon).tag(theme.rawValue) }
        }.pickerStyle(.segmented)
    }
}

/// Account › Language: the app's own list, as on Android. The device's language first, then
/// tafs's 8, each named in itself, the one in use marked. A tap applies it at once, with no
/// relaunch, and it's kept for the next launch (`AppLanguage.choose`).
struct LanguagePicker: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppLanguage.key) private var chosen = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section { row(nil, Words.Settings.languageDevice(device: "\(ThisDevice.kind)")) }
            Section {
                ForEach(Words.languages, id: \.self) { code in row(code, AppLanguage.name(of: code)) }
            }
        }
        .navigationTitle(Words.Settings.languageTitle)
    }

    private func row(_ code: String?, _ name: String) -> some View {
        let picked = (code ?? "") == chosen
        return Button { pick(code) } label: {
            HStack {
                Text(name).foregroundColor(Theme.ink)
                Spacer()
                if picked { Image(systemName: "checkmark").foregroundColor(Theme.amberText) }
            }
        }
        .accessibilityAddTraits(picked ? .isSelected : [])
        .accessibilityIdentifier("language." + (code ?? "device"))
    }

    /// The one in use does nothing. Any other applies at once, and the screens read again in it.
    private func pick(_ code: String?) {
        guard (code ?? "") != chosen else { return }
        AppLanguage.choose(code)
        model.languageChanged()
        dismiss()
    }
}

struct TimeZonePicker: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    var body: some View {
        List {
            Section {
                Button(Words.Settings.useDeviceZone(device: "\(ThisDevice.kind)", identifier: "\(TimeZone.current.identifier)")) { Task { await model.useThisDevicesTimeZone() } }
                if let error = model.timeZoneError { Text(error).foregroundColor(Theme.no) }
            }
            Section {
                ForEach(model.timeZones) { zone in
                    Button { Task { await model.setAccountTimeZone(zone.id) } } label: {
                        HStack { Text(zone.title); Spacer(); if model.kiffAccount?.timeZone == zone.id { Image(systemName: "checkmark") } }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: Words.Settings.findTimeZone)
        .task(id: query) {
            do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            await model.loadTimeZones(query)
        }
        .navigationTitle(Words.Settings.timeZoneTitle)
        .screenBackground()
    }
}
