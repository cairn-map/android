# Embedded offline routing — 0.4.0

Cairn includes BRouter 1.7.9's core, map-access, codec, expressions and utility Java modules. It calls the engine directly on a worker, without an external app, Android service binding or local HTTP server. Source is unmodified and retained under `routing-engine/src/main/java`; the walking profile and lookups are application assets.

Upstream: https://github.com/abrensch/brouter/tree/v1.7.9

Downloaded source archive SHA-256: `54e6b540597e0cfaca9aa6802f6ce67a9cf6de7f8a81d74061981f00f5506ecc`.

The upstream MIT licence is included in `routing-engine/LICENSE`, bundled as an application asset, and available through Routes → Licences & attribution. Geographic graph data derives from OpenStreetMap under ODbL. Both code and graph access have no required fee.

## Data preparation

The editor checks the required regions automatically when nodes change. Installed data goes directly to the offline calculation. Missing data opens one **Download routing data?** confirmation with the missing-region count and **Not now** / **Download & route** actions. Confirmation starts the existing resumable foreground downloader, shows region/percentage/size progress in the planner, and automatically calculates the original points once all required files are installed. Total size becomes available per region during transfer; the confirmation explains that regions can be hundreds of MB.

Cancel pauses preparation and retains the planning points. Failed or paused transfers return to Route, where another confirmation resumes the partial files. Completion is tied to the originating request, so unrelated map or region downloads cannot start a route. Planning points stay fixed during preparation. Activity recreation retains the pending request; background completion waits until Cairn resumes. Starting a hike or canceling planning clears the pending calculation. Missing-data preparation remains blocked during recording; installed-data calculations remain available.

There is no routing-region inventory or manual region download screen. **Routes → Clear all routing data** confirms removal of all installed RD5 graphs and paused transfer files. Clearing is blocked during recording, editing, calculation or active routing downloads, including a second check when confirming. Routes, recordings, map packs and the bundled engine/profile remain available. Missing routing data is requested automatically during route editing. Add files also accepts correctly named RD5 files. To refresh geographic graphs, clear them and let editing download the required data again.

The source is `https://brouter.de/brouter/segments4/`. Preparation is a cancellable dataSync foreground service with a Pause notification. HTTP ranges use ETag/Last-Modified validators, with atomic resume metadata. A changed server file restarts rather than appending to an old revision. Fully received partials surviving process death can be finalized after matching an HTTP 416 validator response.

Downloads are capped at 1.5 GB per region and check allocatable storage before transfer. Completed files are checked for index ordering, expected length, lookup version 11 and available top-index CRC. The engine checks graph-block CRCs when read. Only complete, validated files are renamed into the installed set; failed/paused jobs retain resumable partials. Nothing auto-downloads during recording.

A live bounded range probe on 21 October 2026 read only 200 bytes of `E5_N45.rd5`: HTTP 206, lookup version 11, total file size 252,717,722 bytes, with an ETag. A full geographic region was not downloaded for that probe.

## Calculations

Calculations use the upstream `hiking-mountain.brf` profile, stored as `hiking.brf`, with turn-cue generation disabled. The upstream profile includes its SAC difficulty preferences; no route suitability guarantee is implied by a successful calculation. Raster Plan IGN paths and OSM routing connectivity can differ.

Only explicit calculations run. Time is bounded to 60 seconds and working-memory configuration is capped according to the device memory class. Cancellation, activity pause, hike start, and activity destruction terminate work; parsed profile caches are released afterward. A failed calculation retains the previous route and never produces a fake straight-line trail. Successful geometry is saved as a new GPX-library entry.

BRouter may discover an additional region while searching a detour. Its error identifies missing data; adjust the planning nodes toward the neighboring area to request that data, then retry the intended route. The selected GPX and other library files remain intact.

## Routing preferences

**Routes → Default routing preferences** controls the defaults for future routing.
A new or imported route copies them when routing is first used, or when its routing
preferences are opened. Each routed route saves its own parameters with its geometry
and planning nodes. Reopening, editing, or recreating the editor retains those parameters;
changing global defaults leaves existing route parameters and geometry unchanged.

The editor's **Routing preferences** action applies only to that route. Changes invalidate
its calculated geometry and recalculate after the preferences drawer closes. **Use current
defaults** explicitly copies the latest global settings into the route and recalculates;
route changes never overwrite the global defaults. Recording edits do not use routing.

On upgrading older databases, existing routes receive a snapshot of the current global
preferences without recalculation. Their historical calculation settings were not recorded
and cannot be recovered. Newly imported GPX geometry remains untouched until edited.

## Verification

A small genuine OSM fixture was compiled with BRouter's own map compiler into two RD5 graphs crossing latitude 50°. The embedded application worker successfully calculated a walking route through both graphs in an Android framework simulation, with no companion installed or network routing call. Tests also cover missing graphs, header/name validation and explicit offline calculations during recording without extra location requests.

Transport tests exercise an interrupted body followed by HTTP 206 resume, a changed file returning 200, complete-partial recovery through 416, and cancellation without publishing a region. These do not replace long-running data preparation and memory testing on real phones.
