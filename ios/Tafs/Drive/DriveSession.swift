import SwiftUI
import UIKit

/// The signer's drive rule (docs/drive.md): while a drive is on, every confirming signature needs
/// Face ID or Touch ID, never the passcode or an unlock given before.
enum DriveRule {
    @MainActor static var on = false
}

/// The app's words for each step (docs/drive.md), the same on Android.
enum DriveLines {
    static let looked = "Looked"
    static func lookedOnce(_ event: String) -> String { "Looked once \(event) came" }
    static func didntCome(_ event: String) -> String { "Looked: \(event) didn't come in time" }
    static func tapped(_ words: String) -> String { words.isEmpty ? "Tapped" : "Tapped \(words)" }
    static func longPressed(_ words: String) -> String { words.isEmpty ? "Long-pressed" : "Long-pressed \(words)" }
    static let swiped = "Swiped"
    static func scrolled(_ direction: String) -> String { "Scrolled \(direction)" }
    static func typedInto(_ words: String) -> String { "Typed into \(words.isEmpty ? "the field" : words)" }
    static let wentBack = "Went back"
    static let done = "Done"
    static let confirm = "That's yours to confirm"
    static let tooLate = "Too late"
    static let paused = "Paused"
    static let nothing = "Nothing Coach can use there"
    static let notOnScreen = "That isn't on screen now"
    static let noField = "No field is focused"
    static let secretField = "That's yours to fill"
    static let noBack = "Nothing to go back to"
    static func cantScroll(_ direction: String) -> String { "Can't scroll \(direction) there" }
    static let unknown = "This app can't do that step"
    static func failed(_ why: String) -> String { "That step failed: " + String(why.prefix(120)) }

    /// The caption once a drive is over, when tafs cloud's words are missing.
    static let ended: [String: String] = [
        "owner_took_over": "You took over, so Coach let you carry on",
        "left_app": "The app left the screen, so Coach stopped",
        "stopped": "You stopped Coach",
        "locked": "The phone locked, so Coach stopped",
        "signed_out": "You signed out, so Coach stopped",
        "halted": "The account is halted, so Coach stopped",
        "done": "Coach is done",
        "idle": "Nothing was done for 15 minutes, so Coach stopped",
        "replaced": "Another drive started",
    ]
}

/// The person's drive on this phone (docs/drive.md): from the approved start to its end. It follows
/// the drive's stream, does each request in order and answers with the screen after it. The person's
/// touch pauses it until Continue. It ends at once, with nothing more sent, when the person takes over
/// (back, typing, a third tap on a control), leaves the app, stops it, locks the phone or signs out, or
/// tafs cloud ends it.
@MainActor
final class DriveSession: ObservableObject {
    static let shared = DriveSession()
    static let coachButtonID = "coach.button"

    struct Live: Equatable {
        let id: String
        let task: String
        let session: String
    }

    @Published private(set) var live: Live?
    /// Ended, its words still in the caption for a moment.
    @Published private(set) var ending = false
    @Published private(set) var caption: String?
    @Published private(set) var dot: CGPoint?

    /// A drive is on, paused or not: the border and the caption show.
    var isOn: Bool { live != nil && !ending }
    /// The person touched the app (docs/drive.md, "When the person touches the app"): every request is
    /// refused until Continue, whatever tafs cloud says.
    @Published private(set) var paused = false
    /// The person's taps on controls since the drive paused. The third is a takeover.
    private(set) var controlTaps = 0
    static let takeoverTaps = 3
    /// A step of Coach's is running: a field that changes now changed by Coach.
    private(set) var stepInFlight = false
    /// The app is resigning active (a prompt, the app switcher, the lock): no step runs until it's active
    /// again or the drive's stop lands.
    private(set) var pausing = false

    /// Whether tafs cloud has the pause: what it last took.
    private var cloudPaused = false
    private var telling: Task<Void, Never>?
    /// The person's touch under way: where it began, whether on a control, and the navigation stacks then.
    private var touch: (began: CGPoint, control: Bool, stacks: [ObjectIdentifier: Int]?)?
    /// Continue put the keyboard away: what it commits until then isn't the person's typing.
    private var keyboardSettles: Date?

