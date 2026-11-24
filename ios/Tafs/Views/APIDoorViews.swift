import SwiftUI

/// A door "From another application" (`api`, docs/doors.md): a company's own software hands the taf work with a key. Its
/// panel shows today's count against its cap, its last request in tafs cloud's words, its keys (at most two, each by its
/// last four), New key, Revoke and the API instructions. Every change but pausing is signed (`DoorsModel.newKey`,
/// `revoke`, `reopen`, `removeAPI`). Drawn for what tafs cloud sends: its `TAFS_API_DOORS` decides, never the app.
struct APIDoorPanel: View {
    let door: TafsCloud.Door
    let taf: Taf
    @ObservedObject var doors: DoorsModel
    let manages: Bool
    let remove: () -> Void
    @State private var naming = false
    @State private var revoking: TafsCloud.Door.Key?

    var body: some View {
        Group {
            Panel {
                HStack(spacing: 12) {
                    DoorBadge(app: door.app, open: door.open, size: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Eyebrow(text: Words.Door.Api.kind)
                        Text(door.label).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                    }
                    Spacer()
                    if manages {
                        // Pausing is never signed; opening it again is (Face ID or Touch ID).
                        Toggle(Words.Door.Status.`open`, isOn: Binding(get: { door.open }, set: { on in
                            Task {
                                if on { await doors.reopen(door, reason: Words.Door.Api.openReason(app: door.label)) }
                                else { await doors.setOpen(door, false) }
                            }
                        }))
                        .labelsHidden().tint(Theme.ok).disabled(doors.busy)
                        .accessibilityIdentifier("door.api.open")
                    }
                }
                if !door.open {
                    Text(Words.Door.Api.paused).scaledFont(14).foregroundColor(Theme.amber).fixedSize(horizontal: false, vertical: true)
                }
                let today = door.counts?.today ?? door.today
                Eyebrow(text: Words.Door.Api.today)
                Meter(fraction: Double(today) / Double(max(door.limit, 1)), color: Theme.tafColor(taf.colorIndex))
                Text(Words.Door.Api.ofLimit(count: today, limit: Words.number(door.limit)))
                    .scaledFont(13, weight: .medium).foregroundColor(Theme.soft)
                // Only in tafs cloud's words: the app never words a state itself, so none is drawn until they're sent.
                if let last = door.lastRequest, let words = APIDoorText.lastRequestWords(last) {
                    Text(Words.Door.Api.lastRequest(time: APIDoorText.when(last.at) ?? last.at, status: words))
                        .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("door.api.last_request")
                }
            }
            Panel {
                Eyebrow(text: Words.Door.Api.keys)
                ForEach(door.keys) { key in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(key.name ?? key.id).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(APIDoorText.keyLine(key)).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                        if manages {
                            Button(Words.Taf.revoke) { revoking = key }
                                .confirming()
                                .scaledFont(13, weight: .medium).foregroundColor(Theme.no)
                                .accessibilityIdentifier("door.api.revoke." + key.id)
                        }
                    }
                }
                if manages {
                    Button(Words.Door.Api.newKey) { naming = true }
                        .buttonStyle(OutlineButtonStyle()).confirming()
                        .disabled(doors.busy || door.keys.count >= APIDoorText.maxKeys)
                        .accessibilityIdentifier("door.api.new_key")
                }
                Text(Words.Door.Api.twoKeys).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
                Text(Words.Door.Api.boundary(taf: taf.name)).scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                NavigationLink { APIInstructionsView(door: door, endpoint: doors.endpoint(door), taf: taf) } label: {
                    HStack {
                        Text(Words.Door.Api.instructions).scaledFont(15, weight: .medium).foregroundColor(Theme.ink)
                        Spacer()
                        Image(systemName: "chevron.forward").scaledFont(12, weight: .semibold).foregroundColor(Theme.dim).accessibilityHidden(true)
                    }
                    .frame(minHeight: 44)
                }
                .buttonStyle(.drivablePlain)
                .accessibilityIdentifier("door.api.instructions")
                if manages {
                    Button(Words.Door.removeDoor, role: .destructive, action: remove)
                        .confirming()
                        .scaledFont(14, weight: .medium)
                }
            }
        }
        .sheet(isPresented: $naming) { NewKeySheet(door: door, doors: doors) }
        .sheet(item: Binding(get: { doors.issued.map { IssuedKeyBox(key: $0) } }, set: { if $0 == nil { doors.keyShown() } })) { box in
            IssuedKeyView(key: box.key, door: door, endpoint: doors.endpoint(door)) { doors.keyShown() }
                .interactiveDismissDisabled()
        }
        .confirmationDialog(Words.Door.Api.revokeTitle(key: revoking.map(APIDoorText.name) ?? ""),
                            isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }),
                            titleVisibility: .visible, presenting: revoking) { key in
            Button(Words.Taf.revoke, role: .destructive) {
                Task { await doors.revoke(door, key, reason: Words.Door.Api.revokeReason(key: APIDoorText.name(key))) }
            }
        } message: { _ in
            Text(Words.Door.Api.revokeBody)
        }
    }
}

