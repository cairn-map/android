"""Select automation tests from a complete Git diff; uncertainty runs the suites."""

from pathlib import PurePosixPath
import subprocess
import sys


CONFIGURATION = {
    "AGENTS.md", "release-signing.sha256", "gradlew", "gradlew.bat",
    "package.json", "package-lock.json", "npm-shrinkwrap.json", "yarn.lock",
    "pnpm-lock.yaml", "pyproject.toml", "uv.lock", "poetry.lock",
    ".python-version", ".node-version", ".nvmrc", ".tool-versions",
    "gradle.properties", "gradle.lockfile", "proguard-rules.pro",
    "consumer-rules.pro", "lint.xml",
}


def relevant(path):
    name = PurePosixPath(path).name
    return (
        path.startswith((".github/", "scripts/", "gradle/"))
        or name in CONFIGURATION
        or name.endswith((".gradle", ".gradle.kts"))
        or (name.startswith("requirements") and name.endswith(".txt"))
    )


def required(base, source="HEAD", cwd=None):
    if not base or base == "0" * 40:
        return True
    try:
        # Disabling rename detection includes both names when a script moves
        # into/out of a documentation or application directory. NUL delimiters
        # handle spaces, newlines and Git's quoted non-ASCII paths correctly.
        result = subprocess.run(
            ["git", "diff", "--no-ext-diff", "--no-renames", "--name-only", "-z",
             base, source, "--"], cwd=cwd, capture_output=True, check=True,
            timeout=15,
        )
        paths = result.stdout.decode("utf-8", "surrogateescape").split("\0")
        return any(relevant(path) for path in paths if path)
    except (OSError, subprocess.SubprocessError):
        print("Cannot determine changed paths; running automation suites.", file=sys.stderr)
        return True


if __name__ == "__main__":
    selected = required(sys.argv[1] if len(sys.argv) > 1 else "")
    print(f"Automation suites required: {selected}", file=sys.stderr)
    print(f"required={str(selected).lower()}")
