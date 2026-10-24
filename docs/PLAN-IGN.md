# Plan IGN integration — 0.4.0

The user selected Plan IGN on 21 October 2026. This replaces the pending basemap decision. All required licences and tools must remain free of charge.

## Source

- Provider: IGN, public Géoplateforme WMTS.
- Layer: `GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2`.
- Matrix set: `PM_0_19`; style: `normal`; format: `image/png`.
- No credentials, paid API key, or subscription.
- Attribution: © IGN, Plan IGN, Licence Ouverte 2.0. Save/check dates are shown separately from survey dates.

Primary references: [dataset and licence](https://www.data.gouv.fr/datasets/plan-ign), [WMTS documentation](https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/diffusion/wmts/), [service terms](https://cartes.gouv.fr/cgu/?lang=fr).

## User-provided XML

The supplied `onlinemapsources.xml` was read as configuration data. Entry 47, “France Cartes IGN-V2 (FR)”, identifies the same Plan IGN layer. Entry 40 identifies a different classic IGN map layer. Only Plan IGN was selected for this integration. The original file was not edited and its full provider catalogue was not bundled.

GPX/XML files can be opened or shared to Cairn by Android, or selected using the single Routes → Add files picker. XML recognizes the public Plan IGN entry, selects the built-in source without enabling network access, and reports the remaining providers as unsupported. Custom URLs, headers, cache/download flags, and comments are not executed or installed.

The XML's zoom cap is 15. Current IGN capabilities declare levels through 19. Both the current endpoint and one real tile were verified; the app uses the current supported range. Configuration flags such as `downloadable` describe client behavior, while provider terms determine permitted access.

## Behavior

The map starts offline. A visible preview control enables network fetching while preparing maps. Leaving the activity switches preview off. Starting a hike switches preview off and cancels the map download service; stored tiles remain available. Routing stays local through BRouter.

Online mode automatically retains viewed tiles and the camera is remembered across restarts. There is no separate save-current-area action or Offline tab. A selected GPX offers a complete buffered corridor download, including the path between widely spaced points and excluding bridges across disconnected segments. The detail slider exposes integer zooms 10–19 within the supported `PM_0_19` matrix set. Its visible labels, accessibility descriptions and download estimate use Regional overview, Local overview, Route overview, Route planning, Terrain detail, Trail detail, Walking navigation, Junction detail, Close-up detail and Maximum detail, respectively. These are scale-dependent renderings of the same Plan IGN layer, not independently selectable feature layers or extra survey detail. The default stays at 17 (Junction detail). New downloads include overview levels from 10 to the chosen maximum (for example, 10–17 when selecting Junction detail). The width slider covers 0.1–10 km on each side in 0.1 km steps, defaulting to 1 km. Packs inherit the selected route or recording title, with no name entry. The hard cap is 20,000 distinct tiles per package. Only tiles/levels actually visited are implicitly retained; complete corridor preparation remains explicit.

Tiles are fixed Web Mercator grid cells. Their approximate ground width is `40,075 km × cos(latitude) / 2^zoom`: at 45° latitude, about 111 km at zoom 8 and 28 km at zoom 10. Overview tiles contain the parent cells of the detailed corridor, so their coverage extends beyond its selected width. The slider selects maximum detail; new corridors include no tiles below zoom 10. Existing downloads retain their previously saved overview tiles.

Each route or recording keeps one corridor, linked by ID so renaming the path keeps its maps attached. The database enforces one linked corridor per path. Older duplicates consolidate to the highest maximum zoom; their other cached tiles remain available as viewed maps. Changing settings updates that corridor in place, reusing cached tiles and preserving the finest saved zoom. Every library row has a leading corridor button. Its checkmark means the corridor is complete and all tiles are present; a muted download icon means missing or incomplete. This button and the teal selected-path action open the detail/width selection drawer directly. It starts at the saved detail and width and offers resume/update, pause and removal controls when a corridor exists. Download completion refreshes these indicators. The database upgrade preserves all saved tiles and associates older named corridors when exactly one library entry has the matching title. Ambiguous older corridors remain accessible in the map's layers menu. Deleting a route leaves its downloaded maps accessible there as unlinked packs.

Named packages reference shared tiles in one local SQLite/MBTiles database. Completion requires every planned tile to be committed and present. Interrupted jobs retain their pending list for manual resume from the route's offline corridor menu; unlinked legacy packs remain in the map's layers menu. Update checks revisit each tile conditionally when validators are available. Removing a pack preserves tiles referenced by other packs and unrelated viewed tiles. Viewed tiles are no longer automatically pruned; the explicit clear-cache action keeps all downloaded packs.

The client identifies itself as Cairn and shares a conservative request-start throttle across preview and download clients. Preview and corridor downloads each allow up to four concurrent transfers, overlapping network waits while preserving that shared rate limit. Corridor tiles are committed as transfers complete; only committed tiles advance resumable progress. Visible tiles are queued center-first, without an invisible extra row/column. Camera changes clear obsolete jobs and cancel all in-flight requests; cancelled requests do not reserve future throttle slots. Going online pauses background map/graph preparation so the current view has priority. Cached offline enlargements remain as a backdrop while being replaced by real current-level detail. Connect/read timeouts and HTTP 429/503 retries are bounded. PNG dimensions, chunks, CRCs and end markers are checked before storing a tile.

On the first tile read, saved finer tiles are composited over the overview or parent fallback, including imported XYZ/TMS raster maps. This needs no network request or visit to the finer zoom level. Composition uses a 512-pixel tile and at most 64 saved descendants within eight zoom levels to bound decoding and memory. Partial detail keeps the overview in uncovered areas, and retained finer tiles draw above coarser tiles while zooming.

Outside saved coverage, offline map gaps remain visible. Up to four levels of saved parent tiles can be enlarged when finer tiles are absent. Enlarging changes display size, not underlying data detail.

## Still requires device validation

Background completion/recovery across Android manufacturers, real radio energy behavior, large-package storage pressure, and the native map display on a phone. A successful desktop client probe and simulated Android tests do not establish field endurance.
