# Battery, rendering and suspend performance

## Audit and status — 22 October 2026

No physical-device energy, suspend or competitor measurements exist yet. The pinned baseline
in `scripts/performance/baseline.json` deliberately says `unmeasured`; the comparator fails closed.
There is no measured claim that Cairn beats OruxMaps or that its GPS interval guarantees suspend.

The recorder uses one GPS provider subscription on a worker looper, with the user-selected minimum
interval. It has no app wake lock, alarm, polling loop, compass subscription, continuous screen-on
flag, network fallback or rerouting loop. The worker blocks between events; keeping a thread alive
does not itself keep the CPU awake. SQLite persistence and guidance run on received fixes. Every
accepted fix is still persisted. Guidance changes preserve the subscription; stop removes it.

The audit found activity pause stopped gestures but allowed queued/running tile work and late UI
refreshes to finish. The map now suspends drawing, motion and tile requests on activity pause,
window hiding, ancestor hiding and detachment. It cancels source requests, drops queued tiles,
rejects obsolete results and reloads the viewport on return. Pause cancels delayed UI refreshes and
invalidates pending point loads. Cached tiles remain available on return. Recording notifications
update at most once per minute, triggered by received fixes without a timer; their timestamp names
the fix actually displayed. Route alerts remain immediate. `Cairn.fix` and `Cairn.map.draw` trace
sections mark existing work without adding periodic instrumentation.

Rendering is event driven while visible. Long track/route reprojection and the status distance
calculation remain O(n). Profile 10k/100k-point fixtures before adding simplification/caches that
could alter route display. These changes have behavioral regression tests, not measured speedups.

## Objectives and reference baseline

| Metric | Objective |
| --- | --- |
| App-owned continuous wake locks | 0 seconds |
| App-triggered wakeups outside acquisition/fix work or explicit user actions | 0 |
| Map draws while hidden or screen off | 0 after the transition frame |
| Idle redraws without input/data/tile changes | 0 after settling |
| Visible frame p95 | Within one display refresh budget; no worse than baseline/previous |
| Screen-off between-fix quiet time | Aim for 100% hardware suspend; gate at >=99% for intervals >=20s |
| Energy, fix gaps and frame p95 | No worse than pinned baseline AND previous main |
| Track continuity | >=95% expected fixes, max gap <=3 requested intervals; no service death |
| Positional quality | Report reference-route error p95, distance error, accuracy and rejects; no degradation |

The 99% budget is an engineering objective, not an observed result. Quiet time excludes only
**measured** GNSS acquisition, delivery and persistence windows. Record window duration and GNSS
active duty cycle; do not enlarge windows to hide busy work. High acquisition cost still fails the
energy comparison. At 1s/5s, continuous reception/reacquisition can leave no useful suspend window.
Report actual sleep rather than promising full sleep at an impossible cadence. Screen-on phones
normally cannot enter system suspend: their objective is minimal, event-driven app work.

CPU idle, a sleeping Java thread, Android Doze and hardware suspend are different states. Use
Perfetto `android_suspend_state`, lock attribution and wake reasons. Report both whole-device and
app-attributed wakeups. Modem/OS/other-app wakeups are not automatically Cairn's; unexplained events
leave the result inconclusive. Do not add exact repeating alarms or a perpetual wake lock to force
GPS delivery. A dedicated duty-cycle strategy is a follow-up if traces show the Android provider
keeps GNSS active; measure reacquisition energy and lost fixes before changing that policy.

Reference candidates are **OruxMaps, Locus Map and OsmAnd**, not a measured ranking. Pin version and
APK SHA-1; disable online maps, live sharing, extra sensors and adaptive distance filters. Match
actual GPS request intervals, not just GPX write intervals. OruxMaps documents minimum-time controls
and device-dependent reacquisition issues. Locus documents GPS auto-off, a useful separate duty-cycle
comparison. Non-equivalent settings must use a separate profile.

For each device/profile choose the lowest-energy competitor that passes quality/suspend requirements.
Keep all metrics from that same app's run set, not each app's best metric combined into a fictitious
baseline. Also pin Cairn's first qualifying measured release as its regression baseline. Run `compare`
against both files and previous main: both comparisons must pass. Never silently replace a baseline
with a worse result. There is no universal honest mAh/h target independent of device, screen and GNSS.

