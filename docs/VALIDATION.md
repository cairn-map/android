# Route selection and compact drawer corners — 22 October 2026

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17, SDK 35 extension 15 and Build Tools 35.0.0: **214 tests**, zero failures/errors/skips, and no lint findings. The shared automation selector reports `required=false` for these app/documentation changes.

Regression checks cover the six remaining route actions, selecting a hidden route for guidance, making it visible, persisting guidance after reopening the library store, and retaining corridor downloads after the menu indices changed. Camera checks fit wide, tall, looping and high-latitude routes in portrait/landscape, date-line crossings, single/repeated points, and fitting after the map is laid out or reparented. Padding reserves room for map controls and planning panels.

The native Canvas GPS drawer preview was visually inspected: its top corners are rounded and its bottom corners are square. No physical-device UI trial was performed.

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

Compilation, lint, simulated workflows, and the live client probe establish a useful starting point, not field reliability. The next validation step is to download a small Plan IGN area on a phone, verify it in airplane mode, record a short walk, and then run the battery trial described in BATTERY-VALIDATION.md.


## Map feedback, loading and place search (2026-10-22)

`bash gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --max-workers=2` passed with JDK 17 and Android SDK 35 extension 15: 190 tests, no failures, and no lint findings. The automation selector reports `required=false` for these app/documentation changes.

Regression coverage includes requests beginning during pan/zoom momentum, retaining useful in-flight tiles during a drag, selective cancellation of obsolete HTTP transfers, exact round-tripping of all 1–3600-second GPS settings, top-centered transient feedback, and clickable source/update/licence links through map information. Native Canvas previews of the map controls, feedback and GPS drawer were visually inspected.

Search tests cover a `Thabor` query with a `Mont Thabor` natural-feature response, map-center bias without a hard geographic filter, invalid-coordinate rejection, deduplication, memory caching, bounded HTTP responses, service failures, device-geocoder fallback, cancellation and late responses. These are controlled responses, not a live Photon coverage check. Photon availability and the live ordering/coverage of `Thabor`, accented names and typos still need checking on a device, along with tile-loading smoothness over a real mobile connection.