    private var cloud: TafsCloud?
    private var following: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    /// The app around the drive: Coach's chat closes as it starts and opens at its end, its thread read again.
    var onStart: (@MainActor () -> Void)?
    var onEnd: (@MainActor () -> Void)?
    /// The taf whose screen is up, by name, for the record's words.
    var tafOnScreen: @MainActor () -> String? = { nil }

    // MARK: Start and end

    /// `follows` is false only in tests, which hand the session its requests themselves.
    func begin(_ drive: TafsCloud.Drive, cloud: TafsCloud, follows: Bool = true) {
        if live != nil { finish(why: "replaced", words: "") }
        self.cloud = cloud
        live = Live(id: drive.id, task: drive.task, session: drive.session)
        ending = false
        caption = drive.task
        dot = nil
        clearPause()
        DriveRule.on = true
        DriveOverlay.shared.show()
        DriveTouches.attach()
        observe()
        onStart?()
        if follows { following = Task { [weak self] in await self?.follow(drive.id) } }
    }

    private func clearPause() {
        paused = false
        controlTaps = 0
        cloudPaused = false
        touch = nil
        keyboardSettles = nil
        telling?.cancel()
        telling = nil
    }

    /// The person or the app ends the drive: at once here, and tafs cloud is told.
    func stop(_ why: String) {
        guard let live, !ending else { return }
        let cloud = self.cloud
        finish(why: why, words: DriveLines.ended[why] ?? "")
        let id = live.id
        let task = UIApplication.shared.beginBackgroundTask(withName: "drive-stop")
        Task {
            _ = try? await cloud?.stopDrive(id, why: why)
            UIApplication.shared.endBackgroundTask(task)
        }
    }

    /// As `stop`, waiting until tafs cloud heard: before a sign-out forgets the session.
    func stopAndWait(_ why: String) async {
        guard let live, !ending else { return }
        let cloud = self.cloud
        finish(why: why, words: DriveLines.ended[why] ?? "")
        _ = try? await cloud?.stopDrive(live.id, why: why)
    }

    /// After a restart: a drive this phone left on is over (it never outlives the app).
    func endLeftover(_ cloud: TafsCloud) async {
        guard live == nil, let read = try? await cloud.readDrive(), let d = read.drive, d.ended == nil else { return }
        _ = try? await cloud.stopDrive(d.id, why: "left_app")
    }

