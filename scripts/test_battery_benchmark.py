import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import battery_benchmark as bench


def report():
    runs = []
    for screen in bench.SCREENS:
        for interval in bench.INTERVALS:
            for repeat in range(3):
                runs.append({
                    "trial_id": f"{screen}-{interval}-{repeat}", "revision": "a" * 40,
                    "device": "physical-fixture", "os_build": "test-build", "profile": "matched-v1",
                    "build_type": "release", "screen": screen, "interval_seconds": interval,
                    "duration_seconds": 3600, "invalid_reasons": [], "evidence": {"trace": "fixture"},
                    "metrics": {"mah_per_hour": 10, "app_wakelock_seconds": 0,
                                "screen_on_percent": {"off": 0, "on": 100, "check": 5}[screen],
                                "unrelated_wakeups": 0, "gps_gap_p95_seconds": interval,
                                "gps_max_gap_seconds": interval * 1.1, "frame_p95_ms": 10,
                                "fix_coverage": 1, "screen_off_suspend_percent": 80,
                                "offscreen_frames": 0, "idle_frames": 0,
                                "track_error_p95_m": 5, "distance_error_percent": 1,
                                "gnss_active_percent": 10, "display_refresh_hz": 60,
                                "between_fix_suspend_percent": 100},
                })
    return {"schema": 1, "status": "measured", "runs": runs}


class ParsingTest(unittest.TestCase):
    def test_android_durations_and_accounting(self):
        self.assertEqual(bench.duration("1d 2h 3m 4s 500ms"), 93784.5)
        self.assertEqual(bench.duration("0"), 0)
        for value in ("unknown", "-3s", "3s broken", ""):
            with self.assertRaises(ValueError):
                bench.duration(value)
        self.assertEqual(bench.battery_times(
            "Time on battery: 1h 2m 3s (100.0%) realtime, 7s 500ms (0.1%) uptime"), [3723, 7.5])
        self.assertEqual(bench.battery_times(
            "Time on battery screen off: 10s (1%) realtime, 2s uptime", off=True), [10, 2])
        self.assertIsNone(bench.battery_times("unsupported"))

    def test_missing_battery_fields_are_not_zero_or_unplugged(self):
        parsed = bench.battery_values("USB powered: false\nCharge counter: 1234000\nstatus: 3")
        self.assertFalse(parsed["unplugged"])
        self.assertEqual(parsed["charge_uah"], 1234000)
        self.assertIsNone(parsed["temperature_tenths_c"])
        parsed = bench.battery_values("AC powered: false\nUSB powered: false\nWireless powered: true")
        self.assertFalse(parsed["unplugged"])
        self.assertIsNone(bench.frames("No process found"))
        self.assertEqual(bench.frames("Total frames rendered: 12\nTotal frames rendered: 8"), 20)

    def test_gpx_includes_terminal_gap_and_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.gpx"
            path.write_text('<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>' +
                            ''.join(f'<trkpt lat="0" lon="0"><time>2026-01-01T00:00:{n:02}Z</time></trkpt>'
                                    for n in (0, 10, 20)) + '</trkseg></trk></gpx>')
            result = bench.gpx_metrics(path, 1767225600, 1767229200, 10)
            self.assertEqual(result["gps_max_gap_seconds"], 3580)
            self.assertLess(result["fix_coverage"], .01)
            path.write_text(path.read_text().replace("00:00:20", "00:00:10"))
            with self.assertRaises(ValueError):
                bench.gpx_metrics(path, 1767225600, 1767229200, 10)

    def test_energy_uses_charge_delta_and_suspend_uses_elapsed_minus_uptime(self):
        start = {"elapsed_seconds": 100, "epoch": 1000, "boot_id": "boot",
                 "device": "phone", "os_build": "build", "package_version": ["1"],
                 "build_type": "release", "battery": {"unplugged": True, "status": 3,
                 "charge_uah": 4000000, "temperature_tenths_c": 250}, "capture_seconds": 1,
                 "off_times": [100, 10], "battery_times": [100, 10], "frames": 20}
        end = copy.deepcopy(start)
        end.update(elapsed_seconds=3700, epoch=4600, off_times=[3700, 370], battery_times=[3700, 370])
        end["battery"]["charge_uah"] = 3900000
        result = bench.summarize(start, end, {"interval_seconds": 20})
        self.assertEqual(result["metrics"]["mah_per_hour"], 100)
        self.assertEqual(result["metrics"]["screen_off_suspend_percent"], 90)
        self.assertEqual(result["metrics"]["total_frames"], 0)
        self.assertEqual(len(result["invalid_reasons"]), 1)  # GPX not supplied
        end["battery_times"] = [200, 20]
        self.assertTrue(any("accounting" in e for e in bench.summarize(start, end, {"interval_seconds": 20})["invalid_reasons"]))
        end["boot_id"] = "reboot"
        with self.assertRaises(ValueError):
            bench.summarize(start, end, {"interval_seconds": 20})


