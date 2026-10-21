# IGN map access under the zero-cost requirement

Updated 21 October 2026. This supersedes the earlier proposal to seek a paid offline-map licence. The user requires all licences, external tools, and data access to be free of charge. No purchase or paid licence request is planned.

**Decision: the user selected Plan IGN.** Version 0.2.0 integrates that free public source, including offline downloads. The comparison below is background research; classic SCAN 25 access is no longer a prerequisite. See PLAN-IGN.md for the implementation.

## Exact hiking maps: unresolved

IGN's direct SCAN access tutorial explicitly excludes individual downloads, including personal use. A personal key is therefore not a confirmed free solution for this app. [Current IGN access instructions](https://cartes.gouv.fr/aide/fr/partenaires/ign/representations-cartographiques-souveraines/creation-cles-donnees-scan/introduction/).

No zero-fee permission covering TOP 25 / SCAN 25 in this private app has been established. Sharing with friends must not be assumed to grant those rights. The earlier paid-licence path is incompatible with the user's budget.

## Free alternative: Plan IGN

IGN's Plan IGN dataset is published under Licence Ouverte 2.0. It is different from classic TOP 25 / SCAN 25 hiking cartography. See the [official dataset](https://www.data.gouv.fr/datasets/plan-ign). It is now the selected source; downloads follow the public service's applicable terms.

## Free alternative: an OpenStreetMap hiking map

OSM data is available under ODbL without a per-user licence fee. Offline hiking packs can be prepared with an appropriate renderer and attribution. See [OSM's licence](https://www.openstreetmap.org/copyright).

The standard OSM public raster tile server prohibits bulk offline downloads, so it cannot supply the region/corridor feature. Use permitted downloadable packs or generate them from bulk data. [OSMF tile policy](https://operations.osmfoundation.org/policies/tiles/).

OpenAndroMaps is free for private use, but its published terms restrict direct map links and bundling without written permission. It is not an automatically approved source for an integrated download button. Its detailed hiking maps also need Mapsforge support, which this raster-only starter does not yet have. [Provider terms](https://www.openandromaps.org/en/license).

## Selection

The user clarified that they want current, detailed France IGN mapping, then explicitly accepted Plan IGN. Preserve the mandatory zero-cost constraint. SCAN 25, restricted partner trail layers, HD compositing, and J+1 integration are not needed for the selected baseline.

## New findings: IGN detail and freshness

- **Plan IGN HD** is now listed in IGN's official catalogue under Licence Ouverte 2.0. It combines LiDAR-derived landscape detail with land cover information. Coverage and source dates need checking for the intended trekking areas. [Official dataset](https://www.data.gouv.fr/datasets/plan-ign-hd).
- **Plan IGN** is updated monthly at large scales; its pre-generated downloadable version is annual. A current offline preparation flow must account for that distinction. [Dataset description](https://www.data.gouv.fr/datasets/plan-ign).
- **Plan IGN J+1** is the most current working basemap used to check recent changes before they reach the official basemap. It is not automatically the most suitable hiking presentation. [IGN documentation](https://cartes.gouv.fr/aide/fr/guides-utilisateur/visualiseur-cartographique/signaler-une-anomalie/).
- **IGN contour lines** are published under Licence Ouverte 2.0 and derived from RGE ALTI. [Official dataset](https://www.data.gouv.fr/datasets/courbes-de-niveau-4).
- **SCAN 25 and the official marked hiking trail layer** include partner data and are subject to specific licences and royalties. A free composite must not be presented as automatically containing all of those hiking annotations. [IGN's July 2026 explanation](https://cartes.gouv.fr/aide/fr/partenaires/ign/generalites-ign/actualites/2026-07-plan-ign-hd-et-sentiers/).

The earlier composite-map proposal remains background research. The implemented baseline uses the selected Plan IGN alone, with offline preparation before departure and no network map fetching while recording.