/// The issued key, as a sheet's item.
private struct IssuedKeyBox: Identifiable {
    let key: TafsCloud.IssuedKey
    var id: String { key.id }
}

/// New key: its name (the door's own name when left blank), then Face ID or Touch ID.
struct NewKeySheet: View {
    let door: TafsCloud.Door
    @ObservedObject var doors: DoorsModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Words.Door.Api.newKey).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            Text(Words.Door.Api.twoKeys).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            Text(Words.Door.Api.keyName).scaledFont(13, weight: .semibold).foregroundColor(Theme.soft)
            DriveField(Words.Door.Api.keyNameHint, text: $name).textFieldStyle(.roundedBorder).disableAutocorrection(true)
                // tafs cloud takes at most 80; 60, as Android, so a name never comes back refused.
                .onChange(of: name) { typed in
                    if typed.count > APIDoorText.maxKeyName { name = String(typed.prefix(APIDoorText.maxKeyName)) }
                }
            Button(Words.Door.Api.newKey) {
                Task {
                    let made = await doors.newKey(door, name: APIDoorText.keyName(name, door: door),
                                                  reason: Words.Door.Api.newKeyReason(app: door.label))
                    // The key's own screen follows from `issued`; this sheet steps aside for it.
                    if made != nil { dismiss() }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .confirming()
            .disabled(doors.busy)
            if let error = doors.error {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
            }
            Button(Words.Common.cancel) { dismiss() }.frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(20)
        .screenBackground()
    }
}

/// The key, shown this once: Copy, where to send work, and an example that reads the key from the caller's own secret.
/// Leaving it forgets the key (`keyShown`); the list only ever has its last four. As Android's `FLAG_SECURE` keeps it off
/// the recents and off a recording, the key is covered whenever the app isn't in front (so the app switcher's snapshot
/// never holds it) and while the screen is recorded, mirrored or shared. iOS lets no app refuse a screenshot.
struct IssuedKeyView: View {
    let key: TafsCloud.IssuedKey
    let door: TafsCloud.Door
    let endpoint: String
    let done: () -> Void
    @State private var copied = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var captured = UIScreen.main.isCaptured

    /// Whether the key may be drawn now: the app in front and the screen not captured.
    static func shows(phase: ScenePhase, captured: Bool) -> Bool { phase == .active && !captured }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(Words.Door.Api.shownOnce).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text(Words.Door.Api.shownOnceBody(app: door.label)).scaledFont(15).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                Text(Self.shows(phase: scenePhase, captured: captured) ? key.key : String(repeating: "•", count: 24))
                    .scaledFont(14, weight: .semibold, design: .monospaced).foregroundColor(Theme.ink)
                    .textSelection(.enabled).privacySensitive()
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Theme.raised))
                    .accessibilityIdentifier("door.api.key")
                Button(copied ? Words.Door.Api.copied : Words.Door.Api.copyKey) {
                    // This device only, and off the pasteboard after a minute.
                    Clipboard.copySecret(key.key)
                    copied = true
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("door.api.copy_key")
                Eyebrow(text: Words.Door.Api.`where`)
                Text(endpoint).scaledFont(13, design: .monospaced).foregroundColor(Theme.ink).textSelection(.enabled)
                Eyebrow(text: Words.Door.Api.example)
                CodeBlock(text: APIDoorText.example(endpoint: endpoint))
                Button(Words.Common.done, action: done).buttonStyle(OutlineButtonStyle())
                    .accessibilityIdentifier("door.api.key_done")
            }
            .padding(20)
        }
        .screenBackground()
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captured = UIScreen.main.isCaptured
        }
    }
}

/// API instructions: everything a developer needs but the key.
struct APIInstructionsView: View {
    let door: TafsCloud.Door
    let endpoint: String
    let taf: Taf

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: Words.Door.Api.`where`)
                Text(endpoint).scaledFont(14, design: .monospaced).foregroundColor(Theme.ink).textSelection(.enabled)
                Eyebrow(text: Words.Door.Api.example)
                CodeBlock(text: APIDoorText.example(endpoint: endpoint))
                Text(Words.Door.Api.keyNotHere).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                Text(Words.Door.Api.boundary(taf: taf.name)).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Words.Door.Api.instructions)
    }
}

