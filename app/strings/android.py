"""Android's half of the shared app strings (app/strings/<lang>/<area>.json).

Writes, from the languages' JSON files:
- android/app/src/main/res/values{,-xx}/strings.xml, with <plurals> for counts;
- android/app/src/main/res/xml/locales_config.xml, the languages Android 13+ offers in tafs's settings;
- android/app/src/main/kotlin/bot/tafs/app/model/Words.kt, typed accessors: a missing key, a wrong argument or a wrong name
  fails the Kotlin build;
- the Wear OS watch's own (docs/watch-wear.md): android/wear/src/main/res/values{,-xx}/strings.xml and
  android/wear/src/main/kotlin/bot/tafs/wear/Words.kt, for the keys the watch shows only (WEAR_KEYS).

Nobody edits those files by hand. `generate.py` calls `generated(source, lines, languages)` and writes what it returns, for
the shipped languages only. Run alone (`python app/strings/android.py`), it reads the JSON files itself; `--check` writes
nothing and fails when a file differs from what it would write.
"""

import json
import re
import sys
from pathlib import Path

# The shared rules are generate.py's: the placeholders, a line's values and their order, the possessive table.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate  # noqa: E402

PLACEHOLDER = generate.PLACEHOLDER
KEY = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
FORMS = generate.PLURAL_FORMS
KOTLIN_KEYWORDS = {"as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
                   "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
                   "val", "var", "when", "while"}

RES = "android/app/src/main/res"
WORDS_KT = "android/app/src/main/kotlin/bot/tafs/app/model/Words.kt"
WEAR_RES = "android/wear/src/main/res"
WEAR_WORDS_KT = "android/wear/src/main/kotlin/bot/tafs/wear/Words.kt"
AGES_KT = "android/app/src/main/kotlin/bot/tafs/app/model/MinimumAge.kt"
# The watch's lines: its own area, and the phone's lines it shows as they are, so a fix to one is a fix to both.
WEAR_AREAS = ("watch",)
WEAR_KEYS = ("alert.needs_you", "alert.not_refused", "coach.ask", "coach.title", "coach.start", "chat.delete", "chat.stop", "chat.message_taf", "chat.taf_is_answering", "taf.pause", "home.title", "settings.title", "taf.halt.title", "common.cancel", "common.close", "common.done", "common.loading", "common.send", "common.try_again", "halt.all_now", "halt_all.title", "home.your_tafs", "taf.state.halted", "halt.now", "needs_you.nothing", "needs_you.refuse", "needs_you.title", "card.its_card", "chat.waiting_for_you", "halt.explain.halted", "halt.explain.running_all", "halt.explain.running_one", "common.ok", "calendar.today", "chat.day.yesterday", "calendar.previous_day", "calendar.next_day", "calendar.reading", "calendar.times", "settings.plan", "settings.version", "settings.language_title", "settings.sign_out", "sign_in.with_phone")


def wear_subset(lines):
    """The lines the watch carries, of one language's (or English's) lines."""
    return {k: v for k, v in lines.items() if k.split(".", 1)[0] in WEAR_AREAS or k in WEAR_KEYS}


def res_name(key):
    """`needs_you.approve_once` → `needs_you_approve_once`, as aapt2 would name it in R anyway."""
    return key.replace(".", "_")


def folder(lang):
    """A language's resource folder: `values-pt`; a regional variant takes Android's region form, `values-pt-rBR`."""
    if lang == "en":
        return "values"
    base, _, region = lang.partition("-")
    return f"values-{base}-r{region}" if region else f"values-{base}"


def values(key, source, lines):
    """A line's values, in the order every language and both apps give them (generate.py): its names, a count's number
    first, then each derived value some language writes, `{name|possessive}`, `{name|this}` or `{name|This}`."""
    return generate.arguments(key, source), generate.derived(key, source, lines)


def escape(text, formatted):
    """A line as aapt2 reads it back exactly: escapes, a literal % doubled only where the line is formatted."""
    if formatted:
        text = text.replace("%", "%%")
    out = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    out = out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if out.startswith("@") or out.startswith("?"):
        out = "\\" + out
    # aapt2 collapses runs of spaces and trims the ends of a line outside quotes: a line that has them is quoted.
    if text != text.strip(" ") or "  " in text:
        out = f'"{out}"'
    return out


def android_text(text, base, derived, formatted):
    """A line with each value by its position: `{name}` the name's, a derived value (`{name|possessive}`, `{name|this}`)
    after the names. Every value is text, a count's number too, written in the lines' language (`Res.number`)."""
    out = escape(text, formatted)
    return PLACEHOLDER.sub(lambda m: f"%{generate.position(m, base, derived) + 1}$s", out)


