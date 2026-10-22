"""Run the same virtual Android workload against previous and current Git trees."""

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "scripts/performance/VirtualPowerBenchmarkTest.java"
BASELINE = ROOT / "scripts/performance/baseline.json"
METRICS = ("saved_fixes", "notification_posts", "hidden_draws", "hidden_tile_reads",
           "visible_draws", "queued_recorder_tasks", "gps_subscriptions", "network_subscriptions",
           "subscriptions_after_stop", "requested_interval_ms", "recorder_timer_sites")


def load(path):
    return json.loads(Path(path).read_text())


def dump(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def command(args, cwd=ROOT, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, **kwargs)


def resolve(ref):
    sha = command(["git", "rev-parse", "--verify", ref + "^{commit}"],
                  capture_output=True, text=True).stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected immutable Git revision")
    return sha


def timer_sites(source):
    # Conservative lexical guard complements the measured worker queue. No CPU/power model.
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    return len(re.findall(r"\b(?:postDelayed|postAtTime|scheduleAtFixedRate|scheduleWithFixedDelay|"
                          r"AlarmManager|Timer|TimerTask|newWakeLock|acquire)\b", source))


def measure(revision, output):
    with tempfile.TemporaryDirectory(prefix="cairn-virtual-") as directory:
        tree = Path(directory)
        archive = command(["git", "archive", revision], capture_output=True).stdout
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(tree, filter="data")
        test = tree / "app/src/test/java/app/cairn/trek/VirtualPowerBenchmarkTest.java"
        test.write_bytes(HARNESS.read_bytes())
        with (output / (revision + ".log")).open("w") as log:
            command(["bash", "gradlew", "testDebugUnitTest", "--tests",
                     "app.cairn.trek.VirtualPowerBenchmarkTest", "--console=plain", "--max-workers=2",
                     "--init-script", str(ROOT / "scripts/performance/benchmark.init.gradle"),
                     "--build-cache"], cwd=tree, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        result = load(tree / "app/build/reports/virtual-power/scenarios.json")
        sites = timer_sites((tree / "app/src/main/java/app/cairn/trek/TrackingService.java").read_text())
        for row in result["scenarios"]:
            row["recorder_timer_sites"] = sites
        result["revision"] = revision
        result["harness_sha256"] = hashlib.sha256(HARNESS.read_bytes()).hexdigest()
        return result


def rows(report, baseline):
    if any(report.get(k) != baseline[k] for k in ("schema", "protocol", "sdk", "duration_seconds")):
        raise ValueError("Incompatible virtual benchmark protocol")
    if not re.fullmatch(r"[0-9a-f]{40}", report.get("revision", "")):
        raise ValueError("Missing source revision")
    if not re.fullmatch(r"[0-9a-f]{64}", report.get("harness_sha256", "")):
        raise ValueError("Missing workload identity")
    expected = {(s, i) for s in baseline["screens"] for i in baseline["intervals_seconds"]}
    result = {}
    for row in report["scenarios"]:
        key = row["screen"], row["interval_seconds"]
        if key not in expected or key in result:
            raise ValueError("Unexpected/duplicate virtual scenario")
        for metric in METRICS + ("injected_fixes",):
            if type(row.get(metric)) is not int or row[metric] < 0:
                raise ValueError("Invalid/missing metric: " + metric)
        if row["injected_fixes"] != len(range(0, baseline["duration_seconds"], key[1])):
            raise ValueError("Workload did not inject the expected fixes")
        result[key] = row
    if set(result) != expected:
        raise ValueError("Incomplete virtual screen/GPS matrix")
    return result


def compare(baseline, previous, current):
    old, new = rows(previous, baseline), rows(current, baseline)
    if previous["harness_sha256"] != current["harness_sha256"]:
        raise ValueError("Previous and current used different workloads")
    def cpu_rows(report):
        result = {}
        for row in report["render_cpu"]:
            key = str(row["points"])
            if key not in baseline["render_cpu_max_ms"] or key in result or row["samples"] != 7:
                raise ValueError("Invalid render CPU fixture")
            for metric in ("median_ms", "p95_ms"):
                value = row[metric]
                if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                    raise ValueError("Invalid CPU measurement")
            result[key] = row
        if set(result) != set(baseline["render_cpu_max_ms"]):
            raise ValueError("Incomplete CPU fixtures")
        return result
    old_cpu, new_cpu = cpu_rows(previous), cpu_rows(current)
    failures = []
    lines = ["<!-- cairn:virtual-power -->", "## Virtual performance", "",
             "Measured software work in a virtual Android hour; no physical-phone testing is required.",
             "These counts are not battery drain or hardware deep-sleep measurements.", "",
             f"Previous: `{previous['revision']}`. Current PR: `{current['revision']}`.",
             f"Workload SHA-256: `{current['harness_sha256']}`.", "",
             "| Screen / GPS seconds / metric | Baseline budget | Previous | Current PR |",
             "| --- | ---: | ---: | ---: |"]
    for key in sorted(new):
        row = new[key]
        fixes = row["injected_fixes"]
        limits = dict(baseline["maximum"])
        cadence = max(key[1], baseline["notification_interval_seconds"])
        limits["notification_posts"] = len(range(0, baseline["duration_seconds"], cadence))
        visible = sum(key[0] == "on" or key[0] == "check" and second % 300 < 15
                      for second in range(0, baseline["duration_seconds"], key[1]))
        exact = {"saved_fixes": fixes, "gps_subscriptions": 1,
                 "requested_interval_ms": key[1] * 1000, "visible_draws": visible}
        for metric in METRICS:
            before, after = old[key][metric], row[metric]
            budget = exact.get(metric, limits.get(metric, "workload"))
            lines.append(f"| {key[0]} / {key[1]} / {metric} | {budget} | {before} | {after} |")
            if metric in exact and after != exact[metric]:
                failures.append(f"{key}: {metric} does not match workload")
            if metric in limits and (after > limits[metric] or after > before):
                failures.append(f"{key}: {metric} exceeds baseline or previous")
    lines += ["", "CPU time per native Canvas draw, seven warmed batches; median gates allow the documented runner-noise margin.", "",
              "| Route points / CPU median ms | Baseline maximum | Previous | Current PR |",
              "| --- | ---: | ---: | ---: |"]
    for key, limit in baseline["render_cpu_max_ms"].items():
        before, after = old_cpu[key]["median_ms"], new_cpu[key]["median_ms"]
        lines.append(f"| {key} | {limit} | {before:.4f} | {after:.4f} |")
        if after > limit or after > before * baseline["cpu_regression_ratio"] + baseline["cpu_noise_floor_ms"]:
            failures.append(f"{key} points: CPU median exceeds baseline or previous noise margin")
    lines += ["", "**" + ("FAIL — keep auto-merge off." if failures else "PASS — virtual budgets and previous comparison passed.") + "**"]
    lines += ["- " + item for item in failures]
    return not failures, "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", required=True, help="Previous main SHA, not PR HEAD^")
    parser.add_argument("--current", default="HEAD")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/virtual-power")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    # Remove stale verdict before any operation that can fail.
    report_path = args.output / "performance.md"
    report_path.write_text("<!-- cairn:virtual-power -->\n## Virtual performance\n\nBLOCKED: benchmark incomplete; keep auto-merge off.\n")
    try:
        previous = measure(resolve(args.previous), args.output)
        current = measure(resolve(args.current), args.output)
        dump(args.output / "previous.json", previous)
        dump(args.output / "current.json", current)
        baseline = load(BASELINE)
        dump(args.output / "baseline.json", baseline)
        passed, markdown = compare(baseline, previous, current)
        report_path.write_text(markdown)
        print(markdown)
        return 0 if passed else 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Preserve logs/blocked report; never let an older passing artifact authorize merge.
        print("Virtual benchmark failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
