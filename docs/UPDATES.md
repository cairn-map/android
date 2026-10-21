# Updating Cairn on a phone

The free option for sideloaded builds is [Obtainium](https://github.com/ImranR98/Obtainium). It monitors a release source, notifies you when a version changes, and downloads the APK. Automatic installation depends on the Android version, permissions and installer ownership; an Android install confirmation may still be needed.

## Current state

This repository has no published releases or APK publishing workflow. Cairn has no update checker, installer permission or background update task. The UI change does not enable automatic updates. This repository is private, so a release client also needs GitHub authentication with access to it.

## Set up release-based updates

1. Preserve the signing keystore used for the APK already installed on the phone. The original builds use a development certificate; a newly generated debug key on a build server will not match it. Keep signing material outside the source tree, and use encrypted CI secrets if publishing through CI.
2. Publish versioned GitHub releases containing one signed Cairn APK. Keep `applicationId` as `app.cairn.trek`, increase `versionCode` for each update, and sign with the same certificate each time. A workflow can run tests/lint, assemble and sign the APK, then attach it to a release. GitHub Actions artifacts alone are not GitHub release assets.
3. Install Obtainium once. Add `https://github.com/dayblox/cairn` as a GitHub source and configure authentication in Obtainium for this private repository. Keep that credential on the phone; do not embed it in Cairn or commit it here. Select the Cairn APK if a release contains multiple files.
4. Allow Obtainium to install apps when Android asks and enable its update checks/notifications. It can then handle finding and downloading subsequent versions. Follow the Android confirmation prompt where required.

If the original signing key is unavailable, do not uninstall the existing app as an update workaround: its local routes, recordings and map downloads would be lost. Export GPX data before planning a deliberate migration; exports do not preserve map packs or every setting.

## Other options

- **Google Play internal testing:** once configured with a developer account and a compatible signing setup, testers can receive updates through Play with auto-update enabled. This requires Play Console setup and its developer registration fee.
- **ADB during development:** `adb install -r path/to/app-debug.apk` replaces a compatible signed build without removing its data. USB debugging or paired wireless debugging must be set up on a computer that can reach the phone. It avoids opening APK downloads on the phone, but is not an over-the-air release channel.
- **An in-app updater:** can download a signed release and invoke Android's installer, but needs hosting, private-repository authentication if applicable, and update/installer handling. It cannot universally promise unattended installation. Obtainium avoids adding this work and background traffic to the hiking app.
