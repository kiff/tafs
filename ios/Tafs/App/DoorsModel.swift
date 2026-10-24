import Combine
import Foundation

/// One taf's doors, for its Doors tab and the knocks in its chat, read from tafs cloud
/// with the owner's session.
@MainActor
final class DoorsModel: ObservableObject {
    @Published private(set) var doors: [TafsCloud.Door] = []
    @Published private(set) var apps: [TafsCloud.DoorApp] = []
    @Published private(set) var knocks: [TafsCloud.Knock] = []
    @Published private(set) var loaded = false
    @Published private(set) var busy = false
    @Published var error: String?

    private let cloud: TafsCloud
    /// tafs cloud's id for the taf.
    private let tafID: String
    private let signIn: VendorSigningIn

    init(cloud: TafsCloud, tafID: String, signIn: VendorSigningIn? = nil) {
        self.cloud = cloud
        self.tafID = tafID
        self.signIn = signIn ?? VendorSignIn()
    }

    var openKnocks: [TafsCloud.Knock] { knocks.filter { $0.state == "open" } }

    /// The apps that can still be turned on: one door of each per taf.
    var addable: [TafsCloud.DoorApp] {
        apps.filter { app in !doors.contains { $0.kind == app.kind } }
    }

    func load() async {
        do {
            let r = try await cloud.doors(tafID)
            doors = r.doors
            apps = r.apps
            knocks = r.knocks
            loaded = true
        } catch is CancellationError {
            // The screen went away before the read ended: nothing went wrong.
        } catch let error as URLError where error.code == .cancelled {
        } catch {
            self.error = "Couldn't read the doors: \(error.localizedDescription)"
        }
    }

    /// Turns a door on: email at once; WhatsApp after its signup.
    func add(_ app: TafsCloud.DoorApp) async -> TafsCloud.Door? {
        let cloud = cloud, tafID = tafID
        return await work {
            guard app.kind == "whatsapp" else { return try await cloud.addDoor(tafID, app: app.id) }
            guard let page = cloud.whatsappSignupURL(tafID) else { return nil }
            let back = try await self.signIn.signIn(at: page)
            let q = Self.query(back)
            guard let code = q["code"], !code.isEmpty else { throw DoorsError("WhatsApp's signup didn't finish.") }
            return try await cloud.addDoor(tafID, app: app.id, extra: [
                "code": code, "phone_number_id": q["phone_number_id"] ?? "", "waba_id": q["waba_id"] ?? "",
            ])
        }
    }

    func setOpen(_ door: TafsCloud.Door, _ open: Bool) async {
        let cloud = cloud, tafID = tafID
        _ = await work { try await cloud.setDoorOpen(tafID, door: door.id, open: open) }
    }

    func remove(_ door: TafsCloud.Door) async {
        let cloud = cloud, tafID = tafID
        _ = await work { () -> TafsCloud.Door? in
            try await cloud.removeDoor(tafID, door: door.id)
            self.doors.removeAll { $0.id == door.id }
            return nil
        }
    }

    func pair(_ door: TafsCloud.Door, sender: String, name: String) async {
        let sender = sender.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sender.isEmpty else { return }
        let cloud = cloud, tafID = tafID
        _ = await work { try await cloud.pair(tafID, door: door.id, sender: sender, name: name) }
    }

    func unpair(_ door: TafsCloud.Door, _ key: TafsCloud.Door.Key) async {
        let cloud = cloud, tafID = tafID
        _ = await work { try await cloud.unpair(tafID, door: door.id, sender: key.id) }
    }

    /// The owner's answer to a knock. "do" starts the taf's run from their words.
    func answer(_ knock: TafsCloud.Knock, _ action: String, say: String? = nil) async {
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

    static func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.compactMap { i in i.value.map { (i.name, $0) } }, uniquingKeysWith: { a, _ in a })
    }
}

struct DoorsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
