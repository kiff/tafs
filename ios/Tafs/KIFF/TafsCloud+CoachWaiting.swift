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
            /// The swipe's words, and its button's: "Set aside". None, no swipe and no button.
            var setAside = ""
            init(line: String = "", open: String = "", setAside: String = "") { self.line = line; self.open = open; self.setAside = setAside }
            enum CodingKeys: String, CodingKey { case line, open, setAside = "set_aside" }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                line = ((try? c.decodeIfPresent(String.self, forKey: .line)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                open = ((try? c.decodeIfPresent(String.self, forKey: .open)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                setAside = ((try? c.decodeIfPresent(String.self, forKey: .setAside)) ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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

    /// Home's "Set all aside" (`coach_waiting_clear`), sent for two or more waiting chats: the
    /// button, and the one ask before it, whose title names the count it ends ("End 5 waiting
    /// Coach chats?"). Every word is tafs cloud's.
    struct CoachWaitingClear: Decodable, Equatable {
        struct Words: Decodable, Equatable {
            var button = ""
            var title = ""
            var text = ""
            var yes = ""
            var no = ""
            init(button: String = "", title: String = "", text: String = "", yes: String = "", no: String = "") {
                self.button = button; self.title = title; self.text = text; self.yes = yes; self.no = no
            }
            enum CodingKeys: String, CodingKey { case button, title, text, yes, no }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                func w(_ k: CodingKeys) -> String { (((try? c.decodeIfPresent(String.self, forKey: k)) ?? nil) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
                button = w(.button); title = w(.title); text = w(.text); yes = w(.yes); no = w(.no)
            }
        }
        var words = Words()
        init(words: Words = Words()) { self.words = words }
        enum CodingKeys: String, CodingKey { case words }
        /// Read leniently: one the app can't read is none, never Home with it.
        init(from decoder: Decoder) throws {
            guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
            words = ((try? c.decodeIfPresent(Words.self, forKey: .words)) ?? nil) ?? Words()
        }
        /// One the app can draw: its button, the ask's title, and its yes.
        var usable: Bool { !words.button.isEmpty && !words.title.isEmpty && !words.yes.isEmpty }
    }

    /// What setting Coach chats aside did: the chats it ended, and the connections it withdrew;
    /// `already` when the same chat was set aside before (a POST sent again).
    struct SetAside: Decodable, Equatable {
        var setAside: [String] = []
        var withdrawn = 0
        var already = false
        enum CodingKeys: String, CodingKey { case setAside = "set_aside", withdrawn, already }
        init(setAside: [String] = [], withdrawn: Int = 0, already: Bool = false) {
            self.setAside = setAside; self.withdrawn = withdrawn; self.already = already
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            setAside = ((try? c.decodeIfPresent([String].self, forKey: .setAside)) ?? nil) ?? []
            withdrawn = ((try? c.decodeIfPresent(Int.self, forKey: .withdrawn)) ?? nil) ?? 0
            already = ((try? c.decodeIfPresent(Bool.self, forKey: .already)) ?? nil) ?? false
        }
    }

    /// Where one waiting chat is set aside, or every one with no `session`.
    func setAsidePath(_ session: String?) -> String {
        "/v1/home/coach-waiting/" + (session.map { escaped($0) + "/" } ?? "") + "set-aside"
    }

    /// Sets one waiting Coach chat aside, or every one with no `session`: "not now", answered by
    /// tafs cloud, never an approval. Nothing is signed or sent. The signed read of this phone's
    /// connections (`proof`) lets tafs cloud withdraw a connection the chat asked for.
    func setAsideCoach(_ session: String?, proof: String?) async throws -> SetAside {
        try await send("POST", setAsidePath(session), headers: proof.map { ["Planque-Device-Signature": $0] } ?? [:])
    }
}
