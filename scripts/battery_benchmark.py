"""Endpoint-only Android battery capture and conservative performance comparison.

No polling, battery simulation, screen forcing, alarms, or device wake locks.
See docs/BATTERY-VALIDATION.md for physical-device and trace requirements.
"""

import argparse
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


INTERVALS = (1, 5, 20, 30, 60, 300)
SCREENS = ("off", "on", "check")
LOWER = ("mah_per_hour", "app_wakelock_seconds", "unrelated_wakeups",
         "gps_gap_p95_seconds", "gps_max_gap_seconds", "frame_p95_ms",
         "track_error_p95_m", "distance_error_percent", "gnss_active_percent")
HIGHER = ("fix_coverage",)
TRACE_METRICS = ("app_wakelock_seconds", "unrelated_wakeups", "frame_p95_ms",
                 "offscreen_frames", "idle_frames", "between_fix_suspend_percent",
                 "track_error_p95_m", "distance_error_percent", "gnss_active_percent",
                 "display_refresh_hz")


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def number(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def duration(text):
    """Android TimeUtils format, including zero, days and millisecond suffixes."""
    if text.strip() == "0":
        return 0.0
    parts = re.findall(r"(\d+(?:\.\d+)?)(ms|d|h|m|s)", text)
    if not parts or re.sub(r"(\d+(?:\.\d+)?)(ms|d|h|m|s)|\s+", "", text):
        raise ValueError("Unsupported Android duration: " + text)
    scale = {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": .001}
    return sum(float(n) * scale[unit] for n, unit in parts)


def battery_values(text):
    def integer(key):
        match = re.search(r"^\s*" + re.escape(key) + r":\s*(-?\d+)\s*$", text, re.M | re.I)
        return int(match[1]) if match else None

    powered = re.findall(r"^\s*(?:AC|USB|Wireless|Dock) powered:\s*(\w+)", text, re.M | re.I)
    return {"charge_uah": integer("Charge counter"), "temperature_tenths_c": integer("temperature"),
            "level": integer("level"), "status": integer("status"),
            "unplugged": len(powered) >= 3 and all(p.lower() == "false" for p in powered)}


def battery_times(text, off=False):
    label = "Time on battery screen off:" if off else "Time on battery:"
    match = re.search(re.escape(label) + r"\s*(.*?)\s*\([^\n]*?\) realtime,\s*(.*?)\s*(?:\([^\n]*?\)\s*)?uptime", text)
    return [duration(match[1]), duration(match[2])] if match else None


def frames(text):
    totals = re.findall(r"Total frames rendered:\s*(\d+)", text)
    return sum(map(int, totals)) if totals else None


def snapshot(args, phase):
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    folder = Path(args.directory)
    started = time.monotonic()

    def shell(name, *command):
        value = subprocess.run(adb + ["shell", *command], check=True, capture_output=True,
                               text=True, timeout=30).stdout
        (folder / (phase + "-" + name + ".txt")).write_text(value)
        return value

    elapsed = float(shell("uptime", "cat", "/proc/uptime").split()[0])
    epoch = float(shell("epoch", "date", "+%s").strip())
    boot = shell("boot", "cat", "/proc/sys/kernel/random/boot_id").strip()
    battery = shell("battery", "dumpsys", "battery")
    stats = shell("batterystats", "dumpsys", "batterystats", "--charged")
    gfx = shell("gfxinfo", "dumpsys", "gfxinfo", args.package)
    shell("location", "dumpsys", "location")
    package = shell("package", "dumpsys", "package", args.package)
    device = shell("device", "getprop", "ro.product.model").strip()
    os_build = shell("build", "getprop", "ro.build.fingerprint").strip()
    return {"elapsed_seconds": elapsed, "epoch": epoch, "boot_id": boot, "device": device, "os_build": os_build,
            "battery": battery_values(battery), "battery_times": battery_times(stats),
            "off_times": battery_times(stats, off=True), "frames": frames(gfx),
            "package_version": re.findall(r"versionName=(\S+)", package),
            "build_type": "debug" if "DEBUGGABLE" in package else "release",
            "capture_seconds": time.monotonic() - started}


def gpx_metrics(path, start_epoch, end_epoch, interval):
    root = ET.parse(path).getroot()
    times = []
    for point in root.iter():
        if point.tag.split("}")[-1] != "trkpt":
            continue
        stamp = next((c.text for c in point if c.tag.split("}")[-1] == "time"), None)
        if stamp:
            parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise ValueError("GPX timestamps must include timezone")
            value = parsed.timestamp()
            if start_epoch <= value <= end_epoch:
                times.append(value)
    if len(times) < 2 or any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError("Need at least two strictly increasing GPX timestamps in trial window")
    gaps = [b - a for a, b in zip(times, times[1:])]
    # Include start/end gaps so a dead recorder cannot pass on a small valid prefix.
    gaps_with_edges = gaps + [times[0] - start_epoch, end_epoch - times[-1]]
    expected = max(1, (end_epoch - start_epoch) / interval)
    return {"gps_gap_p95_seconds": sorted(gaps)[math.ceil(.95 * len(gaps)) - 1],
            "gps_max_gap_seconds": max(gaps_with_edges),
            "fix_coverage": min(1.0, len(times) / expected), "fix_count": len(times)}


def summarize(start, end, meta, gpx=None):
    errors = []
    elapsed = end["elapsed_seconds"] - start["elapsed_seconds"]
    if start["boot_id"] != end["boot_id"] or elapsed <= 0:
        raise ValueError("Device rebooted or time did not advance")
    for key in ("device", "os_build", "package_version", "build_type"):
        if start[key] != end[key]:
            errors.append(key + " changed")
    if abs((end["epoch"] - start["epoch"]) - elapsed) > 2:
        errors.append("device wall clock changed")
    if start["build_type"] != "release" or not start["package_version"]:
        errors.append("need an installed non-debuggable build")
    if elapsed < max(3600, meta["interval_seconds"] * 12):
        errors.append("trial too short (need >=1h and >=12 intervals)")
    for sample in (start, end):
        b = sample["battery"]
        if not b["unplugged"] or b["status"] != 3:
            errors.append("device must be physically unplugged and discharging")
        if sample["capture_seconds"] > 15:
            errors.append("endpoint overhead exceeded 15 seconds")
        if b["temperature_tenths_c"] is None:
            errors.append("battery temperature unavailable")
    a, b = start["battery"]["charge_uah"], end["battery"]["charge_uah"]
    energy = None
    if a is None or b is None or b <= 0 or a <= b:
        errors.append("missing, quantized or increasing charge counter; use an external meter")
    else:
        energy = (a - b) / 1000 * 3600 / elapsed
    suspend, screen_on = None, None
    if start["off_times"] and end["off_times"]:
        real, uptime = [b - a for a, b in zip(start["off_times"], end["off_times"])]
        if 0 <= real <= elapsed + 15:
            screen_on = max(0, (elapsed - real) / elapsed * 100)
        if real > 0 and 0 <= uptime <= real:
            suspend = (real - uptime) / real * 100
    if not start["battery_times"] or not end["battery_times"]:
        errors.append("battery accounting unavailable")
    else:
        real, uptime = [b - a for a, b in zip(start["battery_times"], end["battery_times"])]
        if abs(real - elapsed) > 20 or not 0 <= uptime <= real:
            errors.append("battery accounting reset or device charged during trial")
    frame_delta = None
    if start["frames"] is not None and end["frames"] is not None:
        frame_delta = end["frames"] - start["frames"]
        if frame_delta < 0:
            errors.append("gfxinfo counters reset")
            frame_delta = None
    metrics = {"mah_per_hour": energy, "screen_off_suspend_percent": suspend,
               "total_frames": frame_delta, "screen_on_percent": screen_on}
    if gpx:
        metrics.update(gpx_metrics(gpx, start["epoch"], end["epoch"],
                                   meta["interval_seconds"]))
    else:
        errors.append("GPX not supplied; track survival/quality unknown")
    return {**meta, "duration_seconds": elapsed, "device": start["device"],
            "build_type": start["build_type"],
            "os_build": start["os_build"], "metrics": metrics, "invalid_reasons": errors}


def validate_report(report, label):
    errors = []
    if report.get("schema") != 1 or report.get("status") != "measured":
        return [label + ": no measured report"]
    runs = report.get("runs", [])
    if not isinstance(runs, list) or not runs:
        return [label + ": no trials"]
    seen = set()
    for run in runs:
        ident = run.get("trial_id")
        if not ident or ident in seen:
            errors.append(label + ": missing/duplicate trial_id")
        seen.add(ident)
        if run.get("invalid_reasons") != []:
            errors.append(label + ": invalid or unvalidated trial " + str(ident))
        if not re.fullmatch(r"[0-9a-f]{40}", run.get("revision", "")):
            errors.append(label + ": missing immutable revision/APK SHA")
        if not run.get("profile") or not run.get("evidence"):
            errors.append(label + ": missing profile or raw evidence")
        if not run.get("device") or not run.get("os_build") or run.get("build_type") != "release":
            errors.append(label + ": missing device/OS or non-release build")
        if run.get("screen") not in SCREENS:
            errors.append(label + ": invalid screen mode")
        interval = run.get("interval_seconds")
        if not number(interval) or interval <= 0 or not number(run.get("duration_seconds")):
            errors.append(label + ": invalid interval/duration")
            continue
        if run["duration_seconds"] < max(3600, interval * 12):
            errors.append(label + ": trial too short")
        metrics = run.get("metrics", {})
        required = LOWER + HIGHER + TRACE_METRICS + ("screen_on_percent",)
        if run.get("screen") != "on":
            required += ("screen_off_suspend_percent",)
        for metric in set(required):
            value = metrics.get(metric)
            if not number(value) or value < 0:
                errors.append(label + ": missing/invalid " + metric)
        if errors:
            continue
        if not 0 <= metrics["fix_coverage"] <= 1:
            errors.append(label + ": invalid coverage")
        if not 0 <= metrics["gnss_active_percent"] <= 100:
            errors.append(label + ": invalid GNSS duty cycle")
        if metrics["display_refresh_hz"] <= 0 or metrics["frame_p95_ms"] > 1000 / metrics["display_refresh_hz"]:
            errors.append(label + ": visible frame budget exceeded")
        screen_bounds = {"off": (0, 1), "on": (99, 100), "check": (3, 7)}
        lo, hi = screen_bounds[run["screen"]]
        if not lo <= metrics["screen_on_percent"] <= hi:
            errors.append(label + ": actual screen duration does not match workload")
        if metrics["fix_coverage"] < .95 or metrics["gps_max_gap_seconds"] > interval * 3:
            errors.append(label + ": lost fixes or recording gap")
        for metric in ("app_wakelock_seconds", "unrelated_wakeups", "offscreen_frames", "idle_frames"):
            if metrics[metric] != 0:
                errors.append(label + ": zero-work objective violated: " + metric)
        if not 0 <= metrics["between_fix_suspend_percent"] <= 100:
            errors.append(label + ": invalid between-fix suspend percentage")
        if run["screen"] != "on" and interval >= 20 and metrics["between_fix_suspend_percent"] < 99:
            errors.append(label + ": between-fix suspend objective violated")
        if run["screen"] != "on" and not 0 <= metrics["screen_off_suspend_percent"] <= 100:
            errors.append(label + ": invalid suspend percentage")
    return errors


def group(report):
    result = {}
    for run in report.get("runs", []):
        key = tuple(run.get(k) for k in ("device", "os_build", "profile", "screen", "interval_seconds"))
        result.setdefault(key, []).append(run)
    return result


def compare(baseline, previous, current):
    reports = {"Baseline": baseline, "Previous": previous, "Current PR": current}
    errors = [e for label, report in reports.items() for e in validate_report(report, label)]
    lines = ["## Performance", "", "Baseline, previous and current PR use matched physical-device trials.", ""]
    if errors:
        return False, "\n".join(lines + ["**BLOCKED — auto-merge must remain off.**", ""] +
                                ["- " + e for e in sorted(set(errors))]) + "\n"
    for label, report in reports.items():
        lines.append(label + " revisions: " + ", ".join(sorted({r["revision"] for r in report["runs"]})))
    lines.append("")
    groups = {label: group(report) for label, report in reports.items()}
    keys = set(groups["Baseline"])
    if any(set(value) != keys for value in groups.values()):
        errors.append("Device/OS/profile/screen/interval matrix differs")
    # Every tested device/profile must contain the complete mandatory matrix.
    for prefix in {key[:3] for key in keys}:
        if any(prefix + (s, i) not in keys for s in SCREENS for i in INTERVALS):
            errors.append("Incomplete mandatory screen/GPS matrix")
    lines += ["| Scenario / metric | Baseline | Previous | Current PR |",
              "| --- | ---: | ---: | ---: |"]
    for key in sorted(keys):
        cells = [groups[label].get(key, []) for label in reports]
        if any(len(runs) < 3 for runs in cells):
            errors.append("Need at least three independent trials per scenario")
            continue
        durations = [r["duration_seconds"] for runs in cells for r in runs]
        if max(durations) > min(durations) * 1.02:
            errors.append("Trial durations differ by more than 2%: " + str(key))
        metrics = LOWER + HIGHER + (("screen_off_suspend_percent", "between_fix_suspend_percent")
                                    if key[3] != "on" else ())
        for metric in metrics:
            values = [statistics.median(r["metrics"][metric] for r in runs) for runs in cells]
            lines.append("| " + " / ".join(map(str, key)) + " / " + metric + " | " +
                         " | ".join(f"{v:.4f}" for v in values) + " |")
            worse = (values[2] > min(values[:2]) if metric in LOWER
                     else values[2] < max(values[:2]))
            if worse:
                errors.append("Regression: " + str(key) + " / " + metric)
    lines += ["", "**" + ("BLOCKED — auto-merge must remain off." if errors
                           else "PASS — measured objectives and comparisons passed.") + "**"]
    lines += ["- " + e for e in sorted(set(errors))]
    return not errors, "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("matrix")
    for phase in ("begin", "end"):
        p = sub.add_parser(phase)
        p.add_argument("directory")
        p.add_argument("--adb", default="adb")
        p.add_argument("--serial")
        if phase == "begin":
            p.add_argument("--package", default="app.cairn.trek")
            p.add_argument("--interval", type=int, required=True)
            p.add_argument("--screen", choices=SCREENS, required=True)
            p.add_argument("--profile", required=True, help="Immutable matched-conditions profile ID")
            p.add_argument("--revision", required=True, help="Git SHA or competitor APK SHA-1")
        else:
            p.add_argument("--gpx")
            p.add_argument("--trace-metrics", type=Path,
                           help="Reviewed metrics JSON; must include trace_sha256 and query_revision")
    p = sub.add_parser("summarize")
    p.add_argument("directory")
    p.add_argument("--gpx", required=True)
    p.add_argument("--trace-metrics", type=Path)
    p = sub.add_parser("pack")
    p.add_argument("output")
    p.add_argument("runs", nargs="+")
    p = sub.add_parser("compare")
    for label in ("baseline", "previous", "current"):
        p.add_argument("--" + label, required=True)
    p.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "matrix":
        write_out = [{"screen": s, "interval_seconds": i, "repeats": 3,
                      "minimum_duration_seconds": max(3600, 12 * i)} for s in SCREENS for i in INTERVALS]
        print(json.dumps(write_out, indent=2))
    elif args.command == "begin":
        if not 1 <= args.interval <= 3600 or not re.fullmatch(r"[0-9a-f]{40}", args.revision):
            raise ValueError("Require interval 1..3600 and full immutable revision")
        Path(args.directory).mkdir(parents=True, exist_ok=False)
        meta = {"trial_id": Path(args.directory).name, "package": args.package,
                "interval_seconds": args.interval, "screen": args.screen, "profile": args.profile,
                "revision": args.revision, "start_epoch": time.time()}
        write(Path(args.directory) / "meta.json", meta)
        write(Path(args.directory) / "start.json", snapshot(args, "start"))
        print("Start captured. Disconnect ADB; do not poll the phone during the trial.")
    elif args.command in ("end", "summarize"):
        folder = Path(args.directory)
        meta = read(folder / "meta.json")
        if args.command == "end":
            if (folder / "end.json").exists():
                raise ValueError("End already captured; preserve trial evidence")
            args.package = meta["package"]
            end = snapshot(args, "end")
            write(folder / "end.json", end)
            if not args.gpx:
                print("End captured. Export GPX, then run summarize with these preserved endpoints.")
                return 0
        else:
            end = read(folder / "end.json")
        result = summarize(read(folder / "start.json"), end, meta, args.gpx)
        result["evidence"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(folder.iterdir()) if p.is_file() and p.name != "run.json"}
        result["evidence"]["gpx_sha256"] = hashlib.sha256(Path(args.gpx).read_bytes()).hexdigest()
        if args.trace_metrics:
            trace = read(args.trace_metrics)
            if not re.fullmatch(r"[0-9a-f]{64}", trace.get("trace_sha256", "")) or not trace.get("query_revision"):
                raise ValueError("Trace metrics need raw trace SHA-256 and query revision")
            for key in TRACE_METRICS:
                result["metrics"][key] = trace.get(key)
            result["evidence"]["trace"] = trace
        write(folder / "run.json", result)
    elif args.command == "pack":
        write(args.output, {"schema": 1, "status": "measured", "runs": [read(p) for p in args.runs]})
    elif args.command == "compare":
        passed, markdown = compare(read(args.baseline), read(args.previous), read(args.current))
        Path(args.output).write_text(markdown)
        print(markdown)
        return 0 if passed else 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        print("Benchmark blocked: " + str(error), file=sys.stderr)
        sys.exit(2)
