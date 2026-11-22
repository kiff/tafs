"""The checks over the shared words (README.md): run with
python3 -m unittest discover -s app/strings"""
import json
import os
import re
import tempfile
import unicodedata
import unittest

import android_literals
import generate
import ios_literals

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

# The plural forms each language's whole numbers need (CLDR), beside "other".
NEEDS = {lang: set(forms) for lang, forms in generate.PLURALS.items()}

# Lines that read the same in English and another language, on purpose: by language, the keys.
# The lines a language writes as English does, each read and kept on purpose: names (tafs, Coach, Card, Face ID,
# the sample's tafs), units and lines that are only their values, and words the language shares ("Agenda", "Status").
with open(os.path.join(HERE, "same_as_english.json"), encoding="utf-8") as f:
    SAME_AS_ENGLISH = json.load(f)

# A line that is exactly a glossary term but names a group, where the language says the term in the plural:
# the Record's "Done" filter sits beside "Held" and "Refused", plural as tafs cloud's filters are.
TERM_AS_PLURAL = {"record.filter.done": {"fr": "terminés", "es": "hechas", "it": "fatte", "pt": "concluídas"}}


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


def glossary_being_written():
    """The tables under "Korean and Japanese", "Batch 1", "Batch 2", "Batch 3" and "Batch 4" (docs/languages.md): a language that ships before its column joins the
    Terms table is held to its own table, English term to its word ("Approve / Approve once" is two terms)."""
    with open(os.path.join(REPO, "docs", "languages.md"), encoding="utf-8") as f:
        md = f.read()
    codes = {"Korean": "ko", "Japanese": "ja", "Estonian": "et", "Greek": "el", "Lithuanian": "lt", "Latvian": "lv", "Czech": "cs", "Slovak": "sk", "Slovenian": "sl", "Hungarian": "hu", "Romanian": "ro", "Bulgarian": "bg", "Croatian": "hr", "Turkish": "tr", "Indonesian": "id", "Vietnamese": "vi", "Thai": "th", "Traditional Chinese": "zh-Hant", "Serbian": "sr-Latn", "Albanian": "sq", "Georgian": "ka", "Armenian": "hy", "pt-BR": "pt-BR"}
    tables, rows = [], []
    # Korean and Japanese's tables, batch 1's, then each regional variant's (pt-BR, under "Regional variants").
    text = ""
    for section in ("\n## Korean and Japanese", "\n## Batch 1", "\n## Batch 2", "\n## Batch 3", "\n## Batch 4", "\n## Regional variants"):
        # Every section that opens so ("Batch 3" has more than one piece, Arabic's apart from the others).
        for found in re.finditer(re.escape(section), md):
            start = found.start()
            end = md.find("\n## ", start + 1)
            text += md[start:end if end > 0 else len(md)] + "\n"
    for line in text.split("\n") + [""]:
        if line.startswith("|"):
            rows.append(line)
        elif rows:
            tables.append(rows)
            rows = []
    terms = {}
    for rows in tables:
        head = [c.strip() for c in rows[0].split("|")[1:-1]]
        if len(head) != 2 or head[0] != "English" or head[1] not in codes:
            continue
        for row in rows[2:]:
            english, word = [c.strip() for c in row.split("|")[1:-1]]
            english = re.sub(r"\s*\(.*?\)", "", english)
            word = re.sub(r"\s*\(.*?\)", "", word)
            pairs = list(zip(english.split(" / "), word.split(" / ")))
            assert len(english.split(" / ")) == len(word.split(" / ")), row
            for e, w in pairs:
                terms.setdefault(e.strip(), {})[codes[head[1]]] = w.strip()
    return terms


