import SwiftUI

/// The taf's dial as Setup's screens read it: tafs cloud's, or the screenshots' sample.
private extension AppModel {
    func setupKnob(_ tafID: UUID) -> TafsCloud.Knob? {
        Screenshots.enabled && mode == .sample ? Screenshots.setupKnob : knobs[tafID]
    }

    func managesSetup(_ tafID: UUID) -> Bool { manages(tafID) || (Screenshots.enabled && mode == .sample) }
}

/// Setup › Starts when (the taf layout): its schedule, the app events it listens to, its
/// doors and the follow-ups it set itself, then Pause every start. Every line is tafs
/// cloud's (`GET …/knob` `wakes`); a change makes its next version, made live below.
struct StartsWhenView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var approving: TafsCloud.Knob.Item?
    @State private var addingDoor = false
    @State private var slackApp: TafsCloud.DoorApp?
    @State private var busy = false

    static let title = Words.Setup.startsWhen
    static let approveNote = Words.Setup.approveNote

    var body: some View {
        let knob = model.setupKnob(tafID)
        let manages = model.managesSetup(tafID)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let knob {
                    KnobPendingPanel(tafID: tafID, knob: knob)
                    schedule(knob, manages: manages)
                    events(knob, manages: manages)
                    doors(manages: manages)
                    followUps(knob, manages: manages)
                    if manages && model.mayUseCoach {
                        Button(Words.Setup.changeStarts) { model.improveTaf(tafID); model.coachOpen = true }
                            .buttonStyle(OutlineButtonStyle())
                    }
                    pause(knob, manages: manages)
                } else {
                    Text(Words.Setup.readingStarts).scaledFont(15).foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Self.title)
        .task(id: tafID) { await model.loadKnob(tafID) }
        .refreshable { await model.loadKnob(tafID) }
        .sheet(item: $approving) { item in
            AppEventApprovalSheet(item: item, approve: { await model.approveKnobEvent(tafID, item: item) })
                .environmentObject(model)
        }
    }

    // MARK: Its schedule

    @ViewBuilder
    private func schedule(_ knob: TafsCloud.Knob, manages: Bool) -> some View {
        let schedules = knob.wakes.filter { $0.kind == "schedule" }
        let cloud = model.cloudTaf(for: tafID)
        Panel {
            Eyebrow(text: Words.Setup.schedule)
            if schedules.isEmpty {
                Text(cloud?.worksFromChat == true ? Words.Setup.noStart : Words.Setup.noSchedule)
                    .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(schedules) { item in
                VStack(alignment: .leading, spacing: 8) {
                    Text(item.words.isEmpty ? item.label : item.words)
                        .scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    if let size = item.size {
                        SizeStepper(label: size.choices.indices.contains(size.index) ? "\(size.label) · \(size.choices[size.index].label)" : size.label,
                                    canLower: manages && !busy && canMove(knob, item, -1),
                                    canRaise: manages && !busy && canMove(knob, item, 1),
                                    move: { adjust(item, $0) })
                    }
                }
            }
            if let next = cloud?.nextRun, cloud?.paused != true {
                Text(Words.Taf.nextRunAt(time: next.zonedShortTime) + " · " + (model.kiffAccount?.timeZone ?? "UTC"))
                    .scaledFont(13).foregroundColor(Theme.dim)
            }
            if !schedules.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text(Words.Setup.ifBusy).scaledFont(13).foregroundColor(Theme.soft)
                    Picker(Words.Setup.ifStillWorking, selection: Binding(
                        get: { knob.setting?.busy ?? "skip" },
                        set: { value in change { $0.busy = value } })) {
                        Text(Words.Setup.skipIt).tag("skip")
                        Text(Words.Setup.runAfter).tag("latest")
                    }
                    .pickerStyle(.segmented)
                    .disabled(!manages || busy || knob.setting == nil)
                }
            }
        }
    }

    private func canMove(_ knob: TafsCloud.Knob, _ item: TafsCloud.Knob.Item, _ direction: Int) -> Bool {
        knob.acceptsChoices(waitingFor: model.knobChanges[tafID]?.version) && item.size?.choice(moving: direction) != nil
    }

    private func adjust(_ item: TafsCloud.Knob.Item, _ direction: Int) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            _ = await model.adjustKnob(tafID, item: item, direction: direction)
        }
    }

    private func change(_ edit: @escaping (inout TafsCloud.Knob.Setting) -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            if await model.changeKnob(tafID, edit) { await model.loadKnob(tafID) }
        }
    }

    // MARK: App events

    @ViewBuilder
    private func events(_ knob: TafsCloud.Knob, manages: Bool) -> some View {
        let events = knob.wakes.filter { $0.kind == "app_event" }
        if !events.isEmpty {
            Panel {
                Eyebrow(text: Words.Setup.appEvents)
                ForEach(events) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        if !item.words.isEmpty {
                            Text(item.words).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                        if item.approval?.state == "needed", item.ok == "approve" {
                            Button(Words.Setup.approve) { approving = item }
                                .buttonStyle(PrimaryButtonStyle())
                                .confirming()
                                .disabled(!manages || model.approvalBlocked)
                        } else if item.approval?.state == "approved" || item.on {
                            HStack {
                                Label(Words.Setup.listening, systemImage: "dot.radiowaves.left.and.right")
                                    .scaledFont(13, weight: .semibold).foregroundColor(Theme.ok)
                                Spacer()
                                if manages, let trigger = item.trigger {
                                    Button(Words.Setup.stop) { change { $0.wakes.removeAll { $0 == trigger } } }
                                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                                        .disabled(busy || knob.setting == nil)
                                }
                            }
                        }
                    }
                }
                Text(Self.approveNote).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Doors

    /// Doors are tafs cloud's, for whoever manages a taf it runs (docs/doors.md).
    @ViewBuilder
    private func doors(manages: Bool) -> some View {
        if manages, let taf = model.taf(tafID), let doors = model.doors(for: tafID),
           model.mode == .kiff || Screenshots.enabled {
            DoorsList(taf: taf, doors: doors, myEmail: model.kiffAccount?.email,
                      add: { addingDoor = true },
                      removeSlack: { await model.removeSlackDoor($0, doors: doors) })
                .sheet(isPresented: $addingDoor) { AddDoorSheet(doors: doors, addSlack: { slackApp = $0 }) { _ in } }
                .sheet(item: $slackApp) { app in SlackDoorSheet(tafID: tafID, doors: doors, app: app).environmentObject(model) }
        }
    }

    // MARK: Follow-ups

    @ViewBuilder
    private func followUps(_ knob: TafsCloud.Knob, manages: Bool) -> some View {
        let followUps = knob.wakes.filter { $0.kind == "follow_up" }
        if !followUps.isEmpty {
            Panel {
                Eyebrow(text: Words.Setup.followUps)
                ForEach(followUps) { item in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.words.isEmpty ? item.label : item.words)
                                .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                            if let at = item.expires.flatMap(Self.date) {
                                Text(at.zonedShown(date: .abbreviated, time: .shortened)).scaledFont(12).foregroundColor(Theme.dim)
                            }
                        }
                        Spacer()
                        if manages, let id = item.followUp {
                            Button(Words.Setup.remove) {
                                busy = true
                                Task { await model.removeFollowUp(tafID, id); busy = false }
                            }
                            .buttonStyle(OutlineButtonStyle(color: Theme.no))
                            .confirming()
                            .disabled(busy)
                        }
                    }
                }
            }
        }
    }

    private static func date(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: s)
    }

    // MARK: Pause every start

    @ViewBuilder
    private func pause(_ knob: TafsCloud.Knob, manages: Bool) -> some View {
        if manages, knob.canPause, let cloud = model.cloudTaf(for: tafID) {
            Button(cloud.paused ? Words.Setup.resumeEvery : Words.Setup.pauseEvery) {
                busy = true
                Task {
                    await model.setPaused(tafID, !cloud.paused)
                    await model.loadKnob(tafID)
                    busy = false
                }
            }
            .buttonStyle(OutlineButtonStyle(color: cloud.paused ? Theme.ok : Theme.no))
            .confirming()
            .disabled(busy)
        }
    }
}

