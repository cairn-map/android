# Slow climbs, readable profiles and consistent GPS return — 27 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2`
passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **447 tests**, no
failures/errors/skips and no lint findings. Python automation passed 33 tests and Node
automation passed 41 tests. These changes affect application code, tests and documentation;
the shared automation selector does not require automation suites. Required PR CI supplies
the fresh baseline/previous/current virtual performance comparison.

Regression coverage includes sub-metre climbing, stationary altitude drift, rejected altitude,
live inspection preserving distance as a track grows, 30-second follow return, immediate locate,
recreation, independent GPS/inspection details, large text, and portrait/landscape control space.
Native Canvas previews were visually inspected. A private replay of the supplied 104-fix GPX
through the application filter leaves one poor-vertical-accuracy sample excluded and no internal
dashed connectors; the user's GPX files are not committed. Routing diagnosis is bounded to the
exported geometry and bundled defaults because the installed geographic graph is unavailable.

# Routing concurrency test synchronization — 24 October 2026

CI exposed a scheduling race in the edit-during-calculation test: a fast worker could finish while the test advanced its UI clock, before the concurrent edit was applied. A bounded latch now holds the routing worker until the edit, undo or preferences action has been applied, then releases it before the existing result assertions run.

All 26 routing flow tests passed in a focused run. `bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` then passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **356 tests**, zero failures/errors/skips, and no lint findings. Python automation passed 31 tests and Node automation passed 41 tests. Required PR CI supplies a fresh baseline/previous/current virtual performance comparison for the updated head.

# Corridor slider ticks — 24 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **356 tests**, zero failures/errors/skips, and no lint findings. Python automation passed 31 tests and Node automation passed 41 tests. The shared automation selector reports `required=false` for these app/documentation changes.

The native Canvas corridor preview was visually inspected: numbered ticks mark zoom levels 10–19, whole kilometres 1–10 and the 0.1 km endpoint, with GPS interval slider styling and room for the labels. Saved corridors only show available detail levels. Existing workflow checks retain 0.1 km precision and exact tile selections; portrait/landscape regressions retain control positions and scroll offset during estimate refreshes. Required PR CI supplies the fresh baseline/previous/current virtual performance comparison.

# Steady corridor preview — 24 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **356 tests**, zero failures/errors/skips, and no lint findings. The Python automation suite passed 31 tests and the Node suite passed 41 tests. The shared automation selector reports `required=false` for these app/test/documentation changes.

Native Canvas regressions verify that both sliders, the Download button and the scroll offset stay in place through pending, successful, oversized and recovered estimates in portrait and landscape. The previous estimate stays visible while recalculating; stale results and dismissed drawers remain guarded. Removing the estimate height reservation makes both orientation regressions fail on control movement. The refreshed drawer preview was visually inspected. Required PR CI supplies the fresh baseline/previous/current virtual performance comparison; no new performance numbers are claimed here.

# Route selection and corridor sliders — 24 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **342 tests**, zero failures/errors/skips. Lint reported zero errors and five existing warnings in unchanged `SwipeToRemoveView` code. The shared automation selector reports `required=false` for these app/test/documentation changes.

Regression checks save the exact corridor tile set at every Plan IGN zoom level from 0 through 19, preserve the default zoom 17, exercise 0.1/10 km slider endpoints and save 0.2/2 km widths, and verify that no name field is offered. A native Canvas preview at `app/build/test-previews/corridor-sliders-drawer.png` was visually inspected. Confirmed empty-map taps clear selection and its saved preference while preserving route visibility and guidance; existing editing, path selection and gestures remain covered. Clearing routing data removes finished RD5 files and all supported partial/resume/import files, keeps routes/map packs/profiles, supports cancellation, and rechecks recording/edit/calculation/download activity at confirmation. Required PR CI supplies the baseline/previous/current virtual performance comparison for the committed head.

# Download and calculate from Route — 23 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **247 tests**, zero failures/errors/skips, and no lint findings. The shared automation selector reports `required=false` for these app/test/documentation changes.