/// Code, as written: monospaced, selectable, scrolled sideways rather than wrapped.
private struct CodeBlock: View {
    let text: String
    var body: some View {
        ScrollView(.horizontal) {
            Text(text).scaledFont(12, design: .monospaced).foregroundColor(Theme.ink).textSelection(.enabled)
                .padding(12)
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.raised))
    }
}

/// The `api` door's lines that aren't words: dates, a key's name, the endpoint and the example.
enum APIDoorText {
    /// A door holds at most two live keys, so one can be replaced without stopping the other.
    static let maxKeys = 2

    /// A field the application sends, as a developer reads it: its name, then its choices or its type.
    static func field(_ field: TafsCloud.DoorDraft.Field) -> String {
        if let values = field.values, !values.isEmpty { return field.name + " · " + values.joined(separator: " | ") }
        return field.name + " · " + field.type
    }

    /// A door's address on tafs cloud, whole, as the shared rule writes it (vectors/api-door-endpoint.json, every app alike).
    /// It's always rebuilt on tafs cloud's own host, over HTTPS, with no port or user: the example sends the developer's key,
    /// so it never names another host. And it's pasted into a shell, so the path is kept **only** if it's made of unreserved
    /// characters (`^/[A-Za-z0-9/_.~-]*$`), and a query or fragment never is. Anything else (`$(…)`, `;`, `&`, a quote, a
    /// backtick, whitespace, a scheme with no host of its own such as `mailto:`) gives tafs cloud's address alone, with its
    /// slash. The door's own path is `/v1/doors/api/api-…/requests`.
    static func endpoint(_ door: TafsCloud.Door, base: URL) -> String {
        guard let host = base.host?.lowercased(), !host.isEmpty else { return base.absoluteString }
        let alone = "https://\(host)/"
        let address = door.address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let path = Self.path(of: address) else { return alone }
        let rooted = path.hasPrefix("/") ? path : "/" + path
        return rooted.range(of: #"^/[A-Za-z0-9/_.~-]*$"#, options: .regularExpression) != nil ? "https://\(host)" + rooted : alone
    }

    /// An address's path as written, its query and fragment dropped: after `scheme://host` or `//host`, from the first `/`; a
    /// bare path as it is. Nil for anything with whitespace or a backslash, and for a scheme with no host of its own.
    private static func path(of address: String) -> String? {
        guard !address.contains(where: { $0.isWhitespace || $0 == "\\" }) else { return nil }
        var s = Substring(address)
        if let cut = s.firstIndex(where: { $0 == "?" || $0 == "#" }) { s = s[..<cut] }
        if let scheme = s.range(of: "://") {
            let rest = s[scheme.upperBound...]
            return rest.firstIndex(of: "/").map { String(rest[$0...]) } ?? ""
        }
        if s.hasPrefix("//") {
            let rest = s.dropFirst(2)
            return rest.firstIndex(of: "/").map { String(rest[$0...]) } ?? ""
        }
        // `mailto:`, `javascript:`, `foo:x@…`: a colon before any slash is a scheme, never a path.
        if let colon = s.firstIndex(of: ":"), s.firstIndex(of: "/").map({ colon < $0 }) ?? true { return nil }
        return String(s)
    }

    /// The longest key name the field takes (tafs cloud takes 80; 60, as Android).
    static let maxKeyName = 60

    /// The last request's state in tafs cloud's words, trimmed; none until it sends them, never the raw `status`.
    static func lastRequestWords(_ last: TafsCloud.Door.LastRequest) -> String? {
        let words = last.words?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return words.isEmpty ? nil : words
    }

    /// An ISO time from tafs cloud, shown in the account's zone; nil when it isn't one.
    static func when(_ iso: String?) -> String? {
        guard let iso else { return nil }
        let precise = ISO8601DateFormatter()
        precise.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = precise.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else { return nil }
        return date.shown(date: .abbreviated, time: .shortened)
    }

    /// What a key is called: its name, else its last four.
    static func name(_ key: TafsCloud.Door.Key) -> String { key.name ?? key.last4.map { "…" + $0 } ?? key.id }

    /// The key's line: made, last used or not yet, and its last four. Never the key.
    static func keyLine(_ key: TafsCloud.Door.Key) -> String {
        let made = when(key.madeAt) ?? ""
        let last4 = key.last4 ?? ""
        if let used = when(key.lastUsedAt) { return Words.Door.Api.keyLine(made: made, used: used, last4: last4) }
        return Words.Door.Api.keyNeverUsed(made: made, last4: last4)
    }

    /// A new key's name: what the owner typed, else the door's own name.
    static func keyName(_ typed: String, door: TafsCloud.Door) -> String {
        let name = String(typed.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxKeyName))
        return name.isEmpty ? String(door.label.prefix(maxKeyName)) : name
    }

