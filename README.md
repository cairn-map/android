# Cairn 0.5.0

A native Android trekking app built around offline maps, a minimal map screen, and user-selected GPS intervals. Cairn is a working name. All required tools, map access, routing data and licences are free of charge.

## This build

- Always-dark interface inspired by Morphe: charcoal surfaces, blue accents, flat rounded controls, and dark dialogs/settings. Map tiles keep their original cartographic colors; controls and scale stay dark. Drawers use the full screen width, with source/licence details in their footers. Transient feedback shares one top-centered banner; distinct notices queue and progress updates replace in place. Imported maps keep their own attribution.
- Manual in-app updates directly in **Routes**: check, download, verify and open Android's installer. No update polling, scheduled jobs or automatic downloads; transfers stop when you leave Routes, and updates are blocked while recording a hike.
- Full-screen map, no header or zoom buttons, two floating Map/Routes destinations. Map mode, location, saved maps and GPS settings sit on the right for one-handed use.
- Smooth pinch zoom, cached geographic backdrops, and center-first loading with up to 32 tile requests in flight. Offline downloads continuously refill available transfer slots; the old 350 ms request delay is removed. Server busy responses pause requests together and honor Retry-After. Saved finer tiles are used immediately at startup and stay visible over overview tiles when zoomed out. Tiles start loading during drags, pinch/quick zoom and momentum, while useful in-flight transfers keep running. Obsolete viewport requests are canceled, and offline enlargements are replaced with real detail when online mode is enabled.
- Double-tap zoom animates briefly on release; holding the second tap and dragging zooms at a consistent rate. A quick flick carries farther on release with smooth, speed-sensitive deceleration. Touching the map stops either animation. The map's search icon suggests mountains, towns and addresses as you type using Photon's partial-name and typo-tolerant OpenStreetMap search, with the map center as a soft ranking bias and the device geocoder as a fallback. Relevant natural features, huts, refuges and other hiking destinations rank higher: for example, “Thabor” favors Mont Thabor over urban namesakes. Geographic qualifiers still matter, accents are ignored, and nearby duplicates are merged. All returned matches remain accessible in the scrolling list, including towns, businesses and addresses. Decimal coordinates work directly. Choose a match to center the map. Search stays off during recording.
- Plan IGN through the public WMTS service. Online mode automatically keeps viewed tiles permanently in local storage; the camera is remembered across restarts. No provider account or key is needed.
- A GPX library: open, share or select multiple files; preserve them separately; select one to follow; show/hide others; rename, export, or remove a route. Recordings remain separate; swipe left to delete a finished hike directly. Earlier single-route databases are migrated.
- One **Add files** action detects GPX, OruxMaps source XML, raster MBTiles, or BRouter regional RD5 data. Android Open with/Share is registered for GPX/XML, including multi-file sharing. XML currently recognizes public Plan IGN; other providers are reported as unsupported.
- Route-specific offline map corridors, saved pack completion checks, pause/resume, update checks, and shared-tile-safe removal. Pack management lives under the map's layers icon. There is no Offline tab or duplicate preview/save-current-area button.
- **Embedded BRouter 1.7.9**, walking profile and lookups. No companion app, external routing server or paid API. Routing data is downloaded/resumed inside Cairn or imported as RD5 files. Calculations are explicit, bounded and cancellable.
- All drawers use the full available width; variable lists use full height and smaller drawers fit their content. GPS settings apply immediately, with marked intervals for taps and precise whole-second selection while dragging.
- GPS recording in a foreground service, local SQLite storage, a 20-second default with adjustable 1–3600-second requests on a logarithmic slider (60 seconds at the midpoint), and off-route alerts from those same fixes. Manual Locate can use network location outside a hike; during recording it only reuses the recording position and never starts additional requests.
- Transient feedback appears at the top center. No accounts, ads, analytics or cloud sync.

## Install and try

