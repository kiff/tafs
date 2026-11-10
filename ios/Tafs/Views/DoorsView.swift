import SwiftUI

/// A taf's Doors tab (docs/doors.md): the taf's dial ("The knob, end to end"), its doors
/// stops on Wakes it with When and Results go around them; tapping a door's stop picks it,
/// pressing the face opens or closes it. Without the dial (it couldn't be read), the doors'
/// own dial: one stop per door and a last stop to add one.
/// Below it, the door picked: its address, who may give it work, and its day.
struct DoorsView: View {
    let taf: Taf
    /// The taf's dial, as tafs cloud last sent it; nil: the doors' own dial.
    var knob: TafsCloud.Knob? = nil
    /// The held calls the dial's face can show, the taf's own.
    var held: [HeldCall] = []
    /// The face was pressed on a chosen stop that does something.
    var pressKnob: (TafsCloud.Knob.Item) async -> Void = { _ in }
    /// The face was tapped on a held call: approve it once.
    var approve: (HeldCall) -> Void = { _ in }
    /// Adding a Slack door: its own sheet, which picks the connection and the channel.
    var addSlack: ((TafsCloud.DoorApp) -> Void)? = nil
    /// Removing a Slack door: planque's subscription first, then the door.
    var removeSlack: ((TafsCloud.Door) async -> Void)? = nil
    /// One door's own screen, opened from Setup › Starts when: its settings, no dial.
    var focus: String? = nil
    @State private var face: TafKnobView.Face = .overview
    @ObservedObject private var doors: DoorsModel
    @State private var selected = 0
    @State private var adding = false
    @State private var pairing: TafsCloud.Door?
    @State private var confirmRemove: TafsCloud.Door?
    @State private var copied = false
    /// Whether this person manages the taf: only then are its doors' controls shown.
    let manages: Bool
    /// This person's sign-in: their own key, paired for them, reads "You"; anyone else's doesn't.
    let myEmail: String?

    /// `doors`: the taf's own, shared with its chat (`AppModel.doors(for:)`).
    init(taf: Taf, doors: DoorsModel, manages: Bool = true, myEmail: String? = nil) {
        self.taf = taf
        self.manages = manages
        self.myEmail = myEmail
        self.doors = doors
    }

    /// This Doors tab with the taf's dial in place of the doors' own.
    func knob(_ knob: TafsCloud.Knob?, held: [HeldCall], press: @escaping (TafsCloud.Knob.Item) async -> Void,
              approve: @escaping (HeldCall) -> Void) -> DoorsView {
        var v = self
        v.knob = knob
        v.held = held
        v.pressKnob = press
        v.approve = approve
        return v
    }

    /// One door's screen: its address, who may give it work and its day (Setup › Starts when).
    func only(_ doorID: String) -> DoorsView {
        var v = self
        v.focus = doorID
        return v
    }

    /// This Doors tab with Slack's own sheet for adding a Slack door.
    func slack(add: @escaping (TafsCloud.DoorApp) -> Void, remove: @escaping (TafsCloud.Door) async -> Void) -> DoorsView {
        var v = self
        v.addSlack = add
        v.removeSlack = remove
        return v
    }

    private var door: TafsCloud.Door? {
        if let focus { return doors.doors.first { $0.id == focus } }
        return selected < doors.doors.count ? doors.doors[selected] : nil
    }