    private func finish(why: String, words: String) {
        guard let live, !ending else { return }
        ending = true
        stepInFlight = false
        pausing = false
        clearPause()
        DriveTouches.detach()
        dot = nil
        let said = words.isEmpty ? (DriveLines.ended[why] ?? "Coach stopped") : Self.sentence(words)
        caption = said
        DriveRule.on = false
        following?.cancel()
        following = nil
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard let self, self.live == live else { return }
            DriveOverlay.shared.hide()
            self.live = nil
            self.ending = false
            self.caption = nil
            self.onEnd?()
        }
    }

    /// tafs cloud's words ("the owner stopped it") as a caption: "The owner stopped it".
    nonisolated static func sentence(_ words: String) -> String {
        let w = words.trimmingCharacters(in: .whitespacesAndNewlines)
        return w.prefix(1).uppercased() + w.dropFirst()
    }

    // MARK: What ends it

    private func observe() {
        let c = NotificationCenter.default
        func on(_ name: Notification.Name, _ run: @escaping @MainActor () -> Void) {
            observers.append(c.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { run() }
            })
        }
        // A sign-in page, a permission dialog, the share sheet, the app switcher, the lock: the drive
        // ends. The lock says so a moment later, when the phone's data closes.
        on(UIApplication.willResignActiveNotification) { [weak self] in
            guard let self, self.isOn else { return }
            self.resigned()
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 600_000_000)
                self?.stop(UIApplication.shared.isProtectedDataAvailable ? "left_app" : "locked")
            }
        }
        on(UIApplication.protectedDataWillBecomeUnavailableNotification) { [weak self] in self?.stop("locked") }
        on(UIApplication.didBecomeActiveNotification) { [weak self] in self?.becameActive() }
        on(UIApplication.didEnterBackgroundNotification) { [weak self] in self?.stop("left_app") }
        // A field the person typed in: Coach's own typing is a step in flight. The change is kept.
        for name in [UITextField.textDidChangeNotification, UITextView.textDidChangeNotification] {
            on(name) { [weak self] in
                guard let self, self.isOn, !self.stepInFlight else { return }
                if let until = self.keyboardSettles, Date() < until { return }
                self.stop("owner_took_over")
            }
        }
        // A window that opens mid-drive, such as a menu's, is watched too.
        on(UIWindow.didBecomeVisibleNotification) { [weak self] in
            guard let self, self.isOn else { return }
            DriveTouches.attach()
        }
    }

    // MARK: The person's touch

    /// A touch of the person's began on one of the app's windows, at a point of the screen. It pauses the
    /// drive, and is delivered as usual: the Coach button's own action then stops the drive.
    func personBegan(at p: CGPoint) {
        guard isOn else { return }
        // A step of Coach's under way may itself go back: this touch doesn't tell the two apart.
        let stacks = stepInFlight ? nil : DriveHands.stacks()
        pause()
        touch = (began: p, control: Self.isControl(DriveTargets.shared.hit(p)), stacks: stacks)
    }

    /// That touch ended. A tap on a control counts toward a takeover, and a back (the edge swipe, or
    /// the bar's button) is one.
    func personEnded(at p: CGPoint) {
        guard isOn, let t = touch else { return }
        touch = nil
        if t.control, hypot(p.x - t.began.x, p.y - t.began.y) < 12 {
            controlTaps += 1
            if controlTaps >= Self.takeoverTaps { stop("owner_took_over"); return }
        }
        guard let before = t.stacks, let id = live?.id else { return }
        // A pop settles after the finger lifts.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard let self, self.isOn, self.live?.id == id else { return }
            if DriveHands.wentBack(since: before) { self.stop("owner_took_over") }
        }
    }

    /// Something that acts when tapped: a button, a row, a toggle, a link, a tab. A field only takes the
    /// focus, and the Coach button stops the drive.
    static func isControl(_ shown: DriveTargets.Shown?) -> Bool {
        guard let e = shown?.entry else { return false }
        return e.actions[.activate] != nil && e.role != .textField && e.id != coachButtonID
    }

    private func pause() {
        guard !paused else { return }
        paused = true
        controlTaps = 0
        dot = nil
        tellCloud()
    }

    /// Continue: the keyboard goes away first (the field keeps its text), then the drive goes on.
    func resume() {
        guard isOn, paused else { return }
        keyboardSettles = Date().addingTimeInterval(1)
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        paused = false
        controlTaps = 0
        touch = nil
        tellCloud()
    }

    /// Tells tafs cloud the pause, or its end, until it has it: the app's own pause never waits for
    /// this. One task at a time, so a pause and a Continue reach tafs cloud in order.
    private func tellCloud() {
        guard telling == nil, let id = live?.id, let cloud else { return }
        telling = Task { [weak self] in
            var wait: UInt64 = 1
            while let self, self.isOn, self.live?.id == id, self.cloudPaused != self.paused, !Task.isCancelled {
                let want = self.paused
                do {
                    let ended: TafsCloud.DriveEnded?
                    if want { ended = try await cloud.pauseDrive(id) } else { ended = try await cloud.resumeDrive(id) }
                    guard self.live?.id == id, !Task.isCancelled else { return }
                    // tafs cloud had already ended it (halted, idle): its words go in the caption.
                    if let ended {
                        self.finish(why: ended.why, words: ended.words ?? "")
                        return
                    }
                    self.cloudPaused = want
                    wait = 1
                } catch {
                    if Task.isCancelled { return }
                    // A 404 says the drive isn't this person's any more: it ends here, in tafs cloud's
                    // words. Anything else (the network, a 5xx, a 429, a sign-in to refresh) leaves a drive
                    // tafs cloud still has, so it's posted again, and the app's own pause stands meanwhile.
                    if let e = error as? TafsCloud.CloudError, e.status == 404 {
                        await self.endAsTafsCloudSays(id)
                        return
                    }
                    try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
                    wait = min(wait * 2, 8)
                }
            }
            // A drive that ended or was replaced cleared this itself, and may have a telling of its own.
            if let self, !Task.isCancelled, self.live?.id == id { self.telling = nil }
        }
    }

    // MARK: The stream

    private func follow(_ id: String) async {
        var failures = 0
        while let current = live, current.id == id, !ending, !Task.isCancelled {
            guard let cloud else { return }
            do {
                let events = try await cloud.driveStream(id)
                failures = 0
                for try await event in events {
                    guard self.live?.id == id, !ending else { return }
                    switch event {
                    case .drive, .reopen: continue
                    case .ended(let why, let words): finish(why: why, words: words); return
                    case .request(let r): await handle(r, drive: id)
                    }
                }
            } catch {
                if Task.isCancelled || ending { return }
                if let e = error as? TafsCloud.CloudError, [401, 403, 404].contains(e.status) {
                    finish(why: "replaced", words: e.errorDescription ?? "")
                    return
                }
                failures += 1
                try? await Task.sleep(nanoseconds: UInt64(min(failures, 4)) * 1_000_000_000)
                if let read = try? await cloud.readDrive(), read.drive?.id != id || read.drive?.ended != nil {
                    finish(why: read.drive?.ended?.why ?? "replaced", words: read.drive?.ended?.words ?? "")
                    return
                }
                if failures >= 8 { stop("left_app"); return }
            }
        }
    }

    /// tafs cloud doesn't have this drive for the person any more. It ends in the app, with nothing
    /// posted: with the end tafs cloud gives for it, else the app's own "Coach stopped". Never as a
    /// takeover, which the person didn't make.
    private func endAsTafsCloudSays(_ id: String) async {
        let read = try? await cloud?.readDrive()
        guard live?.id == id, !ending else { return }
        if let drive = read?.drive, drive.id != id {
            finish(why: "replaced", words: "")
        } else {
            let ended = read?.drive?.ended
            finish(why: ended?.why ?? "", words: ended?.words ?? "")
        }
    }

    /// The app is resigning active: nothing runs while it does.
    func resigned() {
        pausing = true
        stepInFlight = false
    }

    func becameActive() { pausing = false }

    /// A request's answer: refused while the app resigns active, or while the person has the app, else
    /// done or refused by the step itself.
    func answer(_ r: TafsCloud.DriveRequest) async -> TafsCloud.DriveResult {
        if pausing { return withScreen(.refused(DriveLines.tooLate)) }
        if paused { return Self.refusedPaused }
        return await run(r)
    }

    /// The answer to any request while the drive is paused. It carries no screen: the person is using
    /// the app, and Coach looks again after Continue.
    static let refusedPaused = TafsCloud.DriveResult.refused(DriveLines.paused)

    /// One request: done or refused, and always answered with the screen after it.
    func handle(_ r: TafsCloud.DriveRequest, drive: String) async {
        let result = await answer(r)
        guard isOn || r.action == "done" else { return }
        do {
            try await cloud?.postDriveResult(drive, request: r.id, result: result)
        } catch {
            DriveDiagnostics.shared.error("A step's answer didn't reach tafs cloud: \(error.localizedDescription)")
        }
    }

    /// Does a request and says what came of it. Too late, it's refused and not done.
    func run(_ r: TafsCloud.DriveRequest, now: Date = Date()) async -> TafsCloud.DriveResult {
        if let within = r.startWithinMs, now.timeIntervalSince(r.came ?? now) * 1000 > Double(within) {
            return withScreen(.refused(DriveLines.tooLate))
        }
        switch r.tool {
        case "app_look": return await look(r)
        case "app_act": return await act(r)
        default: return withScreen(.refused(DriveLines.unknown))
        }
    }

    private func withScreen(_ result: TafsCloud.DriveResult) -> TafsCloud.DriveResult {
        var out = result
        let shot = DriveCapture.capture()
        if let jpeg = shot.jpeg { out.image = TafsCloud.DriveResult.Image(mediaType: "image/jpeg", data: jpeg.base64EncodedString()) }
        return out
    }

    private func settle(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private func look(_ r: TafsCloud.DriveRequest) async -> TafsCloud.DriveResult {
        var result = TafsCloud.DriveResult.done(DriveLines.looked)
        if let event = r.wait {
            let came = await DriveWaits.wait(event, timeout: r.timeoutS ?? 10)
            result.waited = TafsCloud.DriveResult.Waited(event: event, came: came)
            result.line = came ? DriveLines.lookedOnce(event) : DriveLines.didntCome(event)
        }
        if paused { return Self.refusedPaused }
        let geo = DriveCapture.geometry()
        let shown = DriveTargets.shared.shown()
        let image = DriveCapture.image(geo, blank: DriveCapture.blanked(shown))
        if let jpeg = DriveCapture.jpeg(image) { result.image = TafsCloud.DriveResult.Image(mediaType: "image/jpeg", data: jpeg.base64EncodedString()) }
        if r.tree { result.tree = Self.tree(shown, geo: geo, lines: DriveWords.lines(in: image)) }
        if r.diagnostics { result.diagnostics = DriveDiagnosticsReport.now() }
        return result
    }

    /// The view tree as both apps send it: what can be seen or used in the topmost screen, its bounds
    /// in the screenshot's pixels. A control that registered no words is named by the words drawn in it.
    static func tree(_ shown: [DriveTargets.Shown], geo: DriveCapture.Geometry, lines: [(String, CGRect)]) -> [TafsCloud.DriveResult.Element] {
        shown.compactMap { s -> TafsCloud.DriveResult.Element? in
            let e = s.entry
            guard e.id != coachButtonID else { return nil }
            let px = geo.toPixels(s.frame)
            var words = e.words
            if e.secret { words = "Secret field" } else if words.isEmpty && !e.terms { words = DriveWords.words(in: px, from: lines) }
            var actions: [String] = []
            if !e.terms {
                if e.actions[.activate] != nil { actions.append("tap") }
                if e.actions[.longPress] != nil { actions.append("long_press") }
                if e.actions[.swipeLeft] != nil || e.actions[.swipeRight] != nil { actions.append("swipe") }
                if e.role == .textField { actions.append("type") }
            }
            guard !words.isEmpty || !actions.isEmpty else { return nil }
            return TafsCloud.DriveResult.Element(id: e.id, words: String(words.prefix(200)), role: e.role.rawValue,
                         bounds: .init(x: Int(px.minX.rounded()), y: Int(px.minY.rounded()), w: Int(px.width.rounded()), h: Int(px.height.rounded())),
                         actions: actions, confirm: e.confirm ? true : nil)
        }
    }

    // MARK: Acting

    private func act(_ r: TafsCloud.DriveRequest) async -> TafsCloud.DriveResult {
        guard let action = r.action else { return withScreen(.refused(DriveLines.unknown)) }
        if let line = r.line, !line.isEmpty { caption = String(line.prefix(60)) }
        let geo = DriveCapture.geometry()
        let shown = DriveTargets.shared.shown()
        var point = r.at.map { geo.toScreen($0) }
        var target: DriveTargets.Shown?
        if let id = r.element {
            guard let s = shown.first(where: { $0.entry.id == id }) else { return withScreen(.refused(DriveLines.notOnScreen)) }
            target = s
            point = CGPoint(x: s.frame.midX, y: s.frame.midY)
        } else if let p = point {
            target = DriveTargets.shared.hit(p, in: shown)
        }
        stepInFlight = true
        defer { stepInFlight = false }
        switch action {
        case "tap", "long_press":
            guard let p = point else { return withScreen(.refused(DriveLines.nothing)) }
            // Hit-tested before anything runs: a confirming control is the person's.
            if DriveTargets.shared.confirms(p, in: shown) || target?.entry.confirm == true { return withScreen(.refused(DriveLines.confirm)) }
            guard let t = target, t.entry.id != Self.coachButtonID,
                  let perform = t.entry.actions[action == "tap" ? .activate : .longPress] else { return withScreen(.refused(DriveLines.nothing)) }
            let words = wordsOf(t, geo: geo)
            await showDot(p)
            // The person touched the app while the dot showed: nothing was dispatched.
            if paused { return Self.refusedPaused }
            perform()
            await settle(0.6)
            var result = withScreen(.done(action == "tap" ? DriveLines.tapped(words) : DriveLines.longPressed(words)))
            result.touched = touched(words)
            return result
        case "swipe":
            guard let from = point, let to = r.to.map({ geo.toScreen($0) }) else { return withScreen(.refused(DriveLines.nothing)) }
            if DriveTargets.shared.confirms(from, in: shown) { return withScreen(.refused(DriveLines.confirm)) }
            let dx = to.x - from.x, dy = to.y - from.y
            await showDot(from)
            if paused { return Self.refusedPaused }
            if abs(dx) > abs(dy), let t = target, let perform = t.entry.actions[dx < 0 ? .swipeLeft : .swipeRight] {
                perform()
            } else if let view = DriveHands.scrollView(at: from), DriveHands.scroll(view, dx: -dx, dy: -dy) {
                // A finger moving up shows what's below.
            } else {
                return withScreen(.refused(DriveLines.nothing))
            }
            await settle(0.5)
            return withScreen(.done(DriveLines.swiped))
        case "scroll":
            let direction = r.direction ?? "down"
            let view = target.flatMap { $0.entry.anchor.flatMap(DriveHands.enclosingScrollView) }
                ?? DriveHands.scrollView(at: point ?? CGPoint(x: geo.screen.width / 2, y: geo.screen.height / 2))
            guard let view, DriveHands.scroll(view, direction: direction) else { return withScreen(.refused(DriveLines.cantScroll(direction))) }
            await settle(0.5)
            return withScreen(.done(DriveLines.scrolled(direction)))
        case "type":
            guard let text = r.text, let field = DriveFields.focused(), let input = field as? UIKeyInput else {
                return withScreen(.refused(DriveLines.noField))
            }
            let place = DriveTargets.visibleFrame(field) ?? .null
            let entry = shown.filter { $0.entry.role == .textField && $0.frame.intersects(place) }
                .max { $0.frame.intersection(place).width * $0.frame.intersection(place).height < $1.frame.intersection(place).width * $1.frame.intersection(place).height }
            // A secret field (a key, a password, a code) is the person's to fill: nothing is typed.
            if entry?.entry.secret == true || (field as? UITextField)?.isSecureTextEntry == true {
                return withScreen(.refused(DriveLines.secretField))
            }
            let words = entry?.entry.words ?? ""
            input.insertText(text)
            await settle(0.4)
            var result = withScreen(.done(DriveLines.typedInto(words)))
            result.touched = touched(words.isEmpty ? "a field" : words)
            return result
        case "back":
            guard DriveHands.back() else { return withScreen(.refused(DriveLines.noBack)) }
            await settle(0.7)
            return withScreen(.done(DriveLines.wentBack))
        case "done":
            return withScreen(.done(DriveLines.done))
        default:
            return withScreen(.refused(DriveLines.unknown))
        }
    }

    private func wordsOf(_ s: DriveTargets.Shown, geo: DriveCapture.Geometry) -> String {
        if !s.entry.words.isEmpty || s.entry.secret { return s.entry.secret ? "" : s.entry.words }
        let image = DriveCapture.image(geo, blank: [])
        return DriveWords.words(in: geo.toPixels(s.frame), from: DriveWords.lines(in: image))
    }

    private func touched(_ words: String) -> TafsCloud.DriveTouched? {
        words.isEmpty ? nil : TafsCloud.DriveTouched(words: String(words.prefix(200)), taf: tafOnScreen())
    }

    /// The dot at a touch's target, a moment before it.
    private func showDot(_ p: CGPoint) async {
        dot = p
        await settle(0.35)
        dot = nil
    }
}