class SharedWords(unittest.TestCase):
    def setUp(self):
        self.langs = generate.languages()
        self.source = generate.load("en")
        # Every language and each regional variant as the apps read it: its base's lines with its own on top.
        self.lines = generate.load_lines()

    def test_the_age_table_is_a_default_and_countries_that_ask_more(self):
        # legal-i18n's table: 16 by default (the GDPR's ceiling), and a country only where its law asks for more.
        table = generate.ages()
        self.assertEqual(table["default"], 16)
        for country, age in table.items():
            if country == "default":
                continue
            self.assertRegex(country, r"^[A-Z]{2}$", "an ISO 3166-1 code, upper case")
            self.assertIsInstance(age, int)
            self.assertGreater(age, table["default"], f"{country}: only a country asking more than the default is listed")
            self.assertLessEqual(age, 21, country)

    def test_the_languages_are_the_eight_then_japanese_and_korean(self):
        # The eight ship, and Korean (complete, PM 20 Nov) and Japanese (complete, PM 21 Nov) with them. A language
        # ships only once every key is there, so no app shows half a language.
        self.assertEqual(self.langs["all"], ["en", "de", "fr", "es", "it", "pt", "nl", "pl", "ja", "ko", "et", "el", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh-Hant", "ar", "sr-Latn", "sq", "ka", "hy"])
        # pt-BR ships as a regional variant of pt (Wave 1, Brazil): its own lines over pt's.
        self.assertEqual(self.langs["shipped"], ["en", "de", "fr", "es", "it", "pt", "nl", "pl", "ko", "ja", "et", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh-Hant", "sr-Latn", "sq", "ka", "hy", "pt-BR"])
        self.assertIn("en", self.langs["shipped"])
        self.assertTrue(set(self.langs["shipped"]) <= set(self.langs["all"]) | set(self.langs.get("variants", {})))

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
        for lang in self.lines:
            for path, lines in generate.files_of(lang).items():
                self.assertEqual(list(lines), sorted(lines), f"{os.path.relpath(path, REPO)} isn't sorted by key")

    def test_each_feature_has_its_own_file(self):
        # One file per feature in each language: a key lives in its area's file, and in one file only.
        for lang in self.lines:
            for path, lines in generate.files_of(lang).items():
                name = os.path.splitext(os.path.basename(path))[0]
                self.assertEqual(os.path.basename(os.path.dirname(path)), lang, f"{path}: {lang}'s words are in app/strings/{lang}/")
                self.assertEqual([k for k in lines if generate.area(k) != name], [], f"{os.path.relpath(path, REPO)} holds another feature's keys")
                self.assertTrue(lines, f"{os.path.relpath(path, REPO)} is empty: remove it")

    def test_a_key_in_the_wrong_file_or_in_two_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            os.makedirs(os.path.join(folder, "en"))
            with open(os.path.join(folder, "en", "home.json"), "w", encoding="utf-8") as f:
                json.dump({"chat.title": "Chat"}, f)
            with self.assertRaises(ValueError):
                generate.load("en", folder)
            with open(os.path.join(folder, "en", "home.json"), "w", encoding="utf-8") as f:
                json.dump({"home.title": "Tafs"}, f)
            with open(os.path.join(folder, "en", "chat.json"), "w", encoding="utf-8") as f:
                json.dump({"chat.title": "Chat", "home.title": "Tafs"}, f)
            with self.assertRaises(ValueError):
                generate.load("en", folder)

    def test_an_old_language_file_beside_its_folder_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            generate.save("en", {"home.title": "Tafs"}, folder)
            with open(os.path.join(folder, "en.json"), "w", encoding="utf-8") as f:
                json.dump({"home.title": "Old"}, f)
            with self.assertRaises(ValueError):
                generate.load("en", folder)

    def test_saving_writes_one_sorted_file_per_feature(self):
        with tempfile.TemporaryDirectory() as folder:
            generate.save("en", {"home.title": "Tafs", "chat.waiting": "Waiting", "chat.title": "Chat"}, folder)
            self.assertEqual(sorted(os.listdir(os.path.join(folder, "en"))), ["chat.json", "home.json"])
            self.assertEqual(generate.load("en", folder), {"chat.title": "Chat", "chat.waiting": "Waiting", "home.title": "Tafs"})

    def test_no_language_has_a_key_english_lacks(self):
        for lang, lines in self.lines.items():
            self.assertEqual(sorted(set(lines) - set(self.source)), [], lang)

    def test_a_shipped_language_has_every_key(self):
        for lang in self.langs["shipped"]:
            self.assertEqual(sorted(set(self.source) - set(self.lines[lang])), [], lang)

    def test_every_phone_line_the_watch_shows_is_here(self):
        # The Wear OS watch carries its own area and these lines of the phone's (android.py); a key renamed or removed
        # here would otherwise just drop out of the watch's files.
        import android
        self.assertEqual([k for k in android.WEAR_KEYS if k not in self.source], [])

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
                self.assertEqual(sorted((NEEDS[lang.split("-")[0]] | {"other"}) - set(line)), [], f"{lang} {key} lacks a plural form")
                # A form needn't show the number ("All of the Cards"), but the count is always its first value.
                self.assertEqual(generate.arguments(key, self.lines["en"])[0], "count", f"{lang} {key}")

    def test_a_line_has_the_same_placeholders_in_every_language(self):
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                want = set(generate.placeholders(self.source[key]))
                for text in generate.forms_of(line):
                    got = set(generate.names_in(text))
                    # A count's form may leave the number out ("One request", "All of the Cards").
                    extra_ok = {"count"} if isinstance(line, dict) else set()
                    self.assertTrue(got <= want and want - got <= extra_ok, f"{lang} {key}: {sorted(got)} != {sorted(want)}")
                    self.assertNotRegex(generate.PLACEHOLDER.sub("", text), r"[{}]", f"{lang} {key} has a stray brace")
                    if lang not in generate.POSSESSIVE:
                        self.assertNotIn("|possessive}", text, f"{lang} {key}: {lang} has no possessive rule; word the line around the name")
                    if lang not in generate.THIS:
                        self.assertNotRegex(text, r"\|[Tt]his\}", f"{lang} {key}: {lang} has no rule for \"this\"; write the word in the line")

    def test_a_right_to_left_language_writes_its_menu_arrows_pointing_left(self):
        # "→" doesn't mirror with the text direction, so an Arabic line writes "←" where English writes "→".
        # "‹" and "›" do mirror, and stay as English writes them.
        for lang in ("ar",):
            for key, line in self.lines[lang].items():
                for text in generate.forms_of(line):
                    self.assertNotIn("→", text, f"{lang} {key}: write ← in a right-to-left language")
                if "→" in "".join(generate.forms_of(self.source[key])):
                    self.assertIn("←", "".join(generate.forms_of(line)), f"{lang} {key}: English has →, so this line writes ←")

    def test_a_right_to_left_language_keeps_a_reference_number_in_its_own_left_to_right_run(self):
        # After an Arabic letter the digits of "#5098" become Arabic-script digits (bidi rule W2), which leaves the "#" outside
        # their run: "7802#". A reference written in a line is wrapped in U+2066 … U+2069 so it reads "#7802".
        for lang in ("ar",):
            for key, line in self.lines[lang].items():
                for text in generate.forms_of(line):
                    for found in re.finditer(r"#\d+", text):
                        self.assertEqual(text[max(0, found.start() - 1):found.start()], "\u2066", f"{lang} {key}: isolate {found.group()}")
                        self.assertEqual(text[found.end():found.end() + 1], "\u2069", f"{lang} {key}: close the isolate after {found.group()}")

    def test_a_macs_own_sentence_sits_beside_the_phones_and_names_the_macs_settings(self):
        # A `*_mac` line that isn't a Touch ID prompt (no "prompt" in its key) is a whole sentence for
        # the Mac: its phone line is beside it, it opens with a capital and ends with a stop, and a
        # line that sends the person to settings names macOS's own, for macOS 13 on and for macOS 12,
        # never the iPhone's Settings path.
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                if not key.endswith("_mac") or "prompt" in key:
                    continue
                self.assertIn(key[:-len("_mac")], self.source, f"{key}: the phone's line beside it")
                for text in generate.forms_of(line):
                    bare = generate.PLACEHOLDER.sub("", text).strip()
                    # A script with no capitals (Arabic, Korean, Japanese) opens a sentence with its first letter. So does
                    # Georgian: Unicode gives its letters (Mkhedruli) an upper case (Mtavruli), but a sentence isn't written in it.
                    first = bare[:1]
                    self.assertTrue(first.isupper() or first.lower() == first.upper() or "GEORGIAN" in unicodedata.name(first, ""),
                                    f"{lang} {key} doesn't open a sentence: {text!r}")
                    # Armenian ends a sentence with its own full stop, "։" (U+0589).
                    self.assertTrue(bare.endswith((".", "!", "?", "。", "։")), f"{lang} {key} doesn't end a sentence: {text!r}")
                    phone = self.lines[lang][key[:-len("_mac")]]
                    if "›" in (phone if isinstance(phone, str) else "") and key.startswith("voice."):
                        self.assertIn("macOS 12", text, f"{lang} {key}: macOS 12's path too")
                        self.assertNotEqual(text.split("›")[0], phone.split("›")[0], f"{lang} {key} sends the person to the iPhone's Settings")

    def test_a_macs_touch_id_reason_continues_the_systems_sentence(self):
        # A Mac's Touch ID prompt puts the reason after its own "<app> is trying to" ("tafs está intentando"),
        # so a prompt's `*_mac` line (a key with "prompt" in it) is a verb phrase that continues it: no capital to
        # open it, no final stop. Each has the phone's line beside it, which shows the reason alone, as a
        # sentence. Other `*_mac` lines (a Mac's own sentence) are sentences.
        for lang, lines in self.lines.items():
            for key, line in lines.items():
                if not (key.endswith("_mac") and "prompt" in key):
                    continue
                self.assertIn(key[:-len("_mac")], self.source, f"{key}: the phone's line beside it")
                for text in generate.forms_of(line):
                    bare = generate.PLACEHOLDER.sub("", text).lstrip()
                    self.assertFalse(bare[:1].isupper(), f"{lang} {key} opens with a capital: {text!r}")
                    self.assertFalse(text.rstrip().endswith((".", "!", "?", "。")), f"{lang} {key} ends a sentence: {text!r}")

    def test_a_shipped_language_isnt_left_in_english(self):
        for lang in self.langs["shipped"]:
            if lang == "en":
                continue
            same = [k for k, line in self.lines[lang].items()
                    if line == self.source[k] and k not in SAME_AS_ENGLISH.get(lang, SAME_AS_ENGLISH.get(lang.split("-")[0], ()))]
            self.assertEqual(same, [], lang)

    def test_every_line_kept_as_english_still_is(self):
        # A line kept as English that is then translated, or removed, comes off same_as_english.json.
        for lang, keys in SAME_AS_ENGLISH.items():
            stale = [k for k in keys if self.lines[lang].get(k) != self.source.get(k)]
            self.assertEqual(stale, [], f"{lang}: no longer the same as English")

    def test_a_line_that_is_a_glossary_term_is_that_term_in_every_language(self):
        terms = glossary()
        self.assertIn("Approve once", terms)
        # A shipped language not yet in the Terms table (ko, ja, et, lt, lv) is held to its own table ("Korean and
        # Japanese", "Batch 1").
        for english, words in glossary_being_written().items():
            for lang, word in words.items():
                if lang in self.langs["shipped"]:
                    terms.setdefault(english, {}).setdefault(lang, word)
        for lang in self.langs["shipped"]:
            self.assertTrue(any(lang in words for words in terms.values()), f"{lang} ships with no glossary")
        for key, english in self.source.items():
            if isinstance(english, dict) or english not in terms:
                continue
            for lang in self.langs["shipped"]:
                lines = self.lines[lang]
                if key in lines and lang in terms[english]:
                    want = TERM_AS_PLURAL.get(key, {}).get(lang, terms[english][lang])
                    self.assertEqual(lines[key].lower(), want.lower(), f"{lang} {key}")

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
            "ages.json": {"default": 16, "IN": 18},
            "en.json": {
                "chat.kept_for": {"one": "Kept {count} for {name}, whose Card uses it.", "other": "Kept {count} for {name}, whose Cards use them."},
                "chat.waiting": "{taf} is waiting on {who}: 100% of it's \"held\" & <new>",
                "home.all_of": {"one": "All of the Card", "other": "All of the Cards"},
                "home.title": "Tafs",
                "usage.all": "100% used",
                "usage.two_lines": "They taf.\nYou kiff.",
            },
            "pl.json": {
                "chat.kept_for": {"one": "{name}: zachowano {count}", "few": "{name}: zachowano {count}", "many": "{name}: zachowano {count}", "other": "{name}: zachowano {count}"},
                "chat.waiting": "{who} czeka na {taf}",
                "home.all_of": {"one": "Cała Card", "few": "Całe Cards", "many": "Całe Cards", "other": "Całe Cards"},
                "home.title": "Tafy",
                "usage.all": "100% zużyte",
                "usage.two_lines": "Oni tafują.\nTy kiffujesz.",
            },
            "de.json": {"home.title": "Tafs"},
        })
        self.files = {os.path.relpath(p, REPO): t for p, t in generate.generated(self.folder).items()}

    def test_a_preview_puts_an_unshipped_language_in_the_ios_catalog_only(self):
        files = {os.path.relpath(p, REPO): t for p, t in generate.generated(self.folder, preview=["de"]).items()}
        self.assertIn('"de"', files["ios/Tafs/Localizable.xcstrings"])
        self.assertIn('"de"', files["ios/Tafs/Design/Words.swift"])
        self.assertEqual([f for f in files if f.startswith("android/")], [], "Android isn't written for a preview")
        self.assertNotIn('"de"', self.files["ios/Tafs/Localizable.xcstrings"], "without it, nothing changes")
        # de has only home.title here: every other key reads in English in de's own catalog, never as the key.
        catalog = json.loads(files["ios/Tafs/Localizable.xcstrings"])["strings"]
        self.assertEqual(catalog["usage.all"]["localizations"]["de"]["stringUnit"]["value"], "100% used")
        self.assertEqual(catalog["home.title"]["localizations"]["de"]["stringUnit"]["value"], "Tafs")

    def test_a_regional_variant_is_its_base_with_its_own_lines_on_top(self):
        # pt-BR (languages.json's "variants") holds only the lines that read differently in Brazil: every other key reads
        # as pt's, so the variant never falls behind. Shipped, it's its own iOS localization and Android's values-pt-rBR.
        folder = self.write({
            "languages.json": {"all": ["en", "pt"], "shipped": ["en", "pt", "pt-BR"], "variants": {"pt-BR": "pt"}},
            "ages.json": {"default": 16},
            "en.json": {"home.title": "Tafs", "needs_you.title": "Needs you", "home.count": {"one": "{count} taf", "other": "{count} tafs"}},
            "pt.json": {"home.title": "Tafs", "needs_you.title": "Precisa de si", "home.count": {"one": "{count} taf", "other": "{count} tafs"}},
        })
        os.makedirs(os.path.join(folder, "pt-BR"))
        with open(os.path.join(folder, "pt-BR", "needs_you.json"), "w", encoding="utf-8") as f:
            json.dump({"needs_you.title": "Precisa de você"}, f, ensure_ascii=False)
        lines = generate.load_lines(folder)
        self.assertEqual(lines["pt-BR"]["needs_you.title"], "Precisa de você", "the variant's own line")
        self.assertEqual(lines["pt-BR"]["home.title"], "Tafs", "the base's line where the variant has none")
        self.assertEqual(generate.base_of("pt-BR", folder), "pt")
        self.assertEqual(generate.base_of("pt", folder), "pt")
        files = {os.path.relpath(p, REPO): t for p, t in generate.generated(folder).items()}
        catalog = json.loads(files["ios/Tafs/Localizable.xcstrings"])["strings"]
        self.assertEqual(catalog["needs_you.title"]["localizations"]["pt-BR"]["stringUnit"]["value"], "Precisa de você")
        self.assertEqual(catalog["needs_you.title"]["localizations"]["pt"]["stringUnit"]["value"], "Precisa de si")
        self.assertEqual(catalog["home.title"]["localizations"]["pt-BR"]["stringUnit"]["value"], "Tafs")
        counts = [k for k in catalog if k.startswith("home.count")]
        self.assertTrue(counts, "the count is in the catalog")
        self.assertTrue(all("pt-BR" in catalog[k]["localizations"] for k in counts), "a count takes its base's plural forms")
        self.assertIn('"pt-BR"', files["ios/Tafs/Design/Words.swift"])
        import android
        self.assertEqual(android.folder("pt-BR"), "values-pt-rBR")
        self.assertEqual(android.folder("pt"), "values-pt")
        # A script is no region: Android takes it only in its BCP 47 form (zh-Hant, when Traditional Chinese ships).
        self.assertEqual(android.folder("zh-Hant"), "values-b+zh+Hant")
        self.assertEqual(android.folder("zh-Hant-TW"), "values-b+zh+Hant+TW")
        # Batch 4: Hebrew also under its old code, as the libraries ship it; Serbian in Latin script in its BCP 47 folder, never
        # plain values-sr (Cyrillic); Azerbaijani in Latin script also in plain values-az, the libraries' Latin.
        self.assertEqual(android.folders("he"), ["values-he", "values-iw"])
        self.assertEqual(android.folders("sr-Latn"), ["values-b+sr+Latn"])
        self.assertEqual(android.folders("az-Latn"), ["values-b+az+Latn", "values-az"])
        self.assertEqual(android.folders("az"), ["values-az"])
        for lang in ("sr", "he", "sq", "ka", "hy", "az", "bn"):
            self.assertIn(lang, generate.PLURALS, f"{lang} has its plural forms")
        # A library's folder Android would take before ours gets the same lines: Indonesian's old code, Chinese by region.
        self.assertEqual(android.folders("id"), ["values-id", "values-in"])
        self.assertEqual(android.folders("zh-Hant"), ["values-b+zh+Hant", "values-zh-rTW", "values-zh-rHK", "values-zh-rMO"])
        self.assertEqual(android.folders("pt-BR"), ["values-pt-rBR"])
        self.assertIn("android/app/src/main/res/values-pt-rBR/strings.xml", files)
        self.assertIn("Precisa de você", files["android/app/src/main/res/values-pt-rBR/strings.xml"])
        self.assertIn('<locale android:name="pt-BR" />', files["android/app/src/main/res/xml/locales_config.xml"])

    def test_a_shipped_variant_ships_with_its_base(self):
        # The repo's own languages.json: a variant's base is shipped too (pt-BR reads pt's lines where it has none), and
        # every key a variant holds is one its base has, so a variant never carries a line English doesn't.
        langs = generate.languages()
        for variant, base in langs.get("variants", {}).items():
            self.assertIn(base, langs["all"], variant)
            if variant in langs["shipped"]:
                self.assertIn(base, langs["shipped"], variant)
            own = generate.load(variant)
            self.assertEqual(sorted(set(own) - set(generate.load(base))), [], variant)

    def test_the_age_table_is_written_for_the_welcome(self):
        swift = self.files["ios/Tafs/Design/MinimumAge.swift"]
        self.assertIn("static let standard = 16", swift)
        self.assertIn('static let byCountry: [String: Int] = ["IN": 18]', swift)

    def test_only_shipped_languages_are_written(self):
        self.assertEqual(sorted(f for f in self.files if f.startswith("ios/")),
                         ["ios/Tafs/Design/MinimumAge.swift", "ios/Tafs/Design/Words.swift", "ios/Tafs/Localizable.xcstrings"])
        self.assertNotIn('"de"', self.files["ios/Tafs/Localizable.xcstrings"])

    def test_ios_places_each_value_by_position_so_a_language_may_reorder(self):
        strings = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]
        waiting = strings["chat.waiting"]["localizations"]
        self.assertEqual(waiting["en"]["stringUnit"]["value"], "%1$@ is waiting on %2$@: 100%% of it's \"held\" & <new>")
        self.assertEqual(waiting["pl"]["stringUnit"]["value"], "%2$@ czeka na %1$@")

    def test_ios_leaves_a_line_with_no_values_as_it_is(self):
        # Words.line returns a line with no values without formatting it, so "%%" would show.
        strings = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]
        self.assertEqual(strings["usage.all"]["localizations"]["en"]["stringUnit"]["value"], "100% used")
        self.assertEqual(strings["usage.all"]["localizations"]["pl"]["stringUnit"]["value"], "100% zużyte")

    def test_a_line_with_a_line_break_stays_one_comment_line_in_swift(self):
        swift = self.files["ios/Tafs/Design/Words.swift"]
        self.assertIn("/// They taf.\\nYou kiff.\n", swift)
        self.assertNotIn("\nYou kiff.", swift.replace("\\nYou kiff.", ""), "a raw line break would end the comment")

    def test_ios_gets_an_entry_for_each_plural_form_a_language_has(self):
        strings = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]
        self.assertNotIn("chat.kept_for", strings, "a count has an entry per form, none of its own")
        # The count is the first value, then English's order.
        self.assertEqual(strings["chat.kept_for|one"]["localizations"]["en"]["stringUnit"]["value"], "Kept %1$@ for %2$@, whose Card uses it.")
        self.assertEqual(strings["chat.kept_for|few"]["localizations"]["pl"]["stringUnit"]["value"], "%2$@: zachowano %1$@")
        self.assertEqual(sorted(strings["chat.kept_for|few"]["localizations"]), ["pl"], "English has no few")
        self.assertEqual(sorted(strings["chat.kept_for|other"]["localizations"]), ["en", "pl"])
        # A count whose words don't show the number still has its forms.
        self.assertEqual(strings["home.all_of|one"]["localizations"]["en"]["stringUnit"]["value"], "All of the Card")
        self.assertEqual(strings["home.all_of|other"]["localizations"]["en"]["stringUnit"]["value"], "All of the Cards")

    def test_ios_asks_the_catalog_which_form_a_number_takes(self):
        plural = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]["_plural"]["localizations"]
        self.assertEqual(sorted(plural["en"]["variations"]["plural"]), ["one", "other"])
        self.assertEqual(sorted(plural["pl"]["variations"]["plural"]), ["few", "many", "one", "other"])
        self.assertEqual(plural["pl"]["variations"]["plural"]["few"]["stringUnit"]["value"], "%lld|few")

    def test_ios_catalog_names_the_language_of_its_lines(self):
        language = json.loads(self.files["ios/Tafs/Localizable.xcstrings"])["strings"]["_language"]["localizations"]
        self.assertEqual({lang: v["stringUnit"]["value"] for lang, v in language.items()}, {lang: lang for lang in language})
        self.assertIn("en", language)

    def test_ios_gets_a_typed_accessor_for_each_key(self):
        swift = self.files["ios/Tafs/Design/Words.swift"]
        self.assertIn('static var title: String { Words.line("home.title") }', swift)
        self.assertIn('static func waiting(taf: String, who: String) -> String { Words.line("chat.waiting", taf, who) }', swift)
        self.assertIn('static func keptFor(count: Int, name: String) -> String { Words.count("chat.kept_for", count, name) }', swift)
        # A count always takes its number, to pick the form, even when its words don't show it.
        self.assertIn('static func allOf(count: Int) -> String { Words.count("home.all_of", count) }', swift)
        # A count's number is written as the app's language writes it, never as plain digits.
        self.assertIn("[number(count)] + values", swift)
        # In the language of the lines, as the catalog names it, never the device's region.
        self.assertIn('bundle.localizedString(forKey: "_language", value: "en", table: nil)', swift)
        # Read from one catalog the app can swap, so a language picked in the app applies at once.
        self.assertIn("nonisolated(unsafe) static var bundle = Bundle.main", swift)
        self.assertNotIn("Bundle.main.localizedString", swift)
        self.assertIn("formatter.locale = locale", swift)
        self.assertNotIn("Locale.current", swift)
        self.assertNotIn("String(count)", swift)
        self.assertIn("enum Chat {", swift)

    def test_a_counts_number_always_comes_first(self):
        source = {"a.shown": {"one": "{name} has {count}", "other": "{name} has {count}"},
                  "a.hidden": {"one": "All of the Card", "other": "All of the Cards"}, "a.plain": "{count} of {name}"}
        self.assertEqual(generate.arguments("a.shown", source), ["count", "name"])
        self.assertEqual(generate.arguments("a.hidden", source), ["count"])
        self.assertEqual(generate.arguments("a.plain", source), ["count", "name"], "a plain line keeps its own order")

    def test_a_reserved_word_is_still_a_swift_name(self):
        self.assertEqual(generate.camel("default"), "`default`")
        self.assertEqual(generate.camel("approve_once"), "approveOnce")
        self.assertEqual(generate.camel("needs_you", upper=True), "NeedsYou")


