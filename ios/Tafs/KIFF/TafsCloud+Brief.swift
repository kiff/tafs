import Foundation

/// "Your brief" (Missions design v2.1 §7): what tafs got done and what needs the person, twice a day,
/// from `GET /v1/brief?label=`. Every word, count, order and urgency is tafs cloud's; the app draws it
/// and decides nothing. Read leniently: a part the app can't read is left out, never the brief with it.
extension TafsCloud {
    struct Brief: Decodable, Equatable {
        /// The headings, in the person's language ("Your brief", "Morning brief · 08:00 · next at 18:00",
        /// "Decisions · 2", "Done since last evening", "Next move", "Coach recommends", "Meanwhile:", "Evidence").
        struct Words: Decodable, Equatable {
            var tab = "", title = "", when = "", decisions = "", done = "", nextMove = "", recommends = "",
                meanwhile = "", evidence = "", all = "", urgent = ""
            enum CodingKeys: String, CodingKey {
                case tab, title, when, decisions, done, recommends, meanwhile, evidence, all, urgent
                case nextMove = "next_move"
            }
            init() {}
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                func text(_ k: CodingKeys) -> String { Brief.text(c, k) ?? "" }
                tab = text(.tab); title = text(.title); when = text(.when); decisions = text(.decisions); done = text(.done)
                nextMove = text(.nextMove); recommends = text(.recommends); meanwhile = text(.meanwhile)
                evidence = text(.evidence); all = text(.all); urgent = text(.urgent)
            }
        }