Planner regressions cover installed-data routing on Android 26/35, one confirmation for missing data, declining, download progress on Android 28/35, automatic calculation across the two real graph fixtures, frozen planning points, unrelated/duplicate completion events, failure/retry, cancellation, background completion, recreation, notification pause and recording restrictions. Service tests transfer a real RD5 fixture over local HTTP and verify correlated progress, validated installation and success/failure signals with simulated Android storage. The Android 35 native Canvas dialog preview at `app/build/test-previews/download-and-route.png` was visually inspected; both actions fit without truncation. Required PR CI supplies the baseline/previous/current virtual performance comparison for the committed head.

# Automated virtual performance — 22 October 2026

This supersedes earlier physical-phone baseline plans: performance approval now uses GitHub Actions, virtual Android tests and native Canvas benchmarks only, per the owner's explicit instruction. No real-phone test or hardware baseline is required. See [virtual performance protocol](BATTERY-VALIDATION.md) for the baseline/previous/current matrix, budgets, runner-noise policy and automatic PR reports.

Local validation passed: 221 Android tests, 26 Python tests, 40 Node tests, Android build/lint and pinned actionlint 1.7.12 (three pre-existing SwipeToRemoveView lint warnings). The same-run virtual comparison also passed all 18 scenarios and both native Canvas CPU fixtures. The benchmark runner separately injects the same harness into previous/current immutable Git trees and writes measured JSON and a comparison table. Those artifacts, rather than this historical log, establish the verdict for each exact PR head. Hardware mAh, endurance and suspend are not claimed.

# Toast position above drawers with display cutouts — 22 October 2026

Gradle 8.11.1 `assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **216 tests**, zero failures/errors/skips. Lint reported zero errors and five warnings in unchanged map/swipe code. The shared automation selector reports `required=false` for these app/test/documentation changes.

The remaining shift came from applying the top safe-area inset twice. Android's `WindowLayout.computeFrames()` still clips a floating application window to the display-cutout safe rectangle with `FLAG_LAYOUT_IN_SCREEN` and `fitInsetsTypes(0)`. The banner then added the activity's full top inset inside that already-shifted window. A new regression using Android 35's actual window-frame calculation failed before the fix: a 56 px inset placed the banner at 124 px instead of 68 px.

The overlay now lets WindowManager fit system bars and cutouts and uses only a 12 dp internal top margin. Its keyboard adjustment is explicitly disabled. The activity-decor path retains its own inset. Screen-coordinate checks include ordinary status bars, a top camera cutout, keyboard visibility, resized task bounds and a nonzero task origin. Existing checks retain application-token, nested-drawer, queue, progress, expiration and cleanup coverage on Android 28 and 35.

These are framework/JVM checks, including the platform window-layout algorithm; no emulator or physical-device UI trial was performed.

# Route selection, compact drawer corners and toast crashes — 22 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **215 tests**, zero failures/errors/skips, and no lint findings. The shared automation selector reports `required=false` for these app/documentation changes.

Regression checks cover the six remaining route actions, selecting a hidden route for guidance, making it visible, persisting guidance after reopening the library store, and retaining corridor downloads after the menu indices changed. Camera checks fit wide, tall, looping and high-latitude routes in portrait/landscape, date-line crossings, single/repeated points, and fitting after the map is laid out or reparented. Padding reserves room for map controls and planning panels.

Feedback checks on API 28 and 35 now distinguish the activity's application token from its view-window token. The earlier overlay failed these checks on both APIs; letting the activity's WindowManager supply the application token passes them. Coverage includes a toast before opening a drawer, nested drawers, dismissal, feedback created inside a drawer, expiration, clearing and continuing progress.

The native Canvas GPS drawer preview was visually inspected: its top corners are rounded and its bottom corners are square. No physical-device UI trial was performed.

# Stable toast placement and cleaner search/GPS — 22 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **210 tests**, zero failures/errors/skips. Lint reported zero errors and five existing warnings in unchanged `TrekMapView` and `SwipeToRemoveView` code. The shared automation selector reports `required=false` for these app/documentation changes.

Feedback checks on API 28 and 35 require a sibling application window with the activity's application token, preventing inheritance of a content-sized drawer's frame; they retain nested-dialog, queue, expiration and cleanup coverage. Settings checks verify the 20-second default and persistence of custom intervals. Search checks cover a blank input without a hint or empty-submit error, keyboard-compatible window flags, result selection and cancellation. Native Canvas previews of the empty search bar, search results at full/reduced heights and compact GPS drawer were visually inspected.

No physical-device window-positioning or keyboard trial was performed. Window placement is covered by framework configuration checks, and reduced-height previews simulate space above a keyboard.

# Nature-prioritized search — 22 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **207 tests**, zero failures/errors/skips, and no lint findings. The shared automation selector reports `required=false` for this app/documentation change.

Controlled Photon responses verify that “Thabor” ranks Mont Thabor first, with alpine and wilderness refuges ahead of exact urban namesakes even when the map is centered near the urban results. Ranking checks cover other natural features, trails, viewpoints, campsites, shelters and protected areas; explicit country/street qualifiers; name relevance; soft distance bias; and retaining all 40 distinct provider matches instead of truncating to ten. Parser checks preserve OSM categories, and the request remains unrestricted by place type.

No live Photon or physical-device search trial was performed. Results still depend on the provider's coverage and OSM classification; the preference reorders returned candidates.

# Full-width drawers, GPS ticks and unified feedback — 22 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **202 tests**, zero failures/errors/skips, and no lint findings. The shared automation selector reports `required=false` for this app/documentation change; workflows and automation are unchanged.

Framework regression checks cover full-width drawers on phones/tablets, variable content filling the available height, swipe dismissal and scrolling, immediate GPS/alert persistence on API 26 and 35, and a slider gesture starting below the track with 120 pixels of downward drift. A tap near 70 seconds selects the 60-second tick; a drag selects 43 seconds without dismissing the drawer. Existing tests round-trip every whole-second setting from 1 through 3600.

Feedback checks on API 28 and 35 cover one banner following nested dialogs, queued independent notices, collapsed duplicates, replacement of related location/update messages, persistent transfer progress, accessibility timeouts, expiration and cleanup on pause. Attribution is reachable from drawer footers with clickable source/update/licence links; imported maps retain their existing on-map credit.

Search checks cover exact/partial names, accents, country qualifiers, city priority, soft distance bias, duplicate merging, direct coordinates, street numbers/postcodes, preserving named POIs with addresses, fallback, cancellation and late responses. Native Canvas previews of GPS, feedback, and populated search at full and reduced window heights were visually inspected. The reduced-height check simulates the space available above a keyboard; it is not a real IME/device trial.

No physical-device gesture or live search-provider trial was performed. Ranking tests use controlled responses, and Photon/device-geocoder coverage remains provider-dependent; Google Maps equivalence has not been established.

# Content-sized drawers, GPS slider and centered feedback — 22 October 2026

JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: `bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed. All **178 Android/JVM tests passed**, with no failures or skips. Android lint reported zero errors and four existing warnings in the swipe-to-remove view and place-search text. The Python automation suite (19 tests) and Node automation suite (39 tests) also passed.