class Possessives(unittest.TestCase):
    """`{name|possessive}`: the name's possessive by the line language's rule (generate.POSSESSIVE)."""

    def test_the_rule_by_language(self):
        self.assertEqual([generate.possessive(n, "en") for n in ["Refunds", "Rae", "Chargebacks", "BILLS"]], ["Refunds'", "Rae's", "Chargebacks'", "BILLS'"])
        self.assertEqual([generate.possessive(n, "de") for n in ["Klaus", "Rae", "Fritz", "Max"]], ["Klaus'", "Raes", "Fritz'", "Max'"])
        self.assertEqual(generate.possessive("Rae", "fr"), "Rae", "a language with no rule keeps the name")
        self.assertEqual(sorted(generate.POSSESSIVE), ["de", "en"])

    def test_a_possessive_is_a_value_after_the_lines_own(self):
        source = {"a.also_on": "Also on {taf|possessive} Card, with {vendor}"}
        lines = {"de": {"a.also_on": "Auch auf {taf|possessive} Karte, mit {vendor}"},
                 "fr": {"a.also_on": "Aussi sur la Carte de {taf}, avec {vendor}"}}
        self.assertEqual(generate.arguments("a.also_on", source), ["taf", "vendor"], "the accessor takes the name as it is")
        self.assertEqual(generate.possessives("a.also_on", source, lines), ["taf"])
        owned = generate.derived("a.also_on", source, lines)
        self.assertEqual(generate.ios_text(source["a.also_on"], "a.also_on", source, owned), "Also on %3$@ Card, with %2$@")
        self.assertEqual(generate.ios_text(lines["de"]["a.also_on"], "a.also_on", source, owned), "Auch auf %3$@ Karte, mit %2$@")
        self.assertEqual(generate.ios_text(lines["fr"]["a.also_on"], "a.also_on", source, owned), "Aussi sur la Carte de %1$@, avec %2$@")
        swift = generate.ios_swift(source, lines)
        self.assertIn('static func alsoOn(taf: String, vendor: String) -> String { Words.line("a.also_on", taf, vendor, Words.possessive(taf)) }', swift)

    def test_ios_writes_the_rule_from_the_table(self):
        swift = generate.ios_swift({"a.b": "x"})
        self.assertIn('case "en": return name + ("s".contains(last) ? "\'" : "\'s")', swift)
        self.assertIn('case "de": return name + ("sßxz".contains(last) ? "\'" : "s")', swift)
        self.assertIn("switch locale.language.languageCode?.identifier", swift, "picked by the lines' language, never the device's")


