#!/usr/bin/env python3
"""No build step that ios/project.yml doesn't declare (docs/mac-app.md, "The download for macOS 13 and later").

The Developer ID workflow's signing job takes the generated Xcode project from a job that ran XcodeGen.
A project can make Xcode run code while it builds, in the job that holds the keys. The extension filter
there can't see it, since it lives inside generated files. This check runs in the signing job, from its
own checkout, before any key is written. It reads the project as data (OpenStep, XML or JSON plist, as
Xcode does) and also scans its text, and fails on:

- a script build phase, build rule, external build (legacy) target or aggregate target, anywhere and
  however it's written; a script phase passes only if the SHA-256 of its shellScript is in ALLOWED;
- a build setting that replaces a tool Xcode runs (CC, CPLUSPLUS, LD, LDPLUSPLUS, LIBTOOL, any *_EXEC)
  or loads a compiler plugin, in the project or in any .xcconfig under ios/;
- a Swift package other than PACKAGES, exactly, or any local package;
- a scheme pre- or post-action;
- an ios/*.yml declaring something XcodeGen turns into one of these.

project.yml declares none of these today. Declaring one means adding it here in the same PR.

On a Mac, the workflow also hands it the project as Apple's own `plutil -convert json` reads it, so the
data checked is the data Xcode reads; without it, the project's text is read here.

    build_phases.py <ios dir> [<project.pbxproj as JSON, from plutil>]
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import plistlib
import re
import sys

# SHA-256 of each script phase's shellScript that project.yml declares. None today. Matched by the
# script itself, never by its name, which the generator being checked writes.
ALLOWED: frozenset = frozenset()

# The one package project.yml declares, exactly as it pins it.
PACKAGES = frozenset({("https://github.com/21-DOT-DEV/swift-secp256k1", "exactVersion", "0.24.0")})

# Object types whose presence alone makes Xcode run something while building.
RUNS = ("PBXShellScriptBuildPhase", "PBXBuildRule", "PBXLegacyTarget", "PBXAggregateTarget", "XCLocalSwiftPackageReference")
RUNS_TEXT = re.compile(r"\b(" + "|".join(RUNS) + r")\b")

# Build settings that name a tool Xcode runs, and flags that load a compiler plugin.
TOOL_SETTING = re.compile(r"^(CC|CPLUSPLUS|LD|LDPLUSPLUS|LIBTOOL|[A-Z0-9_]*_EXEC)$")
PLUGIN_FLAG = re.compile(r"-load-plugin|-plugin-path|-fplugin|-fpass-plugin|-Xclang\s+-load\b")
XCCONFIG_LINE = re.compile(r"^\s*([A-Za-z0-9_]+)\s*(?:\[[^\]]*\])*\s*=\s*(.*)$")

# What XcodeGen turns into something Xcode runs while building.
DECLARING = re.compile(r"^\s*(preBuildScripts|postCompileScripts|postBuildScripts|buildRules|buildToolPlugins|legacy|preActions|postActions|aggregateTargets)\s*:", re.M)


class OpenStep:
    """A reader for the OpenStep plist form project.pbxproj is usually written in: dictionaries, arrays,
    quoted or bare strings, <data> and comments. Anything it can't read is an error, never skipped."""

    BARE = re.compile(r"[A-Za-z0-9_$+/:.\-]+")

    def __init__(self, text: str):
        self.text, self.i = text, 0

    def parse(self):
        value = self.value()
        self.space()
        if self.i != len(self.text):
            raise ValueError(f"unexpected text at {self.i}")
        return value

    def space(self):
        while self.i < len(self.text):
            if self.text[self.i].isspace():
                self.i += 1
            elif self.text.startswith("//", self.i):
                end = self.text.find("\n", self.i)
                self.i = len(self.text) if end < 0 else end + 1
            elif self.text.startswith("/*", self.i):
                end = self.text.find("*/", self.i + 2)
                if end < 0:
                    raise ValueError("unclosed comment")
                self.i = end + 2
            else:
                return

    def expect(self, ch: str):
        self.space()
        if self.text[self.i:self.i + 1] != ch:
            raise ValueError(f"expected {ch!r} at {self.i}")
        self.i += 1

    def value(self):
        self.space()
        ch = self.text[self.i:self.i + 1]
        if ch == "{":
            self.i += 1
            d = {}
            while True:
                self.space()
                if self.text[self.i:self.i + 1] == "}":
                    self.i += 1
                    return d
                key = self.string()
                self.expect("=")
                d[key] = self.value()
                self.expect(";")
        if ch == "(":
            self.i += 1
            items = []
            while True:
                self.space()
                if self.text[self.i:self.i + 1] == ")":
                    self.i += 1
                    return items
                items.append(self.value())
                self.space()
                if self.text[self.i:self.i + 1] == ",":
                    self.i += 1
        if ch == "<":
            end = self.text.find(">", self.i)
            if end < 0:
                raise ValueError("unclosed data")
            data, self.i = self.text[self.i + 1:end], end + 1
            return data
        return self.string()

    SIMPLE = {"a": "\a", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t", "v": "\v", '"': '"', "\\": "\\", "'": "'"}

    def escape(self) -> str:
        """One backslash escape in a quoted string, as Xcode writes them: \\Uxxxx (four hex digits), up to
        three octal digits, or a single character. Unknown ones stand for the character itself."""
        nxt = self.text[self.i + 1:self.i + 2]
        if nxt == "U":
            digits = self.text[self.i + 2:self.i + 6]
            if len(digits) == 4 and all(c in "0123456789abcdefABCDEF" for c in digits):
                self.i += 6
                return chr(int(digits, 16))
            raise ValueError(f"a \\U escape needs four hex digits at {self.i}")
        if nxt in "01234567" and nxt:
            j = self.i + 1
            while j < self.i + 4 and self.text[j:j + 1] in tuple("01234567"):
                j += 1
            value, self.i = int(self.text[self.i + 1:j], 8), j
            return chr(value)
        if not nxt:
            raise ValueError("a string ends in a backslash")
        self.i += 2
        return self.SIMPLE.get(nxt, nxt)

    def string(self) -> str:
        self.space()
        if self.text[self.i:self.i + 1] == '"':
            out, self.i = [], self.i + 1
            while self.i < len(self.text):
                ch = self.text[self.i]
                if ch == '"':
                    self.i += 1
                    # \U escapes can spell a surrogate pair; joined, they're the one character they name.
                    return "".join(out).encode("utf-16", "surrogatepass").decode("utf-16")
                if ch == "\\":
                    out.append(self.escape())
                    continue
                out.append(ch)
                self.i += 1
            raise ValueError("unclosed string")
        m = self.BARE.match(self.text, self.i)
        if not m:
            raise ValueError(f"expected a string at {self.i}")
        self.i = m.end()
        return m.group(0)


def read_plist(text: str):
    """The project as data, in whichever of its forms Xcode reads."""
    stripped = text.lstrip()
    if stripped.startswith("<?xml") or stripped.startswith("<plist"):
        return plistlib.loads(text.encode())
    try:
        return json.loads(text)
    except ValueError:
        return OpenStep(text).parse()


def setting_problems(where: str, key: str, value) -> list:
    name = re.sub(r"\[.*$", "", key)
    found = []
    if TOOL_SETTING.match(name):
        found.append(f"{where}: {key} replaces a tool Xcode runs")
    words = " ".join(value) if isinstance(value, list) else str(value)
    if PLUGIN_FLAG.search(words):
        found.append(f"{where}: {key} loads a compiler plugin")
    return found


def project_problems(name: str, text: str, data: str | None = None) -> list:
    # Found in the text, however it's written (quoted, compact, XML, JSON, or only in a comment).
    found = [f"{name}: a {kind}, which runs something while building"
             for kind in sorted({m.group(1) for m in RUNS_TEXT.finditer(text)}) if kind != "PBXShellScriptBuildPhase"]
    try:
        plist = json.loads(data) if data is not None else read_plist(text)
        objects = plist["objects"]
        assert isinstance(objects, dict)
    except Exception as e:  # noqa: BLE001 - anything unreadable fails the check
        return found + [f"{name}: can't be read as a project ({e})"]
    packages, shell_phases = set(), 0
    for oid, obj in objects.items():
        if not isinstance(obj, dict):
            continue
        isa = obj.get("isa")
        if isa == "PBXShellScriptBuildPhase":
            shell_phases += 1
            digest = hashlib.sha256(str(obj.get("shellScript", "")).encode()).hexdigest()
            if digest not in ALLOWED:
                found.append(f"{name}: a script build phase project.yml doesn't declare: {obj.get('name', oid)} (sha256 {digest[:16]})")
        elif isa == "XCRemoteSwiftPackageReference":
            req = obj.get("requirement") or {}
            packages.add((obj.get("repositoryURL"), req.get("kind"), req.get("version")))
        settings = obj.get("buildSettings")
        if isinstance(settings, dict):
            for key, value in settings.items():
                found += setting_problems(f"{name} {obj.get('name', oid)}", key, value)
    # A script phase is judged by its script, from the data. One the text names but the data doesn't show is
    # hidden from that judgement, so it fails.
    if re.search(r"\bPBXShellScriptBuildPhase\b", text) and not shell_phases:
        found.append(f"{name}: a PBXShellScriptBuildPhase in its text that its data doesn't show")
    if packages != PACKAGES:
        found.append(f"{name}: Swift packages {sorted(map(str, packages))}, not project.yml's {sorted(map(str, PACKAGES))}")
    return found


def problems(ios: pathlib.Path, data: pathlib.Path | None = None) -> list:
    found = []
    for spec in sorted(ios.glob("*.yml")):
        for m in DECLARING.finditer(spec.read_text()):
            found.append(f"{spec.name} declares {m.group(1)}: add what it runs to build_phases.py first")
    for xcconfig in sorted(ios.rglob("*.xcconfig")):
        for line in xcconfig.read_text().splitlines():
            m = XCCONFIG_LINE.match(line)
            if m:
                found += setting_problems(str(xcconfig.relative_to(ios)), m.group(1), m.group(2))
    projects = sorted(ios.glob("*.xcodeproj"))
    if data is not None and len(projects) != 1:
        found.append(f"one project expected beside its JSON, found {len(projects)}")
    if not projects:
        found.append("no generated .xcodeproj")
    for project in projects:
        pbx = project / "project.pbxproj"
        text = pbx.read_text() if pbx.exists() else ""
        if not text:
            found.append(f"{project.name} has no project.pbxproj")
        else:
            found += project_problems(project.name, text, data.read_text() if data is not None else None)
        for scheme in sorted(project.rglob("*.xcscheme")):
            if "ExecutionAction" in scheme.read_text():
                found.append(f"{scheme.name}: a scheme pre- or post-action")
    return found


def main(argv: list) -> int:
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[-1].strip(), file=sys.stderr)
        return 2
    found = problems(pathlib.Path(argv[1]), pathlib.Path(argv[2]) if len(argv) == 3 else None)
    for line in found:
        print(f"::error::{line}")
    if not found:
        print("No build step beyond what project.yml declares.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
