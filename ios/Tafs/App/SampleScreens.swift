import Foundation

/// The iPad reference set's screens over the sample (`-screenshots YES`, docs/ios-app.md): what tafs
/// cloud would send for Connect apps and the job gallery, kept as its JSON in `Samples/`, and the
/// devices KIFF would list. Read only for a screenshot over the sample: a real account reads its own.
/// On the main actor, as tafs cloud's and KIFF's decoders are.
@MainActor
enum SampleScreens {
    /// One of `Samples/`' answers, read as tafs cloud's own would be.
    static func fixture<T: Decodable>(_ name: String, in bundle: Bundle = .main) -> T? {
        guard let url = bundle.url(forResource: name, withExtension: "json"), let data = try? Data(contentsOf: url) else { return nil }
        return try? KIFFClient.decoder.decode(T.self, from: data)
    }

    /// One of `Samples/`' answers as it is.
    static func data(_ name: String, in bundle: Bundle = .main) -> Data? {
        bundle.url(forResource: name, withExtension: "json").flatMap { try? Data(contentsOf: $0) }
    }

    /// planque's connections behind Connect apps: Gmail for Support replies and for Lead follow-up, and
    /// Zendesk for Support replies.
    static var grants: [PlanqueClient.Grant]? { fixture("grants") }

    /// Each sample connection's KIFF agent, and the sample taf it's for, by its template.
    static let grantAgents = ["sample-support": "support", "sample-leads": "leads"]

    /// Coach mid-conversation: setting up a support replies taf, as tafs cloud's conversation sends it.
    static var coachThread: SetupThread? { data("coach-chat").flatMap { try? TafsCloud.setupThread(decoding: $0) } }

    /// Connect apps: two of the person's own, then apps to connect.
    static var connectApps: TafsCloud.ConnectApps? { fixture("connect-apps") }

    /// + New's gallery: every ready-made job, by category.
    static var gallery: TafsCloud.Gallery? { fixture("gallery") }

    /// Devices: this one, an iPhone used a few hours ago, and a Mac yesterday.
    static func devices(now: Date = Date()) -> [KIFFClient.Device] {
        let name = { (kind: String) in Words.Settings.Devices.thisDeviceName(device: kind) }
        return [
            KIFFClient.Device(id: "dev_this", name: name("\(ThisDevice.kind)"), lastUsed: now, thisDevice: true),
            KIFFClient.Device(id: "dev_phone", name: name(ThisDevice.kind == "iPhone" ? "iPad" : "iPhone"),
                              lastUsed: now.addingTimeInterval(-3 * 3600), thisDevice: false),
            KIFFClient.Device(id: "dev_mac", name: name("Mac"), lastUsed: now.addingTimeInterval(-26 * 3600), thisDevice: false),
        ]
    }
}
