import Foundation

/// The Record across the person's tafs (`GET /v1/record`, docs/api.md): search it, open one
/// case, export receipts. tafs cloud finds, words and decides; the app sends what's typed or tapped.
extension TafsCloud {
    /// One page of a search, newest first: each entry a taf's Activity entry with its taf and
    /// `about`. The search box's words (`hint`), "nothing matched" (`none_words`) and the end's
    /// words are tafs cloud's.
    struct RecordSearchPage: Decodable {
        var q = ""
        var hint = ""
        var entries: [RecordHit] = []
        var groups: [RecordHitGroup] = []
        var next_cursor: String?
        var end_words: String?
        var none_words: String?

        init(q: String = "", hint: String = "", entries: [RecordHit] = [], groups: [RecordHitGroup] = [], next_cursor: String? = nil,
             end_words: String? = nil, none_words: String? = nil) {
            self.q = q; self.hint = hint; self.entries = entries; self.groups = groups
            self.next_cursor = next_cursor; self.end_words = end_words; self.none_words = none_words
        }

        enum CodingKeys: String, CodingKey { case q, hint, entries, groups, next_cursor, end_words, none_words }
        /// What's missing is none: an older tafs cloud, or a page with no groups.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            q = (try? c.decodeIfPresent(String.self, forKey: .q)) ?? ""
            hint = (try? c.decodeIfPresent(String.self, forKey: .hint)) ?? ""
            entries = try c.decodeIfPresent([RecordHit].self, forKey: .entries) ?? []
            groups = try c.decodeIfPresent([RecordHitGroup].self, forKey: .groups) ?? []
            next_cursor = try? c.decodeIfPresent(String.self, forKey: .next_cursor)
            end_words = try? c.decodeIfPresent(String.self, forKey: .end_words)
            none_words = try? c.decodeIfPresent(String.self, forKey: .none_words)
        }

        /// tafs cloud's groups, or every entry as one when it sent none.
        var shownGroups: [RecordHitGroup] {
            groups.isEmpty && !entries.isEmpty ? [RecordHitGroup(id: "all", title: "", entries: entries)] : groups
        }
        var isEmpty: Bool { entries.isEmpty && groups.allSatisfy { $0.entries.isEmpty } }
    }

    struct RecordHitGroup: Decodable, Identifiable {
        let id: String
        var title = ""
        var entries: [RecordHit] = []

        init(id: String, title: String, entries: [RecordHit]) { self.id = id; self.title = title; self.entries = entries }
        enum CodingKeys: String, CodingKey { case id, title, entries }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
            entries = try c.decodeIfPresent([RecordHit].self, forKey: .entries) ?? []
        }
    }

    /// A call found in the Record: a taf's Activity entry (`entry`, drawn as its taf's Activity
    /// draws it), with which taf made it and what it's about.
    struct RecordHit: Decodable, Identifiable {
        struct Taf: Decodable, Equatable { let id: String; var name = "" }
        let entry: ActivityEntry
        let taf: Taf
        var about: String?
        var id: String { entry.id }

        enum CodingKeys: String, CodingKey { case taf, about }
        enum TafKeys: String, CodingKey { case id, name }
        init(from decoder: Decoder) throws {
            entry = try ActivityEntry(from: decoder)
            let c = try decoder.container(keyedBy: CodingKeys.self)
            let t = try c.nestedContainer(keyedBy: TafKeys.self, forKey: .taf)
            taf = Taf(id: try t.decode(String.self, forKey: .id), name: (try? t.decodeIfPresent(String.self, forKey: .name)) ?? "")
            let words = try? c.decodeIfPresent(String.self, forKey: .about)
            about = words?.trimmingCharacters(in: .whitespaces).isEmpty == false ? words : nil
        }
    }

    /// One case (`GET /v1/record/cases/{about}`): every call about the same thing, oldest
    /// first, each with its receipt; `failed` names the latest step that didn't go through, in
    /// tafs cloud's words, with its own next step, the only retry.
    struct RecordCase: Decodable {
        struct Failed: Decodable { let id: String; var words: String?; var next: NextStep? }
        let about: String
        var steps: [RecordHit] = []
        var failed: Failed?

        init(about: String, steps: [RecordHit] = [], failed: Failed? = nil) { self.about = about; self.steps = steps; self.failed = failed }
        enum CodingKeys: String, CodingKey { case about, steps, failed }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            about = try c.decode(String.self, forKey: .about)
            steps = try c.decodeIfPresent([RecordHit].self, forKey: .steps) ?? []
            failed = try? c.decodeIfPresent(Failed.self, forKey: .failed)
        }
    }

    /// The receipts of some days for an accountant (`GET /v1/record/receipts`): `json` is tafs
    /// cloud's answer byte for byte, so KIFF's receipts are exactly as issued, never re-encoded;
    /// `csv` is as tafs cloud wrote it. `missing` counts calls whose receipt KIFF couldn't give.
    struct ReceiptsExport: Equatable {
        let from: String
        let to: String
        let json: Data
        let csv: String
        let missing: Int
    }

    /// The Record searched by `q` (an order, invoice, amount or action); `cursor` continues the same search.
    func searchRecord(_ q: String, cursor: String? = nil, limit: Int? = nil) async throws -> RecordSearchPage {
        let encode = { (s: String) in s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }
        var query = ["q=\(encode(q))"]
        if let cursor { query.append("cursor=\(encode(cursor))") }
        if let limit { query.append("limit=\(limit)") }
        return try await send("GET", "/v1/record?" + query.joined(separator: "&"))
    }

    /// One case: every call about `about`, oldest first. A 404 says no call of the person's is about it.
    func recordCase(_ about: String) async throws -> RecordCase {
        try await send("GET", "/v1/record/cases/\(escaped(about))")
    }

    /// The receipts of `from` to `to` (YYYY-MM-DD, at most 92 days), for someone who sees every taf's Record.
    func exportReceipts(from: String, to: String) async throws -> ReceiptsExport {
        let raw = try await sendData("GET", "/v1/record/receipts?from=\(from)&to=\(to)", body: Optional<[String: String]>.none)
        let body = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] ?? [:]
        return ReceiptsExport(from: body["from"] as? String ?? from, to: body["to"] as? String ?? to, json: raw,
                              csv: body["csv"] as? String ?? "", missing: (body["missing"] as? NSNumber)?.intValue ?? 0)
    }
}