def strings_xml(lang, words, english, all_lines):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- Generated by app/strings from app/strings/" + lang + "/. Don't edit: change the JSON and generate. -->",
             "<resources>",
             # The language these lines are in, as Android resolves them: a possessive and a number follow it, never the
             # phone's own setting, which may be a language tafs doesn't ship.
             f'    <string name="words_language" translatable="false">{lang}</string>']
    for key in sorted(words):
        value = words[key]
        base, derived = values(key, english, all_lines)
        name = res_name(key)
        if isinstance(value, dict):
            lines.append(f'    <plurals name="{name}">')
            for form in FORMS:
                if form in value:
                    lines.append(f'        <item quantity="{form}">{android_text(value[form], base, derived, True)}</item>')
            lines.append("    </plurals>")
        else:
            lines.append(f'    <string name="{name}">{android_text(value, base, derived, bool(base or derived))}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def locales_xml(langs):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- Generated by app/strings: the languages tafs offers in Android 13+'s per-app language setting. -->",
             '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">']
    lines += [f'    <locale android:name="{lang}" />' for lang in langs]
    lines.append("</locale-config>")
    return "\n".join(lines) + "\n"


def camel(name, upper):
    parts = name.split("_")
    head = parts[0].capitalize() if upper else parts[0]
    out = head + "".join(p.capitalize() for p in parts[1:])
    return f"`{out}`" if out in KOTLIN_KEYWORDS else out


def possessive_kt():
    """`Words.possessive`, from generate.py's POSSESSIVE table, by the lines' language."""
    out = ["  /** A name's possessive in the lines' language (app/strings/generate.py's POSSESSIVE); as it is in one with no rule. */",
           "  fun possessive(name: String): String = if (name.isEmpty()) name else when (Res.language) {"]
    for lang, (ends, after, other) in generate.POSSESSIVE.items():
        out.append(f'    "{lang}" -> name + if (name.last().lowercaseChar() in "{ends}") "{after}" else "{other}"')
    out += ["    else -> name", "  }", "",
            "  /** \"This name\" in the lines' language (app/strings/generate.py's THIS): \"ce Mac\", \"cet appareil\"; `capital` opens",
            "   * a sentence. As it is in a language with no rule: its lines write their own word. */",
            "  fun demonstrative(name: String, capital: Boolean = false): String {",
            "    if (name.isEmpty()) return name",
            "    val word = when (Res.language) {"]
    for lang, (starts, before, other) in generate.THIS.items():
        out.append(f'      "{lang}" -> if (name.first().lowercaseChar() in "{starts}") "{before}" else "{other}"')
    out += ["      else -> return name", "    }",
            "    return (if (capital) word.replaceFirstChar { it.uppercaseChar() } else word) + name", "  }"]
    return out


def words_kt(english, all_lines, package="bot.tafs.app.model", r_class="bot.tafs.app.R",
             about="Every line the app shows"):
    tree = {}
    for key in sorted(english):
        *path, leaf = key.split(".")
        node = tree
        for seg in path:
            child = node.setdefault(seg, {})
            if not isinstance(child, dict):
                raise SystemExit(f"{key}: {'.'.join(path)} is a line and an area")
            node = child
        if leaf in node:
            raise SystemExit(f"{key}: is a line and an area")
        node[leaf] = key
    for taken in ("possessive", "demonstrative"):
        if taken in tree:
            raise SystemExit(f"{taken} is Words.{taken} on Android: no area may take the name")

    out = ["// Generated by app/strings from app/strings/en/. Don't edit: change the JSON and generate.",
           "@file:Suppress(\"ClassName\", \"unused\")", "",
           f"package {package}", "",
           f"import {r_class}", "",
           f"/** {about}, by its shared key (docs/languages.md), in the app's language through [Res]. */"]

    def emit(node, name, depth):
        pad = "  " * depth
        out.append(f"{pad}object {name} {{")
        for seg in sorted(node):
            child = node[seg]
            if isinstance(child, dict):
                emit(child, camel(seg, True), depth + 1)
                continue
            key, value = child, english[child]
            r = res_name(key)
            member = camel(seg, False)
            base, derived = values(key, english, all_lines)
            ipad = pad + "  "
            counted = isinstance(value, dict)
            params = ", ".join(f"{camel(a, False)}: {'Int' if counted and a == 'count' else 'String'}" for a in base)
            call = ", ".join(["Res.number(count)" if counted and a == "count" else camel(a, False) for a in base] +
                             [f"Words.possessive({camel(a, False)})" if form == "possessive" else
                              f"Words.demonstrative({camel(a, False)}{', capital = true' if form == 'This' else ''})"
                              for a, form in derived])
            if counted:
                out.append(f"{ipad}fun {member}({params}): String = Res.p(R.plurals.{r}, count, {call})")
            elif base:
                out.append(f"{ipad}fun {member}({params}): String = Res.s(R.string.{r}, {call})")
            else:
                out.append(f"{ipad}val {member}: String get() = Res.s(R.string.{r})")
        out.append(f"{pad}}}")

    emit(tree, "Words", 0)
    out[-1:-1] = [""] + possessive_kt()
    return "\n".join(out) + "\n"


