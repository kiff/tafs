"""Checks a translated catalog against app/strings/en: python3 -I check_lang.py <lang> <dir>
Every en file has a twin with the same keys, a text stays a text and a count a count with the language's forms,
and each line names the same placeholders as English (a |possessive or |this modifier may be dropped)."""
import json, os, re, sys

REPO = "/home/user/wt-batch2"
FORMS = {"el": ["one", "other"], "et": ["one", "other"], "lv": ["zero", "one", "other"], "lt": ["one", "few", "other"],
         "cs": ["one", "few", "other"], "sk": ["one", "few", "other"], "sl": ["one", "two", "few", "other"], "hu": ["one", "other"],
         "ro": ["one", "few", "other"], "bg": ["one", "other"], "hr": ["one", "few", "other"]}
lang, folder = sys.argv[1], sys.argv[2]
en_dir = os.path.join(REPO, "app", "strings", "en")
problems = []
names = lambda s: sorted({m.split("|")[0] for m in re.findall(r"\{([^{}]+)\}", s)})
total = 0
for f in sorted(os.listdir(en_dir)):
    en = json.load(open(os.path.join(en_dir, f), encoding="utf-8"))
    path = os.path.join(folder, f)
    if not os.path.exists(path):
        problems.append(f"missing file {f}")
        continue
    try:
        tr = json.load(open(path, encoding="utf-8"))
    except Exception as e:
        problems.append(f"{f}: not JSON: {e}")
        continue
    if list(tr) != sorted(tr):
        problems.append(f"{f}: keys not sorted")
    for k in set(en) - set(tr):
        problems.append(f"{f}: missing {k}")
    for k in set(tr) - set(en):
        problems.append(f"{f}: extra {k}")
    for k, v in en.items():
        if k not in tr:
            continue
        total += 1
        t = tr[k]
        texts = list(t.values()) if isinstance(t, dict) else [t]
        if isinstance(v, dict) != isinstance(t, dict):
            problems.append(f"{k}: English is a {'count' if isinstance(v, dict) else 'text'}, this isn't")
            continue
        if isinstance(t, dict) and sorted(t) != sorted(FORMS[lang]):
            problems.append(f"{k}: forms {sorted(t)}, want {FORMS[lang]}")
        want = set(names(" ".join(v.values()) if isinstance(v, dict) else v))
        for text in texts:
            if not isinstance(text, str) or not text.strip():
                problems.append(f"{k}: empty or not a text")
                continue
            got = set(names(text))
            ok = {"count"} if isinstance(t, dict) else set()
            if not (got <= want and want - got <= ok):
                problems.append(f"{k}: placeholders {sorted(got)}, English has {sorted(want)}")
            if re.search(r"[{}]", re.sub(r"\{[a-z][a-z0-9_]*(\|(possessive|this|This))?\}", "", text)):
                problems.append(f"{k}: stray brace or bad placeholder")
            if "|possessive}" in text or re.search(r"\|[Tt]his\}", text):
                problems.append(f"{k}: no |possessive or |this in this language; word the line around the name")
for p in problems[:200]:
    print(p)
print(f"{lang}: {total} lines checked, {len(problems)} problems")
sys.exit(1 if problems else 0)
