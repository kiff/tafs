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
        "owner_took_over": "You touched the app, so it's yours now",
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
/// the drive's stream, does each request in order, answers with the screen after it, and ends at
/// once, with nothing more sent, when the person touches the app, leaves it, stops it, locks the
/// phone or signs out, or tafs cloud ends it.
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

    /// Coach is driving: the overlay takes every touch.
    var isOn: Bool { live != nil && !ending }
    /// A step of Coach's is running: a field that changes now changed by Coach.
    private(set) var stepInFlight = false
    /// The app is resigning active (a prompt, the app switcher, the lock): no step runs until it's active
    /// again or the drive's stop lands.
    private(set) var pausing = false

    private var cloud: TafsCloud?
    private var following: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    /// The app around the drive: Coach's chat closes as it starts and opens at its end, its thread read again.
    var onStart: (@MainActor () -> Void)?
    var onEnd: (@MainActor () -> Void)?
    /// The taf whose screen is up, by name, for the record's words.
    var tafOnScreen: @MainActor () -> String? = { nil }

    // MARK: Start and end

    func begin(_ drive: TafsCloud.Drive, cloud: TafsCloud) {
        if live != nil { finish(why: "replaced", words: "") }
        self.cloud = cloud
        live = Live(id: drive.id, task: drive.task, session: drive.session)
        ending = false
        caption = drive.task
        dot = nil
        DriveRule.on = true
        DriveOverlay.shared.show()
        observe()
        onStart?()
        following = Task { [weak self] in await self?.follow(drive.id) }
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
        // A field the person typed in: Coach's own typing is a step in flight.
        for name in [UITextField.textDidChangeNotification, UITextView.textDidChangeNotification] {
            on(name) { [weak self] in
                guard let self, self.isOn, !self.stepInFlight else { return }
                self.stop("owner_took_over")
            }
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

    /// The app is resigning active: nothing runs while it does.
    func resigned() {
        pausing = true
        stepInFlight = false
    }

    func becameActive() { pausing = false }

    /// A request's answer: refused while the app resigns active, else done or refused by the step itself.
    func answer(_ r: TafsCloud.DriveRequest) async -> TafsCloud.DriveResult {
        if pausing { return withScreen(.refused(DriveLines.tooLate)) }
        return await run(r)
    }

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
