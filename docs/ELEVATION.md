# Sea-level elevation

Planned terrain elevations and displayed/exported recording elevations use heights above mean
sea level. Android `Location.getAltitude()` supplies WGS84 ellipsoid heights, which can differ
by tens of metres. Cairn retains that original measurement and its explicit reference, then uses
an already supplied Android mean-sea-level height when available (Android 14+), otherwise:

`sea-level height = GPS ellipsoid height - EGM96 geoid separation`

The full global 15-arc-minute EGM96 grid is bundled offline. A bilinear lookup uses four nearby
grid values. This aligns the vertical reference with the terrain-height convention; it is not
terrain snapping or a correction of GPS noise. Local national height references and terrain models
can still differ, and unreliable altitude changes remain subject to the recording filter.

## Slow walking and stairs

A sub-metre horizontal step does not invalidate an otherwise usable altitude fix. At a
one-second GPS interval, slow climbs and stairs frequently produce these steps. The filter
holds its last accepted estimate until displacement from its movement anchor reaches 3 m,
then applies the existing altitude uncertainty band. It uses net displacement rather than
accumulating stationary jitter. This keeps slow progress continuous without counting altitude
drift while standing still. Poor vertical accuracy (over 10 m), missing altitude and abrupt
vertical jumps still exclude samples. Raw stored and exported measurements are unchanged.

The display remains a filtered GPS estimate: a few stair risers can be smaller than its
uncertainty band, and dashed connectors describe excluded measurements rather than terrain
steepness or the presence of steps.

## Profile readouts and following

Totals, point details and GPS always share a single text line above the graph in portrait
and landscape. Spare width is distributed between metric groups. Timestamped tracks also
show elapsed time. While inspecting a planned route, a compact blue GPS percentage retains
the live route position if a recent accurate fix lies within 50 m of that route; its full
distance and altitude remain available to accessibility. Large text scrolls horizontally
without wrapping or adding header height.

During recording, all touches pause map following until 5 seconds after release; another
interaction restarts that delay. Keyboard/accessibility profile seeking also restarts it.
The inspected point remains at its selected distance when the live track grows. When following
resumes, the manual profile cursor clears and the camera follows existing recording fixes.
Find my location clears inspection and resumes immediately. Editing suspends automatic following.
Outside recording, manual inspection and map panning persist until an explicit action such as
Find my location or selecting another route; no continuous GPS subscription is started.

## Short display gaps

The profile draws a subdued dashed connector across an internal missing/excluded stretch only
when accepted finite endpoints lie in the same uninterrupted track segment and are at most 50 m
apart along the route (`ElevationProfile.MAX_DISPLAY_GAP_METERS`). Its straight line uses cumulative
distance, rather than sample count, for linear interpolation. Existing uncertainty shading remains;
the accessibility description identifies the dashes as a display-only estimate and retains
“uncertain elevation excluded” for recordings or “elevation unavailable” for imported tracks.

Leading/trailing gaps, segment boundaries, longer gaps and zero-distance endpoints remain
unconnected. The cursor continues to show unavailable altitude inside these gaps, including dashed
ones. No inferred samples enter stored points, GPS filtering, ascent/descent or GPX export. Connector
endpoints are tracked incrementally with constant work per arriving fix; rendering caches their
path alongside the accepted profile and shading. No elevation service or extra GPS request is used.

## Storage, old recordings and GPX

Database version 5 tags historical points belonging to saved recordings as WGS84; their numeric
measurements are not rewritten. Routes and imported elevations retain their sea-level reference.
New fixes preserve raw GPS height and any already supplied sea-level height in the same SQLite
insert. Edits, undo and versioned drafts preserve this metadata. Older GPS draft formats are read
with their original WGS84 reference.

GPX `<ele>` contains sea-level height. Original raw GPS height is retained in the optional
`cairn:ellipsoidHeight` extension (namespace `https://cairn-map.github.io/gpx/1`, metres above
WGS84). Reimport preserves that raw value and the exported sea-level value; it never applies
the conversion twice. Ordinary GPX without this extension follows the standard sea-level
convention. Ambiguous historical GPX exports without reference metadata cannot be safely
identified from names, magnitudes or creator strings; they are not guessed or shifted automatically.
The original files on disk are never modified.

## Battery and model provenance

The recorder never loads or looks up the geoid. It only copies fields already present in the
received fix. Display/filtering and user-requested export perform the conversion. The model is
loaded lazily once, cached as approximately 2 MB of immutable shorts, and adds approximately
1.7 MB to the APK. No GPS subscription, cadence change, sensor, timer, wake lock or recording-time
network request is added. Missing/corrupt bundled data produces unavailable height, not a false
zero correction. The existing virtual performance matrix and budgets remain unchanged.

Source: PROJ `egm96_15.gtx`, as distributed by Debian `proj-data`.
Original SHA-256: `c02a6eb70a7a78efebe5adf3ade626eb75390e170bb8b3f36136a2c28f5326a0`.
The source has a -90/-180 degree origin, .25 degree spacing and 721 × 1440 samples.
The complete grid is quantized to centimetres (at most .005 m rounding error) and deterministically
gzip-compressed. This numeric precision does not imply centimetre geoid/GPS accuracy.

Reproduce with `python3 scripts/prepare_geoid.py /path/to/egm96_15.gtx`.
The bundled file SHA-256 is `4194ede8b34d4407f494b0f2150d40aea2d8a0e1f841ac376e7aa3e2698b91f8`.
Its 12-byte big-endian header is `EGM96CM1` plus unsigned 16-bit rows/columns, followed by
signed 16-bit centimetre samples in the source's south-to-north, west-to-east order.

The PROJ distribution's attribution and MIT-style terms are preserved in
`app/src/main/resources/app/cairn/trek/EGM96-NOTICE.txt`, bundled in the APK and available through
Routes → Licences & attribution. See [third-party notices](../THIRD-PARTY-NOTICES.md).