Install the latest APK from [Releases](https://github.com/cairn-map/android/releases) on Android 8.0 or newer. The first automatic release after 0.5.0 uses a replacement signing key and needs a one-time uninstall/reinstall, which removes local app data. Later releases reuse that key and update in place. Passing PRs merge automatically; merged source is tested, signed and published by GitHub Actions once the signing secrets are configured. See [release setup](docs/UPDATES.md).

1. Tap the crossed-cloud icon on the right to load Plan IGN. Pan and pinch; viewed tiles are saved as they arrive. Tap the cloud again for offline maps. This keeps only visited tiles/zoom levels, not an entire area at every detail level.
2. Open GPX/XML from an Android file manager and choose Cairn, or share files to it. Routes → Add files is a single fallback picker with multiple selection. Each GPX stays in the library; several can be displayed together. One route is selected for guidance at a time.
3. Tap a route in Routes for Show/select, Follow, Save offline corridor, Export, visibility, Rename or Remove. Download a corridor before a trek to cover the full path and chosen detail, including space between GPX points. Partial jobs remain resumable from the map's layers menu.
4. To create a route, choose Routes → Create route, long-press map points, then Route. For graph data, use Routes → Routing data or More while planning. Regions are selected from the route/planning points; Regions → Prepare map center lets you prepare another area. Download missing data before going offline. Regions also offers removal/update-by-redownload and discarding paused files.
5. Tap the floating GPS pill to choose the recording interval and alerts; changes apply immediately. Tap the slider to choose the nearest marked interval (1, 5, 20, 60, 180, 600 or 3600 seconds), or drag to choose any whole second. Start hike requests precise location permission and optionally notifications. Online preview and graph/map downloads stop during recording. Explicit offline calculations remain available with installed graphs and preserve the GPS interval.
6. Lock the screen for a short test walk. Stop with Finish and export the recording from Routes. Check your phone's actual screen-off recording behavior before a longer trek.

Android file managers differ in MIME reporting. Typed GPX/XML content URIs work even with opaque IDs. Generic binary/plain-text files are offered by filename where exposed; use Add files when a provider does not supply a usable type or filename. Source XML configures the supported built-in Plan IGN source without enabling network access. It does not install arbitrary providers or execute comments/headers.

Raster MBTiles accepts PNG/JPEG tiles in TMS or declared XYZ order. Vector MBTiles and Mapsforge `.map` files are not supported. Routing RD5 imports must retain their original region filename, such as `E5_N45.rd5`.

## Updates on your phone

Open **Routes → App updates → Check**. If a newer release is available, tap **Download update**, then **Install downloaded update** and confirm in Android. Android may first ask you to allow Cairn to install apps; return and tap Install again after granting that permission. No GitHub account or token is needed. Checks only happen when requested and downloads are never automatic. See [update behavior and release publishing](docs/UPDATES.md).

## Offline routing and storage

BRouter code/profile data is in the APK; geographic routing graphs are not. Its 5 × 5 degree graph regions can be hundreds of MB, separate from Plan IGN map tiles. Downloads use HTTPS, range/validator resume, partial files, index/version validation and atomic installation. A region is only published after the transfer completes and validates. Block checksums are checked by BRouter when read. Graph updates are explicit: remove an installed region, then download it again.

Routing uses OpenStreetMap-derived connectivity with the upstream hiking-mountain profile. It can differ from the paths drawn by Plan IGN. See [routing implementation](docs/ROUTING.md) and [third-party notices](THIRD-PARTY-NOTICES.md).

Visited map tiles stay on disk until explicitly removed through the layers menu. Clearing the viewed-map cache preserves downloaded corridors. A downloaded corridor is capped at 20,000 tiles, with Standard/Detailed/Maximum detail (16/17/19) and an estimated size before download. Corridors follow the selected route and width; only tiles touching the buffered route are selected at the chosen detail. Downloads start with detailed tiles, followed by lower zoom levels for context. An individual overview tile can cover roughly 100 km per side; that square is overview coverage, not the width of the detailed corridor. Saved dates are tile-check dates, not field-survey dates. Complete packs can be refreshed from the layers menu; other packs' shared tiles and unrelated viewed tiles are retained when a pack is removed.

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