Framework checks cover independent drawer width/height on phones and tablets, content-sized fixed menus, full-height variable lists, scrolling, swipe dismissal and cancellation cleanup. GPS checks on API 26 and 35 preserve an existing interval, update the slider's displayed value, retain settings on Cancel, and save the full 1–3600-second range with alert settings. Toast checks on API 28 and 35 verify centered placement, replacement and cancellation on pause. Native Canvas previews of the compact GPS drawer and centered update feedback were visually inspected. No physical-device UI trial was performed.

# Zoom momentum and corridor downloads — 22 October 2026

JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: `bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed. All **139 Android/JVM tests passed**, with no failures or skips; Android lint reported no issues.

Gesture simulation covers a longer, speed-sensitive coast in both directions, deceleration, a fixed geographic anchor, consistent travel across frame rates, zoom limits, reversals, held gestures and immediate interruption by touch or lifecycle changes. Simulated HTTP transfers fill all 32 slots without the old serial delay and cancel every connection. A service integration check holds one tile open, confirms the pipeline continues beyond the initial pool, pauses without committing canceled data, and resumes to a complete pack.

The route-menu workflow saves the exact planned tile sets for 0.2 km and 2 km corridors. Geometry checks cover width changes, diagonal routes without filling their bounding square, disconnected GPX segments, date-line crossing, continuous coverage between sparse points, and rejecting tiles beyond the buffered footprint. Store checks confirm selected-detail tiles precede the large overview tiles. The viewport cancellation test deliberately creates a queue and observes center priority with one worker, avoiding an assumption about concurrent thread start order.

No physical-device comparison with Google Maps or live IGN throughput benchmark was performed. The transfer cap and simulated concurrency are verified; actual speed depends on the connection and server throttling. Overview tiles remain useful geographic context and can span roughly 100 km per side. They download after the detailed corridor. Existing saved packs retain their selections; newly prepared corridors use the tighter footprint.

# Map gestures and place search — 22 October 2026

JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: `bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed. All **92 Android/JVM tests passed**; Android lint reported no issues. Python automation tests (19) and Node automation tests (39) also passed.

