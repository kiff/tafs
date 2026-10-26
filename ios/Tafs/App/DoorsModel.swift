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

    /// Waits between tries of a read that failed: a device's first request after it wakes
    /// often times out (kiff/tafs#236, item 22).
    var retryDelays: [UInt64] = [1_000_000_000, 3_000_000_000]

    /// Reads the doors, trying again quietly when a read fails; only a read that keeps
    /// failing says so, in plain words.
    func load() async {
        if Screenshots.enabled {
            (doors, apps, knocks) = (Self.sample.doors, Self.sample.apps, Self.sample.knocks)
            loaded = true
            return
        }
        for attempt in 0...retryDelays.count {
            do {
                let r = try await cloud.doors(tafID)
                doors = r.doors
                apps = r.apps
                knocks = r.knocks
                loaded = true
                error = nil
                return
            } catch is CancellationError {
                // The screen went away before the read ended: nothing went wrong.
                return
            } catch let error as URLError where error.code == .cancelled {
                return
            } catch {
                guard attempt < retryDelays.count else { break }
                do { try await Task.sleep(nanoseconds: retryDelays[attempt]) } catch { return }
            }
        }
        error = "The doors couldn't be read just now. They're tried again when you come back here."
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
            // Pairing keeps the message waiting, with Do it and Ignore (kiff/tafs#236, item 18).
            if action == "pair" { await load() } else { knocks.removeAll { $0.id == knock.id } }
            error = nil
        } catch let e as TafsCloud.CloudError where e.status == 429 {
            // In the app's words, as the chat says it, not tafs cloud's (the 755 retest).
            self.error = "That's a lot of messages in a few minutes. Wait a little, then send it again: nothing was sent."
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

extension DoorsModel {
    /// The docs' screenshot (`-screen doors`): an email door at an address no one owns, the
    /// owner paired, and one stranger's knock waiting.
    static let sample = TafsCloud.Doors(
        doors: [TafsCloud.Door(id: "door_sample", app: "email", kind: "email", label: "Email", open: true,
                               address: "refunds@tafs.example", proof: "proven",
                               how: "It checks the sender's server signed the message.",
                               keys: [.init(id: "you@company.example", name: "You", auto: true), .init(id: "ana@company.example", name: "Ana")],
                               today: 3, limit: 20, resting: false, othersToday: 1, othersLimit: 10)],
        apps: [TafsCloud.DoorApp(id: "email", label: "Email", kind: "email", proof: "proven", how: "It checks the sender's server signed the message.")],
        knocks: [TafsCloud.Knock(id: "knock_sample", door: "door_sample", app: "email", from: "mara@customer.example", name: "Mara",
                                 proof: "pass", subject: "Refund request", text: "Hi, I'd like $15 back on order T-3001.",
                                 at: Date().addingTimeInterval(-600))])
}

struct DoorsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
