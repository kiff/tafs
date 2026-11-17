#!/usr/bin/env python3
"""No build step that ios/project.yml doesn't declare (docs/mac-app.md, "The download for macOS 13 and later").

The Developer ID workflow's signing job takes the generated Xcode project from a job that ran XcodeGen.
A project or scheme can carry shell scripts that Xcode runs while it builds, in the job that holds
the keys. The extension filter there can't see them, since they live inside generated files. This check
runs in the signing job, from its own checkout, before any key is written. It fails on any script build
phase, build rule, legacy (external build) target or scheme pre- or post-action that isn't in ALLOWED.
project.yml declares none today. Declaring one means adding its name here in the same PR.

    build_phases.py <ios dir>
"""
import pathlib
import re
import sys

# Script build phases project.yml declares, by name. None today.
ALLOWED: frozenset = frozenset()

# What XcodeGen turns into something Xcode runs while building.
DECLARING = re.compile(r"^\s*(preBuildScripts|postCompileScripts|postBuildScripts|buildRules|buildToolPlugins|legacy|preActions|postActions)\s*:", re.M)
SCRIPT_PHASE = re.compile(r"isa = PBXShellScriptBuildPhase;(.*?)\n\t\t\};", re.S)
PHASE_NAME = re.compile(r'^\s*name = "?(.*?)"?;\s*$', re.M)
RUNS = re.compile(r"isa = (PBXBuildRule|PBXLegacyTarget);")


def problems(ios: pathlib.Path) -> list:
    found = []
    for spec in sorted(ios.glob("*.yml")):
        for m in DECLARING.finditer(spec.read_text()):
            found.append(f"{spec.name} declares {m.group(1)}: add what it runs to ALLOWED in build_phases.py first")
    projects = sorted(ios.glob("*.xcodeproj"))
    if not projects:
        found.append("no generated .xcodeproj")
    for project in projects:
        pbx = project / "project.pbxproj"
        text = pbx.read_text() if pbx.exists() else ""
        if not text:
            found.append(f"{project.name} has no project.pbxproj")
        for m in SCRIPT_PHASE.finditer(text):
            name = PHASE_NAME.search(m.group(1))
            name = name.group(1) if name else "(unnamed)"
            if name not in ALLOWED:
                found.append(f"{project.name}: a script build phase project.yml doesn't declare: {name}")
        for m in RUNS.finditer(text):
            found.append(f"{project.name}: a {m.group(1)}, which runs a tool while building")
        for scheme in sorted(project.rglob("*.xcscheme")):
            if "ExecutionAction" in scheme.read_text():
                found.append(f"{scheme.name}: a scheme pre- or post-action")
    return found


def main(argv: list) -> int:
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-1].strip(), file=sys.stderr)
        return 2
    found = problems(pathlib.Path(argv[1]))
    for line in found:
        print(f"::error::{line}")
    if not found:
        print("No build step beyond what project.yml declares.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