New framework-simulated checks exercise second-tap release timing, a held second tap without an accidental planning point, quick-scale in both directions with no release jump, cancellation and ordinary panning. Place-search checks cover matching and invalid coordinates, missing geocoding support, empty results, network errors and retry, timeout, discarded late replies, keyboard submission, empty input, result selection without enabling online tiles, activity pause, and recording blocking online search. The native Canvas map preview was visually inspected with the added search control and compact IGN credit.

No physical-device gesture trial or live device geocoding lookup was performed. Place-name coverage and network behavior depend on the phone's geocoding service. Full IGN source/licence/update-information links are in Routes; the map retains a compact © IGN credit.

# Earlier validation — version 0.4.0, 21 October 2026

## Executed

The native Android debug APK was compiled with JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, SDK 35 extension 15, and Build Tools 35.0.0.

Command:

```text
gradle assembleDebug testDebugUnitTest lintDebug
```

All **59 tests passed**, with zero failures, errors, or skipped tests:

- Geographic projection round trips.
- No imaginary distance or off-route connection across disconnected GPX segments.
- Nearest-point distance against segment interiors and endpoints.
- Degenerate segment handling.
- Rejection of invalid coordinates.
- GPX export/import preserving timestamps, elevation availability, and segment breaks.
- Route-only GPX import.
- Preference for track geometry when a file also contains a route.
- Rejection of document declarations/external-entity input.
- Rejection of empty and invalid GPX files.
- Area coverage at every saved detail level, with tile deduplication.
- Continuous corridor coverage between sparse diagonal GPX points, including its width.
- No download bridge across disconnected GPX segments.
- Date-line area wrapping without downloading the rest of the world.
- Size limits and invalid download selections.
- Complete PNG acceptance and rejection of truncated, corrupt, and non-map responses.
- SQLite package completion only after every planned tile is committed.
- Partial download recovery after closing and reopening the database.
- Shared-tile retention when another saved area is removed.
- Conditional refresh retaining cached bytes and requiring package rechecking.
- Simulated launch/navigation without location permission, two destinations, one Add files action and access to integrated routing-data controls.
- Public Plan IGN recognition in OruxMaps XML, rejection of spoofed hosts/restricted layers and document declarations, and differentiation of GPX versus other XML.
- Manual network positioning without a satellite fix, approximate permission, recent cache use, old-fix rejection, cancellation, and timeout cleanup.
- Native Android Canvas rendering of cached tiles during zoom-in, zoom-out, and multi-level jumps; fractional zoom keeping the geographic focus fixed.
- Rendered compact map layout with narrow GPS/hike controls and no visible zoom buttons. The generated preview was visually inspected.
- GPX opening from an opaque content URI while retaining the loaded recording, and XML sharing into an already-open activity without enabling preview.
- Manifest resolution for typed GPX/XML files and filename-limited generic binary associations.
- GPX library persistence, selection, visibility, rename/removal, and migration from the prior single-route database without losing recordings.
- Multi-GPX sharing retains both files and displays their geometries together.
- Actual embedded walking-route calculation across two real RD5 fixtures, missing-region errors, format/name validation, and explicit offline calculation during recording without extra location requests.
- Recording uses only GPS; Locate adds no GPS/network subscriptions even with an old recording position, guidance updates preserve the existing subscription, and stopping removes it.
- Visible-tile ordering, exact screen-edge coverage without an extra row/column, and date-line wrapping.
- Native Canvas tests for obsolete-queue cancellation, current-view center-first loading, replacing blurred offline tiles online while retaining a backdrop, and fractional camera restoration.
- Removing a pack retains unrelated viewed tiles; explicitly clearing viewed tiles retains completed corridor packs.
- Loopback HTTP transfer tests for truncated-body/206 resume, changed-revision/200 restart, process-death/416 completion recovery, and cancellation without publishing a partial graph.