    var body: some View {
        Group {
            if focus != nil {
                ScrollView { doorDetails.padding(.horizontal, 16).padding(.vertical, 12) }
                    .navigationTitle(door.map { "\($0.label) door" } ?? "Door")
            } else if let knob {
                TafDialPanel(taf: taf, knob: knob, face: $face, doors: doors, manages: manages,
                             held: held, press: pressKnob, approve: approve, details: doorDetails)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Panel {
                            Eyebrow(text: "Doors · turn to pick, press to open")
                            DoorDial(taf: taf, doors: doors.doors, knocks: doors.openKnocks, selected: $selected,
                                     press: { press() })
                                .frame(height: 300)
                            Text(hint).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity)
                        }
                        doorDetails
                    }
                    .padding(.horizontal, 16).padding(.vertical, 12)
                }
            }
        }
        .task { await doors.load() }
        .onChange(of: face) { face in
            // A door's stop on Wakes it picks that door for its settings below.
            guard case .chosen(.wakes, let id) = face,
                  let doorID = knob?.wakes.first(where: { $0.id == id })?.door,
                  let i = doors.doors.firstIndex(where: { $0.id == doorID }) else { return }
            selected = i
        }
        .onChange(of: held.map(\.id)) { ids in
            // An answered call leaves the face.
            if case .held(let id) = face, !ids.contains(id) { face = .overview }
        }
        .sheet(isPresented: $adding) { AddDoorSheet(doors: doors, addSlack: addSlack) { added in if added { selected = doors.doors.count - 1 } } }
        .sheet(item: $pairing) { PairSheet(door: $0, doors: doors) }
        .confirmationDialog("Remove the \(confirmRemove?.label.lowercased() ?? "") door?", isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }), titleVisibility: .visible) {
            Button(Words.Door.remove, role: .destructive) {
                if let d = confirmRemove {
                    Task {
                        if d.kind == "slack", let removeSlack { await removeSlack(d) } else { await doors.remove(d) }
                        selected = min(selected, doors.doors.count)
                    }
                }
            }
        } message: {
            Text(Words.Door.removeBody(taf: "\(taf.name)"))
        }
    }

    private var doorDetails: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let settings = door?.words?.settings, knob == nil || focus != nil {
                Text(settings).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .frame(maxWidth: .infinity)
            }
            if let door { panel(door) } else if focus != nil {
                Text("This door was removed.").scaledFont(15).foregroundColor(Theme.soft)
            } else if manages, knob == nil { addPanel } else if !manages {
                Text(Words.Door.noDoorYet(taf: "\(taf.name)"))
                    .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            // Strangers' knocks wait on the owner; a paired sender's message, kept from its knock,
            // waits for Do it or Ignore, apart and calmly.
            let paired = doors.openKnocks.filter { !$0.isLine && $0.paired }
            let waiting = doors.openKnocks.filter { !$0.isLine && !$0.paired }
            if !paired.isEmpty {
                Panel {
                    Eyebrow(text: Words.Door.paired)
                    ForEach(paired) { KnockCard(knock: $0, taf: taf, doors: doors, compact: true) }
                }
            }
            if !waiting.isEmpty {
                Panel(stroke: Theme.amber.opacity(0.45)) {
                    Eyebrow(text: Words.Door.knocksWaiting, color: Theme.amber)
                    ForEach(waiting) { KnockCard(knock: $0, taf: taf, doors: doors, compact: true) }
                }
            }
            if let error = doors.error {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
            }
            Text(Words.Door.footer(taf: "\(taf.name)"))
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        }
    }

    private var hint: String {
        guard let door else { return "Press \(taf.name) to add a door" }
        if let tap = door.words?.tap { return tap }
        return "Press \(taf.name) to \(door.open ? "close" : "open") \(door.label)"
    }

    private func press() {
        guard manages else { return }
        guard let door else { adding = true; return }
        Haptics.tap()
        Task { await doors.setOpen(door, !door.open) }
    }

    private var addPanel: some View {
        Panel {
            Eyebrow(text: Words.Door.addDoor)
            Text(Words.Door.addIntro(taf: "\(taf.name)"))
                .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            Button(Words.Door.addDoor) { adding = true }.buttonStyle(PrimaryButtonStyle()).disabled(!doors.loaded || doors.addable.isEmpty)
            if doors.loaded && doors.addable.isEmpty {
                Text(Words.Door.noMore).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
    }

    private func panel(_ door: TafsCloud.Door) -> some View {
        Group {
            Panel {
                HStack(spacing: 12) {
                    DoorBadge(app: door.app, open: door.open, size: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Eyebrow(text: "\(door.label) door")
                        Text(door.words?.state ?? (door.open ? (door.resting ? Words.Door.Status.resting : Words.Door.Status.`open`) : Words.Door.Status.closed))
                            .scaledFont(13, weight: .medium).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer()
                    if manages {
                        Toggle(Words.Door.Status.`open`, isOn: Binding(get: { door.open }, set: { on in Task { await doors.setOpen(door, on) } }))
                            .labelsHidden().tint(Theme.ok).disabled(doors.busy)
                    }
                }
                Text(door.address).scaledFont(15, weight: .medium, design: .monospaced).foregroundColor(Theme.ink).textSelection(.enabled)
                if door.kind == "email" {
                    Button(copied ? Words.Common.copied : Words.Door.copyAddress) {
                        Clipboard.copy(door.address)
                        copied = true
                        Task { try? await Task.sleep(nanoseconds: 1_400_000_000); copied = false }
                    }
                    .buttonStyle(OutlineButtonStyle())
                }
                Meter(fraction: Double(door.today) / Double(max(door.limit, 1)), color: door.resting ? Theme.dim : Theme.tafColor(taf.colorIndex))
                Text(door.resting ? "\(door.limit) of \(door.limit) from you and paired senders today. More are dropped until tomorrow."
                     : "\(door.today) of \(door.limit) from you and paired senders today. Text only: attachments aren't opened, and the reply says so.")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                if let others = door.othersToday, let cap = door.othersLimit {
                    Text("\(others) of \(cap) from anyone else today.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                }
            }
            Panel {
                Eyebrow(text: door.holdsKeys ? Words.Door.Key.paired : Words.Door.Key.noOnePaired)
                if door.holdsKeys {
                    ForEach(door.keys) { key in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                // The address once, when the name is only the address (kiff/tafs#236, item 20).
                                // "You" only on this person's own key (the 870 test: the owner's key read "You").
                                let mine = key.auto && key.id.lowercased() == myEmail?.lowercased()
                                let name = mine ? "You" : key.name.flatMap { $0.lowercased() == key.id.lowercased() ? nil : $0 }
                                Text(name ?? key.id).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                                if name != nil {
                                    Text(key.id).scaledFont(12, design: .monospaced).foregroundColor(Theme.dim)
                                }
                            }
                            Spacer()
                            if !key.auto, manages {
                                Button(Words.Door.unpair) { Task { await doors.unpair(door, key) } }
                                    .confirming()
                                    .scaledFont(13, weight: .medium).foregroundColor(Theme.soft)
                            }
                        }
                    }
                    Text(Words.Door.Key.foot(how: "\(door.how)"))
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    if manages {
                        Button(Words.Door.pairSomeone) { pairing = door }.buttonStyle(OutlineButtonStyle()).confirming()
                    }
                } else {
                    Text(Words.Door.Key.none(how: "\(door.how)", taf: "\(taf.name)"))
                        .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
                if manages {
                    Button(Words.Door.removeDoor, role: .destructive) { confirmRemove = door }
                        .confirming()
                        .scaledFont(14, weight: .medium)
                }
            }
        }
    }
}

/// The dial: the taf in the middle, its doors on a ring that turns, a pointer at the top.
/// Each stop is a door; the last is always "Add a door".
struct DoorDial: View {
    let taf: Taf
    let doors: [TafsCloud.Door]
    let knocks: [TafsCloud.Knock]
    @Binding var selected: Int
    let press: () -> Void

    /// The dial's turn, in degrees: stop `i` is at the top when it's `i` steps.
    @State private var rotation: Double = 0
    @State private var drag: (angle: Double, rotation: Double, stop: Int)?
    @State private var breathe = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var stops: Int { doors.count + 1 }
    private var step: Double { DialMath.step(stops: stops) }

    var body: some View {
        GeometryReader { g in
            let size = min(g.size.width, g.size.height)
            let r = size * 0.36
            let center = CGPoint(x: g.size.width / 2, y: g.size.height / 2 + 6)
            ZStack {
                // The taf's glow: it breathes while a door is open.
                Circle()
                    .fill(RadialGradient(colors: [Theme.tafColor(taf.colorIndex).opacity(anyOpen ? (breathe ? 0.22 : 0.12) : 0.04), .clear],
                                         center: .center, startRadius: 10, endRadius: r * 1.3))
                    .frame(width: r * 2.6, height: r * 2.6)
                // The knob, with grip ticks that turn with it.
                Circle()
                    .fill(LinearGradient(colors: [Theme.panel, Theme.raised], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay(Circle().stroke(Theme.line))
                    .frame(width: (r - 14) * 2, height: (r - 14) * 2)
                ForEach(0..<60, id: \.self) { i in
                    Capsule().fill(Theme.ink.opacity(i % 5 == 0 ? 0.16 : 0.07))
                        .frame(width: 1, height: i % 5 == 0 ? 8 : 4)
                        .offset(y: -(r - 26))
                        .rotationEffect(.degrees(Double(i) * 6 - rotation))
                }
                Circle().stroke(Theme.line, lineWidth: 1).frame(width: r * 2, height: r * 2)
                ForEach(Array(doors.enumerated()), id: \.element.id) { i, door in
                    arc(door, at: DialMath.angle(index: i, stops: stops, rotation: rotation), radius: r)
                }
                // The pointer.
                Triangle().fill(Theme.ink).frame(width: 12, height: 9).offset(y: -r - 20)
                ForEach(0..<stops, id: \.self) { i in
                    stop(i, radius: r)
                }
                Button(action: press) { TafAvatar(taf: taf, size: 56) }
                    .buttonStyle(.drivablePlain)
                    .offset(y: -6)
                    .accessibilityHidden(true)
                Text(centerLine).scaledFont(10, weight: .semibold, design: .monospaced).foregroundColor(centerColor)
                    .offset(y: 38)
            }
            .frame(width: g.size.width, height: g.size.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 6).onChanged { v in turn(v, center: center) }.onEnded { _ in settle() })
        }
        .onAppear {
            rotation = Double(selected) * step
            guard !reduceMotion else { return }
            withMotion(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) { breathe = true }
        }
        .onChange(of: stops) { _ in snap(to: min(selected, stops - 1)) }
        .accessibilityElement()
        .accessibilityLabel("Door dial")
        .accessibilityValue(accessibilityValue)
        .accessibilityHint("Swipe up or down to pick a door. Double-tap to open or close it.")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: snap(to: Int((rotation / step).rounded()) + 1)
            case .decrement: snap(to: Int((rotation / step).rounded()) - 1)
            @unknown default: break
            }
        }
        .accessibilityAction { press() }
    }

    private var anyOpen: Bool { doors.contains { $0.open } }

    private var accessibilityValue: String {
        guard selected < doors.count else { return "Add a door, \(stops) of \(stops)" }
        let d = doors[selected]
        return "\(d.label) door, \(d.open ? "open" : "closed"), \(selected + 1) of \(stops)"
    }

    private var centerLine: String {
        guard selected < doors.count else { return "ADD A DOOR" }
        return "\(doors[selected].label.uppercased()) · \(doors[selected].open ? "OPEN" : "CLOSED")"
    }

    private var centerColor: Color {
        guard selected < doors.count, !doors[selected].open else { return selected < doors.count ? Theme.dim : Theme.tafColor(taf.colorIndex) }
        return Theme.tafColor(taf.colorIndex)
    }

    /// A door's arc on the ring: lit while open, dashed while closed, grey while resting.
    private func arc(_ door: TafsCloud.Door, at angle: Double, radius: CGFloat) -> some View {
        let span = min(step * 0.76, 56)
        let color = door.open ? (door.resting ? Theme.dim : Theme.tafColor(taf.colorIndex)) : Theme.ink.opacity(0.22)
        return Circle()
            .trim(from: 0, to: span / 360)
            .stroke(color, style: StrokeStyle(lineWidth: door.open ? 4 : 2, lineCap: .round, dash: door.open ? [] : [3, 5]))
            .frame(width: radius * 2, height: radius * 2)
            .rotationEffect(.degrees(angle - span / 2))
    }

    /// A stop: the door's badge on the ring and its name outside, or "+" to add one.
    private func stop(_ i: Int, radius: CGFloat) -> some View {
        let a = CGFloat(DialMath.angle(index: i, stops: stops, rotation: rotation) * .pi / 180)
        let isSelected = i == selected
        let door = i < doors.count ? doors[i] : nil
        let knocking = door.map { d in knocks.contains { $0.door == d.id && !$0.isLine } } ?? false
        return ZStack {
            Group {
                if let door {
                    DoorBadge(app: door.app, open: door.open, size: isSelected ? 30 : 24)
                } else {
                    Circle().fill(Theme.panel)
                        .overlay(Circle().stroke(Theme.tafColor(taf.colorIndex), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3])))
                        .overlay(Text("+").font(.system(size: 14, weight: .bold)).foregroundColor(Theme.tafColor(taf.colorIndex)))
                        .frame(width: isSelected ? 30 : 24, height: isSelected ? 30 : 24)
                }
            }
            .overlay(alignment: .topTrailing) {
                if knocking { Circle().fill(Theme.amber).frame(width: 9, height: 9).offset(x: 3, y: -3) }
            }
            .offset(x: cos(a) * radius, y: sin(a) * radius)
            .onTapGesture { turn(to: i) }
            Text((door?.label ?? "Add").uppercased())
                .font(Theme.mono(isSelected ? 11 : 9.5, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? Theme.ink : Theme.dim)
                .fixedSize()
                .offset(x: cos(a) * (radius + 40), y: sin(a) * (radius + 30))
                .opacity(isSelected && abs(sin(a) + 1) < 0.05 ? 0 : 1)
        }
    }

    private func turn(_ v: DragGesture.Value, center: CGPoint) {
        let angle = Double(atan2(v.location.y - center.y, v.location.x - center.x)) * 180 / .pi
        if drag == nil {
            let start = Double(atan2(v.startLocation.y - center.y, v.startLocation.x - center.x)) * 180 / .pi
            drag = (angle: start, rotation: rotation, stop: selected)
        }
        guard let d = drag else { return }
        rotation = d.rotation - DialMath.delta(from: d.angle, to: angle)
        let now = DialMath.stop(rotation: rotation, stops: stops)
        if now != selected {
            selected = now
            Haptics.step()
        }
    }

    private func settle() {
        drag = nil
        snap(to: Int((rotation / step).rounded()))
    }

    /// Turns the shortest way to stop `i`.
    private func turn(to i: Int) {
        let current = Int((rotation / step).rounded())
        snap(to: current + DialMath.shortest(from: current, to: i, stops: stops))
    }

    private func snap(to raw: Int) {
        let target = Double(raw) * step
        if reduceMotion { rotation = target } else {
            withMotion(.spring(response: 0.35, dampingFraction: 0.78)) { rotation = target }
        }
        let now = DialMath.stop(rotation: target, stops: stops)
        if now != selected { Haptics.step() }
        selected = now
    }
}

/// The dial's geometry, apart from drawing it, so it can be tested.
enum DialMath {
    static func step(stops: Int) -> Double { 360 / Double(max(stops, 1)) }

    /// Stop `index`'s angle on screen, in degrees (0 is 3 o'clock, clockwise): the top when the dial is turned to it.
    static func angle(index: Int, stops: Int, rotation: Double) -> Double {
        -90 + Double(index) * step(stops: stops) - rotation
    }

    /// The stop at the top for a turn.
    static func stop(rotation: Double, stops: Int) -> Int {
        let n = Int((rotation / step(stops: stops)).rounded())
        return ((n % stops) + stops) % stops
    }

    /// The turn between two angles, the short way round, in -180...180.
    static func delta(from a: Double, to b: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return d
    }

    /// Steps from stop `from` (any turn) to stop `to`, the short way round.
    static func shortest(from: Int, to: Int, stops: Int) -> Int {
        var d = ((to - from) % stops + stops) % stops
        if Double(d) > Double(stops) / 2 { d -= stops }
        return d
    }
}

struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

/// A door's app, as a small round badge: filled while open.
struct DoorBadge: View {
    let app: String
    let open: Bool
    var size: CGFloat = 24

    var body: some View {
        Circle().fill(open ? DoorNames.color(app) : Theme.panel)
            .overlay(Circle().stroke(open ? Color.clear : Theme.ink.opacity(0.25), lineWidth: 1.5))
            .overlay(Text(DoorNames.mark(app)).font(.system(size: size * 0.46, weight: .heavy, design: .rounded))
                .foregroundColor(open ? .white : Theme.dim))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// How a door's app is named and drawn.
enum DoorNames {
    static func label(_ app: String) -> String {
        switch app {
        case "email": return "email"
        case "whatsapp": return "WhatsApp"
        default: return app.prefix(1).uppercased() + app.dropFirst()
        }
    }

    static func mark(_ app: String) -> String {
        switch app {
        case "email": return "@"
        case "whatsapp": return "W"
        default: return String(app.prefix(1)).uppercased()
        }
    }

    static func color(_ app: String) -> Color {
        switch app {
        case "email": return Theme.sky
        case "whatsapp": return Theme.ok
        // The same colour on every launch: from the name's letters, not its hash.
        default: return Theme.tafColor(app.unicodeScalars.reduce(0) { $0 + Int($1.value) })
        }
    }
}

/// Picks the app a new door opens on: those that can tell who's writing first.
struct AddDoorSheet: View {
    @ObservedObject var doors: DoorsModel
    var addSlack: ((TafsCloud.DoorApp) -> Void)? = nil
    let done: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(Words.Door.addDoor).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Spacer()
                Button(Words.Common.done) { dismiss() }
            }
            DriveField(Words.Connect.Apps.search, text: $query)
                .textFieldStyle(.roundedBorder)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    section("Knows who's writing · can hold keys", doors.addable.filter { $0.proof == "proven" && matches($0) })
                    section("Can't prove who's writing · knocks only", doors.addable.filter { $0.proof != "proven" && matches($0) })
                    if doors.addable.filter(matches).isEmpty {
                        Text(query.isEmpty ? Words.Door.noMore : "No app called \"\(query)\" here yet.")
                            .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                    }
                    Text(Words.Door.repliesOnly)
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).padding(.top, 6)
                }
            }
        }
        .padding(20)
        .screenBackground()
    }

    private func matches(_ app: TafsCloud.DoorApp) -> Bool {
        query.isEmpty || app.label.localizedCaseInsensitiveContains(query)
    }

    @ViewBuilder
    private func section(_ title: String, _ apps: [TafsCloud.DoorApp]) -> some View {
        if !apps.isEmpty {
            Eyebrow(text: title).padding(.top, 6)
            ForEach(apps) { app in
                Button {
                    if app.kind == "slack", let addSlack {
                        dismiss()
                        addSlack(app)
                        return
                    }
                    Task {
                        let added = await doors.add(app) != nil
                        done(added)
                        if added { dismiss() }
                    }
                } label: {
                    HStack(spacing: 12) {
                        DoorBadge(app: app.id, open: true, size: 32)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(app.how).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                        }
                        Spacer()
                    }
                    .padding(12)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.drivablePlain)
                .disabled(doors.busy)
            }
        }
    }
}

