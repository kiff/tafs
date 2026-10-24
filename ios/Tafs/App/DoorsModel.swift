import Combine
import Foundation

/// One taf's doors, for its Doors tab and the knocks in its chat: read from tafs cloud
/// with the owner's session, or, in sample mode, kept here with sample doors.
@MainActor
final class DoorsModel: ObservableObject {
    @Published private(set) var doors: [TafsCloud.Door] = []
    @Published private(set) var apps: [TafsCloud.DoorApp] = []
    @Published private(set) var knocks: [TafsCloud.Knock] = []
    @Published private(set) var loaded = false
    @Published private(set) var busy = false
    @Published var error: String?

    private let cloud: TafsCloud?
    /// tafs cloud's id for the taf; nil in sample mode.
    private let tafID: String?
    private let signIn: VendorSigningIn

    init(cloud: TafsCloud?, tafID: String?, signIn: VendorSigningIn? = nil) {
        self.cloud = cloud
        self.tafID = tafID
        self.signIn = signIn ?? VendorSignIn()
        if tafID == nil { sample() }
    }

    var openKnocks: [TafsCloud.Knock] { knocks.filter { $0.state == "open" } }

    /// The apps that can still be added: one email and one WhatsApp door per taf.
    var addable: [TafsCloud.DoorApp] {
        apps.filter { app in app.kind == "connector" || !doors.contains { $0.kind == app.kind } }
    }

    func load() async {
        guard let cloud, let tafID else { loaded = true; return }
        do {
            let r = try await cloud.doors(tafID)
            doors = r.doors
            apps = r.apps
            knocks = r.knocks
            loaded = true
        } catch {
            self.error = "Couldn't read the doors: \(error.localizedDescription)"
        }
    }

    /// Opens a door on `app`: email at once; WhatsApp and connector apps after their sign-in.
    func add(_ app: TafsCloud.DoorApp) async -> TafsCloud.Door? {
        guard let cloud, let tafID else { return sampleAdd(app) }
        return await work {
            switch app.kind {
            case "whatsapp":
                guard let page = cloud.whatsappSignupURL(tafID) else { return nil }
                let back = try await self.signIn.signIn(at: page)
                let q = Self.query(back)
                guard let code = q["code"], !code.isEmpty else { throw DoorsError("WhatsApp's signup didn't finish.") }
                return try await cloud.addDoor(tafID, app: app.id, extra: [
                    "code": code, "phone_number_id": q["phone_number_id"] ?? "", "waba_id": q["waba_id"] ?? "",
                ])
            case "connector":
                let link = try await cloud.connectLink(tafID, app: app.id)
                guard let url = URL(string: link.url) else { throw DoorsError("\(app.label) gave no sign-in page.") }
                let back = try await self.signIn.signIn(at: url)
                if let status = Self.query(back)["status"], status != "success" { throw DoorsError("\(app.label) wasn't connected.") }
                return try await cloud.addDoor(tafID, app: app.id, extra: ["account": link.account])
            default:
                return try await cloud.addDoor(tafID, app: app.id)
            }
        }
    }

    func setOpen(_ door: TafsCloud.Door, _ open: Bool) async {
        guard let cloud, let tafID else { replace(door.id) { $0.open = open }; return }
        _ = await work { try await cloud.setDoorOpen(tafID, door: door.id, open: open) }
    }

    func remove(_ door: TafsCloud.Door) async {
        guard let cloud, let tafID else { doors.removeAll { $0.id == door.id }; return }
        _ = await work { () -> TafsCloud.Door? in
            try await cloud.removeDoor(tafID, door: door.id)
            self.doors.removeAll { $0.id == door.id }
            return nil
        }
    }

    func pair(_ door: TafsCloud.Door, sender: String, name: String) async {
        let sender = sender.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sender.isEmpty else { return }
        guard let cloud, let tafID else {
            replace(door.id) { $0.keys.append(.init(id: sender.lowercased(), name: name.isEmpty ? nil : name)) }
            return
        }
        _ = await work { try await cloud.pair(tafID, door: door.id, sender: sender, name: name) }
    }

    func unpair(_ door: TafsCloud.Door, _ key: TafsCloud.Door.Key) async {
        guard let cloud, let tafID else { replace(door.id) { $0.keys.removeAll { $0.id == key.id } }; return }
        _ = await work { try await cloud.unpair(tafID, door: door.id, sender: key.id) }
    }

    /// The owner's answer to a knock. "do" starts the taf's run from their words.
    func answer(_ knock: TafsCloud.Knock, _ action: String, say: String? = nil) async {
        guard let cloud, let tafID else { knocks.removeAll { $0.id == knock.id }; return }
        busy = true
        defer { busy = false }
        do {
            try await cloud.answerKnock(tafID, knock: knock.id, action: action, say: say)
            knocks.removeAll { $0.id == knock.id }
            if action == "pair" { await load() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: -

    /// A change on tafs cloud that answers with the door: it replaces the one shown.
    private func work(_ change: @escaping () async throws -> TafsCloud.Door?) async -> TafsCloud.Door? {
        busy = true
        defer { busy = false }
        do {
            guard let door = try await change() else { return nil }
            if let i = doors.firstIndex(where: { $0.id == door.id }) { doors[i] = door } else { doors.append(door) }
            return door
        } catch VendorSignIn.SignInError.cancelled {
            return nil
        } catch {
            self.error = error.localizedDescription
            return nil
        }
    }

    private func replace(_ id: String, _ change: (inout TafsCloud.Door) -> Void) {
        guard let i = doors.firstIndex(where: { $0.id == id }) else { return }
        change(&doors[i])
    }

    static func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.compactMap { i in i.value.map { (i.name, $0) } }, uniquingKeysWith: { a, _ in a })
    }

    // MARK: Sample mode

    private func sample() {
        apps = [
            .init(id: "email", label: "Email", kind: "email", proof: "proven", how: "The email must pass the sender check"),
            .init(id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: "WhatsApp knows the phone number"),
            .init(id: "slack", label: "Slack", kind: "connector", proof: "proven", how: "The workspace signs people in"),
            .init(id: "form", label: "Web form", kind: "connector", proof: "none", how: "Anyone can type any name"),
        ]
        doors = [
            .init(id: "email", app: "email", kind: "email", label: "Email", open: true, address: "refunds@tafs.example",
                  proof: "proven", how: "The email must pass the sender check",
                  keys: [.init(id: "you@yourco.example", auto: true), .init(id: "maya@yourco.example", name: "Maya")],
                  today: 3, limit: 20, resting: false),
        ]
        knocks = [
            .init(id: "k-sample", door: "email", app: "email", from: "ana@northwind.example", name: "Ana Lima", proof: "pass",
                  subject: "Order 4471", text: "I was charged twice for order 4471. Can I get $180 back?",
                  at: Date().addingTimeInterval(-600)),
        ]
        loaded = true
    }

    private func sampleAdd(_ app: TafsCloud.DoorApp) -> TafsCloud.Door? {
        let address = app.kind == "email" ? "refunds@tafs.example" : app.kind == "whatsapp" ? "+34 600 000 000" : "\(app.label) account"
        let door = TafsCloud.Door(id: app.kind == "connector" ? "c-\(app.id)" : app.id, app: app.id, kind: app.kind, label: app.label,
                                  open: true, address: address, proof: app.proof, how: app.how, keys: [], today: 0, limit: 20, resting: false)
        doors.append(door)
        return door
    }
}

struct DoorsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