class This(unittest.TestCase):
    """`{name|this}`: "this name" by the line language's rule (generate.THIS), `{name|This}` to open a sentence."""

    def test_the_rule_both_ways(self):
        # French: "ce" before a consonant, "cet" before a vowel or a mute h, for every device word it has.
        self.assertEqual([generate.this(n, "fr") for n in ["Mac", "PC", "PC Windows", "appareil", "iPad", "écran", "hôte"]],
                         ["ce Mac", "ce PC", "ce PC Windows", "cet appareil", "cet iPad", "cet écran", "cet hôte"])
        self.assertEqual([generate.this(n, "fr", capital=True) for n in ["Mac", "appareil"]], ["Ce Mac", "Cet appareil"])
        self.assertEqual(generate.this("Mac", "en"), "Mac", "a language with no rule keeps the name")
        self.assertEqual(generate.this("", "fr"), "")
        self.assertEqual(sorted(generate.THIS), ["fr"])

    def test_french_never_writes_its_own_word_before_a_device(self):
        # "cet {device}" read "cet Mac" on a Mac, "ce {device}" "ce appareil" on a phone, and "l'{device}" "l'Mac":
        # the rule picks the word.
        for key, line in generate.load("fr").items():
            for text in generate.forms_of(line):
                self.assertNotRegex(text, r"(\b([Cc]e(t|tte)?|[Ll][ea]|[Dd]u|[Aa]u) |\b[Ll]')\{device\}", f"fr {key}: write {{device|this}}")

    def test_this_is_a_value_after_the_lines_own_and_each_app_says_it(self):
        source = {"a.on_device": "Alerts on this {device} for {taf|possessive} Card"}
        lines = {"fr": {"a.on_device": "Alertes sur {device|this} pour la Carte de {taf}"},
                 "pl": {"a.on_device": "Alerty na tym {device} dla Karty {taf}"},
                 "nl": {"a.on_device": "{device|This}: meldingen voor {taf}"}}
        self.assertEqual(generate.derived("a.on_device", source, lines),
                         [("taf", "possessive"), ("device", "this"), ("device", "This")], "English first, then each language's")
        self.assertEqual(generate.possessives("a.on_device", source, lines), ["taf"])
        owned = generate.derived("a.on_device", source, lines)
        self.assertEqual(generate.ios_text(lines["fr"]["a.on_device"], "a.on_device", source, owned), "Alertes sur %4$@ pour la Carte de %2$@")
        self.assertEqual(generate.ios_text(lines["pl"]["a.on_device"], "a.on_device", source, owned), "Alerty na tym %1$@ dla Karty %2$@")
        self.assertEqual(generate.ios_text(lines["nl"]["a.on_device"], "a.on_device", source, owned), "%5$@: meldingen voor %2$@")
        swift = generate.ios_swift(source, lines)
        self.assertIn('static func onDevice(device: String, taf: String) -> String { Words.line("a.on_device", device, taf, '
                      'Words.possessive(taf), Words.demonstrative(device), Words.demonstrative(device, capital: true)) }', swift)
        self.assertIn('case "fr": word = "aeiouyhàâäéèêëîïôöùûüœæ".contains(first) ? "cet " : "ce "', swift)
        import android
        kotlin = android.words_kt(source, lines)
        self.assertIn('fun onDevice(device: String, taf: String): String = Res.s(R.string.a_on_device, device, taf, '
                      'Words.possessive(taf), Words.demonstrative(device), Words.demonstrative(device, capital = true))', kotlin)
        self.assertIn('"fr" -> if (name.first().lowercaseChar() in "aeiouyhàâäéèêëîïôöùûüœæ") "cet " else "ce "', kotlin)
        self.assertEqual(android.android_text(lines["fr"]["a.on_device"], ["device", "taf"], owned, True),
                         "Alertes sur %4$s pour la Carte de %2$s")

