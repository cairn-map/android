# Product definition — 21 October 2026

## Purpose

Build a native Android trekking app for day hikes and multi-day remote treks. Preserve the core offline capabilities the user values in OruxMaps while making the interface smaller, clearer, and easier to operate at junctions.

Initial distribution: the user and close friends. Public release may follow later. No account or backend is required for the initial workflow.

## Mandatory zero-cost constraint

All app dependencies, external tools, map access, routing data, and licences must be free of charge. No paid API, subscription, mandatory paid companion app, paid map licence, or required paid hosting can be part of the solution. Avoid expiring trials and dependencies requiring a payment card. Private distribution can use APK files without a paid store account.

The user selected **Plan IGN** as the map source after reviewing the alternatives. Use the free public Plan IGN service as the default. Keep source attribution visible. Neither Plan IGN HD nor SCAN 25 is required by this selection. See COSTS.md and PLAN-IGN.md.

## Agreed requirements

| Area | Decision |
| --- | --- |
| Maps | Plan IGN, selected explicitly by the user. Use the current public service and offline snapshots prepared before the trek. |
| Cost | Zero mandatory fees for development, external tools, data, licences, routing, and private distribution. |
| Offline preparation | Both whole-area selection and a corridor around a GPX route. |
| Recording | GPS track recording with adjustable update intervals. |
| Endurance | Maximize battery efficiency at each user-selected interval, using OruxMaps as a comparison baseline. No arbitrary daily battery target. |
| GPX | Import, follow, record, create via trail routing, and export. |
| Routing | Creation must work completely offline. |
| Rerouting | Only on explicit user request. |
| Alerts | Off-route guidance; turn alerts optional and initially off until their energy cost is understood. |
| Interface | Modern, restrained, map-first; advanced options outside the main hiking workflow. |
| Use while hiking | Screen usually off, map checked briefly at junctions. |

France-first coverage and an English interface are provisional implementation assumptions. Cross-border coverage and interface language have not been explicitly selected by the user.

## Main workflow

For map preparation, compare publication/edition dates with the available information about survey/source dates. A recently updated catalogue record is not proof that every path was recently surveyed. Show the offline package preparation date and available source edition to the user. Evaluate daily Plan IGN J+1 as a working-data freshness reference, not automatically as the main trekking basemap.

Before departure: import or create a route → download its corridor or a larger area → verify both map and routing coverage → set the GPS interval.

On the trail: start a hike → put the phone away → receive enabled guidance from requested fixes → glance at the map when needed → manually request a new route if desired.

After the hike: stop recording → inspect and export GPX. Keep recordings local by default.

## Interface direction

Two destinations: Map and Routes. No full-width header. Navigation and recording controls float over the map; location, map mode, saved maps and GPS interval are reachable on the right. Viewed map tiles are kept implicitly; complete offline corridors belong to individual GPX routes. The GPX library retains multiple files and supports separate visibility and a selected route for guidance. Use warm neutral surfaces, forest green accents, readable type, and touch targets of at least 48 dp. Validate outdoors, with large fonts, and with one-handed operation.

## Battery requirements

No hidden escalation to faster GPS updates for turn alerts, off-route detection, opening the screen, route display, or Locate during recording. Indoor/network Locate is bounded and only runs outside a recording; hiking Locate reuses the recorded position. Stop subscriptions when a hike stops. Avoid continuous display refresh and continuous application-held wake locks. Embedded routing runs only after an explicit request, including offline while hiking if graph data is already present. Network preparation is disabled during recording.

Long intervals necessarily reduce the timeliness of observation-based guidance. Show the last fix time and the chosen interval; never imply continuous live position when fixes are sparse.

Only comparable device trials can establish efficiency relative to OruxMaps. Track completeness, missed fixes, and alert latency must accompany energy results so a stopped recorder cannot appear successful.

## Delivery sequence

1. Native starter: file interchange, imported offline maps, recording, interval control, and offline routing integration.
2. Version 0.4.0 includes retained Plan IGN views, route-corridor packs, a GPX library, and embedded BRouter with internal routing-data downloads. Validate these workflows on devices.
3. Measure energy and reliability on devices, improve location scheduling, and add opt-in turn cues using the existing fixes.
4. Polish route editing, accessibility, localization, and friend-group installation/update handling.

The starter does not meet all requirements yet. README and VALIDATION distinguish implemented behavior from planned work.
