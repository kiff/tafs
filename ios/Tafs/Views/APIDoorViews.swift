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
        "curl -X POST \(endpoint) \\\n  -H \"Authorization: Bearer $TAFS_API_KEY\" \\\n  -H \"Idempotency-Key: <your request id>\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"input\": {}}'"
    }
}
