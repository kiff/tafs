import Combine
import Foundation

/// One taf's doors, for its Doors tab and the knocks in its chat, read from tafs cloud
/// with the owner's session.
@MainActor
final class DoorsModel: ObservableObject {
    @Published private(set) var doors: [TafsCloud.Door] = []
    @Published private(set) var apps: [TafsCloud.DoorApp] = []
    @Published private(set) var knocks: [TafsCloud.Knock] = [] {
        didSet { onKnocks?(openKnocks) }
    }
    /// The open knocks, each time they change: the chat places them at their times.
    var onKnocks: (@MainActor ([TafsCloud.Knock]) -> Void)?
    @Published private(set) var loaded = false
    @Published private(set) var busy = false
    @Published var error: String?

    private let cloud: TafsCloud
    /// tafs cloud's id for the taf.
    private let tafID: String
    private let signIn: VendorSigningIn
    /// Signs a change to a door "From another application" (`APIDoorFeature`): the method, path and raw body, after Face ID
    /// or the fingerprint, with the words the prompt shows. Answers the `KIFF-Device-Signature` header's value.
    typealias SignRequest = @MainActor (_ method: String, _ path: String, _ body: Data, _ reason: String) async throws -> String
    private let sign: SignRequest?

    /// A key made on this phone, shown once: the key screen reads it, and `keyShown()` forgets it.
    @Published private(set) var issued: TafsCloud.IssuedKey?

    init(cloud: TafsCloud, tafID: String, signIn: VendorSigningIn? = nil, sign: SignRequest? = nil) {
        self.cloud = cloud
        self.tafID = tafID
        self.signIn = signIn ?? VendorSignIn()
        self.sign = sign
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
        error = Words.Door.notRead
    }

    /// Turns a door on: email at once; WhatsApp after its signup.
    func add(_ app: TafsCloud.DoorApp) async -> TafsCloud.Door? {
        let cloud = cloud, tafID = tafID
        return await work {
            guard app.kind == "whatsapp" else { return try await cloud.addDoor(tafID, app: app.id) }
            guard let page = cloud.whatsappSignupURL(tafID) else { return nil }
            let back = try await self.signIn.signIn(at: page)
            let q = Self.query(back)
            guard let code = q["code"], !code.isEmpty else { throw DoorsError(Words.Door.whatsappSignupDidNotFinish) }
            return try await cloud.addDoor(tafID, app: app.id, extra: [
                "code": code, "phone_number_id": q["phone_number_id"] ?? "", "waba_id": q["waba_id"] ?? "",
            ])
        }
    }

    /// A Slack door on one channel of one Slack bot connection, once planque subscribed that
    /// channel with the owner's approval: tafs cloud routes what's written there to the taf.
    func addSlack(_ app: TafsCloud.DoorApp, grant: String, channel: String, trigger: String) async -> TafsCloud.Door? {
        let cloud = cloud, tafID = tafID
        return await work { try await cloud.addDoor(tafID, app: app.id, extra: ["grant": grant, "channel": channel, "trigger": trigger]) }
    }

    func setOpen(_ door: TafsCloud.Door, _ open: Bool) async {
        let cloud = cloud, tafID = tafID
        _ = await work { try await cloud.setDoorOpen(tafID, door: door.id, open: open) }
    }