/// − and + through the choices tafs cloud offers for a stop, its value between them.
struct SizeStepper: View {
    let label: String
    let canLower: Bool
    let canRaise: Bool
    let move: (Int) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button { move(-1) } label: { Image(systemName: "minus").frame(width: 44, height: 36) }
                .buttonStyle(OutlineButtonStyle()).disabled(!canLower)
                .accessibilityLabel(Words.Common.less)
            Text(label).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                .frame(maxWidth: .infinity)
            Button { move(1) } label: { Image(systemName: "plus").frame(width: 44, height: 36) }
                .buttonStyle(OutlineButtonStyle()).disabled(!canRaise)
                .accessibilityLabel(Words.Common.more)
        }
        .accessibilityElement(children: .contain)
        .accessibilityValue(label)
    }
}

/// Each door, open or closed, with Edit (its own screen), then Add a door.
private struct DoorsList: View {
    let taf: Taf
    @ObservedObject var doors: DoorsModel
    let myEmail: String?
    let add: () -> Void
    let removeSlack: (TafsCloud.Door) async -> Void

    var body: some View {
        Panel {
            Eyebrow(text: Words.Setup.doors)
            ForEach(doors.doors) { door in
                HStack(spacing: 12) {
                    DoorBadge(app: door.app, open: door.open, size: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(door.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(door.words?.state ?? (door.open ? Words.Setup.`open` : Words.Door.Status.closed)).scaledFont(13).foregroundColor(Theme.soft)
                    }
                    Spacer()
                    NavigationLink(Words.Setup.edit) {
                        DoorsView(taf: taf, doors: doors, manages: true, myEmail: myEmail)
                            .slack(add: { _ in }, remove: removeSlack)
                            .only(door.id)
                    }
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                }
            }
            Button(action: add) { Label(Words.Setup.addDoor, systemImage: "plus") }
                .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                .disabled(!doors.loaded || doors.addable.isEmpty)
        }
        .task { await doors.load() }
    }
}

/// Setup › Results go to: the places tafs delivers to itself, set at once (`PUT …/results`),
/// then each place in a connected app, turned through the dial's next version. At least one
/// of tafs' own places stays on: tafs cloud takes no change without one, so its last switch
/// is disabled.
struct ResultsGoToView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var busy = false