/// Pairs a sender with a door: they can give the taf work from now on.
struct PairSheet: View {
    let door: TafsCloud.Door
    @ObservedObject var doors: DoorsModel
    @Environment(\.dismiss) private var dismiss
    @State private var sender = ""
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Words.Door.pairSomeone).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            Text(Words.Door.pairIntro(door: "\(door.label.lowercased())"))
                .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            DriveField(door.kind == "email" ? "Email address" : door.kind == "whatsapp" ? "Phone number, with its country code"
                      : door.kind == "slack" ? "Slack member ID, such as U012AB3CD" : "Who, as \(door.label) names them", text: $sender)
                .textFieldStyle(.roundedBorder)
                .disableAutocorrection(true)
            DriveField(Words.Door.nameOptional, text: $name).textFieldStyle(.roundedBorder)
            Button(Words.Door.pair) {
                Task {
                    await doors.pair(door, sender: sender, name: name)
                    if doors.error == nil { dismiss() }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .confirming()
            .disabled(sender.trimmingCharacters(in: .whitespaces).isEmpty || doors.busy)
            Button(Words.Common.cancel) { dismiss() }.frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(20)
        .screenBackground()
    }
}

/// Someone without a key wrote: who, what they want, and the owner's three answers.
struct KnockCard: View {
    let knock: TafsCloud.Knock
    let taf: Taf?
    @ObservedObject var doors: DoorsModel
    var compact = false
    @State private var asking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(knock.paired ? "PAIRED · BY " : "● KNOCK · BY ")\(DoorNames.label(knock.app).uppercased())\(knock.proof == "fail" ? " · DIDN'T CHECK OUT" : "")")
                .scaledFont(10, weight: .semibold, design: .monospaced).foregroundColor(knock.paired ? Theme.soft : Theme.amberText)
            if knock.paired {
                Text(Words.Door.Knock.pairedLine).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
            }
            if let rest = knock.rest {
                Text(rest == "keys"
                     ? "This door had its messages from you and paired senders for today. \(knock.dropped) more \(knock.dropped == 1 ? "was" : "were") dropped."
                     : "This door had its messages from people who aren't paired for today. \(knock.dropped) more \(knock.dropped == 1 ? "was" : "were") dropped.")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Button(Words.Common.ok) { Task { await doors.answer(knock, "ignore") } }.buttonStyle(OutlineButtonStyle())
            } else {
                Text("\(knock.who) wrote\(knock.subject.map { " about \"\($0)\"" } ?? ""). Nothing was done.")
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Text(knock.text).scaledFont(14).foregroundColor(Theme.soft).lineLimit(compact ? 3 : 8).textSelection(.enabled)
                if knock.attachments > 0 {
                    Text("It had \(knock.attachments) attachment\(knock.attachments == 1 ? "" : "s"), not opened.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                }
                if let note = proofNote {
                    Text(note).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 8) {
                    if knock.proof != "fail" {
                        Button(Words.Door.doItButton) { asking = true }
                            .buttonStyle(.drivablePlain)
                            .scaledFont(14, weight: .semibold).foregroundColor(.white)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(Theme.amber).clipShape(Capsule())
                    }
                    if knock.proof == "pass", !knock.paired {
                        Button("Pair \(knock.name?.components(separatedBy: " ").first ?? "them")") { Task { await doors.answer(knock, "pair") } }
                            .buttonStyle(.drivablePlain).confirming().scaledFont(14, weight: .medium).foregroundColor(Theme.ink)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .overlay(Capsule().stroke(Theme.ink.opacity(0.2)))
                    }
                    Button(Words.Door.ignore) { Task { await doors.answer(knock, "ignore") } }
                        .buttonStyle(.drivablePlain).scaledFont(14, weight: .medium).foregroundColor(Theme.soft)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                }
                .disabled(doors.busy)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        // A paired sender's message waits too, but it isn't a stranger at the door: it reads
        // calmly, not in amber.
        .background(knock.paired ? Theme.panel : Theme.amber.opacity(0.07))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(knock.paired ? Theme.ink.opacity(0.2) : Theme.amber.opacity(0.45)))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .sheet(isPresented: $asking) { DoItSheet(knock: knock, taf: taf, doors: doors) }
    }

