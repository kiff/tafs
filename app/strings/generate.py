#!/usr/bin/env python3
"""Writes both apps' words from the shared files here (see README.md).

  python3 app/strings/generate.py           write the generated files
  python3 app/strings/generate.py --check   fail if a generated file differs from what this writes

Generated for iPhone and iPad here: ios/Tafs/Localizable.xcstrings and
ios/Tafs/Design/Words.swift. Android's files are written by android.py, beside this file, when
it's there: it gives `generated(source, lines, languages) -> {path: text}`, as `ios` below does.
Nobody edits a generated file by hand.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
IOS_CATALOG = os.path.join(REPO, "ios", "Tafs", "Localizable.xcstrings")
IOS_SWIFT = os.path.join(REPO, "ios", "Tafs", "Design", "Words.swift")
IOS_AGES = os.path.join(REPO, "ios", "Tafs", "Design", "MinimumAge.swift")

PLURAL_FORMS = ("zero", "one", "two", "few", "many", "other")
KEY = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
# A value: `{name}`; `{name|possessive}` for the name's possessive in the line's language; `{name|this}`
# for "this name" (`{name|This}` to open a sentence with it).
PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]*)(?:\|(possessive|this|This))?\}")

# How a language makes a name's possessive, for `{name|possessive}`: a name ending in one of these
# letters takes the first suffix, any other the second ("Refunds'", "Rae's"; "Klaus'", "Raes").
# Only languages with such a rule are here; any other writes `{name}` and words the line around it
# ("la Carte de {taf}"). Both apps' generators read this one table.
POSSESSIVE = {"en": ("s", "'", "'s"), "de": ("sßxz", "'", "s")}

# How a language says "this name", for `{name|this}`: a name starting with one of these letters takes
# the first word, any other the second ("cet appareil", "ce Mac"). Only languages whose word for "this"
# changes with the name are here; any other writes its word in the line ("this {device}"). Both apps'
# generators read this one table, and the desktop apps keep the same rule (docs/languages.md).
# The table is right for the device kinds the apps name (Mac, PC, iPad, appareil, écran…): it takes every h as
# mute and y as a vowel, so a name a person typed ("héros", "yaourt") would need an exception list first.
THIS = {"fr": ("aeiouyhàâäéèêëîïôöùûüœæ", "cet ", "ce ")}
SWIFT_WORDS = {"default", "case", "class", "func", "let", "var", "in", "is", "as", "do", "for", "if", "else", "return",
               "self", "true", "false", "nil", "switch", "where", "while", "import", "init", "enum", "struct", "static",
               "protocol", "extension", "operator", "repeat", "throw", "throws", "try", "catch", "guard", "defer",
               "break", "continue", "internal", "private", "public", "open", "super", "Type", "Self", "Any"}


def area(key):
    """The feature a key belongs to: its first part (`drive.paused` is `drive`), and the file it's in."""
    return key.split(".", 1)[0]


def files_of(lang, folder=HERE):
    """A language's files, each with its lines as written: one per feature (`<lang>/<area>.json`), or, before
    the split, the language's one file (`<lang>.json`)."""
    split = os.path.join(folder, lang)
    old = os.path.join(folder, f"{lang}.json")
    if os.path.isdir(split) and os.path.exists(old):
        raise ValueError(f"{os.path.relpath(old, REPO)} is beside {lang}/: its words are in {lang}/ now, so remove it")
    if os.path.isdir(split):
        names = sorted(n for n in os.listdir(split) if n.endswith(".json"))
        paths = [os.path.join(split, n) for n in names]
    else:
        paths = [p for p in [os.path.join(folder, f"{lang}.json")] if os.path.exists(p)]
    out = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            out[path] = json.load(f)
    return out


