"""The English still written in the iPhone and iPad app's code, outside app/strings.

Every line the app shows reads through `Words` (README.md). This scans `ios/Tafs` for string
literals that read as English and are not data: each literal is judged on its own, so one that is
compared (`== "declined"`) is data while the words beside it on the same line are not. What must
stay in code is kept by a rule below, or by name in ios_kept.json, with the reason.

    python3 app/strings/ios_literals.py      lists what is left, as file:line: literal
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SOURCES = os.path.join(REPO, "ios", "Tafs")
KEPT = os.path.join(HERE, "ios_kept.json")

# Whole files that stay in code, and why.
KEPT_FILES = {
    "Design/Words.swift": "the generated accessors",
    "Design/AppRoutes.swift": "the generated routes (app/routes.py): paths and line keys, not words",
    "Services/KnobSample.swift": "the knob's server-shaped sample",
    "Drive/": "the drive's lines, which only Coach reads",
}

# Just before a literal: it is data. A comparison, a pattern, a subscript's key, a path, an identifier.
DATA_BEFORE = re.compile(
    r"(==|!=|\bcase|hasPrefix\(|hasSuffix\(|contains\(|range\(of:|forKey:|forHTTPHeaderField:|(?<=[\w)\]?!])\[|systemName:|systemImage:"
    r"|named:|identifier:|accessibilityIdentifier\(|Logger\(|subsystem:|category:|withExtension:|forResource:|ofType:"
    r"|rawValue:|URL\(string:|Data\(|keyPath:|#selector\(|\.id\(|Image\(|label:|agentLabel:|heldID:|debugDescription:)\s*$")
# Just after a literal: it is data. Compared, or sent as bytes.
DATA_AFTER = re.compile(r"^\s*(==|!=|\.utf8|\.data\()")


def literals(text):
    """Each single-line string literal, with the literals inside its interpolations, as (start, end, body)."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif text[i] == "#" and i + 1 < n and text[i + 1] in '"#':
            k = i
            while k < n and text[k] == "#":
                k += 1
            if k < n and text[k] == '"':
                close = '"' + "#" * (k - i)
                j = text.find(close, k + 1)
                i = n if j < 0 else j + len(close)
            else:
                i = k
        elif text[i] == '"':
            end = _string_end(text, i)
            if end is None:
                i += 1
                continue
            out.append((i, end, text[i + 1:end - 1]))
            body_start = i + 1
            for kind, part, at in _parts(text[i + 1:end - 1]):
                if kind == "expr":
                    out += [(body_start + at + s, body_start + at + e, b) for s, e, b in literals(part)]
            i = end
        else:
            i += 1
    return out


def _string_end(text, i):
    j, n = i + 1, len(text)
    while j < n:
        c = text[j]
        if c == "\n":
            return None
        if c == "\\":
            if j + 1 < n and text[j + 1] == "(":
                j = _interp_end(text, j + 2)
                if j is None:
                    return None
                continue
            j += 2
            continue
        if c == '"':
            return j + 1
        j += 1
    return None


def _interp_end(text, j):
    depth, n = 1, len(text)
    while j < n:
        c = text[j]
        if c == "\n":
            return None
        if c == '"':
            e = _string_end(text, j)
            if e is None:
                return None
            j = e
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return None


def _parts(body):
    """A literal's body as text and interpolations: (kind, part, offset of the part in the body)."""
    out, buf, start, j, n = [], "", 0, 0, len(body)
    while j < n:
        if body[j] == "\\" and j + 1 < n and body[j + 1] == "(":
            end = _interp_end(body, j + 2) or n
            if buf:
                out.append(("text", buf, start))
            out.append(("expr", body[j + 2:end - 1], j + 2))
            buf, j, start = "", end, end
            continue
        if not buf:
            start = j
        if body[j] == "\\" and j + 1 < n:
            buf += body[j:j + 2]
            j += 2
            continue
        buf += body[j]
        j += 1
    if buf:
        out.append(("text", buf, start))
    return out


def text_of(body):
    return "".join(p for k, p, _ in _parts(body) if k == "text")


def english(body):
    """Whether a literal reads as words: a word of three letters or more, and not an id, a key,
    a path, a STATE or a camelCase name. Words beside a value are always words."""
    text = re.sub(r'\\[nt"\\]', " ", text_of(body))
    if not any(len(w) >= 3 for w in re.findall(r"[A-Za-z][A-Za-z'’]+", text)):
        return False
    # A value with words beside it is a line made in code ("\\(name) door"), whatever the words.
    if " " in text.strip(" ") or (any(k == "expr" for k, _, _ in _parts(body)) and " " in text):
        return True
    s = text.strip()
    return not (re.fullmatch(r"[a-z0-9_.\-/:]+", s) or re.fullmatch(r"[A-Z0-9_]+", s) or re.fullmatch(r"[a-z]+([A-Z][a-z0-9]+)+", s))


def kept_by_rule(text, before, after):
    """Why a literal stays in code, by its kind, or None."""
    if DATA_BEFORE.search(before) or DATA_AFTER.match(after):
        return "data: compared, a key, an id, or sent to a server"
    words = text_of(text)
    if "|" in words and " " not in words.replace(" · ", ""):
        return "a key or a signed message"
    if re.match(r"^[/?&]", words) or re.match(r"^[\w.]+=", words):
        return "a URL or a query"
    if words.startswith("Bearer "):
        return "a header"
    if "@" in words and " " not in words:
        return "a sample address"
    if words.startswith("CFBundle"):
        return "a bundle key"
    if re.fullmatch(r"[A-Z][A-Za-z]*(-[A-Z][A-Za-z]*)+", words):
        return "a header's name"
    if words.startswith('\\"'):
        return "JSON sent to a server"
    return None


def scan(sources=SOURCES, kept_path=KEPT):
    """The English literals left in code: [(file, line, body)]."""
    kept = json.load(open(kept_path, encoding="utf-8")) if kept_path and os.path.exists(kept_path) else {}
    left = []
    for root, _, files in os.walk(sources):
        for name in sorted(files):
            if not name.endswith(".swift"):
                continue
            path = os.path.join(root, name)
            rel = os.path.relpath(path, sources).replace(os.sep, "/")
            if any(rel == f or (f.endswith("/") and rel.startswith(f)) for f in KEPT_FILES):
                continue
            text = open(path, encoding="utf-8").read()
            for start, end, body in literals(text):
                if not english(body):
                    continue
                line_start = text.rfind("\n", 0, start) + 1
                if text[line_start:start].lstrip().startswith("//"):
                    continue
                if kept_by_rule(body, text[line_start:start], text[end:end + 40]):
                    continue
                if body in kept.get(rel, {}):
                    continue
                left.append((rel, text.count("\n", 0, start) + 1, body))
    return sorted(set(left))


if __name__ == "__main__":
    rows = scan()
    for f, n, b in rows:
        print(f"{f}:{n}: {b}")
    sys.exit(1 if rows else 0)
