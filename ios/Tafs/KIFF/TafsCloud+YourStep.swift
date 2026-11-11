import Foundation

/// "Your step" (docs/api.md › Your step): a step a taf may never take itself, such as approving a
/// bank transfer, which it prepared and handed to its owner. The owner answers "Done" or "Skip it"
/// to KIFF, signed with this phone's device key (`KIFFClient.handoffMessage`); tafs cloud only shows it.
extension TafsCloud {
    struct YourStep: Decodable, Equatable, Identifiable {
        struct Taf: Decodable, Equatable { let id: String; var name = "" }
        /// Every word is tafs cloud's, in the person's language; `open` comes only with a vouched link.
        struct Words: Decodable, Equatable {
            var title = ""
            var done = ""
            var skip = ""
            var open: String?
            var line = ""

            init(title: String = "", done: String = "", skip: String = "", open: String? = nil, line: String = "") {
                self.title = title; self.done = done; self.skip = skip; self.open = open; self.line = line
            }
            enum CodingKeys: String, CodingKey { case title, done, skip, open, line }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
                done = (try? c.decodeIfPresent(String.self, forKey: .done)) ?? ""
                skip = (try? c.decodeIfPresent(String.self, forKey: .skip)) ?? ""
                open = try? c.decodeIfPresent(String.self, forKey: .open)
                line = (try? c.decodeIfPresent(String.self, forKey: .line)) ?? ""
            }
        }

        /// KIFF's hand-off id: what the answer names and signs.
        let handoff: String
        var run = ""
        /// Home's steps name their taf; a taf's request view, already on its taf, doesn't.
        var taf: Taf?
        var what = ""
        var why = ""
        var link: String?
        var linkHost: String?
        var since = ""
        var expires = ""
        var words = Words()
        var id: String { handoff }

        init(handoff: String, run: String = "", taf: Taf? = nil, what: String = "", why: String = "", link: String? = nil,
             linkHost: String? = nil, since: String = "", expires: String = "", words: Words = Words()) {
            self.handoff = handoff; self.run = run; self.taf = taf; self.what = what; self.why = why
            self.link = link; self.linkHost = linkHost; self.since = since; self.expires = expires; self.words = words
        }

        enum CodingKeys: String, CodingKey { case handoff, run, taf, what, why, link, since, expires, words, linkHost = "link_host" }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            handoff = try c.decode(String.self, forKey: .handoff)
            run = (try? c.decodeIfPresent(String.self, forKey: .run)) ?? ""
            taf = try? c.decodeIfPresent(Taf.self, forKey: .taf)
            what = (try? c.decodeIfPresent(String.self, forKey: .what)) ?? ""
            why = (try? c.decodeIfPresent(String.self, forKey: .why)) ?? ""
            link = try? c.decodeIfPresent(String.self, forKey: .link)
            linkHost = try? c.decodeIfPresent(String.self, forKey: .linkHost)
            since = (try? c.decodeIfPresent(String.self, forKey: .since)) ?? ""
            expires = (try? c.decodeIfPresent(String.self, forKey: .expires)) ?? ""
            words = (try? c.decodeIfPresent(Words.self, forKey: .words)) ?? Words()
        }

        /// The vendor's page, only as tafs cloud vouched for it: an https link with its host apart
        /// and the button's words. The host is tafs cloud's, never parsed here, and must be the
        /// link's own, so the line under the button names where it really goes. Anything else is
        /// no button, only the words.
        var openLink: (url: URL, host: String, words: String)? {
            guard let link, let host = linkHost, !host.isEmpty, let words = words.open, !words.isEmpty,
                  let url = URL(string: link), url.scheme?.lowercased() == "https",
                  url.host?.lowercased() == host.lowercased() else { return nil }
            return (url, host, words)
        }
    }
}
