# Cairn 0.4.0

A native Android trekking app built around offline maps, a minimal map screen, and user-selected GPS intervals. Cairn is a working name. All required tools, map access, routing data and licences are free of charge.

## This build

- Full-screen map, no header or zoom buttons, two floating Map/Routes destinations. Map mode, location, saved maps and GPS settings sit on the right for one-handed use.
- Smooth pinch zoom, cached geographic backdrops, center-first loading of visible tiles, and cancellation of obsolete viewport requests. Offline enlargements are replaced with real detail when online mode is enabled.
- Plan IGN through the public WMTS service. Online mode automatically keeps viewed tiles permanently in local storage; the camera is remembered across restarts. No provider account or key is needed.
- A GPX library: open, share or select multiple files; preserve them separately; select one to follow; show/hide others; rename, export, or remove a route. Recordings remain separate. Earlier single-route databases are migrated.
- One **Add files** action detects GPX, OruxMaps source XML, raster MBTiles, or BRouter regional RD5 data. Android Open with/Share is registered for GPX/XML, including multi-file sharing. XML currently recognizes public Plan IGN; other providers are reported as unsupported.
- Route-specific offline map corridors, saved pack completion checks, pause/resume, update checks, and shared-tile-safe removal. Pack management lives under the map's layers icon. There is no Offline tab or duplicate preview/save-current-area button.
- **Embedded BRouter 1.7.9**, walking profile and lookups. No companion app, external routing server or paid API. Routing data is downloaded/resumed inside Cairn or imported as RD5 files. Calculations are explicit, bounded and cancellable.
- GPS recording in a foreground service, local SQLite storage, adjustable 1–3600-second requests, and off-route alerts from those same fixes. Manual Locate can use network location outside a hike; during recording it only reuses the recording position and never starts additional requests.
- No accounts, ads, analytics or cloud sync.

## Install and try

Install `Cairn-0.4.0-debug.apk` on Android 8.0 or newer. Its certificate matches the earlier prototypes, allowing an update that retains local data. This is a development-signed evaluation build.

1. Tap the crossed-cloud icon on the right to load Plan IGN. Pan and pinch; viewed tiles are saved as they arrive. Tap the cloud again for offline maps. This keeps only visited tiles/zoom levels, not an entire area at every detail level.
2. Open GPX/XML from an Android file manager and choose Cairn, or share files to it. Routes → Add files is a single fallback picker with multiple selection. Each GPX stays in the library; several can be displayed together. One route is selected for guidance at a time.
3. Tap a route in Routes for Show/select, Follow, Save offline corridor, Export, visibility, Rename or Remove. Download a corridor before a trek to cover the full path and chosen detail, including space between GPX points. Partial jobs remain resumable from the map's layers menu.
4. To create a route, choose Routes → Create route, long-press map points, then Route. For graph data, use Routes → Routing data or More while planning. Regions are selected from the route/planning points; Regions → Prepare map center lets you prepare another area. Download missing data before going offline. Regions also offers removal/update-by-redownload and discarding paused files.
5. Tap the floating GPS pill to choose the recording interval and alerts. Start hike requests precise location permission and optionally notifications. Online preview and graph/map downloads stop during recording. Explicit offline calculations remain available with installed graphs and preserve the GPS interval.
6. Lock the screen for a short test walk. Stop with Finish and export the recording from Routes. Check your phone's actual screen-off recording behavior before a longer trek.

Android file managers differ in MIME reporting. Typed GPX/XML content URIs work even with opaque IDs. Generic binary/plain-text files are offered by filename where exposed; use Add files when a provider does not supply a usable type or filename. Source XML configures the supported built-in Plan IGN source without enabling network access. It does not install arbitrary providers or execute comments/headers.

Raster MBTiles accepts PNG/JPEG tiles in TMS or declared XYZ order. Vector MBTiles and Mapsforge `.map` files are not supported. Routing RD5 imports must retain their original region filename, such as `E5_N45.rd5`.

## Offline routing and storage

BRouter code/profile data is in the APK; geographic routing graphs are not. Its 5 × 5 degree graph regions can be hundreds of MB, separate from Plan IGN map tiles. Downloads use HTTPS, range/validator resume, partial files, index/version validation and atomic installation. A region is only published after the transfer completes and validates. Block checksums are checked by BRouter when read. Graph updates are explicit: remove an installed region, then download it again.

Routing uses OpenStreetMap-derived connectivity with the upstream hiking-mountain profile. It can differ from the paths drawn by Plan IGN. See [routing implementation](docs/ROUTING.md) and [third-party notices](THIRD-PARTY-NOTICES.md).

Visited map tiles stay on disk until explicitly removed through the layers menu. Clearing the viewed-map cache preserves downloaded corridors. A downloaded corridor is capped at 20,000 tiles, with Standard/Detailed/Maximum detail (16/17/19) and an estimated size before download. Saved dates are tile-check dates, not field-survey dates. Complete packs can be refreshed from the layers menu; other packs' shared tiles and unrelated viewed tiles are retained when a pack is removed.

## Battery behavior and limits

The indoor fallback adds only an explicitly requested, foreground locate operation outside a recording, bounded to 30 seconds and canceled on pause or hike start. During recording, Locate reuses the existing position even if old; it does not request faster GPS or network updates. Route/alert-setting changes preserve the GPS subscription when its interval is unchanged. Stopping recording removes subscriptions, including queued configuration cleanup.

The app holds no continuous wake lock, screen-on request, map animation loop or compass polling loop. Preparation traffic is off during a hike. Android and GNSS hardware still control actual delivery and receiver duty cycling. **No measured claim of best endurance or superiority to OruxMaps has been established.** See [battery validation](docs/BATTERY-VALIDATION.md).

If Android kills the recording process, committed points remain but recording does not automatically resume. Reopen Cairn, inspect/export the previous hike, and explicitly start a new recording. Turn alerts are not implemented; off-route alerts use existing fixes and can be delayed by long intervals.

## Build and validation

Use JDK 17, Android SDK Platform 35 extension 15, and Build Tools 35.0.0. Gradle 8.11.1 and Android Gradle Plugin 8.9.2 are pinned by the wrapper/build files.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

The app uses Android platform APIs and the vendored MIT BRouter Java modules. Test-only dependencies include JUnit, XML Pull/KXml and Robolectric. No paid IDE or runtime service is needed. Build output: `app/build/outputs/apk/debug/app-debug.apk`.

See [validation](docs/VALIDATION.md) for executed checks and physical-device work still required. Test regional graphs and their original PBF are in `app/src/test/resources/routing`; they are not bundled in the APK. Native rendering previews are generated under `app/build/test-previews/`.