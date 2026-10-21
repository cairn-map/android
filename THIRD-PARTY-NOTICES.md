# Third-party components

## BRouter

Version 0.4.0 bundles the BRouter 1.7.9 core, map-access, expressions, codec and utility Java modules, lookup data and upstream hiking-mountain profile. No companion service or AIDL interface is required. Regional map graphs are downloaded separately by the user.

Project and interface documentation: https://github.com/abrensch/brouter

BRouter is licensed under the MIT License. Copyright (c) 2019 BRouter contributors. The complete notice is retained in `routing-engine/LICENSE`, bundled in application assets and accessible through Routes → Licences & attribution. Source: https://github.com/abrensch/brouter/tree/v1.7.9.

Routing graphs derive from © OpenStreetMap contributors under the Open Database Licence (ODbL): https://www.openstreetmap.org/copyright. Data and code access have no mandatory fee.

## Gradle wrapper

Generated Gradle wrapper scripts and JAR are included for reproducible builds, under their upstream Apache License 2.0. See https://github.com/gradle/gradle/blob/v8.11.1/LICENSE.

## Maps

No IGN or other geographic map tiles are bundled. Imported maps retain their own source, attribution, and access terms. BRouter regional data is obtained separately and retains its own source terms.

## Test dependencies

JUnit 4.13.2 (EPL 1.0), KXml2 2.3.0 (BSD-style licence), and XML Pull API 1.1.3.1 (public-domain API terms) are build/test dependencies only and are not part of the APK.

Robolectric 4.14.1 is an MIT-licensed, test-only Android simulation framework. It and its test dependencies are not included in the APK. Source and licence: https://github.com/robolectric/robolectric.

The test resources include BRouter's upstream Dreieich OpenStreetMap PBF fixture and small graphs compiled from it, under the data's ODbL terms. The original PBF is included alongside the derived graphs for source access. These resources are test-only and absent from the APK. See `app/src/test/resources/routing/README.md`.

## Plan IGN

The app fetches Plan IGN from IGN's public service when the user explicitly enables preview or starts a download. The source is identified on the map and in offline package details. Dataset: https://www.data.gouv.fr/datasets/plan-ign. Licence Ouverte 2.0: https://www.etalab.gouv.fr/licence-ouverte-open-licence/.
