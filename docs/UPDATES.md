# Manual in-app updates

Open **Routes → App updates → Check for updates**. Cairn fetches the latest stable public GitHub release and its small `update.json` manifest. If the release is newer, choose **Download update**, then **Install downloaded update**. Android confirms installation. If prompted, allow Cairn under Android's Install unknown apps settings, return, and tap Install again.

There are no startup/resume checks, timers, jobs, alarms, update services, automatic downloads, automatic retries or wake locks. Opening/resuming the update screen only reads local state. All network work starts from a button press and is canceled when that screen stops. A canceled download is deleted and never resumed automatically. A verified completed download can be installed later without another network request. Check, download and install are blocked during a hike recording; installation can restart the app and interrupt a recording.

## Release integrity and installation

- Only stable releases from `dayblox/cairn` are considered. GitHub API responses/redirects have timeouts, redirect limits, HTTPS host restrictions and bounded response sizes. No user credentials are stored or sent.
- Release tags and asset metadata must match `update.json`. APK downloads are capped at 150 MiB, checked for exact byte length and SHA-256, and kept in private cache storage.
- Android's package parser must report the expected app ID, version and minimum Android level. The APK must have a strictly newer version code and the same actual signing certificate(s) as the installed app. There is no automatic signing-key rotation or downgrade path.
- Downloaded APKs are reverified locally every time Install is tapped. Android's installer receives a temporary read-only content URI for one fixed APK path. Other files cannot be shared through the provider. Android performs its own install/signature checks and asks for confirmation.
- A permission grant or return from Android settings never triggers a network request. An expired/removed cached download can be downloaded again explicitly.

Version 0.5.0 uses a new release key as requested. An older prototype must be removed before that first installation; uninstalling erases its local data. After this one-time switch, keep the same release key for every update. The public certificate's SHA-256 fingerprint is pinned in `release-signing.sha256`; the private key/password must never enter Git, the APK, logs or public release assets.

## Publishing future updates

The release key stays on the build computer. GitHub Actions receives only an already-signed APK, verifies its certificate and metadata again, and publishes the public GitHub release. No signing secrets or personal GitHub token are needed in Actions.

1. Increase both `versionCode` and `versionName` in `app/build.gradle`, then commit the source changes.
2. On the build computer, with JDK 17, the pinned Android SDK packages, Git write access and the private release key available, run:

   ```text
   python3 scripts/publish-update.py --version 0.6.0 --keystore /secure/path/cairn-release.p12 --password-file /secure/path/password.txt
   ```

   Substitute the next version and the private file paths. The key alias is `cairn`; the PKCS12 key and store share the password in that file. Use `--prepare-only` to build and verify without uploading.
3. The script requires a clean source checkout, runs all unit tests and Android lint, builds/signs the release APK, and checks its actual identity/version/certificate. It pushes the source commit, then commits the verified APK, metadata and publishing workflow to the separate `updates` branch. Versions must increase; published versions are never overwritten.
4. The **Publish signed Cairn update** workflow verifies the uploaded bundle and creates a draft release tagged at the source commit. It uploads the APK, manifest and checksum list before publishing the release as latest. If upload fails, the draft stays invisible to the updater. A failed run can be retried while the release remains a draft; a published release cannot be replaced by this workflow.

`updates` is a distribution branch; develop and review source changes on `main` and feature branches. Each new bundle removes the prior APK from that branch's current tree; historical releases remain available in GitHub Releases. Git still retains previous commits. GitHub Actions must remain enabled with permission for this workflow to write repository contents.

Keep an offline backup of the release key and its password. Losing that key prevents compatible in-place updates; the repository's certificate fingerprint alone cannot recreate it.

## Platform references

- [GitHub release assets](https://docs.github.com/en/rest/releases/assets#get-a-release-asset): public binary downloads, redirects and API behavior.
- [Android installation permissions](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()): per-source permission for installing APKs.
- [Android app signing](https://developer.android.com/studio/publish/app-signing): update signing identity.

The automated checks cover transfer boundaries, cancellation, release validation, signing/identity/version rejection, scoped file sharing and activity lifecycle behavior. A real phone must confirm the platform installer and OEM-specific permission flow; the simulated tests cannot perform a physical-device install.