    static let title = Words.Setup.resultsGoTo

    private struct BuiltIn {
        let place: String
        let title: String
        let line: String
    }

    /// tafs' own places, in the same words as on Android.
    private var builtIns: [BuiltIn] {
        [BuiltIn(place: "door_reply", title: Words.Setup.Place.replyTitle, line: Words.Setup.Place.replyWords),
         BuiltIn(place: "app", title: Words.Setup.Place.appTitle, line: Words.Setup.Place.appWords),
         BuiltIn(place: "owner_email", title: Words.Setup.Place.emailTitle, line: model.kiffAccount?.email.map { Words.Setup.Place.emailWords(email: $0) } ?? Words.Setup.Place.emailWordsYours)]
    }

    private static let builtInPlaces: Set<String> = ["door_reply", "app", "owner_email"]
    static let builtInOrder = ["door_reply", "app", "owner_email"]

    /// tafs' own places on, as the live taf has them: its `results` as tafs cloud last sent
    /// them, never a waiting version's, which goes live only with Make it live. Without them,
    /// the dial's when it shows the live version (the sample); nil when they aren't known.
    static func liveBuiltIns(_ live: [JSONValue]?, knob: TafsCloud.Knob?) -> [String]? {
        if let live {
            let on = Set(live.compactMap(TafsCloud.Knob.Setting.place(of:)))
            return builtInOrder.filter(on.contains)
        }
        guard let knob, knob.pending == nil else { return nil }
        return builtInOrder.filter { p in knob.results.first { $0.place == p }?.on ?? knob.setting?.sends(to: p) ?? false }
    }

    /// What `PUT …/results` is sent when one of tafs' places is switched: the live ones with
    /// that one changed, nothing else; nil when it would leave none.
    static func places(_ live: [String], switching place: String, on: Bool) -> [String]? {
        var places = live.filter { $0 != place }
        if on { places.append(place) }
        let sent = builtInOrder.filter(places.contains)
        return sent.isEmpty ? nil : sent
    }

    var body: some View {
        let knob = model.setupKnob(tafID)
        let manages = model.managesSetup(tafID)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let knob {
                    let actions = knob.results.filter { !Self.builtInPlaces.contains($0.place ?? "") }
                    let live = Self.liveBuiltIns(model.cloudTaf(for: tafID)?.results, knob: knob)
                    Text(Words.Setup.atLeastOne).scaledFont(13).foregroundColor(Theme.dim)
                    KnobPendingPanel(tafID: tafID, knob: knob)
                    Panel {
                        Eyebrow(text: Words.Setup.fromTafs)
                        ForEach(builtIns, id: \.place) { b in
                            let on = live?.contains(b.place) ?? false
                            placeRow(title: b.title, line: b.line, on: on,
                                     disabled: !manages || busy || live == nil || (on && (live?.count ?? 0) <= 1)) { setBuiltIn(live, b.place, $0) }
                        }
                    }
                    Panel {
                        Eyebrow(text: Words.Setup.inApps)
                        ForEach(actions) { item in
                            placeRow(title: item.label, line: item.words, on: item.on,
                                     disabled: !manages || busy || toggled(item) == nil) { _ in
                                if let choice = toggled(item) { change { $0 = choice.setting } }
                            }
                        }
                        if manages, model.mayUseCoach, model.mode == .kiff {
                            Button(Words.Setup.addPlace) {
                                model.improveTaf(tafID)
                                model.coachOpen = true
                            }
                            .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                        }
                    }
                    notSent(manages: manages)
                } else {
                    Text(Words.Setup.readingResults).scaledFont(15).foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Self.title)
        .task(id: tafID) {
            await model.loadKnob(tafID)
            await model.loadWork(tafID)
        }
        .refreshable {
            await model.loadKnob(tafID)
            await model.loadWork(tafID)
        }
    }