## Physical-device matrix

Generate scenarios with `python3 scripts/battery_benchmark.py matrix`:

- Intervals **1, 5, 20 (default), 30, 60, 300 seconds**. For interval-policy changes also test custom
  137s and max 3600s, with >=12 intervals (12h at 3600s).
- Screen **off and locked**, **on with map visible**, and **check** (off except 15-second map checks
  every five minutes). Record actual screen-on duration, including transition/settling frames.
- >=**3 independent trials**, >=1h and >=12 intervals per scenario. Randomize/alternate app/build
  order. Never run two recorders simultaneously. Match durations within 2%.
- Matched recording-off idle controls per screen profile. Archive absolute drain plus incremental
  drain above control; do not subtract controls to hide invalid counters. Idle controls have no GPX
  and remain separate from the recording report.

Pin physical phone, OS fingerprint, battery health, thermal/charge range (e.g. 40–80%), radios,
map viewport/tiles/zoom, brightness in nits, refresh rate, screen timeout, battery optimization,
guidance, walking route and weather/reception. Save an immutable profile document; pass its hash or
version as `--profile`. Confirm no competing GPS client. Use open sky, forest, stationary sections
and difficult reception as separate profiles. Test Pixel, Samsung and older/low-end phones where
available; repeat with Battery Saver, guidance and several-hour locks. Separately test provider/
permission loss, low storage and process survival. A dead recorder cannot earn a battery win.

Use non-debuggable release builds, no emulator/debugger/profiler/screen recording during energy
trials. Cache maps and physically unplug charging. `dumpsys battery unplug` only simulates unplugging
and must not be used. Use wireless ADB at endpoints under matched radio conditions, disconnect ADB
between them, and do no host polling. Reconnecting USB adds charge and invalidates the trial.
For radio-isolated trials, use an external meter under identical supply conditions and retain raw
current/voltage integrals. The script currently imports charge-counter trials only; an external
meter needs a reviewed adapter to the same schema. Never fabricate counter data to import a meter.

## Capture commands

Set the interval and start the recorder in the app normally. Warm up until GPS and tiles settle.
The harness does not expose production benchmark controls or change GPS, recording, permissions,
screen state or battery accounting. Screen-on/check actions are the workload, not a recorder timer.
Use a unique directory for every trial. Dumps and GPX contain sensitive locations: keep raw evidence
in private artifacts, not Git.

```sh
python3 scripts/battery_benchmark.py begin /tmp/battery/current-off-20-1 \
  --serial PHONE_IP:PORT --interval 20 --screen off \
  --profile pixel-profile-v1 --revision FULL_40_CHARACTER_GIT_SHA
# Disconnect ADB, run the >=1-hour workload, reconnect wirelessly.
python3 scripts/battery_benchmark.py end /tmp/battery/current-off-20-1 --serial PHONE_IP:PORT
# Now stop/export the track so export work is outside the energy window.
python3 scripts/battery_benchmark.py summarize /tmp/battery/current-off-20-1 --gpx /tmp/current.gpx
```

The endpoint snapshots retain battery, batterystats, location, gfxinfo, boot ID and package metadata.
No battery stats reset or polling occurs. The summary checks reboot/clock changes, release build,
charging/accounting resets, counter increases, unsupported counters and endpoint overhead. It derives
mAh/h from charge delta and whole-screen-off suspend from elapsed minus awake time. GPX checks
include the start/end gaps: a recorder that dies halfway cannot pass with a short valid prefix.
Battery-percent-only readings are insufficient. Missing data means **unknown**, never zero.

## Separate suspend/rendering diagnostics

Tracing perturbs power: keep diagnostic runs separate from untraced energy trials, on identical
profiles. The config has no battery/CPU counter polling:

