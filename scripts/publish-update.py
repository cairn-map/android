#!/usr/bin/env python3
"""Build/sign/test locally and publish a prepared bundle through the updates branch.

The private key stays on the build computer. GitHub Actions only receives the signed APK.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


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
    branch = subprocess.check_output(['git', 'branch', '--show-current'], cwd=ROOT, text=True).strip()
    if not branch: raise SystemExit('Publish from a named source branch')
    run('git', 'push', 'origin', f'HEAD:refs/heads/{branch}')
    existing = subprocess.check_output(['git', 'ls-remote', '--heads', 'origin', 'updates'], cwd=ROOT, text=True).strip()
    with tempfile.TemporaryDirectory(prefix='cairn-publish-') as directory:
        channel = Path(directory)
        subprocess.run(['git', 'init', '-b', 'updates', str(channel)], check=True, capture_output=True)
        remote = subprocess.check_output(['git', 'remote', 'get-url', 'origin'], cwd=ROOT, text=True).strip()
        subprocess.run(['git', '-C', str(channel), 'remote', 'add', 'origin', remote], check=True)
        if existing:
            subprocess.run(['git', '-C', str(channel), 'fetch', '--depth=1', 'origin', 'updates'], check=True)
            subprocess.run(['git', '-C', str(channel), 'reset', '--hard', 'FETCH_HEAD'], check=True)
            old = json.loads((channel / 'update.json').read_text())
            new = json.loads((output / 'update.json').read_text())
            if old['versionCode'] >= new['versionCode']:
                raise SystemExit('Increase versionCode and versionName before publishing another release')
            for apk in channel.glob('Cairn-*.apk'): apk.unlink()
        for path in output.iterdir(): shutil.copyfile(path, channel / path.name)
        for relative in ['.github/workflows/release.yml', 'scripts/prepare-release.py', 'release-signing.sha256']:
            target = channel / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        for key in ['user.name', 'user.email']:
            value = subprocess.check_output(['git', 'config', '--get', key], cwd=ROOT, text=True).strip()
            subprocess.run(['git', '-C', str(channel), 'config', key, value], check=True)
        subprocess.run(['git', '-C', str(channel), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(channel), 'commit', '-m', f'Publish signed Cairn {args.version}'], check=True)
        subprocess.run(['git', '-C', str(channel), 'push', 'origin', 'HEAD:refs/heads/updates'], check=True)
    print('Signed bundle published. The Publish signed Cairn update workflow will create the GitHub release.')


if __name__ == '__main__':
    main()
