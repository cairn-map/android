# Real routing fixture

`dreieich.pbf` is the upstream BRouter v1.7.9 map-creator test fixture:
https://github.com/abrensch/brouter/blob/v1.7.9/brouter-map-creator/src/test/resources/dreieich.pbf

The two small RD5 graphs were generated from that PBF using the same tag's `OsmFastCutter`, `PosUnifier` and `WayLinker`, lookup/profile files, and small test elevation fixtures. The process follows upstream `MapcreatorTest`:
https://github.com/abrensch/brouter/blob/v1.7.9/brouter-map-creator/src/test/java/btools/mapcreator/MapcreatorTest.java

These graphs cover a small real network crossing latitude 50°, so application tests can calculate actual walking geometry across a regional boundary. They are not geographic packs for use on a trek and are not bundled in the APK.

Data © OpenStreetMap contributors, Open Database Licence (ODbL):
https://www.openstreetmap.org/copyright

The original PBF is included here alongside the derived graphs. Upstream map compiler code is MIT licensed; see the retained engine licence.
