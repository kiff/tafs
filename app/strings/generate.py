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
PLACEHOLDER = re.compile(r"\{([a-z][a-z0-9_]*)\}")
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


def placeholders(line):
    """A line's placeholders, in the order they first appear."""
    seen = []
    for text in ([line["other"]] if isinstance(line, dict) and "other" in line else []) + forms_of(line):
        for name in PLACEHOLDER.findall(text):
            if name not in seen:
                seen.append(name)
    return seen


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

# The plural forms a language's whole numbers take (CLDR), beside "other".
PLURALS = {"en": ["one"], "de": ["one"], "nl": ["one"], "it": ["one"], "es": ["one"], "pt": ["one"], "fr": ["one"],
           "pl": ["one", "few", "many"]}


def ios_text(text, key, source):
    """A line in the catalog's form: each placeholder by its position, so a language may reorder
    them. Every value is text, a count's number too, as the app writes it. A line with no
    values is never formatted (`Words.line` returns it as it is), so its `%` stays as it is."""
    names = arguments(key, source)
    if not names:
        return text
    text = text.replace("%", "%%")
    return PLACEHOLDER.sub(lambda m: f"%{names.index(m.group(1)) + 1}$@", text)


def ios_form_key(key, form):
    """A count's entry for one plural form."""
    return f"{key}|{form}"


def ios_catalog(source, shipped, lines):
    def unit(value):
        return {"stringUnit": {"state": "translated", "value": value}}

    strings = {}
    for key in sorted(source):
        if isinstance(source[key], dict):
            # A count: one entry for each plural form any shipped language has.
            for form in PLURAL_FORMS:
                localizations = {lang: unit(ios_text(lines[lang][key][form], key, source)) for lang in shipped
                                 if isinstance(lines[lang].get(key), dict) and form in lines[lang][key]}
                if localizations:
                    strings[ios_form_key(key, form)] = {"extractionState": "manual", "localizations": localizations}
            continue
        localizations = {lang: unit(ios_text(lines[lang][key], key, source)) for lang in shipped if key in lines[lang]}
        strings[key] = {"extractionState": "manual", "localizations": localizations}
    # Which form a number takes, in each shipped language's own rules.
    strings[IOS_PLURAL_KEY] = {"extractionState": "manual", "localizations": {
        lang: {"variations": {"plural": {form: unit(f"%lld|{form}") for form in PLURALS[lang] + ["other"]}}} for lang in shipped}}
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    return json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def camel(part, upper=False):
    words = part.split("_")
    out = (words[0].capitalize() if upper else words[0]) + "".join(w.capitalize() for w in words[1:])
    return f"`{out}`" if out in SWIFT_WORDS else out


def ios_swift(source):
    """A typed accessor for each key, nested by the key's parts: Words.NeedsYou.approveOnce."""
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
            if isinstance(source[key], dict):
                rest = "".join(f", {camel(n)}" for n in names[1:])
                out.append(f'{pad}static func {camel(name)}({params}) -> String {{ Words.count("{key}", count{rest}) }}')
            else:
                args = ", ".join(camel(n) for n in names)
                out.append(f'{pad}static func {camel(name)}({params}) -> String {{ Words.line("{key}", {args}) }}')

    emit(tree, 1)
    out += ["",
            "    /// A line by its key, with its values.",
            "    static func line(_ key: String, _ values: String...) -> String {",
            "        filled(key, values)",
            "    }",
            "",
            "    /// A count's line: the plural form its number takes in the app's language, else `other`.",
            "    /// The number is the line's first value, written as the app writes a number.",
            "    static func count(_ key: String, _ count: Int, _ values: String...) -> String {",
            "        let form = \"\\(key)|\\(pluralForm(count))\"",
            "        let known = Bundle.main.localizedString(forKey: form, value: nil, table: nil) != form",
            "        return filled(known ? form : \"\\(key)|other\", [String(count)] + values)",
            "    }",
            "",
            "    /// The plural form a number takes in the app's language: one, few, many, other…",
            "    static func pluralForm(_ count: Int) -> String {",
            f"        let format = Bundle.main.localizedString(forKey: \"{IOS_PLURAL_KEY}\", value: nil, table: nil)",
            "        let picked = String(format: format, locale: Locale.current, count)",
            "        return picked.split(separator: \"|\").last.map(String.init) ?? \"other\"",
            "    }",
            "",
            "    private static func filled(_ key: String, _ values: [String]) -> String {",
            "        let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)",
            "        return values.isEmpty ? format : String(format: format, locale: Locale.current, arguments: values)",
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
    files = {IOS_CATALOG: ios_catalog(source, shipped, lines), IOS_SWIFT: ios_swift(source)}
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