Thirty tests use Robolectric on Android API 28, including five native-graphics tests with Android Canvas rendering. They simulate framework/location/SQLite behavior and are not physical-device measurements. The other 29 tests run directly on the JVM.

Android lint passed with **zero errors and zero warnings**. Compatibility and broadcast registration issues found during development were corrected before packaging.

The APK's signing and compatibility metadata were checked with the Android Build Tools. It is a development-signed build, not a production release.

## Live Plan IGN checks

The public WMTS capabilities were retrieved and the Plan IGN layer, PNG format, and current supported zoom range were inspected. The application HTTP client was then executed on the desktop JVM against the real `PM_0_19` endpoint: it fetched a 74,122-byte map tile and successfully validated its PNG structure and checksums. A second request using available conditional headers also returned a valid map tile. The sample image was visually inspected and showed the expected Plan IGN map.

The supplied XML was parsed with DTD/external-entity processing disabled. Its Plan IGN entry matched the verified public layer. No embedded credentials or full provider configuration were copied into the project.

## Routing validation

BRouter 1.7.9's actual Java engine and upstream hiking profile calculate a route in the application worker using two small genuine OSM-derived graphs. The original upstream PBF and derived graphs are included only as test resources. No external BRouter service or online routing API participates.

A bounded live HTTP range probe on the public graph endpoint read only 200 bytes: HTTP 206, compatible lookup version 11, Content-Range total size 252,717,722 bytes and an ETag. Local HTTP tests validate transfer interruption/resume separately. No full regional download was needed for these checks.

## Not yet executed

- Developer-run installation, launch, and interaction on an emulator or physical phone. The user's earlier prototype feedback motivated these fixes; the revised behavior has been checked in simulation only.
- Screen-off recording under manufacturer-specific battery policies.
- Measured comparison with OruxMaps or any battery endurance measurement.
- Live GNSS accuracy, alert timeliness, or acquisition/reacquisition trials.
- Embedded BRouter route calculation, memory behavior and full-size regional download/resume on a physical device. Framework-simulated actual routing and local HTTP transport tests have passed.
- Imported raster MBTiles rendering and very large map handling on a device.
- Large-font, TalkBack, landscape, and outdoor readability checks.
- Long-running downloads, pause/resume, offline tile rendering, and download cancellation on an actual Android device. Desktop HTTP checks and simulated Android store tests cover parts of this flow, not the whole device workflow.

This historical validation record did not establish field reliability. Its proposed phone-trial follow-up has been superseded by the automated virtual performance policy at the top of this document. No physical-phone testing is required for PR approval.


## Map feedback, loading and place search (2026-10-22)

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17 and Android SDK 35 extension 15: 190 tests, no failures, and no lint findings. The automation selector reports `required=false` for these app/documentation changes.

Regression coverage includes requests beginning during pan/zoom momentum, retaining useful in-flight tiles during a drag, selective cancellation of obsolete HTTP transfers, exact round-tripping of all 1–3600-second GPS settings, top-centered transient feedback, and clickable source/update/licence links through map information. Native Canvas previews of the map controls, feedback and GPS drawer were visually inspected.

Search tests cover a `Thabor` query with a `Mont Thabor` natural-feature response, map-center bias without a hard geographic filter, invalid-coordinate rejection, deduplication, memory caching, bounded HTTP responses, service failures, device-geocoder fallback, cancellation and late responses. These are controlled responses, not a live Photon coverage check. Photon availability and the live ordering/coverage of `Thabor`, accented names and typos still need checking on a device, along with tile-loading smoothness over a real mobile connection.

## Unified routes and recordings

Routes and recordings use the same tap-to-select map flow, shared highlight and selected-item
corridor/export/hide controls. Robolectric regressions cover full-path fitting clear of controls,
map taps, hidden-item reselection and visibility across resume, recording geometry in GPX exports
and offline corridors, either-direction swipe handling, and protection of the current recording.
Native Canvas checks cover the selected outline above overlapping paths, single-point recordings
and hiding the current trail. The required Actions comparison reports baseline, previous main and
the exact PR head performance; no physical-device test is required.
