# Multi-day GPS efficiency audit

The engineering goal is no unnecessary work between fixes, bounded work per fix, and inexpensive
map checks as a recording grows. The automated evidence measures software work and host CPU time.
It establishes neither days of battery endurance nor superiority to another app.

## Findings and changes

| Area | Finding | Action |
| --- | --- | --- |
| GNSS | One GPS subscription, selected interval, zero distance filter; no extra network provider during a hike | Preserve platform scheduling and the user's interval |
| App wakeups | No recorder timer, exact alarm, continuous app wake lock, compass loop or screen-on flag | Preserve; do work only on fixes and user events |
| Notification | Hidden recording refreshed System UI once per minute | Refresh on fixes at most once per five minutes while hidden; once per minute with an observing activity; alerts remain immediate |
| Fix broadcasts | Every fix sent an IPC broadcast even with no registered activity | Count registered foreground observers; persist all accepted fixes but send fix broadcasts only with an observer |
| Guidance | Every accurate fix scanned every route vertex and segment | Prepare conservative bounds in blocks of 32; evaluate the most promising block first and skip provably distant blocks |
| Map paths | Every draw projected route twice and the full accumulated track again | Reuse one screen path per list and camera; append only new track points; share route outline/fill geometry |
| Distance status | Every visible fix recalculated the entire hike length | Add only the new segment distance; rebuild the total when loading a recording; reset for a new/deleted hike |
| Hidden map | Lifecycle already suppresses hidden drawing, tile reads and gesture loops | Preserve and rerun the complete virtual matrix |
| Storage | Accepted fixes are written immediately to SQLite with WAL | Preserve process-death resilience; no memory-only multi-minute buffer or relaxed durability |
| Network | Search and online map viewing are user-controlled during recording; bulk map/graph downloads remain blocked | Offline map viewing makes no tile requests; search only runs when used, and hidden map/search work is canceled. No accounts, sync or telemetry |

Guidance still computes the same minimum as `Geo.routeDistance`, including disconnected segments,
backtracking, poles and the dateline. Bounds cover both the spherical vertex distance and the
existing local planar segment distance; no progress window can silently miss a nearby route section.
Worst-case overlapping routes still require a full scan. The work test uses a 10,000-point route
and verifies fewer than 400 exact point evaluations on forward, distant and backtracking queries.
Randomized global/local fixtures compare indexed and exhaustive results.

Map caching changes display work only, with no simplification of exported or recorded data.
Route edits replace lists; the live track appends immutable points. Camera changes, list replacement,
truncation and endpoint replacement invalidate cached coordinates. Removed overlays release caches.
Native bitmap comparisons verify that incremental paths match a fresh rebuild, including segment gaps.
Moving the camera still rebuilds screen paths; this change specifically removes repeated projection
on unchanged-camera frames and growing-track updates.

## GPS interval: what can actually be concluded

Three different mechanisms matter:

1. GNSS receiver scheduling controls when satellites are acquired and the receiver can power down.
2. Application processor suspend depends on the OS, other apps and work done after fixes.
3. Doze is an Android idle policy; walking with the phone is not equivalent to stationary device idle.

An interval is a request, not a guarantee of equal receiver-off time or a precise callback deadline.
In AOSP, hardware with scheduling receives the requested GNSS interval. The framework fallback
hibernates after a fix for intervals **strictly greater than 10 seconds** when hardware scheduling is
unavailable. The fallback waits the interval before starting acquisition again, so time to reacquire
can extend the effective spacing. This implementation detail is not a universal phone threshold.
Other location clients can keep the shared receiver active. Continually removing/restarting GPS or
adding application wake alarms would duplicate the scheduler and risk acquisition/delivery costs.

Sources checked on 2026-10-22:

