# Embedded offline routing — 0.4.0

Cairn includes BRouter 1.7.9's core, map-access, codec, expressions and utility Java modules. It calls the engine directly on a worker, without an external app, Android service binding or local HTTP server. Source is unmodified and retained under `routing-engine/src/main/java`; the walking profile and lookups are application assets.

Upstream: https://github.com/abrensch/brouter/tree/v1.7.9

Downloaded source archive SHA-256: `54e6b540597e0cfaca9aa6802f6ce67a9cf6de7f8a81d74061981f00f5506ecc`.

The upstream MIT licence is included in `routing-engine/LICENSE`, bundled as an application asset, and available through Routes → Licences & attribution. Geographic graph data derives from OpenStreetMap under ODbL. Both code and graph access have no required fee.

## Data preparation

Routes → Routing data identifies missing 5 × 5 degree RD5 regions along the selected GPX or planning points, including a small border margin. Regions → Switch to map center appears only when planning points or a selected GPX determine the current regions. Without a route or planning points, the routing-data dialog already uses the map center; the redundant switch is hidden. Download/resume is handled inside Cairn; Add files also accepts correctly named RD5 files. Installed regions and paused downloads can be removed through Regions. Updates are explicit removal/redownload.

The source is `https://brouter.de/brouter/segments4/`. Preparation is a cancellable dataSync foreground service with a Pause notification. HTTP ranges use ETag/Last-Modified validators, with atomic resume metadata. A changed server file restarts rather than appending to an old revision. Fully received partials surviving process death can be finalized after matching an HTTP 416 validator response.

Downloads are capped at 1.5 GB per region and check allocatable storage before transfer. Completed files are checked for index ordering, expected length, lookup version 11 and available top-index CRC. The engine checks graph-block CRCs when read. Only complete, validated files are renamed into the installed set; failed/paused jobs retain resumable partials. Nothing auto-downloads during recording.

A live bounded range probe on 21 October 2026 read only 200 bytes of `E5_N45.rd5`: HTTP 206, lookup version 11, total file size 252,717,722 bytes, with an ETag. A full geographic region was not downloaded for that probe.

## Calculations

Calculations use the upstream `hiking-mountain.brf` profile, stored as `hiking.brf`, with turn-cue generation disabled. The upstream profile includes its SAC difficulty preferences; no route suitability guarantee is implied by a successful calculation. Raster Plan IGN paths and OSM routing connectivity can differ.

Only explicit calculations run. Time is bounded to 60 seconds and working-memory configuration is capped according to the device memory class. Cancellation, activity pause, hike start, and activity destruction terminate work; parsed profile caches are released afterward. A failed calculation retains the previous route and never produces a fake straight-line trail. Successful geometry is saved as a new GPX-library entry.

BRouter may discover an additional region while searching a detour. Its error identifies missing data; move the map to the neighboring area and use Regions → Switch to map center if route or planning points are selected; otherwise Routing data already uses the map center. The selected GPX and other library files remain intact.

## Verification

A small genuine OSM fixture was compiled with BRouter's own map compiler into two RD5 graphs crossing latitude 50°. The embedded application worker successfully calculated a walking route through both graphs in an Android framework simulation, with no companion installed or network routing call. Tests also cover missing graphs, header/name validation and explicit offline calculations during recording without extra location requests.

Transport tests exercise an interrupted body followed by HTTP 206 resume, a changed file returning 200, complete-partial recovery through 416, and cancellation without publishing a region. These do not replace long-running data preparation and memory testing on real phones.