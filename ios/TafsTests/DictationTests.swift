import XCTest
@testable import Tafs

/// A recognizer the test drives: what permission says, whether the language runs on the
/// device, and what it hears.
@MainActor
final class FakeSpeech: SpeechEngine {
    var refused: String?
    var notHere: String?
    var have: [Dictation.Language] = [.english, .spanish]
    var startedWith: (language: Dictation.Language, hints: [String])?
    var stops = 0
    var starts = 0
    /// From this start on, starting throws: the microphone was taken.
    var failFrom: Int?
    private var onText: (@Sendable (String, Bool) -> Void)?
    private var onLevel: (@Sendable (Float) -> Void)?
    private var onEnd: (@Sendable (String?) -> Void)?

    func authorize() async -> String? { refused }
    func languages() -> [Dictation.Language] { have }
    var noLanguage: String { "Dictation isn't on this iPhone yet." }
    func unavailable(_ language: Dictation.Language) -> String? { notHere }
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String, Bool) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void, onEnd: @escaping @Sendable (String?) -> Void) throws {
        starts += 1
        if let failFrom, starts >= failFrom { throw URLError(.cannotConnectToHost) }
        startedWith = (language, hints)
        self.onText = onText
        self.onLevel = onLevel
        self.onEnd = onEnd
    }
    func stop() { stops += 1 }

    func hear(_ words: String, done: Bool = false) { onText?(words, done) }
    func sound(_ level: Float) { onLevel?(level) }
    func end(_ why: String? = nil) { onEnd?(why) }
}

@MainActor
final class DictationTests: XCTestCase {
    /// Lets the main actor run what the recognizer handed it.
    private func settle() async { await Task.yield(); await Task.yield() }

    func testRefusedPermissionSaysWhyAndHowAndWritesNothing() async {
        let speech = FakeSpeech()
        speech.refused = "The microphone is off for tafs. Turn it on in Settings › tafs › Microphone."
        let dictation = Dictation(engine: speech, language: .english)
        var box = "Refund"
        await dictation.start(text: box, hints: []) { box = $0 }
        XCTAssertEqual(dictation.state, .blocked("The microphone is off for tafs. Turn it on in Settings › tafs › Microphone."))
        XCTAssertNil(speech.startedWith, "nothing listens without permission")
        XCTAssertEqual(box, "Refund")
        dictation.dismiss()
        XCTAssertEqual(dictation.state, .idle)
    }

    func testALanguageNotOnTheDeviceSaysSo() async {
        let speech = FakeSpeech()
        speech.notHere = "Dictation in Spanish isn't available on this device."
        let dictation = Dictation(engine: speech, language: .spanish)
        await dictation.start(text: "", hints: []) { _ in }
        XCTAssertEqual(dictation.state, .blocked("Dictation in Spanish isn't available on this device."))
        XCTAssertNil(speech.startedWith)
    }