class IOSLiterals(unittest.TestCase):
    """No English left in the iPhone and iPad app's code, outside what must stay (ios_literals.py)."""

    def test_every_ios_line_reads_through_words(self):
        left = ios_literals.scan()
        self.assertEqual(left, [], "English still in code; give it a key in app/strings/en/, or keep it in ios_kept.json with why:\n"
                         + "\n".join(f"  {f}:{n}: {b}" for f, n, b in left))

    def test_the_scan_judges_each_literal_on_its_own(self):
        swift = "\n".join([
            'let a = n == 1 ? "One thing waits" : "\\(n) things wait"',
            'if answer == "declined" { title = "Not now" }',
            'let b = ["Tried today", counts].joined()',
            'lines.append("\\(waiting) thing\\(waiting == 1 ? " waits" : "s wait") on you")',
            'request.setValue("Bearer \\(t)", forHTTPHeaderField: "Authorization")',
            'let path = "/v1/tafs/\\(id)"',
            'let x = dict["some key"]',
            'let id = "knob.showing"',
            '// "A comment with words"',
        ])
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "View.swift"), "w") as f:
                f.write(swift)
            left = [b for _, _, b in ios_literals.scan(d, kept_path=None)]
        # Both sides of a ternary, words beside a comparison, an array of words, a count made in code and its parts.
        self.assertEqual(sorted(left), sorted(["One thing waits", "\\(n) things wait", "Not now", "Tried today",
                                               '\\(waiting) thing\\(waiting == 1 ? " waits" : "s wait") on you', "s wait"]))

    def test_ci_runs_the_scan_on_every_change_to_the_ios_sources(self):
        with open(os.path.join(REPO, ".github", "workflows", "strings.yml"), encoding="utf-8") as f:
            workflow = f.read()
        sources = os.path.relpath(ios_literals.SOURCES, REPO).replace(os.sep, "/")
        triggers = re.findall(r"paths: \[(.*)\]", workflow)
        self.assertEqual(len(triggers), 2, "the push and pull_request paths weren't found")
        for paths in triggers:
            self.assertIn(f'"{sources}/**"', paths, f"a change to {sources} doesn't run the strings checks")

    def test_every_kept_literal_is_still_in_its_file(self):
        with open(ios_literals.KEPT, encoding="utf-8") as f:
            kept = json.load(f)
        for rel, bodies in kept.items():
            with open(os.path.join(ios_literals.SOURCES, rel), encoding="utf-8") as f:
                found = {b for _, _, b in ios_literals.literals(f.read())}
            for body, why in bodies.items():
                self.assertTrue(why, f"{rel}: {body!r} has no reason")
                self.assertIn(body, found, f"{rel} no longer has {body!r}: remove it from ios_kept.json")