```sh
adb push scripts/performance/diagnostic.pbtxt /data/local/tmp/cairn-diagnostic.pbtxt
adb shell perfetto --background --txt -c /data/local/tmp/cairn-diagnostic.pbtxt \
  -o /data/misc/perfetto-traces/cairn.perfetto-trace
# Disconnect, perform workload, retrieve after completion.
adb pull /data/misc/perfetto-traces/cairn.perfetto-trace /tmp/cairn.perfetto-trace
trace_processor /tmp/cairn.perfetto-trace -q scripts/performance/inspect.sql
```

The config targets Android 12+. Verify data-source support, clock alignment, trace completeness and
loss counters before analysis. Older phones need equivalent vendor/kernel evidence; missing events
cannot be converted to zero. App markers may need a release-equivalent profileable build; document
that diagnostic build separately. For scheduling attribution add `sched/sched_switch`,
`sched/sched_waking`, task lifecycle and CPU idle events in a short follow-up trace. Do not use it as
an energy trial. Calibrate tracing overhead with matched idle runs and compare suspend with tracing
off as well as on. The SQL is an inspection aid, not an automatic causal classifier.

Review app-owned lock seconds, unrelated app wakeups, app frame-duration p95, offscreen/idle map
frames and between-fix suspend. Inspect platform GNSS locks and measured acquisition windows.
Frame p95 is **not** the `Cairn.map.draw` CPU slice. Confirm it fits the display refresh budget.
Competitors use their frame timeline/provider events rather than Cairn markers. Report positional
error, distance error, accuracy/rejected fixes and delivered interval distribution alongside energy.

Save reviewed diagnostic metrics in JSON (replace nulls with actual measurements):

```json
{
  "trace_sha256": "SHA256_OF_RAW_DIAGNOSTIC_TRACE",
  "query_revision": "PINNED_ANALYSIS_REVISION",
  "app_wakelock_seconds": null,
  "unrelated_wakeups": null,
  "frame_p95_ms": null,
  "offscreen_frames": null,
  "idle_frames": null,
  "between_fix_suspend_percent": null,
  "track_error_p95_m": null,
  "distance_error_percent": null,
  "gnss_active_percent": null,
  "display_refresh_hz": null
}
```

Run `summarize` again with `--trace-metrics reviewed.json` to attach them without recapturing endpoints.
Null blocks the gate. Link diagnostic and energy evidence by profile/trial ID and retain raw artifacts.
Wakeup attribution, positional quality and refresh-budget checks remain human review: the comparator
cannot prove a hand-entered metric is truthful. Include the query version and raw trace SHA-256.

```sh
python3 scripts/battery_benchmark.py pack /tmp/current.json /tmp/battery/current-*/run.json
python3 scripts/battery_benchmark.py compare \
  --baseline scripts/performance/baseline.json --previous /tmp/previous.json \
  --current /tmp/current.json --output /tmp/performance.md
```

The comparator requires complete matched matrix, >=3 trials, release builds, revisions/evidence,
track continuity, zero background work and suspend objectives. It reports baseline/previous/current
medians and exits nonzero on regressions/unknowns. There is no automatic regression allowance: if
noise prevents a conclusion, repeat trials and keep auto-merge off. Archive distributions and meter
resolution; this conservative screen is not statistical proof.

## PR policy

Every PR description or linked comment must contain **baseline**, **previous main**, and **current
PR** measurements, immutable revisions, scenarios, raw artifacts, track quality, tests, limitations
and verdict. The template and `AGENTS.md` require this for future agents. Every PR needs an impact
review, not a new full phone matrix. Reuse is allowed only for scenarios whose measured execution
paths and conditions remain unchanged; cite the original revision and raw evidence, and label the
current PR entry **reused**, never newly measured. JVM tests and CI time are not battery measurements.
Missing measurements must say **unmeasured**.

Do not enable or leave auto-merge enabled when performance worsens against baseline/previous, an
objective fails, or evidence is missing, stale or inconclusive. This overrides standing auto-merge
authorization. Never silently lower a baseline or manually merge to bypass the gate. Physical-device
performance review is an additional policy; this repository does not claim to run hardware CI.

## How often you need a phone, and how to start

The baseline is established once **per phone/OS/test profile**, then reused while applicable. It is
not a lifetime guarantee and does not require repeating the full suite on every PR.

