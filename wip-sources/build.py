"""Builds app/strings/<lang>/<file>.json from a compact source: python3 build.py <lang> <src> [<src>...]
Source format: a line "@@ <file>" starts a file (app, halt_all…); then one line per key, "<suffix>|<value>", where the
key is "<file>.<suffix>" (or the full key when it doesn't start with the file prefix), and a value starting with {" is
JSON (a count). Every key of the English file must be there exactly once."""
import json, os, sys
REPO = os.environ.get("REPO", "/home/user/wt-batch2")
lang, srcs = sys.argv[1], sys.argv[2:]
out, cur, errs = {}, None, []
for src in srcs:
    for n, line in enumerate(open(src, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("@@ "):
            cur = line[3:].strip()
            out.setdefault(cur, {})
            continue
        suffix, _, value = line.partition("|")
        en = json.load(open(f"{REPO}/app/strings/en/{cur}.json", encoding="utf-8"))
        key = suffix if suffix in en else f"{cur}.{suffix}"
        if key not in en:
            errs.append(f"{src}:{n}: {key} not in en/{cur}.json")
            continue
        if key in out[cur]:
            errs.append(f"{src}:{n}: {key} twice")
        out[cur][key] = json.loads(value) if value.startswith('{"') or value.startswith('"') else value
for f, d in out.items():
    en = json.load(open(f"{REPO}/app/strings/en/{f}.json", encoding="utf-8"))
    missing = [k for k in en if k not in d]
    if missing:
        errs.append(f"{f}: missing {missing}")
        continue
    os.makedirs(f"{REPO}/app/strings/{lang}", exist_ok=True)
    with open(f"{REPO}/app/strings/{lang}/{f}.json", "w", encoding="utf-8") as fh:
        fh.write(json.dumps(dict(sorted(d.items())), ensure_ascii=False, indent=2) + "\n")
print("\n".join(errs) or f"{lang}: wrote {len(out)} files")
sys.exit(1 if errs else 0)