class AndroidLiterals(unittest.TestCase):
    """No English left in the Android app's code, outside what must stay (android_literals.py)."""

    def test_every_android_line_reads_through_words(self):
        left = android_literals.scan()
        self.assertEqual(left, [], "English still in code; give it a key in app/strings/en/, or keep it in android_kept.json with why:\n"
                         + "\n".join(f"  {f}:{n}: {b}" for f, n, b in left))

    def test_the_scan_judges_each_literal_on_its_own(self):
        kotlin = "\n".join([
            'val a = if (n == 1) "One thing waits" else "$n things wait"',
            'if (answer == "declined") title = "Not now"',
            'val b = when (stop) { "cost_cap" -> "Over its limit"; else -> "" }',
            'lines += "$waiting thing${if (waiting == 1) " waits" else "s wait"} on you"',
            'request.header("Authorization", "Bearer $t")',
            'val path = "/v1/tafs/$id"',
            'val x = map["some key"]',
            'val id = "knob.showing"',
            'val c = \'"\'',
            '// "A comment with words"',
        ])
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "View.kt"), "w") as f:
                f.write(kotlin)
            left = [b for _, _, b in android_literals.scan(d, kept_path=None)]
        # Both sides of an if, words beside a comparison or a when branch, a count made in code and its parts.
        self.assertEqual(sorted(left), sorted(["One thing waits", "$n things wait", "Not now", "Over its limit",
                                               '$waiting thing${if (waiting == 1) " waits" else "s wait"} on you', "s wait"]))

    def test_ci_runs_the_scan_on_every_change_to_the_android_sources(self):
        with open(os.path.join(REPO, ".github", "workflows", "strings.yml"), encoding="utf-8") as f:
            workflow = f.read()
        sources = os.path.relpath(os.path.join(REPO, "android", "app", "src", "main", "kotlin"), REPO).replace(os.sep, "/")
        triggers = re.findall(r"paths: \[(.*)\]", workflow)
        self.assertEqual(len(triggers), 2, "the push and pull_request paths weren't found")
        for paths in triggers:
            self.assertIn(f'"{sources}/**"', paths, f"a change to {sources} doesn't run the strings checks")

    def test_every_kept_literal_is_still_in_its_file(self):
        with open(android_literals.KEPT, encoding="utf-8") as f:
            kept = json.load(f)
        for rel, bodies in kept.items():
            with open(os.path.join(android_literals.SOURCES, rel), encoding="utf-8") as f:
                found = {b for _, _, b in android_literals.literals(f.read())}
            for body, why in bodies.items():
                self.assertTrue(why, f"{rel}: {body!r} has no reason")
                self.assertIn(body, found, f"{rel}: {body!r} is kept but no longer in the file")