/// Gestures that aren't a control's own action: a scroll, and back.
@MainActor
enum DriveHands {
    static func enclosingScrollView(_ view: UIView) -> UIScrollView? {
        var v = view.superview
        while let s = v {
            if let sv = s as? UIScrollView, !(sv is UITextView), sv.isScrollEnabled { return sv }
            v = s.superview
        }
        return nil
    }

    /// The innermost scroll view under a point in the topmost screen that can move.
    static func scrollView(at p: CGPoint) -> UIScrollView? {
        guard let top = DriveTargets.topView() else { return nil }
        var found: [(UIScrollView, CGRect)] = []
        func visit(_ v: UIView) {
            if v.isHidden || v.alpha < 0.01 { return }
            if let sv = v as? UIScrollView, !(sv is UITextView), sv.isScrollEnabled, let f = DriveTargets.visibleFrame(sv), f.contains(p) {
                found.append((sv, f))
            }
            v.subviews.forEach(visit)
        }
        visit(top)
        return found.min { $0.1.width * $0.1.height < $1.1.width * $1.1.height }?.0
    }

    /// Moves a scroll view by a distance, within its content; false when it can't move.
    static func scroll(_ sv: UIScrollView, dx: CGFloat, dy: CGFloat) -> Bool {
        let inset = sv.adjustedContentInset
        let minX = -inset.left, maxX = max(minX, sv.contentSize.width - sv.bounds.width + inset.right)
        let minY = -inset.top, maxY = max(minY, sv.contentSize.height - sv.bounds.height + inset.bottom)
        let target = CGPoint(x: min(max(sv.contentOffset.x + dx, minX), maxX), y: min(max(sv.contentOffset.y + dy, minY), maxY))
        guard abs(target.x - sv.contentOffset.x) > 1 || abs(target.y - sv.contentOffset.y) > 1 else { return false }
        sv.setContentOffset(target, animated: true)
        return true
    }

