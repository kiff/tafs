"""The checks over the shared words (README.md): run with
python3 -m unittest discover -s app/strings"""
import json
import os
import re
import tempfile
import unittest

import generate

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

# The plural forms each language's whole numbers need (CLDR), beside "other".
NEEDS = {"en": {"one"}, "de": {"one"}, "nl": {"one"}, "it": {"one"}, "es": {"one"}, "pt": {"one"}, "fr": {"one"},
         "pl": {"one", "few", "many"}}

# Lines that read the same in English and another language, on purpose: by language, the keys.
SAME_AS_ENGLISH = {}


def glossary():
    """The glossary's Terms table (docs/languages.md), read as it is: English term to its word in
    each language. The doc stays the one source."""
    with open(os.path.join(REPO, "docs", "languages.md"), encoding="utf-8") as f:
        md = f.read()
    start = md.index("\n## Terms")
    end = md.find("\n## ", start + 1)
    rows = [l for l in md[start:end if end > 0 else len(md)].split("\n") if l.startswith("|")]
    head = [c.strip() for c in rows[0].split("|")[1:-1]]
    assert head == ["English", "German", "French", "Spanish", "Italian", "Portuguese", "Dutch", "Polish"], head
    terms = {}
    for row in rows[2:]:
        cells = [c.strip() for c in row.split("|")[1:-1]]
        english = re.sub(r"\s*\(.*\)$", "", cells[0])
        terms[english] = dict(zip(["en", "de", "fr", "es", "it", "pt", "nl", "pl"], [english] + cells[1:]))
    return terms


class SharedWords(unittest.TestCase):
    def setUp(self):
        self.langs = generate.languages()
        self.source = generate.load("en")
        self.lines = {lang: generate.load(lang) for lang in self.langs["all"]}

    def test_the_languages_are_the_eight(self):
        self.assertEqual(self.langs["all"], ["en", "de", "fr", "es", "it", "pt", "nl", "pl"])
        self.assertIn("en", self.langs["shipped"])
        self.assertTrue(set(self.langs["shipped"]) <= set(self.langs["all"]))

    def test_every_key_is_named_by_area_and_meaning(self):
        for key in self.source:
            self.assertRegex(key, generate.KEY, key)

    def test_no_two_keys_collide_once_written_for_an_app(self):
        names = [generate.resource_name(k) for k in self.source]
        self.assertEqual(len(names), len(set(names)), "two keys make the same name once their dots are underscores")
        # A key can't be both a line and the start of other keys: Swift nests them.
        for key in self.source:
            self.assertFalse([k for k in self.source if k.startswith(key + ".")], key)

    def test_the_files_are_sorted_by_key(self):
        for lang, lines in self.lines.items():
            self.assertEqual(list(lines), sorted(lines), f"{lang}.json isn't sorted by key")

    def test_no_language_has_a_key_english_lacks(self):
        for lang, lines in self.lines.items():
            self.assertEqual(sorted(set(lines) - set(self.source)), [], lang)

    def test_a_shipped_language_has_every_key(self):
        for lang in self.langs["shipped"]:
            self.assertEqual(sorted(set(self.source) - set(self.lines[lang])), [], lang)

    def test_a_line_is_a_text_or_a_count_everywhere(self):
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                self.assertEqual(isinstance(line, dict), isinstance(self.source[key], dict), f"{lang} {key}")
                for text in generate.forms_of(line):
                    self.assertIsInstance(text, str, f"{lang} {key}")
                    self.assertTrue(text.strip(), f"{lang} {key} is empty")

    def test_a_count_has_the_forms_its_language_needs(self):
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                if not isinstance(line, dict):
                    continue
                self.assertEqual(sorted(set(line) - set(generate.PLURAL_FORMS)), [], f"{lang} {key}")
                self.assertEqual(sorted((NEEDS[lang] | {"other"}) - set(line)), [], f"{lang} {key} lacks a plural form")
                # A form needn't show the number ("All of the Cards"), but the count is always its first value.
                self.assertEqual(generate.arguments(key, self.lines["en"])[0], "count", f"{lang} {key}")

    def test_a_line_has_the_same_placeholders_in_every_language(self):
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                want = set(generate.placeholders(self.source[key]))
                for text in generate.forms_of(line):
                    got = set(generate.PLACEHOLDER.findall(text))
                    # A count's "one" form may write the number out ("One request").
                    extra_ok = {"count"} if isinstance(line, dict) else set()
                    self.assertTrue(got <= want and want - got <= extra_ok, f"{lang} {key}: {sorted(got)} != {sorted(want)}")
                    self.assertNotRegex(generate.PLACEHOLDER.sub("", text), r"[{}]", f"{lang} {key} has a stray brace")

    def test_a_shipped_language_isnt_left_in_english(self):
        for lang in self.langs["shipped"]:
            if lang == "en":
                continue
            same = [k for k, line in self.lines[lang].items()
                    if line == self.source[k] and k not in SAME_AS_ENGLISH.get(lang, ())]
            self.assertEqual(same, [], lang)

    def test_a_line_that_is_a_glossary_term_is_that_term_in_every_language(self):
        terms = glossary()
        self.assertIn("Approve once", terms)
        for key, english in self.source.items():
            if isinstance(english, dict) or english not in terms:
                continue
            for lang, lines in self.lines.items():
                if key in lines:
                    self.assertEqual(lines[key].lower(), terms[english][lang].lower(), f"{lang} {key}")

    def test_the_generated_files_are_what_the_generator_writes(self):
        for path, text in generate.generated().items():
            self.assertTrue(os.path.exists(path), os.path.relpath(path, REPO))
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), text, f"{os.path.relpath(path, REPO)} is out of date: run python3 app/strings/generate.py")