class AppIntentWords(unittest.TestCase):
    """Apple reads an App Intent's title and description keys as its words when it processes a build, and
    refuses a build whose words contain "siri" (ITMS 90626: "App Intent description … cannot contain
    'siri'"), as it refused 1.0 (1930) over `siri.halt_all.description`. So no key an intent uses, and no
    phrase Siri hears, says it."""

    SOURCES = os.path.join(REPO, "ios", "Tafs")
    RESOURCE = re.compile(r'LocalizedStringResource\(\s*"([^"]+)"')
    PHRASE = re.compile(r'"((?:[^"\\]|\\.)*)"')

    def intent_files(self):
        for root, _, files in os.walk(self.SOURCES):
            for name in files:
                if not name.endswith(".swift"):
                    continue
                path = os.path.join(root, name)
                with open(path, encoding="utf-8") as f:
                    text = f.read()
                if re.search(r":\s*(AppIntent|AppShortcutsProvider)\b", text):
                    yield os.path.relpath(path, REPO), text

    def test_no_intent_key_or_phrase_contains_siri(self):
        found = 0
        for rel, text in self.intent_files():
            for key in self.RESOURCE.findall(text):
                found += 1
                self.assertNotIn("siri", key.lower(), f"{rel}: {key!r}")
            for block in re.findall(r"phrases:\s*\[(.*?)\]", text, re.S):
                for phrase in self.PHRASE.findall(block):
                    self.assertNotIn("siri", phrase.lower(), f"{rel}: {phrase!r}")
        self.assertGreaterEqual(found, 2, "the scan found no intent's words")
        for root, _, files in os.walk(self.SOURCES):
            for name in files:
                if name == "AppShortcuts.strings":
                    path = os.path.join(root, name)
                    with open(path, encoding="utf-8") as f:
                        for k, v in re.findall(r'^"(.*?)"\s*=\s*"(.*?)";', f.read(), re.M):
                            self.assertNotIn("siri", (k + v).lower(), os.path.relpath(path, REPO))

    def test_every_intent_key_is_a_shared_line(self):
        lines = generate.load("en")
        for rel, text in self.intent_files():
            for key in self.RESOURCE.findall(text):
                self.assertIn(key, lines, f"{rel}: {key!r} isn't in app/strings")


if __name__ == "__main__":
    unittest.main()
