# Virtual power and rendering performance

Performance validation runs entirely in GitHub Actions, virtual Android tests and native Canvas
benchmarks. No physical phones, field trials, manual battery baselines or competitor installations
are required. This is the owner's explicit scope. These results measure software work and CPU time;
they do not represent mAh, endurance or a measured hardware suspend percentage.

## What runs on every PR

The required `checks` job in **Android checks** builds/tests/lints, then runs:

```sh
python3 scripts/virtual_power.py --previous PREVIOUS_MAIN_SHA --current PR_HEAD_SHA
```

The runner exports isolated Git trees, injects the identical versioned test harness into both,
and runs real Cairn activity/service/location/SQLite code under Robolectric Android 28 with native
Canvas rendering. The comparison uses the PR's actual base/head SHA, not `HEAD^`, so multiple-commit
PRs and merge commits remain correct. The current build/test/lint also validates the merged source.
The benchmark harness itself is tested and reviewed as code. It does not mock out Cairn's recorder.

Each revision executes an hour of virtual time for **18 scenarios**:

- GPS intervals **1, 5, 20, 30, 60 and 300 seconds**.
- Screen on, screen off, and screen checks for 15 seconds every five minutes.
- Timestamped simulated GPS fixes, clock advancement, due callback execution, activity pause/resume
  and complete recording/stop cleanup. There is no one-hour wall-clock wait.

It measures persisted fixes, recording notification posts, requested GPS cadence, active providers,
subscriptions after stop, queued recorder messages and adversarial hidden draw/tile probes. Visible
probes keep the requested workload present: losing the recorder or suppressing visible drawing
cannot earn a lower-work result. Hidden probes deliberately attempt a late draw after pause; they
measure whether app code suppresses that request, not physical display frames.

A lexical guard also rejects recorder timer/wake-lock scheduling sites. This is a conservative
source check, not a runtime CPU wakeup counter. The worker queue check measures pending work after
recording callbacks drain. Native Canvas lifecycle/cancellation/gesture tests complement the matrix.

A separate warmed microbenchmark renders fixed **1,000- and 10,000-point routes** at 128x128 pixels.
It measures current-thread CPU time using the JVM CPU clock, with 20 warmup frames and seven batches
of five frames. It retains median and p95 batch CPU time per draw. This measures route projection/
Canvas work on the Actions host, not device frame latency or an emulator's fake battery percentage.

## Baseline, previous and current

`scripts/performance/baseline.json` is a versioned engineering objective, not fabricated measurement:

| Metric | Baseline budget |
| --- | --- |
| Hidden draw probes that paint / hidden tile reads | 0 |
| Pending recorder work after callbacks drain | 0 |
| Extra network providers / GPS subscriptions after stop | 0 |
| Recorder timer/wake-lock scheduling sites | 0 |
| GPS request interval | Exactly the selected interval |
| GPS subscriptions while recording | Exactly 1 |
| Saved points | Every injected fix |
| Recording notification updates | At most one per max(60s, selected interval) |
| 1k-point native render CPU median | <=10 ms per draw |
| 10k-point native render CPU median | <=50 ms per draw |

Work counts have no regression allowance: current must meet baseline and be no worse than previous
main. Saved fixes/request cadence/visible workload must remain correct. CPU time is noisy even on
the same runner: current median must meet its fixed maximum and be no more than **previous median
x 1.30 + 1 ms**. This explicitly defined noise margin avoids claiming sub-millisecond differences
are reliable. A failed CPU comparison fails the required action and blocks merging; investigate or rerun, do not silently
raise budgets. p95 and raw samples remain diagnostic evidence rather than a hardware FPS guarantee.

The same harness SHA-256, protocol, SDK, complete matrix and immutable revisions are required.
Missing scenarios, malformed/non-finite metrics, mismatched workloads, failed execution or timeouts
fail closed. An incomplete attempt replaces any stale report with a blocked verdict. CPU budgets
and the workload require owner review before being weakened; never reset a baseline to hide a loss.

OruxMaps, Locus Map and OsmAnd remain qualitative design references for interval-based recording
and low background work. They are **not measured competitors in this suite** and do not require
APK access or phone trials. The practical target is no app work between necessary location/user
events, minimum visible rendering and bounded work per fix. The virtual checks enforce observable
software properties supporting that design without asserting hardware deep sleep.

## Actions evidence and PR policy

Actions always retains available logs, previous/current JSON, the exact baseline and `performance.md`
in a `virtual-performance-ATTEMPT` artifact. It writes the table to the job summary and updates one
PR comment on same-repository PRs. Fork PRs retain read-only permissions; their author/agent links
the summary/artifact in a PR description/comment. Never use a privileged `pull_request_target` job
to execute fork code. The comparison exits nonzero for regressions, incomplete results or benchmark
errors, failing the required `checks` job. An explicit gate also requires a successful performance
step before recording reusable validation evidence; skipped or cancelled comparisons cannot pass.

Every PR needs **baseline / previous / current** results for its exact head. The Actions report is
the normal reporting mechanism; an agent should link it and summarize any regression in the PR.
No manual phone step or physical baseline exists. After reviewing the full diff and verifying
repository merge settings, enable native auto-merge immediately under the standing authorization,
even while Actions and the virtual comparison are running. Do not wait for a completed report to
queue auto-merge. It may remain queued while a failed check is fixed or rerun: GitHub waits for all
required checks to pass on the current source before merging. Never bypass a failed or stale check.

## Local reproduction

Use the normal JDK 17/Android SDK setup, then run the command above from the repository root with
full Git revisions available. No emulator, ADB or attached device is needed. The script uses two
temporary source trees and a bounded Gradle invocation for each. Output defaults to
`build/reports/virtual-power/`; the harness is injected only into those temporary trees, so ordinary
unit tests do not accidentally run the benchmark twice. The Python comparator tests cover invalid
data and regression handling. The existing Android power/map regressions still run on every PR.

## Implementation audit

Cairn uses one GPS provider subscription, no application-held continuous wake lock, no recording
polling timer and no continuous screen-on request. Guidance consumes received fixes. Changing
guidance preserves the subscription; stopping removes it. The map cancels gestures and tile work
when paused/hidden/detached, rejects obsolete results and resumes its viewport on return. Recording
notifications coalesce on received fixes, without a timer, while every accepted fix is persisted.
Projection is cached for each unchanged camera/list, the active route's two strokes share a path,
and live-track appends project only new points. Camera changes still rebuild O(n) screen geometry.
Guidance uses conservative block bounds with exact distance evaluation. Hidden fix broadcasts are
suppressed and hidden notification refreshes coalesce to five minutes on received fixes; all
accepted fixes remain persisted immediately. See [the multi-day audit and interval study](MULTIDAY-GPS.md).
