#!/usr/bin/env python3
"""Upload the existing release key to Actions secrets without printing credentials."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='cairn-map/android')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--alias', default='cairn')
    args = parser.parse_args()
    password = args.password_file.read_text().strip()
    if not password:
        raise SystemExit('The password file is empty')
    keytool = str(Path(os.environ['JAVA_HOME']) / 'bin/keytool') if os.environ.get('JAVA_HOME') else 'keytool'
    certificate = subprocess.check_output([keytool, '-exportcert', '-keystore', str(args.keystore),
        '-alias', args.alias, '-storepass:file', str(args.password_file)])
    if hashlib.sha256(certificate).hexdigest() != (ROOT / 'release-signing.sha256').read_text().strip():
        raise SystemExit('The key does not match release-signing.sha256; no secrets were uploaded')
    values = dict(CAIRN_KEYSTORE_BASE64=base64.b64encode(args.keystore.read_bytes()).decode(),
                  CAIRN_KEYSTORE_PASSWORD=password, CAIRN_KEY_ALIAS=args.alias, CAIRN_KEY_PASSWORD=password)
    for name, value in values.items():
        subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repository], input=value,
                       text=True, check=True)
    print('Configured Cairn signing secrets. Keep an offline backup of the key and password.')


if __name__ == '__main__':
    main()