| When | Automated checks | Physical-phone work |
| --- | --- | --- |
| First setup | Run existing simulated power/rendering tests | Establish Cairn baseline and matched competitor reference |
| Every PR | Build/tests/lint, power/rendering regressions, impact review and three-column report | None if reviewed unchanged scenario evidence remains applicable |
| GPS, recorder lifecycle, timers/locks, core renderer/tile loading, background I/O or relevant dependency change | Add/run relevant regressions | Repeat affected scenarios against baseline and previous main; reuse only unaffected scenarios |
| Power-affecting release or changed OS/device/power policy/measurement setup | Full regression checks | Full matrix/requalification; preserve old results for comparison |
| Unknown impact, noisy result or reported drain | Investigate and keep auto-merge off | Targeted measurements first; expand scope until the uncertainty is resolved |

Routine text, documentation or cosmetic changes can often reuse measurements, but file names and
PR size alone do not establish unchanged execution paths. New animations, large images or altered
visibility behavior can affect rendering even in a visually small change. Current simulated checks
exercise behavior; they do not yet constitute a calibrated emulator CPU/energy benchmark.

For targeted changes, use the normal endpoint harness on affected scenarios. Assemble the complete
comparison report using fresh affected trials plus valid unaffected trials, retaining every trial's
actual measured revision. Link a reuse manifest in the PR stating scenario, original revision,
evidence, changed paths and why the measurement remains applicable. The comparator still requires
the full scenario matrix; it does not infer reuse eligibility or impact. A reviewer/agent must reject
reused measurements for changed paths. If reuse cannot be justified, collect fresh results.

A practical first session:

1. Pick one Android phone and a computer with ADB. Install a non-debuggable Cairn build, enable USB
   debugging and pair wireless ADB while still connected by USB. Save phone/build/profile details.
2. Download a small offline map and choose a repeatable walk with good GPS reception. Disable other
   GPS clients and keep brightness/radios/guidance fixed. Charge to a repeatable middle range and let
   the phone cool. Set Cairn to its default 20-second interval.
3. First do a 15–30-minute screen-off practice run: physically unplug, start the hike, capture
   `begin`, disconnect ADB, walk, reconnect wirelessly and capture `end`. Then stop/export GPX and
   run `summarize`. This is only a capture smoke test: it will not satisfy the >=1h gate or prove
   battery endurance. Confirm counters, track and artifact collection work before long trials.
4. Collect >=1-hour screen-off and screen-on runs, then periodic screen checks, at the matrix
   intervals. Repeat three times under matched conditions. Start with one phone; add manufacturers
   later. The full 6-interval x 3-screen x 3-repeat matrix is at least **54 recording hours per
   build/app**, so accumulate trials over multiple sessions; do not mistake it for a quick check.
5. Capture separate diagnostic traces to verify actual suspend and wakeup causes. Run OruxMaps
   alone under the same conditions for the first competitor comparison; Locus/OsmAnd can follow.
   A reduced pilot is useful, but cannot be advertised as a passing full baseline.
6. Archive raw evidence and freeze the baseline. Future PRs run automated tests and identify which,
   if any, phone scenarios need repeating. You should not need to rerun every competitor on each PR.

The cloud workspace can prepare builds, commands, analyze exported evidence and maintain PR reports.
The physical phone must perform the real GPS/suspend trial; its measurements cannot be generated
truthfully from cloud simulation. Until the first qualifying baseline exists, hardware results remain
unmeasured and auto-merge remains off for this performance PR.

## Primary references

- [OruxMaps 9 manual, GPS settings](https://www.oruxmaps.com/OruxMapsManual-9.0_en.pdf)
- [Locus GPS auto-off](https://docs.locusmap.app/doku.php?id=manual:user_guide:settings:gps)
- [OsmAnd trip recording](https://osmand.net/docs/user/plugins/trip-recording/)
- [Android location battery optimization](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)
- [Perfetto power sources](https://perfetto.dev/docs/data-sources/battery-counters)
- [Perfetto suspend/wakeups/wake-lock SQL](https://perfetto.dev/docs/analysis/stdlib-docs)
