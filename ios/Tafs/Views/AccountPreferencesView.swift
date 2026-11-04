import SwiftUI

struct ThemePicker: View {
    @AppStorage(ThemePreference.key) private var choice = ThemePreference.system.rawValue
    var body: some View {
        Picker("Theme", selection: $choice) {
            ForEach(ThemePreference.allCases) { theme in Label(theme.title, systemImage: theme.icon).tag(theme.rawValue) }
        }.pickerStyle(.segmented)
    }
}

struct TimeZonePicker: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    var body: some View {
        List {
            Section {
                Button("Use this \(ThisDevice.kind)'s: \(TimeZone.current.identifier)") { Task { await model.useThisDevicesTimeZone() } }
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
        .searchable(text: $query, prompt: "Find a time zone")
        .task(id: query) {
            do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            await model.loadTimeZones(query)
        }
        .navigationTitle("Time zone")
        .screenBackground()
    }
}
