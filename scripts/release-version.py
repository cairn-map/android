#!/usr/bin/env python3
"""Choose a monotonically increasing public update version after a merge."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
MAX_VERSION_CODE = 2100000000


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise ValueError('Expected a numeric major.minor.patch version')
    return tuple(map(int, value.split('.')))


def next_version(code, name, previous, source):
    version = version_tuple(name)
    if type(code) is not int or not 1 <= code <= MAX_VERSION_CODE:
        raise ValueError('Invalid Android version code')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Expected a full source commit SHA')
    if previous is not None:
        if previous.get('schema') != 1 or previous.get('applicationId') != 'app.cairn.trek':
            raise ValueError('The latest release is not a Cairn update manifest')
        old_version = version_tuple(previous['versionName'])
        old_code = previous['versionCode']
        if type(old_code) is not int or not 1 <= old_code < MAX_VERSION_CODE:
            raise ValueError('Invalid previous Android version code')
        if previous.get('sourceCommit') == source:
            return {'skip': 'true'}
        code = max(code, old_code + 1)
        if version <= old_version:
            version = old_version[:2] + (old_version[2] + 1,)
    return {'skip': 'false', 'version_name': '.'.join(map(str, version)), 'version_code': str(code)}


def is_ancestor(older, newer):
    """Check that the latest published source is included in a proposed release."""
    result = subprocess.run(
        ['git', 'merge-base', '--is-ancestor', older, newer],
        cwd=ROOT, capture_output=True, text=True)
    return result.returncode == 0


def require_forward_release_source(previous, source, ancestor_check=is_ancestor):
    """Block stale manual releases and out-of-order queued release runs."""
    if previous is None:
        return
    old_source = previous.get('sourceCommit')
    if not isinstance(old_source, str) or not re.fullmatch(r'[0-9a-f]{40}', old_source):
        raise ValueError('Invalid previous published release source')
    if old_source != source and not ancestor_check(old_source, source):
        raise ValueError('Release source must contain the latest published source commit')


def latest_manifest(repository):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid GitHub repository')
    response = subprocess.run(['gh', 'api', f'repos/{repository}/releases/latest'],
                              text=True, capture_output=True)
    if response.returncode:
        if '(HTTP 404)' in response.stderr:
            return None
        raise RuntimeError('Could not read the latest release: ' + response.stderr.strip())
    release = json.loads(response.stdout)
    if release.get('draft') or release.get('prerelease'):
        raise ValueError('Expected a published stable release')
    with tempfile.TemporaryDirectory(prefix='cairn-latest-') as directory:
        subprocess.run(['gh', 'release', 'download', release['tag_name'], '--repo', repository,
                        '--pattern', 'update.json', '--dir', directory], check=True)
        previous = json.loads((Path(directory) / 'update.json').read_text())
    if release['tag_name'] != 'v' + previous['versionName']:
        raise ValueError('Latest release tag does not match its update manifest')
    return previous


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository')
    parser.add_argument('--previous', type=Path, help='Use a local update.json instead of GitHub')
    parser.add_argument('--source-commit')
    args = parser.parse_args()
    if args.repository and args.previous:
        parser.error('Choose either --repository or --previous')
    source = args.source_commit or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    gradle = (ROOT / 'app/build.gradle').read_text()
    code = int(re.search(r'\bversionCode\s+(\d+)', gradle)[1])
    name = re.search(r"\bversionName\s+'([^']+)'", gradle)[1]
    previous = json.loads(args.previous.read_text()) if args.previous else latest_manifest(args.repository) if args.repository else None
    require_forward_release_source(previous, source)
    for key, value in next_version(code, name, previous, source).items():
        print(f'{key}={value}')


if __name__ == '__main__':
    main()