    private var proofNote: String? {
        switch knock.proof {
        case "fail": return "It says it's from \(knock.from), but it didn't pass the sender check, so it's treated as a stranger's."
        case "none": return "This door can't prove who wrote it."
        default: return nil
        }
    }
}

/// "Do it": what the owner tells the taf to do about a knock. Their words are the instruction.
struct DoItSheet: View {
    let knock: TafsCloud.Knock
    let taf: Taf?
    @ObservedObject var doors: DoorsModel
    @Environment(\.dismiss) private var dismiss
    @State private var say = Words.Door.DoIt.`default`

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Tell \(taf?.name ?? "your taf") what to do").scaledFont(22, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            Text("\(knock.who)'s message goes with your words, as information. \(taf?.name ?? "It") acts on what you say, within its Card, and doesn't write back to them unless you ask.")
                .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            DriveField("What to do", text: $say, kind: .editor)
                .scaledFont(15)
                .frame(minHeight: 110)
                .padding(6)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.line))
            // Why it wasn't sent, here where the owner is, not on the screen behind the sheet
            // (the 755 retest).
            if let error = doors.error {
                Label(error, systemImage: "exclamationmark.circle.fill")
                    .scaledFont(14, relativeTo: .footnote).foregroundColor(Theme.no)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(doors.busy ? Words.Common.sending : "Send to \(taf?.name ?? "your taf")") {
                Task {
                    await doors.answer(knock, "do", say: say.trimmingCharacters(in: .whitespacesAndNewlines))
                    if doors.error == nil { dismiss() }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(say.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || doors.busy)
            Button(Words.Common.cancel) { dismiss() }.frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(20)
        .screenBackground()
        .onAppear { doors.error = nil }
    }
}

/// The line over a door's message, and under its answer: where it came from, and how it went back.
struct DoorStamp: View {
    let mark: TafsCloud.DoorMark
    let fromOwner: Bool

    var body: some View {
        Text(text).scaledFont(10, weight: .medium, design: .monospaced).foregroundColor(color)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: fromOwner ? .trailing : .leading)
            .accessibilityLabel(text.lowercased())
    }

    private var label: String { DoorNames.label(mark.app) }

    private var text: String {
        if fromOwner {
            return "BY \(label.uppercased())\(mark.from.map { " · \($0)" } ?? "")"
        }
        switch mark.reply {
        case "sent": return "↩ SENT BACK BY \(label.uppercased()) · SAME THREAD"
        case "not_sent": return "NOT SENT BACK\(mark.why.map { " · \($0)" } ?? "")"
        default: return Words.Door.Stamp.goesBack(app: "\(label.uppercased())")
        }
    }

    private var color: Color {
        mark.reply == "not_sent" ? Theme.no : Theme.blue
    }
}