    /// The direction names where the content comes from: "down" shows what's below.
    static func scroll(_ sv: UIScrollView, direction: String) -> Bool {
        let w = sv.bounds.width * 0.7, h = sv.bounds.height * 0.7
        switch direction {
        case "down": return scroll(sv, dx: 0, dy: h)
        case "up": return scroll(sv, dx: 0, dy: -h)
        case "right": return scroll(sv, dx: w, dy: 0)
        case "left": return scroll(sv, dx: -w, dy: 0)
        default: return false
        }
    }

    /// Every navigation stack in the app's windows, with how deep it is.
    static func stacks() -> [ObjectIdentifier: Int] {
        var out: [ObjectIdentifier: Int] = [:]
        func visit(_ vc: UIViewController) {
            if let nav = vc as? UINavigationController { out[ObjectIdentifier(nav)] = nav.viewControllers.count }
            vc.children.forEach(visit)
            if let shown = vc.presentedViewController, shown.presentingViewController === vc { visit(shown) }
        }
        for window in DriveTargets.appWindows() { if let root = window.rootViewController { visit(root) } }
        return out
    }

    /// Whether a stack that was there is shallower now: the person went back. A sheet that closed took
    /// its stacks with it, and isn't one.
    static func wentBack(since before: [ObjectIdentifier: Int]) -> Bool {
        let now = stacks()
        return before.contains { id, depth in (now[id] ?? depth) < depth }
    }

    /// Back: the top sheet or alert dismissed (an alert's cancel, never its confirm), else the
    /// navigation stack on screen goes back one.
    static func back() -> Bool {
        if let top = DriveTargets.topPresented() {
            top.dismiss(animated: true)
            return true
        }
        guard let root = DriveTargets.appWindows().last(where: { $0.rootViewController != nil })?.rootViewController else { return false }
        var stacks: [UINavigationController] = []
        func visit(_ vc: UIViewController) {
            if let nav = vc as? UINavigationController, nav.viewControllers.count > 1, DriveTargets.visibleFrame(nav.view) != nil { stacks.append(nav) }
            vc.children.forEach(visit)
        }
        visit(root)
        guard let nav = stacks.last else { return false }
        nav.popViewController(animated: true)
        return true
    }
}
