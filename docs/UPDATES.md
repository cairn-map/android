# Manual in-app updates

Open **Routes → App updates → Check**. Cairn fetches the latest stable public release's `update.json` manifest through GitHub's public release download URL, without GitHub REST API calls or authentication. If the release is newer, choose **Download update**, then **Install downloaded update**. Android confirms installation. If prompted, allow Cairn under Android's Install unknown apps settings, return, and tap Install again.

There are no startup/resume checks, timers, jobs, alarms, update services, automatic downloads, automatic retries or wake locks. Opening/resuming Routes only reads local state. All network work starts from a button press and is canceled when you leave Routes or the app. A canceled download is deleted and never resumed automatically. A verified completed download can be installed later without another network request. After a successful installation, returning to Routes recognizes the installed version and discards the obsolete APK and manifest, hiding Install/Discard. Canceling Android's installer keeps a still-newer APK available for retry. Check, download and install are blocked during a hike recording; installation can restart the app and interrupt a recording.

## Release integrity and installation

- Only the latest stable public release from `dayblox/cairn` is considered. Public `/releases/latest/download/update.json` and versioned APK asset URLs avoid anonymous REST API quotas. Requests/redirects have timeouts, redirect limits, strict HTTPS host/path restrictions and bounded response sizes. 401/403/429 errors distinguish access denial from rate limiting using available response headers. No user credentials are stored or sent.
- The update manifest specifies the versioned APK download path; public GitHub release assets and local APK metadata/signature checks validate the artifact independently. APK downloads are capped at 150 MiB, checked for exact byte length and SHA-256, and kept in private cache storage.
- Android's package parser must report the expected app ID, version and minimum Android level. The APK must have a strictly newer version code and the same actual signing certificate(s) as the installed app. There is no automatic signing-key rotation or downgrade path.
- Downloaded APKs are reverified locally every time Install is tapped. Android's installer receives a temporary read-only content URI for one fixed APK path. Other files cannot be shared through the provider. Android performs its own install/signature checks and asks for confirmation.
- A permission grant or return from Android settings never triggers a network request. An expired/removed cached download can be downloaded again explicitly.

The first automatically built release after 0.5.0 uses a replacement signing key because the earlier cloud workspace's private backup was unavailable. It requires a one-time uninstall/reinstall; uninstalling removes local app data. Later releases reuse the configured key. The public certificate is pinned in `release-signing.sha256`; never commit or publish the private key or its password.

## Pull requests, merging and releases

Agents must push their changes and file a PR against `main`, as required by `AGENTS.md`. **Android checks** runs the debug build, tests and lint; the job's GitHub status context is `checks`. The `main` ruleset requires this status to pass and the branch to be up to date before merging. Open a non-draft PR and explicitly enable GitHub's **native auto-merge** using **merge commits only** (for example, `gh pr merge --auto --merge`). In repository **Settings → General → Pull Requests**, keep **Allow merge commits** and **Allow auto-merge** checked, and uncheck **Allow squash merging** and **Allow rebase merging**; this also makes ChatGPT's automatic merge-method selection deterministic. Draft PRs, failed checks, unresolved review conversations and outdated heads must not auto-merge. Native auto-merge is not enabled globally for every PR merely by switching on the repository setting.

A native GitHub merge pushes a commit to `main`, triggering the existing **Publish signed Cairn update** workflow normally. No privileged `workflow_run` merge Action or manual workflow dispatch is needed. GitHub's **Automatically delete head branches** setting handles cleanup. The release workflow discovers the successful PR run from a native two-parent merge's second parent. It requires an identical checked merge tree, base commit, and runtime before reusing the proof. Single-parent squash, rebase, or direct pushes cannot reuse PR proof and must undergo full validation. When proof is missing, stale, ambiguous, or invalid, it safely performs full validation; signing and APK verification always run.

### Solo-maintainer native auto-merge configuration

Cairn currently has one maintainer, @dayblox. **Do not enable mandatory approving reviews or "Require review from Code Owners"**. GitHub does not allow PR authors to approve their own PRs; doing so would block routine owner-authored changes. No second account or fake approval is needed. The CODEOWNERS policy was removed for this reason. Use strict automated checks, merge commits only, mandatory resolved conversations and no bypass actors as the merge gate.

To verify or update this directly on GitHub as @dayblox:

1. Open **Settings → Rules → Rulesets → main** in this repository; make sure enforcement is **Active** and the target includes `main`.
2. Under **Require a pull request before merging**, keep **Required approvals: 0**; turn **Require review from Code Owners**, **Require approval of the most recent reviewable push**, and **Dismiss stale pull request approvals** off. Keep **Require conversation resolution before merging** on. Select **merge commits only** for the allowed merge methods.
3. Under **Require status checks to pass**, keep `checks` required and **Require branches to be up to date before merging** on.
4. Keep **Block force pushes** and **Restrict deletions** enabled, with no bypass actors.
5. Under **Settings → General → Pull Requests**, keep **Allow merge commits**, **Allow auto-merge** and **Automatically delete head branches** enabled. Disable squash and rebase merging.
6. Save the ruleset if anything changed. You do not need to enable auto-merge on each PR from this settings page; use the PR-specific **Enable auto-merge** option after verifying the proposed changes.

After the solo-maintainer fix PR merges, you can alternatively apply the same settings with GitHub CLI authenticated as @dayblox. From a local checkout:

```sh
gh auth login
python3 scripts/configure_native_automerge.py --dry-run
python3 scripts/configure_native_automerge.py
```