    /// Removes a door. An `api` door is never removed unsigned: it goes through `removeAPI(_:reason:)`.
    func remove(_ door: TafsCloud.Door) async {
        guard !door.isAPI else { return }
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

    // MARK: A door "From another application" (`api`)

    /// A new key for an `api` door, signed: shown once (`issued`), then only its last four. Nil when the owner didn't
    /// confirm it, or tafs cloud refused; `error` says why.
    @discardableResult
    func newKey(_ door: TafsCloud.Door, name: String, reason: String) async -> TafsCloud.IssuedKey? {
        guard let sign else { return nil }
        let cloud = cloud, tafID = tafID
        let body = TafsCloud.newKeyBody(name: name)
        let path = cloud.doorKeysPath(tafID, door: door.id)
        busy = true
        defer { busy = false }
        do {
            let signature = try await sign("POST", path, body, reason)
            let key = try await cloud.newKey(tafID, door: door.id, body: body, signature: signature)
            issued = key
            error = nil
            await load()
            return key
        } catch {
            self.error = Self.words(error)
            return nil
        }
    }

    /// The key screen was left: the key is never shown again.
    func keyShown() { issued = nil }

    /// Revokes an `api` door's key, signed: requests it sent that haven't started are cancelled; one that's running finishes.
    func revoke(_ door: TafsCloud.Door, _ key: TafsCloud.Door.Key, reason: String) async {
        guard let sign else { return }
        let cloud = cloud, tafID = tafID
        let path = cloud.doorKeyPath(tafID, door: door.id, key: key.id)
        busy = true
        defer { busy = false }
        do {
            let signature = try await sign("DELETE", path, Data(), reason)
            try await cloud.revokeKey(tafID, door: door.id, key: key.id, signature: signature)
            error = nil
            await load()
        } catch {
            self.error = Self.words(error)
        }
    }

    /// Removes an `api` door, signed: its keys stop working with it.
    func removeAPI(_ door: TafsCloud.Door, reason: String) async {
        guard let sign, door.isAPI else { return }
        let cloud = cloud, tafID = tafID
        let path = cloud.doorPath(tafID, door: door.id)
        busy = true
        defer { busy = false }
        do {
            let signature = try await sign("DELETE", path, Data(), reason)
            try await cloud.removeAPIDoor(tafID, door: door.id, signature: signature)
            doors.removeAll { $0.id == door.id }
            error = nil
        } catch {
            self.error = Self.words(error)
        }
    }

    /// Opens a paused `api` door again, signed. Pausing it (`setOpen(door, false)`) needs no signature.
    func reopen(_ door: TafsCloud.Door, reason: String) async {
        guard let sign else { return }
        let cloud = cloud, tafID = tafID
        let path = cloud.doorPath(tafID, door: door.id)
        busy = true
        defer { busy = false }
        do {
            let signature = try await sign("PATCH", path, TafsCloud.reopenBody, reason)
            let opened = try await cloud.reopenDoor(tafID, door: door.id, signature: signature)
            if let i = doors.firstIndex(where: { $0.id == opened.id }) { doors[i] = opened }
            error = nil
        } catch {
            self.error = Self.words(error)
        }
    }

    /// A refusal in plain words: tafs cloud's own when it gives them, else the system's.
    private static func words(_ error: Error) -> String {
        (error as? TafsCloud.CloudError)?.words ?? error.localizedDescription
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
        } catch let e as TafsCloud.CloudError where e.status == 429 && e.words == nil {
            // In the app's words, as the chat says it, not tafs cloud's (the 755 retest).
            self.error = Words.Door.Knock.tooMany
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
        doors: [TafsCloud.Door(id: "door_sample", app: "email", kind: "email", label: Words.Sample.Tool.email, open: true,
                               address: "refunds@tafs.example", proof: "proven",
                               how: Words.Door.checksSenderServerSignedMessage,
                               keys: [.init(id: "you@company.example", name: Words.Door.Key.you, auto: true), .init(id: "ana@company.example", name: "Ana")],
                               today: 3, limit: 20, resting: false, othersToday: 1, othersLimit: 10)],
        apps: [TafsCloud.DoorApp(id: "email", label: Words.Sample.Tool.email, kind: "email", proof: "proven", how: Words.Door.checksSenderServerSignedMessage)],
        knocks: [TafsCloud.Knock(id: "knock_sample", door: "door_sample", app: "email", from: "mara@customer.example", name: "Mara",
                                 proof: "pass", subject: Words.Door.refundRequest, text: Words.Door.hiLikeBackOrder,
                                 at: Date().addingTimeInterval(-600))])
}

struct DoorsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