class Generator(unittest.TestCase):
    """What the generator writes, over a small set of its own."""

    def write(self, files):
        folder = tempfile.mkdtemp()
        for name, value in files.items():
            with open(os.path.join(folder, name), "w", encoding="utf-8") as f:
                json.dump(value, f, ensure_ascii=False)
        return folder

    def setUp(self):
        self.folder = self.write({
            "languages.json": {"all": ["en", "pl", "de"], "shipped": ["en", "pl"]},
            "en.json": {
                "chat.kept_for": {"one": "Kept {count} for {name}, whose Card uses it.", "other": "Kept {count} for {name}, whose Cards use them."},
                "chat.waiting": "{taf} is waiting on {who}: 100% of it's \"held\" & <new>",
                "home.title": "Tafs",
            },
            "pl.json": {
                "chat.kept_for": {"one": "{name}: zachowano {count}", "few": "{name}: zachowano {count}", "many": "{name}: zachowano {count}", "other": "{name}: zachowano {count}"},
                "chat.waiting": "{who} czeka na {taf}",
                "home.title": "Tafy",
            },
            "de.json": {"home.title": "Tafs"},
        })
        self.files = {os.path.relpath(p, REPO): t for p, t in generate.generated(self.folder).items()}

    def test_only_shipped_languages_are_written(self):
        self.assertEqual(sorted(f for f in self.files if f.startswith("ios/")),
                         ["ios/Tafs/Design/Words.swift", "ios/Tafs/Localizable.xcstrings"])
        self.assertNotIn('"de"', self.files["ios/Tafs/Localizable.xcstrings"])

    def test_ios_places_each_value_by_position_so_a_language_may_reorder(self):
        strings = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]
        waiting = strings["chat.waiting"]["localizations"]
        self.assertEqual(waiting["en"]["stringUnit"]["value"], "%1$@ is waiting on %2$@: 100%% of it's \"held\" & <new>")
        self.assertEqual(waiting["pl"]["stringUnit"]["value"], "%2$@ czeka na %1$@")
        kept = strings["chat.kept_for"]["localizations"]
        self.assertEqual(kept["en"]["variations"]["plural"]["one"]["stringUnit"]["value"], "Kept %1$lld for %2$@, whose Card uses it.")
        self.assertEqual(sorted(kept["pl"]["variations"]["plural"]), ["few", "many", "one", "other"])
        self.assertEqual(kept["pl"]["variations"]["plural"]["few"]["stringUnit"]["value"], "%2$@: zachowano %1$lld")

    def test_ios_gets_a_typed_accessor_for_each_key(self):
        swift = self.files["ios/Tafs/Design/Words.swift"]
        self.assertIn('static var title: String { Words.line("home.title") }', swift)
        self.assertIn('static func waiting(taf: String, who: String) -> String { Words.line("chat.waiting", taf, who) }', swift)
        self.assertIn('static func keptFor(count: Int, name: String) -> String { Words.line("chat.kept_for", count, name) }', swift)
        self.assertIn("enum Chat {", swift)

    def test_a_reserved_word_is_still_a_swift_name(self):
        self.assertEqual(generate.camel("default"), "`default`")
        self.assertEqual(generate.camel("approve_once"), "approveOnce")
        self.assertEqual(generate.camel("needs_you", upper=True), "NeedsYou")


if __name__ == "__main__":
    unittest.main()