    /// A place's other choice: on to off, off to on, as tafs cloud offers it.
    private func toggled(_ item: TafsCloud.Knob.Item) -> TafsCloud.Knob.Item.Size.Choice? {
        guard let size = item.size, size.choices.count == 2, size.choices.indices.contains(size.index) else { return nil }
        return size.choices[1 - size.index]
    }

    private func placeRow(title: String, line: String, on: Bool, disabled: Bool, set: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { on }, set: set)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                if !line.isEmpty {
                    Text(line).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(Theme.ok)
        .disabled(disabled)
    }

    /// tafs' own places change at once (`PUT …/results`), from the live taf's; the last one on
    /// can't be turned off.
    private func setBuiltIn(_ live: [String]?, _ place: String, _ on: Bool) {
        guard let live, let places = Self.places(live, switching: place, on: on) else { return }
        busy = true
        Task {
            await model.setResultPlaces(tafID, places)
            busy = false
        }
    }

    private func change(_ edit: @escaping (inout TafsCloud.Knob.Setting) -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            if await model.changeKnob(tafID, edit) { await model.loadKnob(tafID) }
        }
    }

    /// Replies given up on after their tries: Send again, once more through the door.
    @ViewBuilder
    private func notSent(manages: Bool) -> some View {
        let unsent = (model.works[tafID]?.rows ?? []).compactMap { row -> TafsCloud.TafWork.Request? in
            if case .request(let r) = row, r.deliveries.contains(where: \.sendAgain) { return r }
            return nil
        }
        // A failed read of its work, or a Send again that failed, said here too.
        if let error = model.workErrors[tafID] {
            Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("results.workError")
        }
        if !unsent.isEmpty {
            Panel(stroke: Theme.amber.opacity(0.45)) {
                Eyebrow(text: Words.Setup.notSent, color: Theme.amber)
                ForEach(unsent) { r in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(r.line).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                        if manages {
                            let sending = model.sendingAgain.contains(r.id)
                            Button(sending ? Words.Work.sendingAgain : Words.Setup.sendAgain) { Task { await model.sendAgain(tafID, run: r.id) } }
                                .buttonStyle(OutlineButtonStyle())
                                .disabled(sending)
                        }
                    }
                }
            }
        }
    }
}

/// A change to the dial waits as its next version: its words, and Make it live, signed.
struct KnobPendingPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    let knob: TafsCloud.Knob
    @State private var busy = false
    @State private var reading: TafsCloud.Version?

    var body: some View {
        if let pending = knob.pending, model.manages(tafID) {
            Panel(stroke: Theme.amber.opacity(0.45)) {
                Text(Words.Knob.notLive(version: "\(pending.version)")).scaledFont(10, weight: .semibold, design: .monospaced)
                    .foregroundColor(Theme.amberText)
                ForEach(Array((knob.draft?.words ?? []).enumerated()), id: \.offset) { _, line in
                    Text(line).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                Button(busy ? Words.Common.confirming : liveLabel(pending.changes)) { confirm(pending.version) }
                    .buttonStyle(PrimaryButtonStyle()).disabled(busy)
                    .accessibilityIdentifier("setup.makeLive")
            }
            .sheet(item: $reading) { version in
                VersionCardSheet(version: version, tafID: tafID).environmentObject(model)
            }
        }
    }

    private func liveLabel(_ changes: Int) -> String {
        Words.Knob.makeLive(count: changes, biometry: Biometry.name)
    }

    private func confirm(_ n: Int) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            await model.loadVersions(tafID)
            await model.loadKnob(tafID)
            guard model.knobs[tafID]?.pending?.version == n,
                  let version = model.versions[tafID]?.first(where: { $0.n == n }),
                  let change = await model.prepareSwitch(version, of: tafID) else { return }
            if change.draft == nil { _ = await model.useVersion(version, of: tafID) } else { reading = version }
            await model.loadKnob(tafID)
        }
    }
}
