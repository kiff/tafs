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
    private var onText: (@Sendable (String) -> Void)?
    private var onEnd: (@Sendable (String?) -> Void)?

    func authorize() async -> String? { refused }
    func languages() -> [Dictation.Language] { have }
    var noLanguage: String { "Dictation isn't on this iPhone yet." }
    func unavailable(_ language: Dictation.Language) -> String? { notHere }
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String) -> Void,
               onEnd: @escaping @Sendable (String?) -> Void) throws {
        startedWith = (language, hints)
        self.onText = onText
        self.onEnd = onEnd
    }
    func stop() { stops += 1 }

    func hear(_ words: String) { onText?(words) }
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

    /// The recognizer's own end (a final result, or an error) stops listening, never sends,
    /// and says why when it wasn't the owner.
    func testTheRecognizersEndStopsListening() async {
        let speech = FakeSpeech()
        let dictation = Dictation(engine: speech, language: .english)
        var box = ""
        await dictation.start(text: box, hints: []) { box = $0 }
        speech.hear("Halt the refunds")
        await settle()
        speech.end()
        await settle()
        XCTAssertEqual(dictation.state, .idle)
        XCTAssertEqual(box, "Halt the refunds")

        await dictation.start(text: box, hints: []) { box = $0 }
        speech.end("Dictation stopped. Tap the mic to start again.")
        await settle()
        XCTAssertEqual(dictation.state, .blocked("Dictation stopped. Tap the mic to start again."))
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
}