def check_keys(english):
    seen = {}
    for key in english:
        if not KEY.match(key):
            raise SystemExit(f"{key}: keys are area.meaning, lower case with underscores")
        r = res_name(key)
        if r in seen:
            raise SystemExit(f"{key} and {seen[r]} are both R.{r} on Android")
        seen[r] = key


REPO = Path(__file__).resolve().parent.parent.parent


def ages_kt(table):
    """MinimumAge.kt: legal-i18n's table (ages.json), so the welcome says the person's country's age, as iOS's MinimumAge."""
    rows = ", ".join(f'"{country}" to {age}' for country, age in sorted(table.items()) if country != "default")
    return chr(10).join([
        "// Generated by app/strings/generate.py from app/strings/ages.json. Don't edit by hand.",
        "package bot.tafs.app.model",
        "",
        "/** The minimum age to use the app, by country (ISO 3166-1): legal-i18n's table. Any other country, or none known, is [STANDARD]. */",
        "object MinimumAge {",
        f"  const val STANDARD = {table['default']}",
        f"  val byCountry: Map<String, Int> = mapOf({rows})",
        "",
        "  /** The age for a country code as the device's region gives it (\"IN\"), or [STANDARD]. */",
        "  fun of(country: String?): Int = country?.let { byCountry[it.uppercase()] } ?: STANDARD",
        "}",
        "",
    ])


def generated(source, lines, languages):
    """generate.py's hook: Android's files for the shipped languages, by absolute path."""
    check_keys(source)
    shipped = languages["shipped"]
    files = {}
    for lang in shipped:
        files[str(REPO / RES / folder(lang) / "strings.xml")] = strings_xml(lang, lines[lang], source, lines)
    files[str(REPO / RES / "xml" / "locales_config.xml")] = locales_xml(shipped)
    files[str(REPO / WORDS_KT)] = words_kt(source, lines)
    ages = Path(__file__).resolve().parent / "ages.json"
    if ages.exists():
        files[str(REPO / AGES_KT)] = ages_kt(json.loads(ages.read_text(encoding="utf-8")))
    # A key in WEAR_KEYS that isn't here is left out, and the watch's build fails where it's used; the strings tests also
    # check that every one is in app/strings/en/.
    for lang in shipped:
        files[str(REPO / WEAR_RES / folder(lang) / "strings.xml")] = strings_xml(lang, wear_subset(lines[lang]), source, lines)
    files[str(REPO / WEAR_WORDS_KT)] = words_kt(wear_subset(source), lines, package="bot.tafs.wear", r_class="bot.tafs.wear.R",
                                                about="Every line the watch shows")
    return files


def main(argv):
    """Alone, before generate.py is here: reads the JSON files itself and writes, or with --check compares."""
    here = Path(__file__).resolve().parent
    langs_file = here / "languages.json"
    langs = json.loads(langs_file.read_text(encoding="utf-8")) if langs_file.exists() else {"all": ["en"], "shipped": ["en"]}
    def read(lang):
        # One file per feature (<lang>/<area>.json), or the language's one file before the split. As generate.load: a key in
        # another feature's file or in two files, or an old <lang>.json left beside <lang>/, is refused.
        split = here / lang
        if split.is_dir() and (here / f"{lang}.json").exists():
            raise SystemExit(f"app/strings/{lang}.json is beside {lang}/: remove it")
        files = sorted(split.glob("*.json")) if split.is_dir() else [here / f"{lang}.json"]
        out = {}
        for f in files:
            if not f.exists():
                continue
            for key, value in json.loads(f.read_text(encoding="utf-8")).items():
                if split.is_dir() and key.split(".", 1)[0] != f.stem:
                    raise SystemExit(f"{f.relative_to(here)}: {key} belongs in {key.split('.', 1)[0]}.json")
                if key in out:
                    raise SystemExit(f"{f.relative_to(here)}: {key} is in two files")
                out[key] = value
        return dict(sorted(out.items()))
    lines = {lang: read(lang) for lang in langs["all"] if (here / lang).is_dir() or (here / f"{lang}.json").exists()}
    # A regional variant: its base's lines with its own on top (generate.load_lines).
    for variant, base in langs.get("variants", {}).items():
        lines[variant] = {**lines[base], **(read(variant) if (here / variant).is_dir() else {})}
    stale = []
    for path, text in generated(lines["en"], lines, langs).items():
        p = Path(path)
        # A checkout may turn line ends into CRLF: the words are the same.
        current = p.read_text(encoding="utf-8").replace("\r\n", "\n") if p.exists() else None
        if current == text:
            continue
        stale.append(path)
        if "--check" not in argv:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8", newline="\n")
            print("wrote " + path)
    if "--check" in argv and stale:
        print("Not generated: " + ", ".join(stale) + ". Run python app/strings/android.py.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
