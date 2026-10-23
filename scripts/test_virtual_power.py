import copy
from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import virtual_power as power


def fixture():
    baseline = power.load(power.BASELINE)
    report = {k: baseline[k] for k in ("schema", "protocol", "sdk", "duration_seconds")}
    report.update(revision="a" * 40, harness_sha256="b" * 64, scenarios=[])
    report["render_cpu"] = [{"points": points, "samples": 7, "median_ms": 1.0, "p95_ms": 2.0}
                            for points in (1000, 10000)]
    for screen in baseline["screens"]:
        for interval in baseline["intervals_seconds"]:
            fixes = len(range(0, 3600, interval))
            row = {k: 0 for k in power.METRICS}
            row.update(screen=screen, interval_seconds=interval, injected_fixes=fixes,
                       saved_fixes=fixes, gps_subscriptions=1, requested_interval_ms=interval * 1000,
                       notification_posts=len(range(0, 3600, max(interval, 60))),
                       visible_draws=sum(screen == "on" or screen == "check" and second % 300 < 15
                                         for second in range(0, 3600, interval)))
            report["scenarios"].append(row)
    return baseline, report


class VirtualPowerTest(unittest.TestCase):
    def test_equal_counts_pass_with_three_columns(self):
        baseline, report = fixture()
        passed, text = power.compare(baseline, report, report)
        self.assertTrue(passed)
        self.assertIn("Baseline budget | Previous | Current PR", text)
        self.assertIn("not battery drain", text)

    def test_improves_previous_while_meeting_objectives(self):
        baseline, current = fixture()
        previous = copy.deepcopy(current)
        for row in previous["scenarios"]:
            row.update(notification_posts=row["injected_fixes"], hidden_draws=1, hidden_tile_reads=4)
        self.assertTrue(power.compare(baseline, previous, current)[0])

    def test_baseline_and_previous_both_gate(self):
        baseline, current = fixture()
        for metric in baseline["maximum"]:
            worse = copy.deepcopy(current)
            worse["scenarios"][0][metric] = 1
            self.assertFalse(power.compare(baseline, current, worse)[0], metric)
        previous = copy.deepcopy(current)
        previous["scenarios"][0]["notification_posts"] = 59
        self.assertFalse(power.compare(baseline, previous, current)[0])

    def test_lost_fixes_changed_cadence_and_missing_work_cannot_pass(self):
        baseline, previous = fixture()
        for metric in ("saved_fixes", "gps_subscriptions", "requested_interval_ms", "visible_draws"):
            current = copy.deepcopy(previous)
            current["scenarios"][0][metric] += 1
            self.assertFalse(power.compare(baseline, previous, current)[0], metric)

    def test_protocol_identity_complete_matrix_and_numbers_are_required(self):
        baseline, previous = fixture()
        mutations = [lambda r: r.update(protocol="other"), lambda r: r.update(revision="main"),
                     lambda r: r.update(harness_sha256="c" * 64), lambda r: r["scenarios"].pop(),
                     lambda r: r["scenarios"].append(r["scenarios"][0]),
                     lambda r: r["scenarios"][0].update(injected_fixes=1)]
        for value in (None, True, -1, float("nan"), "0"):
            mutations.append(lambda r, v=value: r["scenarios"][0].update(hidden_draws=v))
        for mutate in mutations:
            current = copy.deepcopy(previous)
            mutate(current)
            with self.assertRaises(ValueError):
                power.compare(baseline, previous, current)

    def test_recorder_timer_guard_distinguishes_comments_from_code(self):
        self.assertEqual(0, power.timer_sites('// postDelayed\n/* newWakeLock */ manager.requestLocationUpdates();'))
        for token in ("postDelayed", "AlarmManager", "Timer", "newWakeLock", "scheduleAtFixedRate"):
            self.assertEqual(1, power.timer_sites(token + '(callback);'))

    def test_cpu_regressions_and_missing_or_nonfinite_samples_fail(self):
        baseline, previous = fixture()
        current = copy.deepcopy(previous)
        current["render_cpu"][0]["median_ms"] = 3
        self.assertFalse(power.compare(baseline, previous, current)[0])
        for value in (None, True, float("nan"), -1):
            current["render_cpu"][0]["median_ms"] = value
            with self.assertRaises(ValueError):
                power.compare(baseline, previous, current)

    def test_command_exit_status_gates_required_checks(self):
        _, previous = fixture()
        work_regression = copy.deepcopy(previous)
        work_regression["scenarios"][0]["hidden_draws"] = 1
        cpu_regression = copy.deepcopy(previous)
        cpu_regression["render_cpu"][0]["median_ms"] = 3
        incomplete = copy.deepcopy(previous)
        incomplete["scenarios"].pop()
        cases = [(previous, 0, "PASS"), (work_regression, 1, "FAIL"),
                 (cpu_regression, 1, "FAIL"), (incomplete, 2, "BLOCKED"),
                 (subprocess.CalledProcessError(1, "gradlew"), 2, "BLOCKED"),
                 (subprocess.TimeoutExpired("gradlew", 300), 2, "BLOCKED")]
        for result, status, verdict in cases:
            with self.subTest(status=status, verdict=verdict, result=type(result).__name__), \
                    tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                report = output / "performance.md"
                report.write_text("PASS — stale result")
                argv = ["virtual_power.py", "--previous", "a" * 40,
                        "--current", "c" * 40, "--output", directory]
                with mock.patch("sys.argv", argv), \
                        mock.patch.object(power, "resolve", side_effect=lambda ref: ref), \
                        mock.patch.object(power, "measure", side_effect=[previous, result]), \
                        redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    self.assertEqual(status, power.main())
                self.assertIn(verdict, report.read_text())
                self.assertNotIn("stale result", report.read_text())
                if status:
                    self.assertIn("merging is blocked", report.read_text())


if __name__ == "__main__":
    unittest.main()
