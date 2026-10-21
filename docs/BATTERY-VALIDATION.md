# Battery and recording validation

## Goal

Minimize energy use at the user-selected GPS interval while retaining a functioning recorder and honest guidance. Compare Cairn with OruxMaps under matching conditions. No comparative endurance result is available yet.

## Current implementation

Uses Android's GPS location provider in a location foreground service, requests the selected minimum update interval, and removes updates when recording stops. No application-held continuous wake lock, screen-on request, compass polling loop, map animation loop, or automatic rerouting is present. Off-route detection consumes the same received fixes. Map network access is for explicitly requested Plan IGN preparation, then preview and downloads stop when recording begins.

The manual Locate action can use Android's network location provider and recent cached positions outside a recording. It requests available GPS/network providers only while visible, for at most 30 seconds, and cancels on a usable fix, activity pause, a replacement request, or hike start. Version 0.4.0 always reuses the recording position during a hike, even if stale, without starting extra requests. Route/guidance-setting updates leave the GPS subscription intact when the interval is unchanged, and service cleanup removes queued subscriptions as well as active ones.

Map and routing-data preparation stop at hike start and cannot be enabled during recording. Offline BRouter calculations can still be explicitly requested with installed data: they use bounded CPU work without changing GPS subscriptions or contacting a routing server. Calculations cancel on activity pause and release profile caches afterward. Screen draws occur on input/data changes; no idle rendering loop runs.

Changing a request interval does not prove the GNSS receiver powers down between fixes. Vendor behavior, acquisition time, signal conditions, and other apps' location requests affect consumption. [Android's battery guidance](https://developer.android.com/develop/sensors-and-location/location/battery/optimize) supports limiting subscriptions and bounding temporary requests; it does not establish this app's endurance.

## Trial procedure

1. Record device model, OS/build, battery health, and the two app versions. Use the same route, map coverage, system location settings, screen brightness, connectivity state, and guidance configuration.
2. Charge and thermally stabilize the phone. Run an idle baseline with recording off.
3. Test intervals of 1, 5, 15, 60, and 300 seconds, or the closest explicitly available OruxMaps settings. Note when settings are not equivalent.
4. Start with screen-off recording and no guidance. Repeat with off-route alerts. When turn cues exist, measure them separately without changing the GPS policy.
5. Run repeated trials in alternating app order. Use a repeatable route with open sky, forest, stationary breaks, and difficult reception. Do not run both active recorders simultaneously for comparative energy trials because they may share or alter location behavior.
6. Measure energy using available device charge-counter or external power instrumentation and Android battery diagnostics. Capture requested intervals, actual fix timestamps, accepted/rejected fixes, gaps, distance, process survival, and screen-on duration. A whole-percent battery change alone is too coarse for short trials.
7. Test system Battery Saver, screen locking for several hours, location being disabled, notifications being denied, permission revocation, process death, and low storage.

Use at least one recent Google device, one Samsung device, and one older/low-end device when possible. This is a proposed matrix, not a claim of tested support.

## Acceptance

- Every comparison includes track quality, delivered interval distribution, and unexpected recording gaps.
- Stopping a hike removes GPS subscriptions.
- A manual locate request times out and does not continue when the app goes into the background.
- Alert logic never raises the requested GPS frequency without explicit user choice.
- No successful endurance claim when the service stopped recording.
- Device-specific regressions are identified before recommending settings.

Long-interval modes may need a dedicated scheduling strategy after measurements. Evaluate reacquisition cost and delivery reliability before adopting receiver duty cycling or adding alarms/wake locks. Avoid equating fewer recorded points with lower hardware energy.
