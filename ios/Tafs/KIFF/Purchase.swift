import Foundation

/// A held purchase (Thune), as KIFF serves it on the hold: `contracts/purchase.json`, `approval.value`. Its words are
/// KIFF's, drawn as sent: the shop, the total, the lines behind Show more, the price rule, what the card allows, and why
/// it waits. Approving it signs this case's version and digest (`Binding.signedLine`), so an answer counts only for
/// the case the owner saw.
struct Purchase: Hashable, Codable {
    /// The case an answer binds: read on its own, so a hold whose words don't read can still be refused for its case.
    let binding: Binding
    let words: Words

    /// What an answer to the purchase's hold signs: the case's id, version and digest, checked before anything is signed.
    struct Binding: Hashable, Codable {
        let caseID: String
        let version: Int
        let digest: String

        enum CodingKeys: String, CodingKey { case caseID = "case_id", version, digest }

        init(caseID: String, version: Int, digest: String) {
            self.caseID = caseID
            self.version = version
            self.digest = digest
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            caseID = try c.decode(String.self, forKey: .caseID)
            version = try c.decode(Int.self, forKey: .version)
            digest = try c.decode(String.self, forKey: .digest)
            // An id or digest that could carry a colon or a new line is refused, so the line signed is always exactly
            // one `purchase:` line.
            guard Purchase.isCaseID(caseID), version >= 1, Purchase.isDigest(digest) else {
                throw DecodingError.dataCorruptedError(forKey: .digest, in: c, debugDescription: "a purchase case that can't be signed as sent")
            }
        }

        /// The line an answer to the hold ends with: `purchase:<case_id>:<version>:<digest>`. KIFF refuses an answer
        /// without it, or with a version or digest that isn't the case's current one.
        var signedLine: String { "purchase:\(caseID):\(version):\(digest)" }
    }

    /// One line the checkout lists ("A4 paper, 80 g, 5 reams", "× 2", "€59.80").
    struct Line: Hashable, Codable {
        let name: String
        var quantity: String?
        let amount: String
    }

    /// A charge beside the lines (shipping, tax), so the open list adds up to the total.
    struct Charge: Hashable, Codable {
        let name: String
        let amount: String
    }

    /// KIFF's words for the approval, every one as sent, direction characters taken out.
    struct Words: Hashable, Codable {
        let title: String
        var subtitle: String?
        let total: String
        var totalNote: String?
        /// Where it's delivered: signed into the case, so approved with it, and always shown.
        let delivery: String
        var showMore: String?
        var showLess: String?
        var lines: [Line] = []
        var charges: [Charge] = []
        var priceRule: String?
        var grant: String?
        var buysNow: String?
        var nothingUntil: String?
        var reason: String?
        /// What the approval prompt names: the shop and the total.
        let prompt: String

        enum CodingKeys: String, CodingKey {
            case title, subtitle, total, totalNote = "total_note", delivery, showMore = "show_more", showLess = "show_less"
            case lines, charges, priceRule = "price_rule", grant, buysNow = "buys_now", nothingUntil = "nothing_until", reason, prompt
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            func text(_ key: CodingKeys) -> String? {
                guard let value = (try? c.decodeIfPresent(String.self, forKey: key)) ?? nil else { return nil }
                let shown = KIFFMapping.visible(value)
                return shown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : shown
            }
            // The shop, the total, where it's delivered and the prompt are what's approved: without any of them there is
            // nothing complete to show or name, so nothing to sign.
            guard let title = text(.title), let total = text(.total), let delivery = text(.delivery), let prompt = text(.prompt) else {
                throw DecodingError.dataCorruptedError(forKey: .title, in: c, debugDescription: "a purchase with no shop or total")
            }
            self.title = title
            self.total = total
            self.delivery = delivery
            self.prompt = prompt
            subtitle = text(.subtitle)
            totalNote = text(.totalNote)
            showMore = text(.showMore)
            showLess = text(.showLess)
            priceRule = text(.priceRule)
            grant = text(.grant)
            buysNow = text(.buysNow)
            nothingUntil = text(.nothingUntil)
            reason = text(.reason)
            // A list that doesn't read is refused whole: the open list must be every line the case signs, never part of it.
            lines = try (c.decodeIfPresent([Line].self, forKey: .lines) ?? []).map {
                Line(name: KIFFMapping.visible($0.name), quantity: $0.quantity.map(KIFFMapping.visible), amount: KIFFMapping.visible($0.amount))
            }
            charges = try (c.decodeIfPresent([Charge].self, forKey: .charges) ?? []).map {
                Charge(name: KIFFMapping.visible($0.name), amount: KIFFMapping.visible($0.amount))
            }
        }
    }

    enum CodingKeys: String, CodingKey { case caseID = "case_id", version, digest, words }

    init(binding: Binding, words: Words) {
        self.binding = binding
        self.words = words
    }

    init(from decoder: Decoder) throws {
        binding = try Binding(from: decoder)
        words = try decoder.container(keyedBy: CodingKeys.self).decode(Words.self, forKey: .words)
    }

    /// Kept as the hold sent it (the case at the top, its words beside), so a saved copy reads back the same way.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(binding.caseID, forKey: .caseID)
        try c.encode(binding.version, forKey: .version)
        try c.encode(binding.digest, forKey: .digest)
        try c.encode(words, forKey: .words)
    }

    /// 1 to 128 of `[A-Za-z0-9_-]`, as planque's `issue_card` and the other apps take a case id.
    static func isCaseID(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 128 && s.unicodeScalars.allSatisfy {
            ("a"..."z").contains($0) || ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "_" || $0 == "-"
        }
    }

    static func isDigest(_ s: String) -> Bool {
        s.count == 64 && s.unicodeScalars.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
}

/// A hold's `purchase` block, read without ever failing the hold: the whole purchase when its words read, and its case
/// on its own, so a purchase whose words don't read can still be refused for its case, and never approved.
struct PurchaseBlock: Decodable, Equatable {
    var purchase: Purchase?
    var binding: Purchase.Binding?

    init(purchase: Purchase? = nil, binding: Purchase.Binding? = nil) {
        self.purchase = purchase
        self.binding = binding
    }

    init(from decoder: Decoder) throws {
        purchase = try? Purchase(from: decoder)
        binding = purchase?.binding ?? (try? Purchase.Binding(from: decoder))
    }
}
