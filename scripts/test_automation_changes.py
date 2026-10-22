from pathlib import Path
import subprocess
import tempfile
import unittest

from automation_changes import relevant, required


class AutomationPathsTest(unittest.TestCase):
    def test_automation_and_build_inputs_require_suites(self):
        for path in [
            ".github/workflows/checks.yml", ".github/actions/toolchain/action.yml",
            "scripts/new-tool.py", "scripts/test-validation-reuse.cjs",
            "build.gradle", "app/build.gradle", "routing-engine/build.gradle",
            "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties", "gradlew", "gradlew.bat",
            "release-signing.sha256", "AGENTS.md", "package-lock.json",
            "requirements-dev.txt", ".node-version", "pyproject.toml",
            "app/gradle.lockfile", "app/proguard-rules.pro", "lint.xml",
        ]:
            with self.subTest(path=path):
                self.assertTrue(relevant(path))

    def test_application_tests_resources_and_docs_skip_suites(self):
        for path in [
            "app/src/main/java/app/cairn/trek/MainActivity.java",
            "app/src/test/java/app/cairn/trek/AppUpdateTest.java",
            "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
            "routing-engine/src/main/java/btools/router/RoutingEngine.java",
            "README.md", "docs/UPDATES.md", "routing-engine/README.md",
        ]:
            with self.subTest(path=path):
                self.assertFalse(relevant(path))


class ChangedTreeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI test")
        self.git("config", "user.email", "ci@example.invalid")
        self.write("scripts/tool.py")
        self.write("docs/README.md")
        self.base = self.commit()

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.root, text=True).strip()

    def write(self, path, content="initial\n"):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def test_application_and_documentation_changes_skip(self):
        self.write("app/src/main/java/Main.java")
        self.write("docs/README.md", "updated\n")
        self.commit()
        self.assertFalse(required(self.base, cwd=self.root))

    def test_renaming_script_into_docs_still_runs_suites(self):
        self.git("mv", "scripts/tool.py", "docs/tool.py")
        self.commit()
        self.assertTrue(required(self.base, cwd=self.root))

    def test_deleted_script_runs_suites(self):
        (self.root / "scripts/tool.py").unlink()
        self.commit()
        self.assertTrue(required(self.base, cwd=self.root))

    def test_long_diffs_and_unusual_filenames_do_not_hide_scripts(self):
        for number in range(350):
            self.write(f"app/src/main/assets/{number}.txt")
        self.write("scripts/spaces and\nnewlines.py")
        self.commit()
        self.assertTrue(required(self.base, cwd=self.root))

    def test_entire_push_is_compared_not_only_the_last_commit(self):
        self.write("scripts/tool.py", "changed\n")
        self.commit()
        self.write("docs/README.md", "later\n")
        self.commit()
        self.assertTrue(required(self.base, cwd=self.root))
        self.assertFalse(required("HEAD^", cwd=self.root))

    def test_manual_initial_or_missing_history_runs_suites(self):
        for base in ["", "0" * 40, "f" * 40]:
            with self.subTest(base=base):
                self.assertTrue(required(base, cwd=self.root))


if __name__ == "__main__":
    unittest.main()