/// The taf's avatar's rim: one lit arc while a door is open, turning slowly; amber while a
/// knock waits; grey when every open door rests for the day.
struct DoorRim: View {
    let rim: AvatarRim?
    let size: CGFloat
    let color: Color
    @State private var spin = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let rim, rim.visible {
            let tint = rim.tint == "knock" ? Theme.amber : rim.tint == "resting" ? Theme.dim : color
            // The arc travels along the rounded edge itself, not a rotated copy of it: turning
            // a rounded square swept its corners out past the avatar's, which read as broken
            // (the 755 retest).
            PerimeterArc(cornerRadius: size * 0.3 + 4, phase: spin ? 1 : 0, length: rim.length)
                .stroke(tint, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .frame(width: size + 8, height: size + 8)
                .onAppear {
                    guard !reduceMotion else { return }
                    withMotion(.linear(duration: 4.5).repeatForever(autoreverses: false)) { spin = true }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

/// A stretch of a rounded rectangle's edge, `length` of its perimeter long, starting `phase`
/// of the way round: animating `phase` moves it along the edge, past the start and on.
struct PerimeterArc: Shape {
    let cornerRadius: CGFloat
    var phase: Double
    let length: Double

    var animatableData: Double {
        get { phase }
        set { phase = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let edge = Path(roundedRect: rect, cornerRadius: cornerRadius, style: .continuous)
        let from = phase - phase.rounded(.down)
        let to = from + length
        guard to > 1 else { return edge.trimmedPath(from: from, to: to) }
        // Over the start: the end of the edge, then its beginning.
        var arc = edge.trimmedPath(from: from, to: 1)
        arc.addPath(edge.trimmedPath(from: 0, to: to - 1))
        return arc
    }
}

/// Adding a Slack door: one of the taf's Slack bot connections, then one of its channels.
/// Approving with Face ID has planque subscribe that channel, then tafs cloud makes the door.
/// What's written in the channel reaches the taf as a door message; paired members give it
/// work, anyone else knocks.
struct SlackDoorSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let tafID: UUID
    @ObservedObject var doors: DoorsModel
    let app: TafsCloud.DoorApp
    @State private var grant: PlanqueClient.Grant?
    @State private var channels: [PlanqueClient.Channel]?
    @State private var busy = false

    var body: some View {
        NavStack {
            List {
                let bots = model.slackBots(for: tafID)
                if bots.isEmpty {
                    Text("Connect the Slack bot for \(model.taf(tafID)?.name ?? "this taf") first, in Connect apps. Then add its door here.")
                        .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                } else {
                    Section("Slack workspace") {
                        ForEach(bots) { bot in
                            Button {
                                grant = bot
                                channels = nil
                                Task { channels = await model.slackChannels(bot.id) ?? [] }
                            } label: {
                                HStack {
                                    Text(bot.account ?? bot.vendor).foregroundColor(Theme.ink)
                                    Spacer()
                                    if grant?.id == bot.id { Image(systemName: "checkmark").foregroundColor(Theme.ok) }
                                }
                            }
                        }
                    }
                    if let grant {
                        Section("Channel") {
                            if let channels {
                                if channels.isEmpty {
                                    Text("The Slack bot sees no channel yet. Invite it to one in Slack, then come back.")
                                        .scaledFont(14).foregroundColor(Theme.dim)
                                }
                                ForEach(channels) { channel in
                                    Button { approve(grant, channel) } label: {
                                        HStack {
                                            Label("#" + (channel.name.isEmpty ? channel.id : channel.name),
                                                  systemImage: channel.isPrivate ? "lock" : "number")
                                                .foregroundColor(Theme.ink)
                                            Spacer()
                                        }
                                    }
                                    .disabled(busy)
                                }
                            } else {
                                ProgressView()
                            }
                        }
                    }
                }
                if let error = model.lastError ?? doors.error {
                    Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                }
            }
            .navigationTitle(Words.Door.slackDoor)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() } } }
            .task {
                await model.refreshConnections()
                if let only = model.slackBots(for: tafID).first, model.slackBots(for: tafID).count == 1, grant == nil {
                    grant = only
                    channels = await model.slackChannels(only.id) ?? []
                }
            }
        }
    }

    /// One Face ID: planque subscribes the channel, then tafs cloud makes the door.
    private func approve(_ grant: PlanqueClient.Grant, _ channel: PlanqueClient.Channel) {
        busy = true
        Task {
            defer { busy = false }
            if await model.addSlackDoor(tafID, doors: doors, app: app, grant: grant, channel: channel) { dismiss() }
        }
    }
}