- [AOSP GNSS provider](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/location/gnss/GnssLocationProvider.java): `GPS_POLLING_THRESHOLD_INTERVAL`, `hasScheduling`, `hibernate`.
- [Android location battery guidance](https://developer.android.com/develop/sensors-and-location/location/battery/optimize): longer intervals, remove unused subscriptions, optional batching.
- [Platform LocationRequest builder](https://developer.android.com/reference/android/location/LocationRequest.Builder#setMaxUpdateDelayMillis(long)): batch delivery can be requested on API 31+ and requires a maximum delay at least twice the interval.
- [Android wake-lock guidance](https://android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html): location delivery already has system-managed wake protection; avoid a second continuous lock.

## Reproducible geometry study

Run `python3 scripts/gps_interval_study.py --output build/reports/gps-intervals`.
It emits full JSON for 99 fixture/speed/interval combinations and a Markdown table. Each combination
uses four sampling phases, speeds of 2/4/6 km/h, and intervals of 1/5/10/15/20/30/45/60/90/120/300 s.
The fixtures are a straight 4 km path, twenty 200 m diagonal legs with broad turns, and twenty-four
80 m hairpin legs separated by 15 m connectors. Exact endpoints remove start/stop acquisition loss.
One-second probes and exact bend vertices measure distance to the recorded segment bracketing each
reference time. Using the whole route's nearest segment could conceal missed hairpins.

Worst sampling phase at **4 km/h**, without any sensor error:

| Interval | Fixes/hour | Travel between fixes | Broad bends: p95 / max error | Broad bends: length retained | Tight hairpins: p95 / max error | Tight hairpins: length retained |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 s | 360 | 11.1 m | 0.16 / 3.33 m | 98.94% | 2.16 / 3.93 m | 95.69% |
| 20 s | 180 | 22.2 m | 3.33 / 6.67 m | 97.89% | 5.55 / 7.86 m | 91.19% |
| 30 s | 120 | 33.3 m | 6.67 / 10.00 m | 96.83% | 9.50 / 11.84 m | 85.82% |
| 45 s | 80 | 50.0 m | 11.67 / 15.00 m | 95.25% | 15.95 / 18.97 m | 76.39% |
| 60 s | 60 | 66.7 m | 16.67 / 20.00 m | 93.67% | 21.73 / 26.88 m | 68.44% |

These figures describe synthetic sampling geometry, not empirical trekking accuracy. Real GNSS
noise, multipath, tree cover, missed fixes, elevation and acquisition delays are absent. Four phases
are a sensitivity check, not every possible alignment. Fewer callbacks are not a measured energy
ranking; no sleep percentage, battery capacity or invented current draw is used.

There is no universally optimal interval. With an explicit illustrative requirement of p95 sampling
error <=15 m, maximum <=25 m and at least 95% horizontal length retained, the **longest tested
acceptable interval** at 4 km/h is **45 s on broad bends** and **10 s on tight hairpins**. That is a
geometry-constrained choice, not a hardware energy optimum. Change those requirements and the
answer changes. GPS position accuracy and sampled path-length accuracy are separate requirements.

Practical recommendation:

- **20–30 s** for ordinary trekking: retain the existing **20 s default** and custom selections;
  30 s reduces requested fixes by one third from 20 s, without promising one-third less energy.
- **60 s** for explicitly sparse endurance recording on uncomplicated trails. It halves fix requests
  relative to 30 s, but loses corners and delays guidance. At 6 km/h, nominal spacing becomes 100 m.
- **5–10 s** when tight switchbacks or path-length fidelity matter more than reduced GNSS activity.
- **120–300 s** is a coarse travel log, unsuitable as a general trekking recording default.

Off-route guidance needs two accurate fixes beyond max(50 m, twice reported accuracy). At ideal
cadence, confirmation adds up to roughly two intervals **after crossing that threshold**: about
40/60/120 s at 20/30/60 s, plus acquisition delays or rejected/missed fixes. No interval guarantees
a warning before the next junction. Longer intervals must remain an explicit user choice.

## Remaining opportunities

Hardware-assisted batching on API 31+ is the next substantial candidate for reducing processor
wakeups while retaining denser GNSS samples. It is not enabled by this change: a correct recording-only
mode needs ordered multi-fix ingestion, freshness based on sample time, reliable flush/stop handling,
provider capability fallback and explicit guidance latency semantics. Current rejection of fixes
older than two minutes would otherwise silently discard legitimate batches. Delaying delivery also
delays off-route detection, and batching does not prove that the receiver consumes less energy.

Route-aware adaptive cadence could shorten intervals near known sharp turns. It must never infer
that an unknown path is straight from two sparse fixes, hide user overrides, or repeatedly restart
the subscription. An uncertain/no-route case needs a conservative fallback. Automatic stationary
pausing also risks losing slow climbing or movement between inaccurate fixes.

Process death currently preserves committed points but does not resume recording automatically.
That remains a reliability limitation for multi-day sessions, not evidence of an energy improvement.
The existing behavior is documented; this audit does not trade lost recordings for lower work counts.

## Verification

The unchanged required virtual performance harness compares immutable previous/current revisions
against the fixed baseline for all 18 screen/GPS scenarios and native 1k/10k-point rendering workloads.
No budget, scenario or workload has been weakened. The exact-head Actions report is the merge gate.
Additional tests cover exact guidance distances, bounded exact evaluations, cached display equality,
projection work, hidden broadcast suppression, persistence, and observer lifecycle transitions.
A three-day virtual recording injects 12,960 fixes at 20 s, checks every fix is saved, keeps exactly
one GPS subscription, and expects 864 hidden notification refreshes. Advancing a virtual clock
does not simulate three days of physical battery discharge or prove process survival under Android.