The helper preserves the existing `main` ruleset's other protections while enforcing strict `checks`, resolved conversations, zero review approvals, no code-owner gate, merge commits only, and no bypass. It requires GitHub repository administration permission. Its dry run is read-only.

For security-sensitive changes to `.github`, release/signing scripts, or build infrastructure, the solo maintainer should inspect the full PR diff before enabling auto-merge. For changes that merit an explicit release decision, leave auto-merge disabled and merge normally after required checks pass. This manual step is a process safeguard, not a GitHub-enforced independent review. Routine PRs can continue using native auto-merge after successful CI.

### Validation reuse and caching

Superseded PR check runs are canceled when a newer commit is pushed; only the latest checked head can merge. PR checks always execute the debug build, Android tests and lint, plus automation suites for relevant changes, then upload a small JSON record of the tested merge tree, PR head, workflow attempt and toolchain. A release accepts an optional check run ID, or discovers the unique successful PR check matching the second parent of a native two-parent merge. Both paths independently verify the current **Android checks** attempt, identical checked merge base and Git tree, and Java, runner architecture and runner image. Single-parent commits always trigger Android full validation. It also verifies the recorded checkout against GitHub's commit tree. Missing, expired, invalid or unavailable evidence, a concurrent rerun, or changed source causes Android full validation; automation suites remain selected by changed paths.

Python and Node automation suites run when `.github/`, `scripts/`, Gradle configuration or wrapper files, signing policy, agent instructions, or Python/Node dependency and runtime configuration changes. Ordinary app source, Android tests/resources and documentation changes skip those suites. `scripts/automation_changes.py` compares the PR base with the checked merge, or the entire main push using its `before` commit; deletions and both sides of renames are included without API file-list limits. Unknown/missing history, initial pushes and manual dispatch run both suites conservatively. Thus a runner-image mismatch still triggers Android validation but does not rerun unchanged automation on an app-only push. Required `checks`, workflow lint, signing and APK verification retain their existing gates.

Changes under `.github` or `scripts` always receive full validation again after merge. In particular, the first release installing this optimization takes the full path and warms the default-branch caches. Later source-only PRs can reuse their verified checks. Signing, release building, APK identity/version/certificate verification and publication always run; signed APKs and signing credentials are never reused as cache entries.

The shared Android toolchain uses the SDK packages already installed on `ubuntu-24.04`, installing only missing packages instead of restoring a cache over runner-owned SDK files. Robolectric runtimes are cached separately and restored only when tests are needed; a release reusing PR evidence skips them. The official Gradle setup action caches dependencies, transforms, and Gradle's local build cache, with the default branch writing shared entries and PRs restoring them; the wrapper selects the distribution without a separate Gradle installation. Android test results are never trusted from a cache as a substitute for validation evidence. The standalone Android checks workflow runs on PRs only; direct pushes and manual merges still get their validation in the release workflow, without a second redundant main-push checks run. See [the Actions performance audit](CI-PERFORMANCE.md) for measured timings and repository configuration findings.

Before checking out a historical manual-dispatch commit, the release workflow uses the current `main` script to require that the proposed source includes the latest published release source. It repeats the check before versioning, rejecting stale manual sources and out-of-order queued runs. The release workflow reads the latest public `update.json`, advances the Android version code and patch version, and passes those values to Gradle. An explicitly newer source version is preserved. Rerunning an already published source commit is a no-op. The workflow verifies the actual APK identity, version and pinned signing certificate, creates a draft release at the source commit, uploads APK/manifest/checksums together, then publishes it as latest. Published versions are never overwritten; a failed draft can be retried.

### Changelog in GitHub Releases

Each new GitHub Release shows **GitHub-generated release notes**, listing changes since the previous release (including merged pull request titles and contributors), rather than repeating app-installation instructions. The publishing job generates the changelog for the specific source commit before creating its draft, and refreshes the notes when recovering an existing draft. Failure to generate non-empty notes blocks publication. Write clear, user-facing PR titles because they become release-note entries.

Installation and in-app update instructions live in this document and the README, not in GitHub Release descriptions. Previously published release descriptions are left unchanged.

### CI reliability and validation reuse smoke test

Node test suites are executed individually with a 90-second limit per suite and a four-minute step bound, making hangs identifiable. Required PR CI runs the pinned `actionlint` release binary after checking its archive SHA-256, avoiding a Go compilation on every run. Existing unit tests cover native merge validation reuse; after these CI/script changes merge, validate the real optimized path by merging a **source-only PR** that does not change `.github` or `scripts`. The associated release must log `Reusing successful PR validation run`, skip fallback test steps, and still sign and verify the APK. If evidence cannot be verified, fallback testing is mandatory.

### Measuring PR-to-release time

Before these optimizations, [PR #4](https://github.com/dayblox/cairn/pull/4) to [Cairn 0.5.1](https://github.com/dayblox/cairn/releases/tag/v0.5.1) took **2 minutes 36 seconds**, including runner queues: PR checks took 72 seconds, automatic merge took 11 seconds, and the release job took 64 seconds. Repeated validation accounted for 38 seconds of the release; signed building/verification and publishing took about 8 seconds combined.

After native auto-merge, compare PR creation with release publication to measure end-to-end time. In the release log, look for `Reusing successful PR validation run`; if verification fails or CI/scripts changed, expect a full validation fallback. Builds are bounded by step/job timeouts and Python/Node/Gradle checks have separate log sections. The PR must show a successful required `checks` status and the signed build, APK verification and publication must pass. Optimization must never bypass validation to meet a timing target.

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
