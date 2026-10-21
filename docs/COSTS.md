# Zero-cost policy and dependency review

All tools, dependencies, map access, routing data, and licences must be free of charge. This requirement supersedes the earlier proposal to investigate a paid IGN licence. Free use still carries attribution and other licence obligations.

| Component | Cost position | Status |
| --- | --- | --- |
| Native Android app | No paid runtime service or SDK dependency. | Existing starter. |
| Temurin JDK, Gradle, Android SDK/build tools | No mandatory paid IDE or licence subscription; Android SDK retains its own terms. | Local command-line build already verified. |
| JUnit and test XML parser | No licence fee; absent from the APK. | Existing tests. |
| BRouter | Free MIT-licensed engine; required code and walking profile are embedded. | No companion app needed in 0.4.0. |
| Routing data | Free OSM-derived BRouter regions; no paid routing API. | Downloaded/resumed or imported inside Cairn. |
| GPX and storage | Local files and SQLite; no cloud subscription. | Implemented. |
| Distribution to friends | Share APK files without a paid store account. | Debug APK available; stable signing/update workflow remains work. |
| Hosting | No required backend or paid server. | None required by starter. |

Sources: [Android SDK terms](https://developer.android.com/studio/terms), [BRouter](https://github.com/abrensch/brouter), [F-Droid package](https://f-droid.org/en/packages/btools.routingapp/).

## Maps

The user selected [Plan IGN](https://www.data.gouv.fr/datasets/plan-ign), published under Licence Ouverte 2.0, without a map licence fee. Version 0.2.0 uses the public WMTS endpoint without an access key. OSM remains the free data basis for BRouter routing, under [ODbL](https://www.openstreetmap.org/copyright).

The app implements retained online map tiles, route corridor downloads, and optional raster MBTiles import. It requires no paid map service, subscription, hosting or companion app. Graph regions can be large; ordinary network/data-plan costs are outside the software and data licence costs.

## Downloads

Viewed Plan IGN areas are retained automatically, and complete route corridors can be downloaded separately. Standard OSM raster tiles cannot supply bulk offline downloads under their [usage policy](https://operations.osmfoundation.org/policies/tiles/). BRouter uses free graph data rather than those raster tile servers.

Regional vector packs may require downloading whole regions even for a corridor selection; provider formats determine the actual download granularity. Do not promise byte-exact corridor downloads before selecting the source and format.

No mandatory paid service, payment-card-backed free tier, or expiring trial may become a required dependency. Public release, if requested later, must preserve this constraint unless the user explicitly changes it.
