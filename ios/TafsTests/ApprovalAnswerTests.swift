import XCTest
@testable import Tafs

/// An approval card's outcome comes from tafs cloud's settled `answer`, in no language (tafs#1283),
/// never from the words in the owner's: a declined or failed approval read "Approved" for every owner
/// who doesn't use English (17 Nov). Nothing reads approved by default. As Android's `ApprovalAnswerTest`.
@MainActor
final class ApprovalAnswerTests: XCTestCase {
    /// tafs cloud's words for each language: approved, declined, a failed approval and a connect step
    /// with its Card next (`runtime/src/cloud/words/*.ts`).
    private struct Lang { let approved: String; let declined: String; let failed: String; let connected: String }
    private let langs: [String: Lang] = [
        "en": Lang(approved: "Approved", declined: "Declined", failed: "Approve · it didn't go through: the grant expired", connected: "Stripe is connected. Next, its Card."),
        "fr": Lang(approved: "Approuvé", declined: "Refusé", failed: "Approuver · cela n'a pas abouti : the grant expired", connected: "Stripe est connecté. Ensuite, la Card."),
        "de": Lang(approved: "Genehmigt", declined: "Abgelehnt", failed: "Genehmigen · hat nicht geklappt: the grant expired", connected: "Stripe ist verbunden. Als Nächstes die Card."),
        "es": Lang(approved: "Aprobado", declined: "Rechazado", failed: "Aprobar · no se completó: the grant expired", connected: "Stripe está conectado. Ahora, la Card."),
        "it": Lang(approved: "Approvato", declined: "Rifiutato", failed: "Approva · non è andata a buon fine: the grant expired", connected: "Stripe è connesso. Ora, la Card."),
        "nl": Lang(approved: "Goedgekeurd", declined: "Geweigerd", failed: "Goedkeuren · het is niet gelukt: the grant expired", connected: "Stripe is verbonden. Nu de Card."),
        "pl": Lang(approved: "Zatwierdzone", declined: "Odrzucone", failed: "Zatwierdź · nie powiodło się: the grant expired", connected: "Stripe jest połączony. Teraz Card."),
        "pt": Lang(approved: "Aprovado", declined: "Recusado", failed: "Aprovar · não foi concluído: the grant expired", connected: "Stripe está ligado. A seguir, o Card."),
    ]

    private func card() throws -> SetupComponent {
        try SetupTests.component(#"{"type":"approval","id":"ap-stripe","grant":"g_01"}"#)
    }

    private func text(_ words: String, answer: String?) throws -> String? {
        SetupThread.answer(TafsCloud.CardAnswer(words: words, answer: answer), to: try card())?.text
    }

    /// What the card reads as done (`AppModel.approvalFinished`, the approved lines): approved or already approved.
    private func readsApproved(_ text: String?) -> Bool {
        guard let text, !text.hasPrefix("failed") else { return false }
        return text.hasPrefix("approved") || text.hasPrefix("already approved")
    }

    func testAllEightLanguagesAreChecked() {
        XCTAssertEqual(Set(langs.keys), ["en", "fr", "de", "es", "it", "nl", "pl", "pt"])
    }

    /// With the settled answer, a declined or failed approval never reads approved, in any language.
    func testADeclinedOrFailedAnswerNeverReadsApprovedInAnyLanguage() throws {
        for (lang, w) in langs {
            XCTAssertEqual(try text(w.declined, answer: "declined"), "declined", lang)
            let failed = SetupThread.answer(TafsCloud.CardAnswer(words: w.failed, answer: "failed: the grant expired"), to: try card())
            XCTAssertEqual(failed?.failed, true, lang)
            // tafs cloud's why is written for Coach: the card says only that it didn't go through.
            XCTAssertNil(failed?.why, lang)
            XCTAssertFalse(readsApproved(failed?.text), lang)
        }
    }

    /// With the settled answer, approved, already approved and connected (its Card next) read as done, in any language.
    func testApprovedAlreadyApprovedAndConnectedReadDone() throws {
        for (lang, w) in langs {
            XCTAssertEqual(try text(w.approved, answer: "approved"), "approved", lang)
            XCTAssertEqual(try text(w.connected, answer: "connected"), "approved", lang)
            XCTAssertEqual(try text("", answer: "already approved"), "already approved", lang)
        }
    }

    /// The settled answer decides, whatever the words say: words that say approved with an answer that
    /// says declined read declined.
    func testTheSettledAnswerWinsOverTheWords() throws {
        XCTAssertEqual(try text("Approved", answer: "declined"), "declined")
        XCTAssertTrue(try text("Approved", answer: "failed: x")?.hasPrefix("failed") == true)
    }

    /// An answer value this build doesn't know isn't approved.
    func testAnUnknownAnswerIsNotApproved() throws {
        for value in ["approved_once", "Approved", "yes", "ok", "approvedx"] {
            XCTAssertFalse(readsApproved(try text("Approved", answer: value)), value)
        }
    }

    /// Without the settled answer (an older tafs cloud): only tafs cloud's exact English reads approved.
    /// Declined and failed words in every other language never do.
    func testWithoutTheAnswerOnlyTheExactEnglishReadsApproved() throws {
        XCTAssertEqual(try text("Approved", answer: nil), "approved")
        XCTAssertEqual(try text("Already approved", answer: nil), "already approved")
        XCTAssertEqual(try text("Declined", answer: nil), "declined")
        for (lang, w) in langs {
            XCTAssertFalse(readsApproved(try text(w.declined, answer: nil)), lang)
            XCTAssertFalse(readsApproved(try text(w.failed, answer: nil)), lang)
            if lang != "en" { XCTAssertFalse(readsApproved(try text(w.approved, answer: nil)), "\(lang): words alone, not English") }
        }
        XCTAssertFalse(readsApproved(try text("", answer: nil)))
    }

    /// tafs cloud's `answer` is read off the wire beside the words.
    func testTheAnswerIsReadOffTheWire() throws {
        let read = try KIFFClient.decoder.decode(TafsCloud.CardAnswer.self, from: Data(#"{"state":"answered","words":"Refusé","answer":"declined"}"#.utf8))
        XCTAssertEqual(read.answer, "declined")
        XCTAssertEqual(read.words, "Refusé")
        let older = try KIFFClient.decoder.decode(TafsCloud.CardAnswer.self, from: Data(#"{"state":"answered","words":"Approved"}"#.utf8))
        XCTAssertNil(older.answer)
    }

    /// A refused approval's or sign-in's line is never tafs cloud's text for Coach: the card says only that it didn't go
    /// through (the owner's emulator pass, 21 Nov). A refusal this phone wrote itself, as a message, keeps its words.
    func testARefusalsTextForCoachNeverReachesTheOwner() throws {
        let forCoach = "Approve · it didn't go through: Show the rules again as a new notice, never end a turn saying you're waiting"
        let approval = SetupThread.answer(TafsCloud.CardAnswer(words: forCoach, answer: "failed: Show the rules again as a new notice"), to: try card())
        XCTAssertEqual(approval?.failed, true)
        XCTAssertNil(approval?.why)
        let signIn = try SetupTests.component(#"{"type":"sign_in","id":"s1","grant":"g_01"}"#)
        let refused = SetupThread.answer(TafsCloud.CardAnswer(words: "Sign in · it didn't go through: Ask the owner to sign in again", answer: nil), to: signIn)
        XCTAssertEqual(refused?.failed, true)
        XCTAssertNil(refused?.why)
        XCTAssertEqual(SetupThread.Answer("[approval c1] failed: The approval window has ended.")?.why, "The approval window has ended.")
    }
}