class ComparisonTest(unittest.TestCase):
    def test_equal_or_better_passes_and_includes_all_three_columns(self):
        fixture = report()
        passed, markdown = bench.compare(fixture, fixture, fixture)
        self.assertTrue(passed, markdown)
        self.assertIn("| Baseline | Previous | Current PR |", markdown)
        current = copy.deepcopy(fixture)
        for run in current["runs"]:
            run["metrics"]["mah_per_hour"] = 9
        self.assertTrue(bench.compare(fixture, fixture, current)[0])

    def test_both_baseline_and_previous_are_enforced(self):
        fixture = report()
        for which in ("baseline", "previous"):
            better = copy.deepcopy(fixture)
            for run in better["runs"]:
                run["metrics"]["mah_per_hour"] = 9
            args = [better, fixture, fixture] if which == "baseline" else [fixture, better, fixture]
            passed, markdown = bench.compare(*args)
            self.assertFalse(passed)
            self.assertIn("Regression:", markdown)

    def test_unknown_nan_negative_boolean_and_missing_measurements_block(self):
        fixture = report()
        for value in (None, float("nan"), float("inf"), -1, True, "10"):
            current = copy.deepcopy(fixture)
            current["runs"][0]["metrics"]["mah_per_hour"] = value
            self.assertFalse(bench.compare(fixture, fixture, current)[0], str(value))
        self.assertFalse(bench.compare({"status": "unmeasured"}, fixture, fixture)[0])

    def test_incomplete_or_mismatched_matrix_and_insufficient_repetitions_block(self):
        fixture = report()
        for mutate in (lambda r: r["runs"].pop(),
                       lambda r: r["runs"][0].update(os_build="other"),
                       lambda r: r["runs"][0].update(duration_seconds=4000),
                       lambda r: r["runs"][0].update(trial_id=r["runs"][1]["trial_id"]),
                       lambda r: r["runs"][0].update(build_type="debug"),
                       lambda r: r["runs"][0]["metrics"].update(screen_on_percent=100),
                       lambda r: r["runs"][0].update(invalid_reasons=["charged"])):
            current = copy.deepcopy(fixture)
            mutate(current)
            self.assertFalse(bench.compare(fixture, fixture, current)[0])
        incomplete = report()
        incomplete["runs"] = [r for r in incomplete["runs"] if r["screen"] != "check"]
        self.assertFalse(bench.compare(incomplete, incomplete, incomplete)[0])

    def test_lower_energy_cannot_hide_missing_fixes_or_background_work(self):
        fixture = report()
        for metric, value in (("fix_coverage", .5), ("gps_max_gap_seconds", 10000),
                              ("offscreen_frames", 1), ("idle_frames", 1),
                              ("app_wakelock_seconds", 1), ("unrelated_wakeups", 1),
                              ("frame_p95_ms", 20), ("display_refresh_hz", 0),
                              ("track_error_p95_m", 50), ("gnss_active_percent", 90)):
            current = copy.deepcopy(fixture)
            for run in current["runs"]:
                run["metrics"].update(mah_per_hour=1)
                run["metrics"][metric] = value
            self.assertFalse(bench.compare(fixture, fixture, current)[0], metric)

    def test_no_suspend_claim_for_short_intervals_but_long_intervals_require_quiet_sleep(self):
        fixture = report()
        for run in fixture["runs"]:
            if run["interval_seconds"] < 20:
                run["metrics"]["between_fix_suspend_percent"] = 0
        self.assertTrue(bench.compare(fixture, fixture, fixture)[0])
        fixture["runs"][6]["metrics"]["between_fix_suspend_percent"] = 98
        self.assertFalse(bench.compare(fixture, fixture, fixture)[0])

    def test_cli_fails_closed_for_unmeasured_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            baseline = path / "baseline.json"
            baseline.write_text(json.dumps({"schema": 1, "status": "unmeasured"}))
            result = subprocess.run([sys.executable, str(Path(bench.__file__)), "compare",
                                     "--baseline", str(baseline), "--previous", str(baseline),
                                     "--current", str(baseline), "--output", str(path / "report.md")],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertIn("auto-merge must remain off", (path / "report.md").read_text())


if __name__ == "__main__":
    unittest.main()