    /// An example request. It reads the key from the caller's own secret, `$TAFS_API_KEY`: the key is never written in it.
    static func example(endpoint: String) -> String {
        "curl -X POST '\(endpoint)' \\\n  -H \"Authorization: Bearer $TAFS_API_KEY\" \\\n  -H \"Idempotency-Key: <your request id>\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"input\": {}}'"
    }
}

/// Coach's drafted door "From another application" (`show {type: "door", taf_id, draft_id}`, the owner's mockup screen 1),
/// drawn from tafs cloud's draft, never from Coach's words: the application's name, what it may do, what the taf does,
/// the fields it sends and what it gets back, its own words' risk when it's on, and the boundary. Approve signs the draft's
/// exact `body` (Face ID or Touch ID) and opens the door; its first key is then shown once.
struct DoorDraftCard: View {
    @EnvironmentObject private var model: AppModel
    let component: SetupComponent
    @State private var draft: TafsCloud.DoorDraft?
    @State private var failed: String?
    /// The draft is used up because a door was made from it (`draft_used`): tafs cloud's words then say so, in the
    /// ok colour, since nothing went wrong.
    @State private var used = false
    @State private var busy = false
    /// The door the approval opened and its first key, held only until its screen closes.
    @State private var added: TafsCloud.AddedAPIDoor?
    @State private var approved = false

    private var tafID: UUID? { component.tafID.flatMap { model.tafID(cloud: $0) } }
    private var tafName: String { tafID.flatMap { model.taf($0)?.name } ?? "" }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: Words.Door.Api.draftKind)
            if let draft {
                let d = draft.draft
                Text(d.name).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                Text(Words.Door.Api.draftScope(app: d.name, taf: tafName)).scaledFont(14).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                Text(Words.Door.Api.draftWill(taf: tafName, job: d.job)).scaledFont(15).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if !d.fields.isEmpty {
                    Eyebrow(text: Words.Door.Api.draftSends)
                    ForEach(d.fields, id: \.name) { field in
                        Text(APIDoorText.field(field)).scaledFont(13, design: .monospaced).foregroundColor(Theme.ink)
                    }
                }
                if !d.outcomes.isEmpty {
                    Text(Words.Door.Api.draftBack(outcomes: Words.list(d.outcomes))).scaledFont(14).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if d.text {
                    Text(Words.Door.Api.draftWords(taf: tafName)).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    Text(Words.Door.Api.draftWordsRisk(taf: tafName, app: d.name)).scaledFont(13).foregroundColor(Theme.amber)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(Words.Door.Api.boundary(taf: tafName)).scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                if approved {
                    Text(Words.Door.Status.`open`).scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                } else if let tafID, model.manages(tafID) {
                    Button(busy ? Words.Common.confirming : Words.Approval.approve) { approve(draft) }
                        .buttonStyle(PrimaryButtonStyle()).disabled(busy).confirming()
                        .accessibilityIdentifier("door.draft.approve")
                }
            }
            if let failed {
                Text(failed).scaledFont(13, relativeTo: .footnote).foregroundColor(used ? Theme.ok : Theme.no)
                    .accessibilityIdentifier(used ? "door.draft.used" : "door.draft.failed")
            }
        }
        .task(id: component.draftID) { await read() }
        .sheet(item: Binding(get: { added.map { IssuedKeyBox(key: $0.key) } }, set: { if $0 == nil { added = nil } })) { box in
            if let door = added?.door {
                IssuedKeyView(key: box.key, door: door, endpoint: APIDoorText.endpoint(door, base: model.cloud.baseURL)) { added = nil }
                    .interactiveDismissDisabled()
            }
        }
    }

    private func read() async {
        guard let taf = component.tafID, let id = component.draftID else { return }
        do {
            draft = try await model.cloud.doorDraft(taf, draft: id)
            failed = nil
            used = false
        } catch {
            used = Self.isUsed(error)
            failed = (error as? TafsCloud.CloudError)?.words ?? error.localizedDescription
        }
    }

    /// Whether tafs cloud refused the read because a door was already made from this draft (410 `draft_used`).
    static func isUsed(_ error: Error) -> Bool {
        (error as? TafsCloud.CloudError)?.code == "draft_used"
    }

    private func approve(_ draft: TafsCloud.DoorDraft) {
        guard let taf = component.tafID else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                added = try await model.approveDoorDraft(cloudTaf: taf, draft: draft,
                                                         reason: Words.Door.Api.faceidBody(app: draft.draft.name, taf: tafName))
                approved = true
                failed = nil
            } catch is CancellationError {
                // The owner cancelled Face ID: nothing was sent.
            } catch {
                failed = (error as? TafsCloud.CloudError)?.words ?? error.localizedDescription
            }
        }
    }
}
