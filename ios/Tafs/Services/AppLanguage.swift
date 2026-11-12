import Foundation

/// The language tafs's lines are in, by its own name ("Français", "Polski"), as the system names
/// it: someone in a language they don't read still finds theirs. Account › Language shows it,
/// and its Change language opens tafs's page in Settings, where iOS lists the app's languages.
/// No picker of the app's own (docs/ios-app.md).
enum AppLanguage {
    static func name(of identifier: String = Words.locale.identifier) -> String {
        let locale = Locale(identifier: identifier)
        let code = locale.language.languageCode?.identifier ?? identifier
        let name = locale.localizedString(forLanguageCode: code) ?? code
        return name.prefix(1).uppercased(with: locale) + name.dropFirst()
    }
}
