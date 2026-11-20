import Foundation

/// The first run after one sign-in (docs/onboarding.md): the welcome with one tick for the terms, KIFF's sign-in in the
/// system's own sheet, three cards on how tafs works, and the first Home. Off until the end-to-end test on real devices
/// with the owner: while it's off, the welcome and the sign-in are as they ship.
enum Onboarding {
    static let on = false

    /// The three cards were seen on this device (Get started or Skip): they show once, after the first sign-in here,
    /// and How tafs works opens them again any time.
    static let cardsSeenKey = "tafs.onboarding-cards-seen"

    /// Whether the three cards follow the celebration after signing in.
    static func showsCards(on: Bool = Onboarding.on, seen: Bool) -> Bool { on && !seen }

    /// Whether Home with no taf is the first Home (Ask Coach and How tafs works): with the new onboarding on, for
    /// someone who may use Coach, whatever tafs cloud says there.
    static func showsFirstHome(on: Bool = Onboarding.on, mayUseCoach: Bool) -> Bool { on && mayUseCoach }

    /// The first Home's words: tafs cloud's own empty line when it sends one (its words come first), else the app's
    /// "No tafs yet" and what Coach does.
    static func firstHomeLines(cloud: String?) -> (title: String, lead: String?) {
        if let cloud, !cloud.isEmpty { return (cloud, nil) }
        return (Words.Home.noTafs, Words.Onboarding.homeLead)
    }

    /// The welcome's age line for the person's country (the device's region): its own age where the law asks for more
    /// than 16 (`MinimumAge`, legal-i18n's table: India, Indonesia, Brazil…), else the line every country reads.
    /// Today's sign-in screen (the switch off): the country's own age only where it's above 16 (India, Indonesia,
    /// Brazil…), so a country that asks for more is told before signing in; anywhere else, nothing new is shown.
    static func raisedAgeLine(region: String? = Locale.current.region?.identifier) -> String? {
        let age = MinimumAge.of(region)
        return age > MinimumAge.standard ? Words.Onboarding.ageN(age: "\(age)") : nil
    }

    static func ageLine(region: String? = Locale.current.region?.identifier) -> String {
        let age = MinimumAge.of(region)
        return age > MinimumAge.standard ? Words.Onboarding.ageN(age: "\(age)") : Words.Onboarding.age
    }
}

/// The welcome's one tick: the person agrees to KIFF's Terms and the tafs Terms before Continue. Privacy is linked
/// beside it, never ticked, and the AI consent is its own screen (`AIConsentView`).
struct ConsentTick: Equatable {
    private(set) var agreed = false

    mutating func toggle() { agreed.toggle() }

    /// Continue opens the sign-in only once the terms are agreed to. Trying the sample data needs no account, so it
    /// never waits on the tick.
    var canContinue: Bool { agreed }
}
