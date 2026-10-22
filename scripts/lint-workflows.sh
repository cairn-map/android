#!/usr/bin/env bash
set -euo pipefail

# Use the release binary instead of downloading a Go toolchain and compiling it
# on every runner. Keep the version and archive digest pinned together.
directory="$(mktemp -d)"
trap 'rm -rf "$directory"' EXIT
archive="$directory/actionlint.tar.gz"
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz \
  --output "$archive"
printf '%s  %s\n' '8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8' "$archive" | sha256sum --check --status
tar -xzf "$archive" -C "$directory" actionlint
"$directory/actionlint" "$@"
