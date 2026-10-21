#!/usr/bin/env python3
"""Verify the actual signed APK and create the updater's release contract."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def tool(sdk, name):
    suffix = '.bat' if os.name == 'nt' and name == 'apksigner' else '.exe' if os.name == 'nt' else ''
    path = sdk / 'build-tools' / '35.0.0' / (name + suffix)
    if not path.is_file():
        raise SystemExit(f'Missing Android Build Tools 35.0.0: {path}')
    return str(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, default=Path('dist'))
    parser.add_argument('--expected-version', required=True)
    parser.add_argument('--source-commit')
    args = parser.parse_args()
    source_commit = args.source_commit or subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', source_commit):
        raise SystemExit('Expected a full source commit SHA')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    if not sdk.is_dir():
        raise SystemExit('Set ANDROID_HOME to the Android SDK directory')
    badging = subprocess.check_output([tool(sdk, 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not minimum:
        raise SystemExit('Cannot read APK identity/version')
    application_id, code, version = package.groups()
    expected = args.expected_version.removeprefix('v')
    if application_id != 'app.cairn.trek' or version != expected or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise SystemExit('APK package/version does not match the requested Cairn release')
    if "application-debuggable" in badging:
        raise SystemExit('Refusing to publish a debuggable APK')
    signatures = subprocess.check_output(
        [tool(sdk, 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], text=True)
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signatures)
    trusted = (Path(__file__).resolve().parent.parent / 'release-signing.sha256').read_text().strip()
    if len(signers) != 1 or signers[0].lower() != trusted:
        raise SystemExit('APK does not use the pinned Cairn release signing certificate')
    name = f'Cairn-{version}.apk'
    data = args.apk.read_bytes()
    manifest = dict(schema=1, sourceCommit=source_commit, applicationId=application_id, versionName=version,
                    versionCode=int(code), minSdk=int(minimum[1]), apkName=name,
                    size=len(data), sha256=hashlib.sha256(data).hexdigest())
    if len(data) > 150 * 1024 * 1024:
        raise SystemExit('APK exceeds updater size limit')
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, args.output / name)
    (args.output / 'update.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (args.output / 'SHA256SUMS').write_text(manifest['sha256'] + '  ' + name + '\n')
    (args.output / 'release-notes.md').write_text(
        f'Cairn {version}\n\n'
        'Install this APK for the first release, then use Routes → App updates for future versions. '
        'Checks and downloads happen only when requested. Android asks you to confirm installation.\n\n'
        'The first 0.5.0 install uses a new release signing key. If Android reports a signature '
        'conflict with an older development build, remove that development build first. '
        'Uninstalling removes its local data. Future releases reuse the release key.\n')
    print(f'Verified {name}; versionCode {code}; certificate {trusted}; sha256 {manifest["sha256"]}')


if __name__ == '__main__':
    main()
