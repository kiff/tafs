import SwiftUI

/// A door "From another application" (`api`, docs/doors.md): a company's own software hands the taf work with a key. Its
/// panel shows today's count against its cap, its last request in tafs cloud's words, its keys (at most two, each by its
/// last four), New key, Revoke and the API instructions. Every change but pausing is signed (`DoorsModel.newKey`,
/// `revoke`, `reopen`, `removeAPI`). Drawn only behind `APIDoorFeature.on`.
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
                if let last = door.lastRequest {
                    Text(Words.Door.Api.lastRequest(time: APIDoorText.when(last.at) ?? last.at, status: last.shown))
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
                        Image(systemName: "chevron.right").scaledFont(12, weight: .semibold).foregroundColor(Theme.dim).accessibilityHidden(true)
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
/// Leaving it forgets the key (`keyShown`); the list only ever has its last four.
struct IssuedKeyView: View {
    let key: TafsCloud.IssuedKey
    let door: TafsCloud.Door
    let endpoint: String
    let done: () -> Void
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(Words.Door.Api.shownOnce).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text(Words.Door.Api.shownOnceBody(app: door.label)).scaledFont(15).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                Text(key.key)
                    .scaledFont(14, weight: .semibold, design: .monospaced).foregroundColor(Theme.ink)
                    .textSelection(.enabled).privacySensitive()
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Theme.raised))
                    .accessibilityIdentifier("door.api.key")
                Button(copied ? Words.Door.Api.copied : Words.Door.Api.copyKey) {
                    Clipboard.copy(key.key)
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

    /// A door's address on tafs cloud, whole.
    static func endpoint(_ door: TafsCloud.Door, base: URL) -> String {
        URL(string: door.address, relativeTo: base)?.absoluteString ?? door.address
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
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? door.label : name
    }

    /// An example request. It reads the key from the caller's own secret, `$TAFS_API_KEY`: the key is never written in it.
    static func example(endpoint: String) -> String {
        "curl -X POST \(endpoint) \\\n  -H \"Authorization: Bearer $TAFS_API_KEY\" \\\n  -H \"Idempotency-Key: <your request id>\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"input\": {}}'"
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
                Text(failed).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
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
        } catch {
            failed = (error as? TafsCloud.CloudError)?.words ?? error.localizedDescription
        }
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