def load(lang, folder=HERE):
    """A language's lines, all its features together, by key. A key in another feature's file, or in two files,
    is refused: each feature's words live in one file, so two PRs on different features never touch the same one."""
    lines = {}
    for path, part in files_of(lang, folder).items():
        name = os.path.splitext(os.path.basename(path))[0]
        split = os.path.basename(os.path.dirname(path)) == lang
        for key, value in part.items():
            if split and area(key) != name:
                raise ValueError(f"{os.path.relpath(path, REPO)}: {key} belongs in {area(key)}.json")
            if key in lines:
                raise ValueError(f"{os.path.relpath(path, REPO)}: {key} is in two files")
            lines[key] = value
    return dict(sorted(lines.items()))


def save(lang, lines, folder=HERE):
    """Writes a language's lines, one file per feature, each sorted by key, as the files are kept."""
    split = os.path.join(folder, lang)
    os.makedirs(split, exist_ok=True)
    by_area = {}
    for key in sorted(lines):
        by_area.setdefault(area(key), {})[key] = lines[key]
    for name, part in by_area.items():
        with open(os.path.join(split, f"{name}.json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(part, ensure_ascii=False, indent=2) + "\n")


def languages(folder=HERE):
    with open(os.path.join(folder, "languages.json"), encoding="utf-8") as f:
        return json.load(f)


def base_of(lang, folder=HERE):
    """A regional variant's base language (`languages.json`'s `variants`: "pt-BR" is "pt"), or the language itself."""
    return languages(folder).get("variants", {}).get(lang, lang)


def load_lines(folder=HERE):
    """Every language's lines, by language: each in `all`, and each regional variant (`variants`, "pt-BR": "pt") as its
    base's lines with its own on top. A variant holds only the lines that read differently there (`app/strings/pt-BR/`),
    so it never falls behind its base: a key it doesn't change reads as the base's."""
    langs = languages(folder)
    lines = {lang: load(lang, folder) for lang in langs["all"]}
    for variant, base in langs.get("variants", {}).items():
        lines[variant] = {**lines[base], **load(variant, folder)}
    return lines


def forms_of(line):
    """A line's texts: each plural form of a count, or the one text of a plain line."""
    return list(line.values()) if isinstance(line, dict) else [line]


def names_in(text):
    """A text's values by name, `{taf}` and `{taf|possessive}` alike, as they appear."""
    return [m.group(1) for m in PLACEHOLDER.finditer(text)]


def placeholders(line):
    """A line's placeholders, in the order they first appear."""
    seen = []
    for text in ([line["other"]] if isinstance(line, dict) and "other" in line else []) + forms_of(line):
        for name in names_in(text):
            if name not in seen:
                seen.append(name)
    return seen


def derived(key, source, lines=None):
    """The values a line carries after its own, each `(name, form)`: every `{name|possessive}`,
    `{name|this}` and `{name|This}` any language writes, English first, then the others, in the order
    they first appear. Every language is given them, in this order, whether or not its own words use them."""
    seen = []
    for line in [source.get(key)] + [lines[lang].get(key) for lang in sorted(lines or {})]:
        for text in forms_of(line) if line is not None else []:
            for m in PLACEHOLDER.finditer(text):
                if m.group(2) and (m.group(1), m.group(2)) not in seen:
                    seen.append((m.group(1), m.group(2)))
    return seen


def possessives(key, source, lines=None):
    """The names whose possessive a line carries after its own values (`derived`)."""
    return [name for name, form in derived(key, source, lines) if form == "possessive"]


def position(m, names, extra):
    """A placeholder's place among a line's values: its own (`names`), then the derived (`extra`)."""
    return len(names) + list(extra).index((m.group(1), m.group(2))) if m.group(2) else names.index(m.group(1))


def possessive(name, lang):
    """A name's possessive by the language's rule (POSSESSIVE); the name as it is in a language
    with none. The apps' own functions are generated from the same table."""
    if lang not in POSSESSIVE or not name:
        return name
    ends, after, other = POSSESSIVE[lang]
    return name + (after if name[-1].lower() in ends else other)


def this(name, lang, capital=False):
    """"This name" by the language's rule (THIS): "ce Mac", "cet appareil"; the name as it is in a
    language with none. `capital` opens a sentence: "Cet appareil". The apps' own functions are
    generated from the same table."""
    if lang not in THIS or not name:
        return name
    starts, before, other = THIS[lang]
    word = before if name[0].lower() in starts else other
    return (word[:1].upper() + word[1:] if capital else word) + name


def derive(name, form, lang):
    """A derived value (`derived`) as the language says it."""
    return possessive(name, lang) if form == "possessive" else this(name, lang, capital=form == "This")


def swift_derived(name, form):
    """A derived value as Words.swift's accessors give it."""
    if form == "possessive":
        return f"Words.possessive({name})"
    return f"Words.demonstrative({name}{', capital: true' if form == 'This' else ''})"


def arguments(key, source):
    """A line's values in the order every language is given them: English's order. A count's
    `count` always comes first, whether or not its words show the number ("All of the Card",
    "All of the Cards"): it picks the form."""
    names = placeholders(source[key])
    if isinstance(source[key], dict):
        if "count" in names:
            names.remove("count")
        names.insert(0, "count")
    return names


def is_count(key, source, name):
    return name == "count" and isinstance(source[key], dict)


# ---------------------------------------------------------------- iPhone and iPad

# The catalog's own entry that says which plural form a number takes in the app's language:
# "%lld|one", "%lld|few"… Words.swift asks it, then reads the line's entry for that form.
IOS_PLURAL_KEY = "_plural"

# The catalog's own entry that names the language its lines are in ("en"), so Words.swift writes
# a number, and fills a line, in the language of the line itself. The device's region doesn't
# decide it, and neither does `Bundle.main.preferredLocalizations`: that can answer Spanish or
# French, the languages of Siri's phrases, while every screen is still in English.
IOS_LANGUAGE_KEY = "_language"

# The plural forms a language's whole numbers take (CLDR), beside "other".
PLURALS = {"en": ["one"], "de": ["one"], "nl": ["one"], "it": ["one"], "es": ["one"], "pt": ["one"], "fr": ["one"],
           "pl": ["one", "few", "many"], "ja": [], "ko": [], "et": ["one"], "el": ["one"], "lt": ["one", "few"], "lv": ["zero", "one"],
           "cs": ["one", "few"], "sk": ["one", "few"]}


def ios_text(text, key, source, owned=()):
    """A line in the catalog's form: each placeholder by its position, so a language may reorder
    them. Every value is text, a count's number too, as the app writes it. A name's possessive or
    "this name" (`owned`, from `derived`) comes after the line's own values. A line with no values is
    never formatted (`Words.line` returns it as it is), so its `%` stays as it is."""
    names = arguments(key, source)
    if not names:
        return text
    text = text.replace("%", "%%")

    return PLACEHOLDER.sub(lambda m: f"%{position(m, names, owned) + 1}$@", text)


def ios_form_key(key, form):
    """A count's entry for one plural form."""
    return f"{key}|{form}"


def ios_catalog(source, shipped, lines):
    def unit(value):
        return {"stringUnit": {"state": "translated", "value": value}}

    strings = {}
    for key in sorted(source):
        owned = derived(key, source, lines)
        if isinstance(source[key], dict):
            # A count: one entry for each plural form any shipped language has.
            for form in PLURAL_FORMS:
                localizations = {lang: unit(ios_text(lines[lang][key][form], key, source, owned)) for lang in shipped
                                 if isinstance(lines[lang].get(key), dict) and form in lines[lang][key]}
                if localizations:
                    strings[ios_form_key(key, form)] = {"extractionState": "manual", "localizations": localizations}
            continue
        localizations = {lang: unit(ios_text(lines[lang][key], key, source, owned)) for lang in shipped if key in lines[lang]}
        strings[key] = {"extractionState": "manual", "localizations": localizations}
    # Which form a number takes, in each shipped language's own rules.
    strings[IOS_PLURAL_KEY] = {"extractionState": "manual", "localizations": {
        lang: {"variations": {"plural": {form: unit(f"%lld|{form}") for form in PLURALS[lang.split("-")[0]] + ["other"]}}} for lang in shipped}}
    # The language each language's lines are in: its own tag.
    strings[IOS_LANGUAGE_KEY] = {"extractionState": "manual", "localizations": {lang: unit(lang) for lang in shipped}}
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    return json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def camel(part, upper=False):
    words = part.split("_")
    out = (words[0].capitalize() if upper else words[0]) + "".join(w.capitalize() for w in words[1:])
    return f"`{out}`" if out in SWIFT_WORDS else out


def ages(folder=HERE):
    """The minimum age to use the apps, by country (`ages.json`, legal-i18n's table): `default`, and a
    country (ISO 3166-1, upper case) whose law asks for more. The welcome's age line says the person's."""
    with open(os.path.join(folder, "ages.json"), encoding="utf-8") as f:
        return json.load(f)


def ios_ages(table):
    """MinimumAge.swift: the table as Swift, so the welcome reads the person's country's age."""
    rows = ", ".join(f'"{country}": {age}' for country, age in sorted(table.items()) if country != "default")
    return "\n".join([
        "// Generated by app/strings/generate.py from app/strings/ages.json. Don't edit by hand.",
        "",
        "/// The minimum age to use the app, by country (ISO 3166-1): legal-i18n's table. Any other country,",
        "/// or none known, is `standard`.",
        "enum MinimumAge {",
        f"    static let standard = {table['default']}",
        f"    static let byCountry: [String: Int] = [{rows}]",
        "",
        "    /// The age for a country code as `Locale.Region` gives it (\"IN\"), or `standard`.",
        "    static func of(_ country: String?) -> Int {",
        "        guard let country else { return standard }",
        "        return byCountry[country.uppercased()] ?? standard",
        "    }",
        "}",
        "",
    ])


def ios_swift(source, lines=None, shipped=("en",)):
    """A typed accessor for each key, nested by the key's parts: Words.NeedsYou.approveOnce. It
    takes a name as it is, and gives the catalog its possessive too where a line wants it."""
    tree = {}
    for key in sorted(source):
        node = tree
        parts = key.split(".")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = key

    out = ["// Generated by app/strings/generate.py from app/strings/en/. Don't edit: change the",
           "// shared files and run the generator (docs/languages.md, app/strings/README.md).",
           "import Foundation",
           "",
           "/// The app's own words, each by its shared key, in the app's language.",
           "enum Words {"]

    def emit(node, depth):
        pad = "    " * depth
        for name in sorted(node):
            value = node[name]
            if isinstance(value, dict):
                out.append(f"{pad}enum {camel(name, upper=True)} {{")
                emit(value, depth + 1)
                out.append(f"{pad}}}")
                continue
            key = value
            english = source[key]["other"] if isinstance(source[key], dict) else source[key]
            # One comment line: a line's own line breaks are written as \n.
            out.append(f"{pad}/// {english}".replace("\n", "\\n"))
            names = arguments(key, source)
            if not names:
                out.append(f'{pad}static var {camel(name)}: String {{ Words.line("{key}") }}')
                continue
            params = ", ".join(f"{camel(n)}: {'Int' if is_count(key, source, n) else 'String'}" for n in names)
            owned = "".join(f", {swift_derived(camel(n), form)}" for n, form in derived(key, source, lines))
            if isinstance(source[key], dict):
                rest = "".join(f", {camel(n)}" for n in names[1:]) + owned
                out.append(f'{pad}static func {camel(name)}({params}) -> String {{ Words.count("{key}", count{rest}) }}')
            else:
                args = ", ".join(camel(n) for n in names) + owned
                out.append(f'{pad}static func {camel(name)}({params}) -> String {{ Words.line("{key}", {args}) }}')

    emit(tree, 1)
    out += ["",
            "    /// A line by its key, with its values.",
            "    static func line(_ key: String, _ values: String...) -> String {",
            "        filled(key, values)",
            "    }",
            "",
            "    /// A count's line: the plural form its number takes in the app's language, else `other`.",
            "    /// The number is the line's first value, written as the app's language writes it.",
            "    static func count(_ key: String, _ count: Int, _ values: String...) -> String {",
            "        let form = \"\\(key)|\\(pluralForm(count))\"",
            "        let known = bundle.localizedString(forKey: form, value: nil, table: nil) != form",
            "        return filled(known ? form : \"\\(key)|other\", [number(count)] + values)",
            "    }",
            "",
            "    /// The languages the app's screens are written in, as `languages.json` ships them: what it",
            "    /// asks the servers for, never a language only Siri's phrases are in.",
            "    static let languages = [" + ", ".join(f'"{lang}"' for lang in shipped) + "]",
            "",
            "    /// The catalog the lines are read from: the system's pick for the app, or the language picked",
            "    /// in the app (`AppLanguage.choose`), so a pick applies at once, with no relaunch.",
            "    nonisolated(unsafe) static var bundle = Bundle.main",
            "",
            "    /// The language the app's lines are in, as the catalog names it, never the device's region.",
            f"    static var locale: Locale {{ Locale(identifier: bundle.localizedString(forKey: \"{IOS_LANGUAGE_KEY}\", value: \"en\", table: nil)) }}",
            "",
            "    /// A count's number as the app's language writes it: 1,000, 1.000, 1 000.",
            "    static func number(_ n: Int) -> String {",
            "        let formatter = NumberFormatter()",
            "        formatter.numberStyle = .decimal",
            "        formatter.locale = locale",
            "        return formatter.string(from: NSNumber(value: n)) ?? String(n)",
            "    }",
            "",
            "    /// Names joined as the lines' language joins them: \"Refunds and Bills\", \"A, B, and C\".",
            "    static func list(_ names: [String]) -> String {",
            "        let formatter = ListFormatter()",
            "        formatter.locale = locale",
            "        return formatter.string(from: names) ?? names.joined(separator: \", \")",
            "    }",
            "",
            "    /// A number's ordinal as the lines' language writes it: 21st, 21., 21e.",
            "    static func ordinal(_ n: Int) -> String {",
            "        let formatter = NumberFormatter()",
            "        formatter.numberStyle = .ordinal",
            "        formatter.locale = locale",
            "        return formatter.string(from: NSNumber(value: n)) ?? String(n)",
            "    }",
            "",
            "    /// A name's possessive in the lines' language (app/strings/generate.py, POSSESSIVE): \"Refunds'\",",
            "    /// \"Rae's\". A language with no rule keeps the name: its lines word around it.",
            "    static func possessive(_ name: String) -> String {",
            "        guard let last = name.lowercased().last else { return name }",
            "        switch locale.language.languageCode?.identifier ?? \"en\" {"]
    for lang, (ends, after, other) in POSSESSIVE.items():
        out.append(f'        case "{lang}": return name + ("{ends}".contains(last) ? "{after}" : "{other}")')
    out += ["        default: return name",
            "        }",
            "    }",
            "",
            "    /// \"This name\" in the lines' language (app/strings/generate.py, THIS): \"ce Mac\", \"cet appareil\";",
            "    /// `capital` opens a sentence. A language with no rule keeps the name: its lines write their own word.",
            "    static func demonstrative(_ name: String, capital: Bool = false) -> String {",
            "        guard let first = name.lowercased().first else { return name }",
            "        let word: String",
            "        switch locale.language.languageCode?.identifier ?? \"en\" {"]
    for lang, (starts, before, other) in THIS.items():
        out.append(f'        case "{lang}": word = "{starts}".contains(first) ? "{before}" : "{other}"')
    out += ["        default: return name",
            "        }",
            "        return (capital ? word.prefix(1).uppercased() + word.dropFirst() : word) + name",
            "    }",
            "",
            "    /// The plural form a number takes in the app's language: one, few, many, other…",
            "    static func pluralForm(_ count: Int) -> String {",
            f"        let format = bundle.localizedString(forKey: \"{IOS_PLURAL_KEY}\", value: nil, table: nil)",
            "        let picked = String(format: format, locale: locale, count)",
            "        return picked.split(separator: \"|\").last.map(String.init) ?? \"other\"",
            "    }",
            "",
            "    private static func filled(_ key: String, _ values: [String]) -> String {",
            "        let format = bundle.localizedString(forKey: key, value: nil, table: nil)",
            "        return values.isEmpty ? format : String(format: format, locale: locale, arguments: isolated(values, in: locale))",
            "    }",
            "",
            "    /// In a right-to-left language each value is set apart (U+2068 FIRST STRONG ISOLATE … U+2069 POP DIRECTIONAL",
            "    /// ISOLATE), so a taf's or person's name in Latin letters takes its own direction without turning the sentence",
            "    /// around it. Left to right, values are as they are. Inside the isolate a value carries no direction character of",
            "    /// its own (qa-kiff, #1557), so none can close it early, whoever calls.",
            "    static func isolated(_ values: [String], in locale: Locale) -> [String] {",
            "        guard Locale.characterDirection(forLanguage: locale.identifier) == .rightToLeft else { return values }",
            "        return values.map { \"\\u{2068}\\(withoutDirection($0))\\u{2069}\" }",
            "    }",
            "",
            "    /// The text with U+202A–U+202E, U+2066–U+2069, U+200E, U+200F and U+061C taken out, and nothing else changed.",
            "    static func withoutDirection(_ text: String) -> String {",
            "        String(String.UnicodeScalarView(text.unicodeScalars.filter { s in",
            "            !((0x202A...0x202E).contains(s.value) || (0x2066...0x2069).contains(s.value) || [0x200E, 0x200F, 0x061C].contains(s.value))",
            "        }))",
            "    }",
            "}",
            ""]
    return "\n".join(out)


# ---------------------------------------------------------------- the files

def generated(folder=HERE, preview=()):
    """Every generated file: its path and what it holds. `preview`: languages in `all` but not shipped yet, written
    into the iOS catalog for a screenshot run only (ios.yml's `preview` set), never committed; a key they lack reads
    in English, as the catalog falls back. Android's files aren't written then."""
    langs = languages(folder)
    source = load("en", folder)
    lines = load_lines(folder)
    previewed = [lang for lang in preview if lang in langs["all"] and lang not in langs["shipped"]]
    shipped = langs["shipped"] + previewed
    # A previewed language's missing lines are English in its own catalog: a key without a line of its own would
    # otherwise show as the key itself ("record.title"), since the app reads the language's own table.
    for lang in previewed:
        lines[lang] = {**source, **lines[lang]}
    files = {IOS_CATALOG: ios_catalog(source, shipped, lines), IOS_SWIFT: ios_swift(source, lines, shipped),
             IOS_AGES: ios_ages(ages(folder))}
    android = None if preview else android_emitter()
    if android:
        files.update(android.generated(source, lines, langs))
    return files


def android_emitter():
    """Android's emitter (android.py, beside this file), once it's there."""
    if not os.path.exists(os.path.join(HERE, "android.py")):
        return None
    sys.path.insert(0, HERE)
    import android
    return android


def resource_name(key):
    """A key as both apps' tools can name it: its dots as underscores. Two keys must never make
    the same name."""
    return key.replace(".", "_")


def main(argv):
    check = "--check" in argv
    # `--preview ko`: a screenshot run's catalog with an unshipped language in it (never with --check, never committed).
    preview = argv[argv.index("--preview") + 1].split(",") if "--preview" in argv else []
    if preview and check:
        print("--preview writes a screenshot run's catalog; it can't be checked")
        return 2
    unknown = [lang for lang in preview if lang not in languages()["all"]]
    if unknown:
        print("--preview takes languages from languages.json's all:", *unknown)
        return 2
    stale = []
    for path, text in generated(preview=preview).items():
        current = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                current = f.read()
        if current == text:
            continue
        if check:
            stale.append(os.path.relpath(path, REPO))
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            print("wrote", os.path.relpath(path, REPO))
    if stale:
        print("Out of date, run python3 app/strings/generate.py:", *stale, sep="\n  ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
