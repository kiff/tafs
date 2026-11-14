import Foundation

/// "Coach waiting" (docs/api.md › Coach waiting): a Coach chat paused on a card its person answers (an
/// approval, a sign-in, questions, a confirm). Home lists each once, oldest first, and counts it in Needs
/// you, so Home never says nothing needs the owner while Coach waits. Every word is tafs cloud's.
extension TafsCloud {
    struct CoachWaiting: Decodable, Equatable, Identifiable {
        struct Words: Decodable, Equatable {
            /// What Coach waits for: "Coach is waiting for your approval."
            var line = ""
            /// The way in: "Open Coach".
            var open = ""
            init(line: String = "", open: String = "") { self.line = line; self.open = open }
            enum CodingKeys: String, CodingKey { case line, open }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                line = ((try? c.decodeIfPresent(String.self, forKey: .line)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                open = ((try? c.decodeIfPresent(String.self, forKey: .open)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            }
        }
        /// The taf the chat is on, and the chat (`session`) to open.
        var taf = ""
        var session = ""
        var run = ""
        var since = ""
        var words = Words()
        var id: String { session }
        /// Listed only with its chat and both of tafs cloud's lines.
        var usable: Bool { !session.isEmpty && !words.line.isEmpty && !words.open.isEmpty }

        init(taf: String = "", session: String = "", run: String = "", since: String = "", words: Words = Words()) {
            self.taf = taf; self.session = session; self.run = run; self.since = since; self.words = words
        }
        enum CodingKeys: String, CodingKey { case taf, session, run, since, words }
        /// Read leniently: one the app can't read is left out, never Home with it.
        init(from decoder: Decoder) throws {
            guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
            taf = ((try? c.decodeIfPresent(String.self, forKey: .taf)) ?? nil) ?? ""
            session = ((try? c.decodeIfPresent(String.self, forKey: .session)) ?? nil) ?? ""
            run = ((try? c.decodeIfPresent(String.self, forKey: .run)) ?? nil) ?? ""
            since = ((try? c.decodeIfPresent(String.self, forKey: .since)) ?? nil) ?? ""
            words = ((try? c.decodeIfPresent(Words.self, forKey: .words)) ?? nil) ?? Words()
        }
    }
}
