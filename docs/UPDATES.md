# Manual in-app updates

Open **Routes → Check for updates**. Cairn fetches the latest stable public GitHub release and its small `update.json` manifest. If the release is newer, choose **Download update**, then **Install downloaded update**. Android confirms installation. If prompted, allow Cairn under Android's Install unknown apps settings, return, and tap Install again.

There are no startup/resume checks, timers, jobs, alarms, update services, automatic downloads, automatic retries or wake locks. Opening/resuming Routes only reads local state. All network work starts from a button press and is canceled when you leave Routes or the app. A canceled download is deleted and never resumed automatically. A verified completed download can be installed later without another network request. Check, download and install are blocked during a hike recording; installation can restart the app and interrupt a recording.

## Release integrity and installation

- Only stable releases from `dayblox/cairn` are considered. GitHub API responses/redirects have timeouts, redirect limits, HTTPS host restrictions and bounded response sizes. No user credentials are stored or sent.
- Release tags and asset metadata must match `update.json`. APK downloads are capped at 150 MiB, checked for exact byte length and SHA-256, and kept in private cache storage.
- Android's package parser must report the expected app ID, version and minimum Android level. The APK must have a strictly newer version code and the same actual signing certificate(s) as the installed app. There is no automatic signing-key rotation or downgrade path.
- Downloaded APKs are reverified locally every time Install is tapped. Android's installer receives a temporary read-only content URI for one fixed APK path. Other files cannot be shared through the provider. Android performs its own install/signature checks and asks for confirmation.
- A permission grant or return from Android settings never triggers a network request. An expired/removed cached download can be downloaded again explicitly.

The first automatically built release after 0.5.0 uses a replacement signing key because the earlier cloud workspace's private backup was unavailable. It requires a one-time uninstall/reinstall; uninstalling removes local app data. Later releases reuse the configured key. The public certificate is pinned in `release-signing.sha256`; never commit or publish the private key or its password.

## Pull requests, merging and releases

Agents must push their changes and file a PR against `main`, as required by `AGENTS.md`. **Android checks** builds the debug APK, runs the Android and release-automation tests, and runs lint. **Merge passing pull requests** runs trusted code from the default branch after those checks succeed. It rechecks the current PR head, skips drafts and changed heads, merges the checked SHA, and removes the merged feature branch when its tip has not advanced. Conflicting PRs and failed checks are not merged.

Every push to `main` starts **Publish signed Cairn update**. Automatic merges made with `GITHUB_TOKEN` do not generate push workflow runs, so the merge workflow explicitly dispatches the release workflow with the merge commit. Both routes build only commits contained in `main`. The merge workflow passes the successful PR check run ID to the release workflow.

### Validation reuse and caching

Superseded PR check runs are canceled when a newer commit is pushed; only the latest checked head can merge. PR checks always execute the debug build, Android tests, automation tests and lint, then upload a small JSON record of the tested merge tree, PR head, workflow attempt and toolchain. A release reuses that validation only when GitHub confirms a successful current attempt of **Android checks** for the merged PR head, the recorded Git tree exactly matches the actual merge, and Java, runner architecture and runner image match. It also verifies the recorded checkout against GitHub's commit tree. Missing, expired, invalid or unavailable evidence, a concurrent rerun, changed source, and manual releases without a check run ID all cause full validation.

Changes under `.github` or `scripts` always receive full validation again after merge. In particular, the first release installing this optimization takes the full path and warms the default-branch caches. Later source-only PRs can reuse their verified checks. Signing, release building, APK identity/version/certificate verification and publication always run; signed APKs and signing credentials are never reused as cache entries.

The shared Android toolchain action caches the pinned SDK platform/build tools separately from Robolectric's Android runtime downloads. The existing Java setup continues to cache Gradle dependencies. These caches contain dependencies, not test results. The standalone Android checks workflow runs on PRs only; direct pushes and manual merges still get their validation in the release workflow, without a second redundant main-push checks run.

The release workflow reads the latest public `update.json`, advances the Android version code and patch version, and passes those values to Gradle. An explicitly newer source version is preserved. Rerunning an already published source commit is a no-op. The workflow verifies the actual APK identity, version and pinned signing certificate, creates a draft release at the source commit, uploads APK/manifest/checksums together, then publishes it as latest. Published versions are never overwritten; a failed draft can be retried.

### Measuring PR-to-release time

Before these optimizations, [PR #4](https://github.com/dayblox/cairn/pull/4) to [Cairn 0.5.1](https://github.com/dayblox/cairn/releases/tag/v0.5.1) took **2 minutes 36 seconds**, including runner queues: PR checks took 72 seconds, automatic merge took 11 seconds, and the release job took 64 seconds. Repeated validation accounted for 38 seconds of the release; signed building/verification and publishing took about 8 seconds combined.

Measure a normal source/documentation PR after default-branch caches have warmed, rather than the CI migration itself. Compare the PR creation timestamp with the release publication timestamp; job durations alone omit runner queues. In the release log, confirm `Reusing successful PR validation run` and that the repeated validation step was skipped only after evidence verification succeeded. The PR must still show successful Android checks, and the signed build, APK verification and publishing steps must all pass. A cache miss, changed validation configuration or unverifiable evidence is expected to take longer and must never bypass validation to meet a timing target.

### One-time signing configuration

GitHub-hosted runners need the persistent release key in encrypted repository Actions secrets:

- `CAIRN_KEYSTORE_BASE64`: base64 of the PKCS12 key matching `release-signing.sha256`.
- `CAIRN_KEYSTORE_PASSWORD`: its store password.
- Optional `CAIRN_KEY_ALIAS` (defaults to `cairn`) and `CAIRN_KEY_PASSWORD` (defaults to the store password).

From a computer with JDK 17, GitHub CLI authenticated with repository secret-management access, the private key and password file, run:

```text
python3 scripts/configure-signing.py --keystore /secure/path/cairn-release.p12 --password-file /secure/path/password.txt
```

The helper checks the public certificate before uploading secrets and passes secret values through stdin. The workflow creates the temporary key with private file permissions, deletes it after use, and never uploads it as an artifact. Keep an offline backup of the key and password. Missing or mismatched secrets fail the release explicitly; no replacement key is generated by CI.

The old `updates` distribution branch is no longer used. `scripts/publish-update.py --prepare-only` remains available for local signing/verification; publishing through that branch is retired. Releases now come from merged source commits on `main`.

## Platform references

- [GitHub release assets](https://docs.github.com/en/rest/releases/assets#get-a-release-asset): public binary downloads, redirects and API behavior.
- [Android installation permissions](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()): per-source permission for installing APKs.
- [Android app signing](https://developer.android.com/studio/publish/app-signing): update signing identity.

The automated checks cover transfer boundaries, cancellation, release validation, signing/identity/version rejection, scoped file sharing and activity lifecycle behavior. A real phone must confirm the platform installer and OEM-specific permission flow; the simulated tests cannot perform a physical-device install.
