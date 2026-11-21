import Foundation

/// The language tafs's lines are in, and the person's pick of it in the app (Account › Language).
/// Each language is named in itself ("Français", "Polski"), as the system names it, so someone in a
/// language they don't read still finds theirs. The pick applies at once: the lines are read from
/// that language's catalog (`Words.bundle`), and it's kept for the next launch, where iOS's own
/// Settings › tafs › Language shows the same (`AppleLanguages`). The device's language clears it.
enum AppLanguage {
    /// The language picked in the app, by its code. Not set: the device's language. iOS keeps the
    /// app's own languages beside it (`AppleLanguages`), which its Settings page shows.
    static let key = "tafs.language"

    static func name(of identifier: String = Words.locale.identifier) -> String {
        let locale = Locale(identifier: identifier)
        let code = locale.language.languageCode?.identifier ?? identifier
        // A regional variant the app ships beside its language (pt-BR beside pt) is named with its region,
        // "Português (Brasil)", so the two rows of the picker read apart.
        let variant = Words.languages.contains(identifier) && identifier.contains("-")
        let name = (variant ? locale.localizedString(forIdentifier: identifier) : nil) ?? locale.localizedString(forLanguageCode: code) ?? code
        return name.prefix(1).uppercased(with: locale) + name.dropFirst()
    }

    /// The language picked in the app, only one the app ships. None: the device's.
    static func chosen(_ defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: key).flatMap { Words.languages.contains($0) ? $0 : nil }
    }

    /// At launch: the lines are read in the language picked in the app, else in the system's pick.
    static func apply(_ defaults: UserDefaults = .standard) {
        Words.bundle = chosen(defaults).map { bundle(for: $0) } ?? .main
    }

    /// Picks a language, or the device's with `nil`, and applies it at once. Kept for the next launch,
    /// in the app's own iOS setting too.
    static func choose(_ code: String?, defaults: UserDefaults = .standard) {
        if let code, Words.languages.contains(code) {
            defaults.set(code, forKey: key)
            defaults.set([code], forKey: "AppleLanguages")
            Words.bundle = bundle(for: code)
        } else {
            defaults.removeObject(forKey: key)
            defaults.removeObject(forKey: "AppleLanguages")
            Words.bundle = bundle(for: deviceLanguage(preferred: defaults.stringArray(forKey: "AppleLanguages") ?? Locale.preferredLanguages))
        }
    }

    /// The device's language as the app ships it: the first of the person's languages the app has, a regional
    /// variant first where the app ships one for that region (pt-BR in Brazil, pt in Portugal), else English. Only the
    /// app's own lines read the variant: KIFF's pages and kiff.dev's open in its language (`KIFFClient.screenLanguages`).
    static func deviceLanguage(preferred: [String], shipped: [String] = Words.languages) -> String {
        for tag in preferred {
            let parts = tag.replacingOccurrences(of: "_", with: "-").split(separator: "-").map(String.init)
            guard let code = parts.first?.lowercased() else { continue }
            if let region = parts.dropFirst().first(where: { $0.count == 2 }), shipped.contains("\(code)-\(region.uppercased())") {
                return "\(code)-\(region.uppercased())"
            }
            if shipped.contains(code) { return code }
        }
        return "en"
    }

    /// A shipped language's catalog. One the app doesn't hold reads as the system's pick.
    static func bundle(for code: String) -> Bundle {
        guard let path = Bundle.main.path(forResource: code, ofType: "lproj"), let found = Bundle(path: path) else { return .main }
        return found
    }
}
