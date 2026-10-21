#!/usr/bin/env python3
"""Build, test and verify a local signed bundle with --prepare-only.

Public releases are built by GitHub Actions after PRs merge.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent.parent


def run(*args, **kwargs):
    return subprocess.run(list(args), cwd=ROOT, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--prepare-only', action='store_true', help='Build locally without publishing')
    args = parser.parse_args()
    if not args.prepare_only:
        parser.error('Use --prepare-only for a local bundle; merge a PR for automatic publishing')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip():
        raise SystemExit('Commit source changes before building a release')
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    password = args.password_file.read_text().strip()
    env = dict(os.environ, CAIRN_KEYSTORE_PATH=str(args.keystore.resolve()),
               CAIRN_KEYSTORE_PASSWORD=password, CAIRN_KEY_ALIAS='cairn', CAIRN_KEY_PASSWORD=password)
    gradle = [str(ROOT / 'gradlew.bat')] if os.name == 'nt' else ['bash', str(ROOT / 'gradlew')]
    run(*gradle, 'testDebugUnitTest', 'lintDebug', 'assembleRelease', '--console=plain', '--max-workers=2', env=env)
    output = ROOT / 'build' / 'release-bundle'
    if output.exists(): shutil.rmtree(output)
    run(os.sys.executable, 'scripts/prepare-release.py', 'app/build/outputs/apk/release/app-release.apk',
        '--expected-version', args.version, '--source-commit', source, '--output', str(output))
    if args.prepare_only:
        print(f'Prepared verified signed release in {output}; no upload performed.')
        return


if __name__ == '__main__':
    main()