        /// What a decision is about, opened as the app opens each kind today: a held call, a step, a
        /// connection, or a proposal (Coach's existing signed change sheet). Nothing new is signed.
        struct Ref: Decodable, Equatable {
            var kind = "", id = "", taf = ""
            enum CodingKeys: String, CodingKey { case kind, id, taf }
            init(kind: String = "", id: String = "", taf: String = "") { self.kind = kind; self.id = id; self.taf = taf }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                kind = Brief.text(c, .kind) ?? ""; id = Brief.text(c, .id) ?? ""; taf = Brief.text(c, .taf) ?? ""
            }
        }

        struct Answer: Decodable, Equatable, Identifiable {
            var words = "", answer = ""
            /// At most one, Coach's pick; with no `why`, the answers show as equals.
            var recommended = false
            /// A decline ("Don't send", "Not now"): drawn as one, never read as a no by the app.
            var decline = false
            var id: String { answer + "|" + words }
            enum CodingKeys: String, CodingKey { case words, answer, recommended, decline }
            init(words: String = "", answer: String = "", recommended: Bool = false, decline: Bool = false) {
                self.words = words; self.answer = answer; self.recommended = recommended; self.decline = decline
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                words = Brief.text(c, .words) ?? ""; answer = Brief.text(c, .answer) ?? ""
                recommended = ((try? c.decodeIfPresent(Bool.self, forKey: .recommended)) ?? nil) ?? false
                decline = ((try? c.decodeIfPresent(Bool.self, forKey: .decline)) ?? nil) ?? false
            }
        }

        struct Decision: Decodable, Equatable, Identifiable {
            var id = "", question = ""
            var answers: [Answer] = []
            /// Why Coach recommends its answer; none, and no answer is drawn as recommended.
            var why: String?
            var meanwhile = ""
            /// When it must be decided by, in words ("by Fri 12:00").
            var deadline: String?
            var urgent = false
            /// "Later" moves it to the next brief; left out when it can't be put off.
            var later: String?
            /// Why it can't wait, when `later` is left out ("This can't wait: it expires at 12:00…").
            var cantWait: String?
            /// A line under the answers ("Pay the 3 opens three approvals, one after the other.").
            var note: String?
            var ref = Ref()
            enum CodingKeys: String, CodingKey {
                case id, question, answers, why, meanwhile, deadline, urgent, later, note, ref
                case cantWait = "cant_wait"
            }
            private struct LaterWords: Decodable { var words: String? }
            init() {}
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                id = Brief.text(c, .id) ?? ""; question = Brief.text(c, .question) ?? ""
                answers = (Brief.list(c, .answers) as [Answer]).filter { !$0.words.isEmpty }
                why = Brief.text(c, .why); meanwhile = Brief.text(c, .meanwhile) ?? ""; deadline = Brief.text(c, .deadline)
                urgent = ((try? c.decodeIfPresent(Bool.self, forKey: .urgent)) ?? nil) ?? false
                later = ((try? c.decodeIfPresent(LaterWords.self, forKey: .later)) ?? nil)?.words.flatMap { $0.isEmpty ? nil : $0 }
                cantWait = Brief.text(c, .cantWait); note = Brief.text(c, .note)
                ref = ((try? c.decodeIfPresent(Ref.self, forKey: .ref)) ?? nil) ?? Ref()
            }
            /// Coach's pick, drawn first and filled; only with its why.
            var recommended: Answer? { why == nil ? nil : answers.first { $0.recommended } }
            /// One the app can draw: a question and at least one answer.
            var usable: Bool { !id.isEmpty && !question.isEmpty && !answers.isEmpty }
        }

        /// One item a job works on (an invoice, a ticket), in tafs cloud's line: what was done, what it
        /// waits on, when it's next checked. Closed only when the source says so.
        struct Case: Decodable, Equatable, Identifiable {
            var name = "", words = "", state = ""
            var nextCheck: String?
            var id: String { name + "|" + words }
            enum CodingKeys: String, CodingKey { case name, words, state; case nextCheck = "next_check" }
            init(name: String = "", words: String = "", state: String = "", nextCheck: String? = nil) {
                self.name = name; self.words = words; self.state = state; self.nextCheck = nextCheck
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                name = Brief.text(c, .name) ?? ""; words = Brief.text(c, .words) ?? ""; state = Brief.text(c, .state) ?? ""
                nextCheck = Brief.text(c, .nextCheck)
            }
            var closed: Bool { state == "closed" }
            var waiting: Bool { state == "waiting" }
        }

        struct Done: Decodable, Equatable, Identifiable {
            var mission: String?
            var cases: [Case] = []
            /// What it's worth, in words ("€3,400 due back").
            var value: String?
            /// The mission whose log holds the evidence.
            var log: String?
            var id: String { (mission ?? "") + "|" + cases.map(\.id).joined() }
            enum CodingKeys: String, CodingKey { case mission, cases, value, log }
            init(mission: String? = nil, cases: [Case] = [], value: String? = nil, log: String? = nil) {
                self.mission = mission; self.cases = cases; self.value = value; self.log = log
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                mission = Brief.text(c, .mission); cases = (Brief.list(c, .cases) as [Case]).filter { !$0.words.isEmpty || !$0.name.isEmpty }
                value = Brief.text(c, .value); log = Brief.text(c, .log)
            }
        }

        struct NextMove: Decodable, Equatable {
            var words = "", start = "", dismiss = ""
            var cases: [String] = []
            enum CodingKeys: String, CodingKey { case words, start, dismiss, cases }
            init(words: String = "", start: String = "", dismiss: String = "", cases: [String] = []) {
                self.words = words; self.start = start; self.dismiss = dismiss; self.cases = cases
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                words = Brief.text(c, .words) ?? ""; start = Brief.text(c, .start) ?? ""; dismiss = Brief.text(c, .dismiss) ?? ""
                cases = ((try? c.decodeIfPresent([String].self, forKey: .cases)) ?? nil) ?? []
            }
            var usable: Bool { !words.isEmpty && !start.isEmpty && !dismiss.isEmpty }
        }

        /// A count and its words: "more" decisions, the "rest" that need nothing now.
        struct Counted: Decodable, Equatable {
            var count = 0, words = ""
            enum CodingKeys: String, CodingKey { case count, words }
            init(count: Int = 0, words: String = "") { self.count = count; self.words = words }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                count = ((try? c.decodeIfPresent(Int.self, forKey: .count)) ?? nil) ?? 0; words = Brief.text(c, .words) ?? ""
            }
        }

        /// A label chip, as Home (`labels`, docs/labels.md) and the brief send it: its name in the person's
        /// language, its colour as one of Explore's category tokens, how many listed tafs carry it, how many
        /// of those wait for the person (the amber dot above 0), and Home's line filtered to it.
        struct Label: Decodable, Equatable, Identifiable {
            var id = "", name = "", color = ""
            var count = 0, waiting = 0
            var headline: String?
            enum CodingKeys: String, CodingKey { case id, name, color, count, waiting, headline }
            private enum HeadlineKeys: String, CodingKey { case words }
            init(id: String = "", name: String = "", color: String = "", count: Int = 0, waiting: Int = 0, headline: String? = nil) {
                self.id = id; self.name = name; self.color = color; self.count = count; self.waiting = waiting; self.headline = headline
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                id = Brief.text(c, .id) ?? ""; name = Brief.text(c, .name) ?? ""; color = Brief.text(c, .color) ?? ""
                count = ((try? c.decodeIfPresent(Int.self, forKey: .count)) ?? nil) ?? 0
                // A count; a yes/no from an earlier draft reads as one waiting.
                waiting = ((try? c.decodeIfPresent(Int.self, forKey: .waiting)) ?? nil)
                    ?? (((try? c.decodeIfPresent(Bool.self, forKey: .waiting)) ?? nil) == true ? 1 : 0)
                if let h = try? c.nestedContainer(keyedBy: HeadlineKeys.self, forKey: .headline) {
                    headline = ((try? h.decodeIfPresent(String.self, forKey: .words)) ?? nil)
                        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
                }
            }
            /// One the app can draw and filter by.
            var usable: Bool { !id.isEmpty && !name.isEmpty }
        }

        var at: String?
        var nextAt: String?
        var decisions: [Decision] = []
        var more: Counted?
        var done: [Done] = []
        var nextMove: NextMove?
        var rest: Counted?
        var credits: String?
        var labels: [Label] = []
        var words = Words()

        enum CodingKeys: String, CodingKey {
            case at, decisions, more, done, rest, credits, labels, words
            case nextAt = "next_at", nextMove = "next_move"
        }
        private struct CreditWords: Decodable { var words: String? }

        init() {}
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            at = Self.text(c, .at); nextAt = Self.text(c, .nextAt)
            decisions = (Self.list(c, .decisions) as [Decision]).filter(\.usable)
            more = ((try? c.decodeIfPresent(Counted.self, forKey: .more)) ?? nil).flatMap { $0.count > 0 && !$0.words.isEmpty ? $0 : nil }
            done = (Self.list(c, .done) as [Done]).filter { !$0.cases.isEmpty }
            nextMove = ((try? c.decodeIfPresent(NextMove.self, forKey: .nextMove)) ?? nil).flatMap { $0.usable ? $0 : nil }
            rest = ((try? c.decodeIfPresent(Counted.self, forKey: .rest)) ?? nil).flatMap { $0.words.isEmpty ? nil : $0 }
            credits = ((try? c.decodeIfPresent(CreditWords.self, forKey: .credits)) ?? nil)?.words.flatMap { $0.isEmpty ? nil : $0 }
            labels = (Self.list(c, .labels) as [Label]).filter(\.usable)
            words = ((try? c.decodeIfPresent(Words.self, forKey: .words)) ?? nil) ?? Words()
        }

        /// A line of text, trimmed; none when absent, blank or of another shape.
        fileprivate static func text<K: CodingKey>(_ c: KeyedDecodingContainer<K>, _ key: K) -> String? {
            guard let s = (try? c.decodeIfPresent(String.self, forKey: key)) ?? nil else { return nil }
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }

        /// A list whose items are read one by one: one the app can't read is left out, never the list.
        fileprivate static func list<K: CodingKey, T: Decodable>(_ c: KeyedDecodingContainer<K>, _ key: K) -> [T] {
            (((try? c.decodeIfPresent([LenientItem<T>].self, forKey: key)) ?? nil) ?? []).compactMap(\.value)
        }
    }

    /// Home's labels (`labels`, docs/labels.md), in the order to show them: read one by one, so a label
    /// the app can't read is left out, and another shape is none, never Home unread.
    struct LabelList: Decodable, Equatable {
        var items: [Brief.Label] = []
        init(_ items: [Brief.Label] = []) { self.items = items }
        init(from decoder: Decoder) throws {
            items = ((try? [LenientItem<Brief.Label>](from: decoder)) ?? []).compactMap(\.value).filter(\.usable)
        }
    }

    /// A taf's labels on Home, by id; another shape reads as none.
    struct LabelIDs: Decodable, Equatable {
        var ids: [String] = []
        init(from decoder: Decoder) throws {
            ids = ((try? [JSONValue](from: decoder)) ?? []).compactMap { value -> String? in
                if case .string(let s) = value, !s.isEmpty { return s }
                return nil
            }
        }
    }

    /// Home's words for the labels' first chip (`labels_words.all`); nil when it sent none.
    struct LabelsWords: Decodable, Equatable {
        var all: String?
        enum CodingKeys: String, CodingKey { case all }
        init(from decoder: Decoder) throws {
            guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
            all = Brief.text(c, .all)
        }
    }

    /// A mission's log (design v2.1 §6): read only, newest first. Each entry says when, by whom (a taf,
    /// the lead Coach, or the person), its kind and one or two sentences, with evidence and a value
    /// when there is one. A removed taf's entries stay, marked removed.
    struct MissionLog: Decodable, Equatable {
        struct Evidence: Decodable, Equatable, Identifiable {
            var kind = "", id = "", words = ""
            enum CodingKeys: String, CodingKey { case kind, id, words }
            init(kind: String = "", id: String = "", words: String = "") { self.kind = kind; self.id = id; self.words = words }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                kind = Brief.text(c, .kind) ?? ""; id = Brief.text(c, .id) ?? ""; words = Brief.text(c, .words) ?? ""
            }
        }
        struct Item: Decodable, Equatable, Identifiable {
            var at = "", by = "", kind = "", words = ""
            /// The kind in the person's words ("delivered", "handed", "stuck", "decided", "note").
            var kindWords = ""
            /// Who handed it on to whom, when it was handed ("Bills in → Payouts").
            var byWords = ""
            var evidence: [Evidence] = []
            var value: String?
            var removed = false
            var id: String { at + "|" + by + "|" + words }
            enum CodingKeys: String, CodingKey {
                case at, by, kind, words, evidence, value, removed
                case kindWords = "kind_words", byWords = "by_words"
            }
            init(at: String = "", by: String = "", kind: String = "", words: String = "", kindWords: String = "", byWords: String = "",
                 evidence: [Evidence] = [], value: String? = nil, removed: Bool = false) {
                self.at = at; self.by = by; self.kind = kind; self.words = words; self.kindWords = kindWords; self.byWords = byWords
                self.evidence = evidence; self.value = value; self.removed = removed
            }
            init(from decoder: Decoder) throws {
                guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
                at = Brief.text(c, .at) ?? ""; by = Brief.text(c, .by) ?? ""; kind = Brief.text(c, .kind) ?? ""; words = Brief.text(c, .words) ?? ""
                kindWords = Brief.text(c, .kindWords) ?? ""; byWords = Brief.text(c, .byWords) ?? ""
                evidence = (Brief.list(c, .evidence) as [Evidence]).filter { !$0.words.isEmpty }
                value = Brief.text(c, .value)
                removed = ((try? c.decodeIfPresent(Bool.self, forKey: .removed)) ?? nil) ?? false
            }
        }
        var items: [Item] = []
        var before: String?
        enum CodingKeys: String, CodingKey { case items, before }
        init(items: [Item] = [], before: String? = nil) { self.items = items; self.before = before }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            items = (Brief.list(c, .items) as [Item]).filter { !$0.words.isEmpty }
            before = Brief.text(c, .before)
        }
    }

    func brief(label: String?) async throws -> Brief {
        try await send("GET", "/v1/brief" + (label.map { "?label=" + ($0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0) } ?? ""))
    }

    func missionLog(_ mission: String, before: String? = nil) async throws -> MissionLog {
        let id = mission.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? mission
        return try await send("GET", "/v1/missions/\(id)/log" + (before.map { "?before=" + ($0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0) } ?? ""))
    }
}
