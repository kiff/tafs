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

PLURAL_FORMS = ("zero", "one", "two", "few", "many", "other")
KEY = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
# A value: `{name}`, or `{name|possessive}` for the name's possessive in the line's language.
PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]*)(\|possessive)?\}")

# How a language makes a name's possessive, for `{name|possessive}`: a name ending in one of these
# letters takes the first suffix, any other the second ("Refunds'", "Rae's"; "Klaus'", "Raes").
# Only languages with such a rule are here; any other writes `{name}` and words the line around it
# ("la Carte de {taf}"). Both apps' generators read this one table.
POSSESSIVE = {"en": ("s", "'", "'s"), "de": ("sßxz", "'", "s")}
SWIFT_WORDS = {"default", "case", "class", "func", "let", "var", "in", "is", "as", "do", "for", "if", "else", "return",
               "self", "true", "false", "nil", "switch", "where", "while", "import", "init", "enum", "struct", "static",
               "protocol", "extension", "operator", "repeat", "throw", "throws", "try", "catch", "guard", "defer",
               "break", "continue", "internal", "private", "public", "open", "super", "Type", "Self", "Any"}


def load(lang, folder=HERE):
    path = os.path.join(folder, f"{lang}.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def languages(folder=HERE):
    with open(os.path.join(folder, "languages.json"), encoding="utf-8") as f:
        return json.load(f)


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


def possessives(key, source, lines=None):
    """The names whose possessive a line's values carry after its own: each name any language
    writes as `{name|possessive}`, English first, then the others, in the order they first appear.
    Every language is given them, in this order, whether or not its own words use them."""
    seen = []
    for line in [source.get(key)] + [lines[lang].get(key) for lang in sorted(lines or {})]:
        for text in forms_of(line) if line is not None else []:
            for m in PLACEHOLDER.finditer(text):
                if m.group(2) and m.group(1) not in seen:
                    seen.append(m.group(1))
    return seen


def possessive(name, lang):
    """A name's possessive by the language's rule (POSSESSIVE); the name as it is in a language
    with none. The apps' own functions are generated from the same table."""
    if lang not in POSSESSIVE or not name:
        return name
    ends, after, other = POSSESSIVE[lang]
    return name + (after if name[-1].lower() in ends else other)


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
           "pl": ["one", "few", "many"]}


def ios_text(text, key, source, owned=()):
    """A line in the catalog's form: each placeholder by its position, so a language may reorder
    them. Every value is text, a count's number too, as the app writes it. A name's possessive
    (`owned`, from `possessives`) comes after the line's own values. A line with no values is
    never formatted (`Words.line` returns it as it is), so its `%` stays as it is."""
    names = arguments(key, source)
    if not names:
        return text
    text = text.replace("%", "%%")

    def position(m):
        at = len(names) + list(owned).index(m.group(1)) if m.group(2) else names.index(m.group(1))
        return f"%{at + 1}$@"
    return PLACEHOLDER.sub(position, text)


def ios_form_key(key, form):
    """A count's entry for one plural form."""
    return f"{key}|{form}"


def ios_catalog(source, shipped, lines):
    def unit(value):
        return {"stringUnit": {"state": "translated", "value": value}}

    strings = {}
    for key in sorted(source):
        owned = possessives(key, source, lines)
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
        lang: {"variations": {"plural": {form: unit(f"%lld|{form}") for form in PLURALS[lang] + ["other"]}}} for lang in shipped}}
    # The language each language's lines are in: its own tag.
    strings[IOS_LANGUAGE_KEY] = {"extractionState": "manual", "localizations": {lang: unit(lang) for lang in shipped}}
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    return json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def camel(part, upper=False):
    words = part.split("_")
    out = (words[0].capitalize() if upper else words[0]) + "".join(w.capitalize() for w in words[1:])
    return f"`{out}`" if out in SWIFT_WORDS else out


def ios_swift(source, lines=None):
    """A typed accessor for each key, nested by the key's parts: Words.NeedsYou.approveOnce. It
    takes a name as it is, and gives the catalog its possessive too where a line wants it."""
    tree = {}
    for key in sorted(source):
        node = tree
        parts = key.split(".")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = key

    out = ["// Generated by app/strings/generate.py from app/strings/en.json. Don't edit: change the",
           "// shared file and run the generator (docs/languages.md, app/strings/README.md).",
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
            owned = "".join(f", Words.possessive({camel(n)})" for n in possessives(key, source, lines))
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
            "        let known = Bundle.main.localizedString(forKey: form, value: nil, table: nil) != form",
            "        return filled(known ? form : \"\\(key)|other\", [number(count)] + values)",
            "    }",
            "",
            "    /// The language the app's lines are in, as the catalog names it, never the device's region.",
            f"    static let locale = Locale(identifier: Bundle.main.localizedString(forKey: \"{IOS_LANGUAGE_KEY}\", value: \"en\", table: nil))",
            "",
            "    /// A count's number as the app's language writes it: 1,000, 1.000, 1 000.",
            "    static func number(_ n: Int) -> String {",
            "        let formatter = NumberFormatter()",
            "        formatter.numberStyle = .decimal",
            "        formatter.locale = locale",
            "        return formatter.string(from: NSNumber(value: n)) ?? String(n)",
            "    }",
            "",
            "    /// Names joined as the lines' language joins them: \"Refunds and Bills\", \"A, B and C\".",
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
            "    /// The plural form a number takes in the app's language: one, few, many, other…",
            "    static func pluralForm(_ count: Int) -> String {",
            f"        let format = Bundle.main.localizedString(forKey: \"{IOS_PLURAL_KEY}\", value: nil, table: nil)",
            "        let picked = String(format: format, locale: locale, count)",
            "        return picked.split(separator: \"|\").last.map(String.init) ?? \"other\"",
            "    }",
            "",
            "    private static func filled(_ key: String, _ values: [String]) -> String {",
            "        let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)",
            "        return values.isEmpty ? format : String(format: format, locale: locale, arguments: values)",
            "    }",
            "}",
            ""]
    return "\n".join(out)


# ---------------------------------------------------------------- the files

def generated(folder=HERE):
    """Every generated file: its path and what it holds."""
    langs = languages(folder)
    source = load("en", folder)
    lines = {lang: load(lang, folder) for lang in langs["all"]}
    shipped = langs["shipped"]
    files = {IOS_CATALOG: ios_catalog(source, shipped, lines), IOS_SWIFT: ios_swift(source, lines)}
    android = android_emitter()
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
    stale = []
    for path, text in generated().items():
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
