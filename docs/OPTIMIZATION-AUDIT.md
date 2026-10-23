# App efficiency audit

The audit prioritizes avoidable wakeups, followed by CPU, allocations, disk reads and UI work.
Validation uses software work counts and native Canvas CPU timings. These are not measurements
of battery capacity, endurance or GNSS hardware power.

## Implemented improvements

| Area | Avoidable work | Change |
| --- | --- | --- |
| Offline map downloads | Coordinator woke every 100 ms while waiting for transfers | Wait for a completed transfer; pause, destruction and timeout interrupt the coordinator |
| Server backoff and transfer capacity | Workers polled every 100–200 ms, even during a long Retry-After | Wait on capacity/deadline notifications; cancellation wakes waiting requests immediately |
| Download progress on the map | Each progress event canceled and restarted pending viewport work | Retry missing tiles while retaining useful pending reads; completion still reloads saved imagery |
| Map redraws | Rebuilt, sorted and keyed the same viewport on each stationary redraw | Reuse ordered coordinates and keys until the camera or view dimensions change |
| Map backdrops | Allocated and sorted a combined tile list on every frame | Merge the two already sorted lists while drawing, preserving finer-tile and fresh-tile precedence |
| Imported map attribution | Parsed HTML during every draw | Parse and truncate attribution when assigning the source |
| Recording library | Loaded the current hike both as the live track and a library overlay | Share the existing live track for display, selection and export |
| Route storage | Rebound every point to duplicate saved geometry into the guidance collection | Copy the stored geometry with one ordered SQLite statement in the existing transaction |
| Conditional tile refresh | Read saved PNG blobs only to obtain HTTP validators | Read validators without loading tile bytes; HTTP 304 retains the stored PNG |
| Online tile preview | Read the downloaded PNG back from SQLite immediately after saving | Decode the validated response buffer directly after a successful save |
| Recording controls | Reassigned unchanged interval, distance/accuracy and recording labels | Update text only when its displayed value changes |

Cancellation and lifecycle tests cover stalled downloads, slot waiters, long server backoff,
pending viewport requests, missing-tile retries, camera/size changes and pause/resume. Native
rendering tests cover unchanged-frame equality and map layering. Storage tests preserve route
point order, segment gaps, timestamps, accuracy, missing elevation, saved tile bytes and recording
export after resume.

## Preserved policies and further candidates

Recording retains the exact user-selected GPS interval, one GPS subscription, immediate SQLite
WAL persistence and existing off-route alert semantics. Hidden recording already suppresses unused
fix broadcasts, coalesces notifications on received fixes and has no recorder timer or continuous
application wake lock. Guidance already indexes route blocks, and unchanged-camera paths already
reuse projected geometry. No recorder behavior or performance budget is weakened here.

Location fallback, search, graph preparation, route calculations and app updates remain bounded
and triggered by user actions. Map work stops when hidden. Search and online map viewing remain user-controlled during a hike; bulk map/graph downloads remain blocked.
There is no compass loop, analytics, sync job, automatic update polling or screen-on request to remove.

Full track/library reloads on resume remain a candidate for a separate persistence change. Safe
incremental loading needs database row cursors and ordered reconciliation with fixes arriving
during the read; timestamp-only deduplication can lose legitimate points. Long routes still rebuild
screen paths when moving the camera. Display simplification would need error bounds and image
checks without changing recorded/exported data.

Hardware-assisted GPS batching, automatic cadence changes and stationary pausing can change
delivery latency, alerts or recording fidelity. They require explicit behavior design and reliable
flush/stop handling; fewer callbacks alone do not establish lower energy use. See
[the GPS interval study](MULTIDAY-GPS.md).

## Performance evidence

Every PR runs the unchanged [virtual comparison](BATTERY-VALIDATION.md) against immutable previous
main and PR head revisions: all 18 screen/GPS scenarios and the 1k/10k-point native rendering
workloads must meet the fixed baseline and regression limits. The exact-head Actions artifact and
PR comment contain the baseline/previous/current results. Unit tests also exercise the download
and storage optimizations outside the recording matrix.
