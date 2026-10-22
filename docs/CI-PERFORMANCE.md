# Actions performance audit

Reviewed on 22 October 2026 against `main` at `e5a46de`, including merged PRs
[#5](https://github.com/dayblox/cairn/pull/5),
[#9](https://github.com/dayblox/cairn/pull/9), and
[#10–16](https://github.com/dayblox/cairn/pulls?q=is%3Apr+is%3Amerged).

## Measured baseline

The latest successful [PR checks](https://github.com/dayblox/cairn/actions/runs/39707528817)
took 56 seconds in the job (59 seconds from workflow creation to completion).
The associated [release](https://github.com/dayblox/cairn/actions/runs/39707867073)
took 57 seconds in the job (61 seconds including scheduling and finalization).
Step timestamps are rounded to seconds; nested action timings come from job logs.

| Work | PR checks | Release |
| --- | ---: | ---: |
| Shared Android toolchain | 20 s | 19 s |
| Compile actionlint from source | 12 s | — |
| Debug build, tests and lint | 12 s | 11 s |
| Signed APK build and verification | — | 8 s |
| Publish release | — | 5 s |
| Toolchain post actions | 4 s | 6 s |

Within toolchain setup, restoring the 109 MiB SDK cache failed with permission
errors over existing runner SDK directories (8.9 s in checks, 6.9 s in release).
The cache miss then caused an unnecessary `sdkmanager` invocation (3.3 s / 2.3 s).
Robolectric restored another 354 MiB (2.3 s / 3.1 s). Gradle restore itself was
working and took 3.5–4.4 s; keep it.

The six-minute duration of [PR #14's checks](https://github.com/dayblox/cairn/actions/runs/39703164451)
was mostly runner queueing: the job started at 01:06:26 UTC after workflow
creation at 01:00:52 UTC and ran for only 38 seconds. Removing tests cannot fix
that queue. PR #9's stalled release was canceled; later PRs added step and job
timeouts, so retain those bounds and individually bounded Node suites.

## Changes

- Remove SDK caching. Check for SDK 35 extension 15 and Build Tools 35.0.0 in the
  hosted image, installing only missing packages. This also removes the failed
  restore, cache upload and hundreds of permission-error log lines.
- Use the pinned actionlint 1.7.12 Linux x64 release binary, verifying its archive
  SHA-256 before execution. Downloading a small binary avoids compiling Go on
  every PR; an additional cache action would add overhead for a short download.
- Let `gradlew` select the Gradle distribution. Omit `gradle-version: wrapper`
  from setup-gradle, which otherwise provisions Gradle separately before every
  build. Keep dependency, transform and local build caches enabled.
- Restore Robolectric only in PR checks or release full-validation fallback.
  A release with verified reusable evidence does not need Android test runtimes.
  Keep test-source hashes in the runtime cache key because annotations select
  Android SDK versions; changing a tested SDK must allow saving new runtimes.
- Use `ubuntu-24.04` for both jobs so the moving `ubuntu-latest` alias cannot
  change their OS generation independently. Exact image and Java equality are
  still checked before validation reuse.

## Recent PRs and validation reuse

PR #5 introduced verified proof and caches. PR #9 replaced the privileged merge
workflow with native auto-merge but initially lost automatic proof discovery.
PR #11 restored discovery and Gradle build caching. PR #12 temporarily supported
squash merges; PR #13 deliberately removed that support and selected merge
commits only. PR #14 added generated release notes. PR #15 added pinned workflow
linting, current Actions majors, bounded suites and stale-source release guards.
PR #16 removed the self-approval deadlock for the sole maintainer.

Recent releases after PRs #11–16 all changed CI or automation scripts. Their
full-validation fallback is intentional, not evidence that discovery is broken.
The latest release explicitly logged `CI or validation scripts changed in this
merge`. Preserve that rule and verify the fast path with a subsequent PR changing
only application source or documentation. Its release should log
`Reusing successful PR validation run`, skip both fallback tests and Robolectric
restoration, and still build, sign, verify and publish the APK.

## Repository configuration

Read the live repository settings and active `main` ruleset during this audit:

- Native auto-merge, merge commits and automatic head-branch deletion: enabled.
- Repository squash and rebase merging: disabled.
- Required `checks`, bound to GitHub Actions, with strict up-to-date enforcement:
  enabled. Zero required approvals, no code-owner/last-push approval, resolved
  conversations required, no bypass actors, force pushes and deletion blocked.
- The ruleset itself still lists `merge`, `squash`, and `rebase`, although the
  repository settings already disable the latter two. Align it to `merge` only
  using the existing administrator helper, after reviewing its dry run:
  `python3 scripts/configure_native_automerge.py --dry-run`, then the same command
  without `--dry-run`. The GitHub connector cannot administer repository rulesets.

Do not relax strict checks, skip all required CI on documentation PRs, remove
full history from release checkout, or remove release freshness/version/signature
checks for speed. The stable required job and validation evidence need complete
PR validation; release ancestry checks need history. This small repository's
checkout and freshness checks each took about one second.

Compare job duration separately from queueing and human time. The workflow-changing
optimization PR must itself take full release validation; a later source-only
PR is needed to measure actual reuse. Do not claim a runtime reduction from
estimates alone.

## First measured result

[PR #17](https://github.com/dayblox/cairn/pull/17) passed required Android checks
and merged through native auto-merge. Its
[check job](https://github.com/dayblox/cairn/actions/runs/39708990202) also took
56 seconds overall; individual setup improvements were offset by a slower
Gradle restore and build on that runner.

| Work | Before (#16) | After (#17) |
| --- | ---: | ---: |
| SDK cache restore | 8.9 s, failed | Removed |
| SDK package handling | 3.3 s, sdkmanager | 0.063 s, presence check |
| Workflow lint | 12 s, Go compilation | 2 s, verified binary |
| Gradle cache restore | 3.5 s | 11.4 s |
| Debug build, tests and lint | 12 s | 26 s |
| Complete check job | 56 s | 56 s |

Both builds restored the same 28 Gradle tasks from cache. The first result
confirms removal of roughly 22 seconds of avoidable SDK/linter work, but does
not establish an overall job speedup. This documentation-only follow-up also
exercised validation reuse after the workflow changes, without changing CI or
automation scripts again. [PR #18](https://github.com/dayblox/cairn/pull/18)
completed checks in 38 seconds, but its release still ran fallback validation.
The live smoke test revealed that PR #11 called the nonexistent Octokit method
`listWorkflowRunsForWorkflow`; the test mock repeated that invalid API name.
Octokit's documented method is `github.rest.actions.listWorkflowRuns`.

Correct the method and assert the discovery parameters (workflow, PR event and
merged head) in regression coverage. API or artifact failures should identify
their verification phase while retaining the full-validation fallback. This
fix changes automation scripts, so it also needs full post-merge validation and
a later documentation-only smoke test before claiming working native reuse.

## Checking subsequent releases

After [PR #19](https://github.com/dayblox/cairn/pull/19), which passed required
Android checks in 28 seconds, inspect a source-only release's **Verify reusable
PR validation** step. Successful reuse must identify the PR run with
`Reusing successful PR validation run`, while the Robolectric action and all
three fallback test/build steps are skipped. The signed release build,
certificate/version verification, and publication must still succeed.

If reuse fails, read the reason before changing caching or validation rules.
CI/script changes, different trees/base commits/toolchains, expired proof and
reruns intentionally require fallback. Unexpected API/archive failures now
identify the failed phase, such as `PR run discovery` or `proof download and
parsing`, so investigate that phase instead of removing the guard.

## Selective automation suites

Python and Node suites now run only for automation/workflow, signing-policy or
build-configuration changes. App code, Android tests/resources and ordinary
documentation changes skip them in PR checks and release fallback. The shared
selector compares complete Git trees (including deletions and renamed paths);
release pushes compare against the push's `before` commit, covering every commit
in a multi-commit push. Manual releases and unavailable history run both suites.

The Android build, tests and lint still run on every PR and on release validation
fallback. APK signing and verification always run. Keep Node suite timeouts for
relevant changes; filtering limits exposure to the intermittent hang without
claiming to fix it. The duplicate Node text checks for administrator settings were
removed; Python tests already exercise the actual configuration helper behavior.
