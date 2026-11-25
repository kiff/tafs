#!/usr/bin/env python3
"""Packages the extension for the Chrome Web Store: python3 chrome/store/package.py [--check] [--out DIR]

Writes tafs-chrome-<version>.zip, the extension's files with manifest.json at the root, and prints what it would upload:
the file name, size and SHA-256, the file count, and the SHA-256 of manifest.json in the two forms planque's allowlist
knows (64 lower-case hex, which is what the extension sends). It uploads nothing and needs no key.

The zip is the same bytes every time for the same files (sorted names, fixed times and modes), so a second build can be
compared with the first. `--check` only checks: that every file the manifest names, and every file the extension's own
modules import, is in the package, and that no test, store paper or package.json is.
"""
import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # chrome/
# What goes in: the extension itself. What stays out: its tests, this folder's paper and the npm file.
INCLUDE_DIRS = ("_locales", "icons", "sidepanel", "src")
EXCLUDE_NAMES = {".DS_Store", "package.json", "package-lock.json"}
IMPORT = re.compile(r"""(?:from|import)\s*\(?\s*["'](\.{1,2}/[^"']+)["']""")


def files(root=ROOT):
    """The package's files, relative to the extension's folder, sorted."""
    found = ["manifest.json"]
    for d in INCLUDE_DIRS:
        base = root / d
        if base.is_dir():
            found += [str(p.relative_to(root)) for p in base.rglob("*") if p.is_file() and p.name not in EXCLUDE_NAMES]
    return sorted(set(found))


def referenced(root=ROOT):
    """(file, where it is named) for each file the manifest names and each relative import of a script in the package."""
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    named = [(manifest["background"]["service_worker"], "background.service_worker"), (manifest["side_panel"]["default_path"], "side_panel.default_path")]
    for key in ("icons",):
        named += [(v, key) for v in manifest.get(key, {}).values()]
    named += [(v, "action.default_icon") for v in manifest.get("action", {}).get("default_icon", {}).values()]
    if manifest.get("default_locale"):
        named.append((f"_locales/{manifest['default_locale']}/messages.json", "default_locale"))
    found = []
    for rel in files(root):
        if not rel.endswith((".js", ".mjs")):
            continue
        text = (root / rel).read_text(encoding="utf-8")
        for target in IMPORT.findall(text):
            found.append((str((Path(rel).parent / target).as_posix()), rel))
    # An html page's scripts.
    for rel in files(root):
        if rel.endswith(".html"):
            for src in re.findall(r"""<script[^>]*\ssrc=["']([^"']+)["']""", (root / rel).read_text(encoding="utf-8")):
                found.append((str((Path(rel).parent / src).as_posix()), rel))
    return named + found


def normal(path):
    out = []
    for part in Path(path).parts:
        if part == "..":
            if out:
                out.pop()
        elif part != ".":
            out.append(part)
    return "/".join(out)


def problems(root=ROOT):
    """Everything wrong with the package: a file named or imported that isn't in it, or one that must not be."""
    have = set(files(root))
    bad = []
    for target, where in referenced(root):
        if normal(target) not in have:
            bad.append(f"{where} names {normal(target)}, which is not in the package")
    for name in have:
        if name.startswith(("test/", "e2e/", "store/")) or Path(name).name in EXCLUDE_NAMES:
            bad.append(f"{name} must not be in the package")
    return sorted(set(bad))


def build(out_dir, root=ROOT):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    out = Path(out_dir) / f"tafs-chrome-{manifest['version']}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in files(root):
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, (root / rel).read_bytes())
    return out


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="only check the package's files")
    ap.add_argument("--out", default="dist", help="where the zip is written (default: dist)")
    args = ap.parse_args(argv)
    bad = problems()
    for line in bad:
        print("problem:", line, file=sys.stderr)
    if bad:
        return 1
    if args.check:
        print(f"ok: {len(files())} files, nothing missing, nothing that shouldn't be there")
        return 0
    out = build(args.out)
    manifest = (ROOT / "manifest.json").read_bytes()
    print(f"package        {out.name}")
    print(f"size           {out.stat().st_size} bytes, {len(files())} files")
    print(f"zip sha256     {sha256(out.read_bytes())}")
    print(f"manifest.json  sha256 {sha256(manifest)} (the form planque's allowlist keeps)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