    /// Dictation only writes into the box, after what was there. Stopping leaves the words
    /// there: nothing is sent until the owner taps send.
    func testWordsGoIntoTheBoxAndStayThereUnsent() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = "Please"
        var writes = 0
        await dictation.start(text: box, hints: ["Rayo", "Stripe"]) { box = $0; writes += 1 }
        XCTAssertEqual(dictation.state, .listening)
        XCTAssertEqual(speech.startedWith?.hints, ["Rayo", "Stripe"])
        speech.hear("refund order")
        await settle()
        speech.hear("refund order 4471")
        await settle()
        XCTAssertEqual(box, "Please refund order 4471")
        dictation.stop()
        XCTAssertEqual(dictation.state, .idle)
        XCTAssertEqual(speech.stops, 1)
        speech.hear("late words")
        await settle()
        XCTAssertEqual(box, "Please refund order 4471", "nothing is written after it stopped")
        XCTAssertEqual(writes, 2)
    }

    /// A pause ends a stretch of speech: what was said stays, and the next words go after
    /// it, never over it (in a test on a device, a long pause replaced the whole text).
    func testAfterAPauseTheNextWordsGoAfterTheEarlierOnes() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = "Hi"
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("refund order")
        await settle()
        speech.hear("refund order 4471", done: true)
        await settle()
        await settle()
        speech.hear("and tell")
        await settle()
        speech.hear("and tell the customer")
        await settle()
        await settle()
        XCTAssertEqual(box, "Hi refund order 4471 and tell the customer")
        // Without the recognizer saying the stretch was done: its text starts afresh.
        speech.hear("thanks")
        await settle()
        XCTAssertEqual(box, "Hi refund order 4471 and tell the customer thanks")
        // A revision of the stretch being heard replaces it, as it should.
        speech.hear("thanks a lot")
        await settle()
        XCTAssertEqual(box, "Hi refund order 4471 and tell the customer thanks a lot")
    }

    func testStartsAfresh() {
        XCTAssertFalse(Dictation.startsAfresh("", "hello"))
        XCTAssertFalse(Dictation.startsAfresh("refund ten", "refund 10"), "a revision keeps its start")
        XCTAssertFalse(Dictation.startsAfresh("refund", "refund order"))
        XCTAssertTrue(Dictation.startsAfresh("refund order 4471", "and"))
        XCTAssertFalse(Dictation.startsAfresh("hi", "and tell the customer"), "longer: still being heard")
    }

    /// The recognizer's own end (a pause, its limit, an error) doesn't stop dictating: it
    /// listens again, after what was heard. Only after a few ends with nothing heard does it
    /// stop, quietly; nothing is ever sent.
    func testTheRecognizersEndListensAgainUntilItsQuiet() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("Halt the refunds")
        await settle()
        speech.end()
        await settle()
        XCTAssertEqual(dictation.state, .listening)
        XCTAssertEqual(speech.starts, 2)
        speech.hear("now")
        await settle()
        XCTAssertEqual(box, "Halt the refunds now")

        speech.end("Dictation stopped. Tap the mic to start again.")
        await settle()
        speech.end()
        await settle()
        XCTAssertEqual(dictation.state, .listening, "two quiet ends: still listening")
        speech.end()
        await settle()
        XCTAssertEqual(dictation.state, .idle, "the third quiet end stops it, with no alert")
        XCTAssertEqual(box, "Halt the refunds now")
    }

    /// Listening again can fail (a call took the microphone): it says so, never stops silently.
    func testAFailedRestartSaysWhy() async {
        let speech = FakeSpeech()
        speech.failFrom = 2
        let dictation = Dictation(engine: speech, language: .english)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("Refund")
        await settle()
        speech.end()
        await settle()
        guard case .blocked(let why) = dictation.state else { return XCTFail("state \(dictation.state)") }
        XCTAssertTrue(why.hasPrefix("Dictation stopped: "), why)
        XCTAssertTrue(why.hasSuffix("Tap the mic to start again."), why)
        XCTAssertEqual(box, "Refund", "what was heard stays")
    }

    /// Ending with errors three times stops it with the last error, not silently.
    func testRepeatedErrorsStopWithTheLastWhy() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        await dictation.start(text: "", hints: []) { _ in }
        for _ in 0..<3 {
            speech.end("Dictation stopped. Tap the mic to start again.")
            await settle()
        }
        XCTAssertEqual(dictation.state, .blocked("Dictation stopped. Tap the mic to start again."))
        XCTAssertEqual(speech.starts, 3)
    }

    func testSwitchingLanguageListensAgainInTheOther() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("Refund")
        await settle()
        await dictation.switchLanguage(text: box, hints: []) { box = $0 }
        XCTAssertEqual(dictation.language, .spanish)
        XCTAssertEqual(speech.startedWith?.language, .spanish)
        XCTAssertEqual(dictation.state, .listening)
        speech.hear("el pedido")
        await settle()
        XCTAssertEqual(box, "Refund el pedido")
    }

    func testJoiningAndHints() {
        XCTAssertEqual(Dictation.joined("", "hello"), "hello")
        XCTAssertEqual(Dictation.joined("Hi", "there"), "Hi there")
        XCTAssertEqual(Dictation.joined("Hi ", "there"), "Hi there")
        XCTAssertEqual(Dictation.joined("Hi", "  "), "Hi")
        XCTAssertEqual(Dictation.cleaned(["Stripe", " stripe ", "", "Rayo"]), ["Stripe", "Rayo"])
        XCTAssertEqual(Dictation.cleaned((0..<150).map { "w\($0)" }).count, 100)
        XCTAssertEqual(Dictation.purpose, "tafs turns your voice into text on this device. Your voice isn't sent anywhere.")
    }

    /// Any language the device dictates in, not a fixed two (a device with only French
    /// dictation): one form each, the owner's own first, then the rest by code.
    func testTheDevicesOwnLanguagesTheOwnersFirst() {
        let supported = ["en-US", "en-GB", "es-ES", "es-MX", "fr-FR", "fr-CA", "de-DE"]
        XCTAssertEqual(Dictation.Language.ordered(supported: supported, preferred: ["es-CO", "fr_CA"]).map(\.id),
                       ["es-ES", "fr-CA", "de-DE", "en-GB"])
        XCTAssertEqual(Dictation.Language.ordered(supported: ["fr-FR"], preferred: ["es-CO", "en-US"]).map(\.id), ["fr-FR"])
        XCTAssertEqual(Dictation.Language.ordered(supported: [], preferred: ["es-CO"]), [])
        // The owner's own form missing: the same language's best form, as Android chooses it
        // (English: British, then American; another: its own country's; else alphabetical).
        XCTAssertEqual(Dictation.Language.bestForm(of: "en", among: ["en-AU", "en-GB", "en-US"]), "en-GB")
        XCTAssertEqual(Dictation.Language.bestForm(of: "en", among: ["en-AU", "en-US"]), "en-US")
        XCTAssertEqual(Dictation.Language.bestForm(of: "fr", among: ["fr-CA", "fr-FR"]), "fr-FR")
        XCTAssertEqual(Dictation.Language.bestForm(of: "pt", among: ["pt-BR"]), "pt-BR")
        XCTAssertNil(Dictation.Language.bestForm(of: "fr", among: ["en-US"]))
        XCTAssertEqual(Dictation.Language.ordered(supported: ["en-AU", "en-GB", "en-US"], preferred: ["en-FR"]).map(\.id), ["en-GB"])
        // The owner's own: their keyboards first, then the device's languages; only those, so
        // the switch goes EN, ES, FR, not every language the device has (kiff/tafs#236, item 23).
        XCTAssertEqual(Dictation.Language.owners(supported: supported, keyboards: ["es-ES", "fr-FR"], preferred: ["en-US"]).map(\.id),
                       ["es-ES", "fr-FR", "en-US"])
        XCTAssertEqual(Dictation.Language.owners(supported: ["de-DE"], keyboards: ["es-ES"], preferred: ["en-US"]).map(\.id), ["de-DE"],
                       "none of theirs on the device: every language it has")
        XCTAssertEqual(Dictation.Language(id: "fr-FR").label, "FR")
        XCTAssertEqual(Dictation.Language(id: "fr-FR").name, "Français")
        XCTAssertEqual(Dictation.Language(id: "es-MX").name, "Español")
    }

    /// Only French on the device: dictation starts in French, and the switch has nowhere to go.
    func testItDictatesInTheLanguageTheDeviceHas() async {
        let speech = FakeSpeech()
        speech.have = [Dictation.Language(id: "fr-FR")]
        let dictation = Dictation(engine: speech, language: .spanish)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        XCTAssertEqual(speech.startedWith?.language, Dictation.Language(id: "fr-FR"))
        await dictation.switchLanguage(text: box, hints: []) { box = $0 }
        XCTAssertEqual(dictation.language, Dictation.Language(id: "fr-FR"))
    }

    /// No language on the device: why, and how to turn dictation on, and nothing starts.
    func testNoLanguageSaysHowToTurnItOn() async {
        let speech = FakeSpeech()
        speech.have = []
        let dictation = Dictation(engine: speech)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        XCTAssertEqual(dictation.state, .blocked("Dictation isn't on this iPhone yet."))
        XCTAssertNil(speech.startedWith)
    }

    /// While listening, the bar draws how loud the microphone was lately, so it's plain that
    /// dictation is on; stopping clears it.
    func testTheLevelsShowWhileListening() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        await dictation.start(text: "", hints: []) { _ in }
        for i in 0..<(Dictation.levelCount + 10) { speech.sound(i == 0 ? 2 : 0.5) }
        for _ in 0..<50 where dictation.levels.count < Dictation.levelCount { await settle() }
        XCTAssertEqual(dictation.levels.count, Dictation.levelCount, "the latest only")
        XCTAssertTrue(dictation.levels.allSatisfy { $0 >= 0 && $0 <= 1 })
        dictation.stop()
        XCTAssertEqual(dictation.levels, [])
        speech.sound(0.5)
        await settle()
        XCTAssertEqual(dictation.levels, [], "nothing after it stopped")
    }

    func testLevelOfASound() {
        let silence = [Float](repeating: 0, count: 256)
        let speech = [Float](repeating: 0.1, count: 256) // -20 dB
        let loud = [Float](repeating: 1, count: 256)
        silence.withUnsafeBufferPointer { XCTAssertEqual(Dictation.level($0), 0) }
        speech.withUnsafeBufferPointer { XCTAssertEqual(Dictation.level($0), 0.75, accuracy: 0.01) }
        loud.withUnsafeBufferPointer { XCTAssertEqual(Dictation.level($0), 1) }
        [Float]().withUnsafeBufferPointer { XCTAssertEqual(Dictation.level($0), 0) }
    }

    /// Cancel puts the box back as it was before dictating: nothing heard stays, nothing is sent.
    func testCancelPutsTheBoxBack() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = "Hi"
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("refund order 4471")
        await settle()
        XCTAssertEqual(box, "Hi refund order 4471")
        dictation.cancel()
        XCTAssertEqual(box, "Hi")
        XCTAssertEqual(dictation.state, .idle)
        speech.hear("late words")
        await settle()
        XCTAssertEqual(box, "Hi")
    }

    /// Dictation goes in where the owner left the insertion point, not at the end: what was
    /// after it stays after the words heard.
    func testWordsGoInAtTheInsertionPoint() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = "Refund for Ana."
        dictation.cursor = 6
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("order 4471")
        await settle()
        XCTAssertEqual(box, "Refund order 4471 for Ana.")
        speech.hear("order 4471 today", done: true)
        await settle()
        await settle()
        XCTAssertEqual(box, "Refund order 4471 today for Ana.")
        dictation.cancel()
        XCTAssertEqual(box, "Refund for Ana.", "cancelling puts the box back")
    }

    func testTheBoxSplitsAtItsInsertionPoint() {
        XCTAssertEqual(Dictation.split("Refund Ana", at: 6).0, "Refund")
        XCTAssertEqual(Dictation.split("Refund Ana", at: 6).1, " Ana")
        XCTAssertEqual(Dictation.split("Refund", at: nil).0, "Refund", "no point: the end")
        XCTAssertEqual(Dictation.split("Refund", at: 6).1, "", "at the end")
        XCTAssertEqual(Dictation.split("Refund", at: 40).0, "Refund", "past the end: the end")
        XCTAssertEqual(Dictation.split("Refund", at: 0).1, "Refund", "at the start")
        XCTAssertEqual(Dictation.rest(""), "")
        XCTAssertEqual(Dictation.rest(" Ana"), " Ana")
        XCTAssertEqual(Dictation.rest(". Thanks"), ". Thanks")
        XCTAssertEqual(Dictation.rest("Ana"), " Ana")
    }
}
