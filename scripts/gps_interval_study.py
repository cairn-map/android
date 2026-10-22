"""Deterministic sampling geometry, not a GPS power or hardware sleep benchmark."""

import bisect
import json
import math
from pathlib import Path
import argparse


INTERVALS = (1, 5, 10, 15, 20, 30, 45, 60, 90, 120, 300)
SPEEDS = (2, 4, 6)


def fixtures():
    return {
        "straight": [(0, 0), (4000, 0)],
        "broad_bends": [(i * 160, (i % 2) * 120) for i in range(21)],
        "tight_switchbacks": [point for i in range(24)
                              for point in ((0 if i % 2 == 0 else 80, i * 15),
                                            (80 if i % 2 == 0 else 0, i * 15))],
    }


def cumulative(points):
    result = [0.0]
    for a, b in zip(points, points[1:]):
        result.append(result[-1] + math.dist(a, b))
    return result


def position(points, distances, distance):
    index = min(len(points) - 2, max(0, bisect.bisect_right(distances, distance) - 1))
    length = distances[index + 1] - distances[index]
    fraction = (distance - distances[index]) / length if length else 0
    return tuple(a + (b - a) * fraction for a, b in zip(points[index], points[index + 1]))


def segment_distance(point, a, b):
    delta = (b[0] - a[0], b[1] - a[1])
    squared = delta[0] ** 2 + delta[1] ** 2
    fraction = max(0, min(1, sum((p - v) * d for p, v, d in zip(point, a, delta)) / squared)) if squared else 0
    return math.dist(point, tuple(v + fraction * d for v, d in zip(a, delta)))


def sample(points, speed_kmh, interval, phase):
    distances = cumulative(points)
    speed = speed_kmh / 3.6
    duration = distances[-1] / speed
    # Exact endpoints isolate interior bend loss from start/stop acquisition losses.
    times = [0.0]
    time = interval * phase
    while time < duration:
        if time > 0:
            times.append(time)
        time += interval
    times.append(duration)
    recorded = [position(points, distances, time * speed) for time in times]
    errors = []
    # One-second reference probes plus every exact bend, avoiding aliasing at sharp turns.
    probes = sorted(set([float(t) for t in range(math.ceil(duration))] + [d / speed for d in distances]))
    for time in probes:
        index = min(len(times) - 2, max(0, bisect.bisect_right(times, time) - 1))
        errors.append(segment_distance(position(points, distances, time * speed),
                                       recorded[index], recorded[index + 1]))
    errors.sort()
    return {
        "p95_error_m": errors[math.ceil(len(errors) * .95) - 1],
        "max_error_m": max(errors),
        "length_retained_percent": cumulative(recorded)[-1] / distances[-1] * 100,
    }


def study():
    rows = []
    for name, points in fixtures().items():
        for speed in SPEEDS:
            for interval in INTERVALS:
                phases = [sample(points, speed, interval, phase) for phase in (0, .25, .5, .75)]
                rows.append({
                    "fixture": name, "speed_kmh": speed, "interval_seconds": interval,
                    "fixes_per_hour": 3600 / interval,
                    "spacing_m": speed / 3.6 * interval,
                    "p95_error_m": max(row["p95_error_m"] for row in phases),
                    "max_error_m": max(row["max_error_m"] for row in phases),
                    "length_retained_percent": min(row["length_retained_percent"] for row in phases),
                    "two_fix_confirmation_seconds": 2 * interval,
                })
    return {"schema": 1, "description": "Ideal noise-free sampling geometry with four phases; not battery, hardware sleep, receiver duty cycle or measured GNSS accuracy.",
            "rows": rows}


def markdown(report):
    lines = ["# GPS interval geometry study", "", report["description"], "",
             "Worst result across all three fixtures and four phases at 4 km/h:", "",
             "| Interval (s) | Fixes/hour | Spacing (m) | p95 bend error (m) | Max bend error (m) | Length retained (%) | Two-fix confirmation (s) |",
             "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for interval in INTERVALS:
        rows = [row for row in report["rows"] if row["speed_kmh"] == 4 and row["interval_seconds"] == interval]
        lines.append(f"| {interval} | {3600 / interval:g} | {4 / 3.6 * interval:.1f} | "
                     f"{max(r['p95_error_m'] for r in rows):.2f} | {max(r['max_error_m'] for r in rows):.2f} | "
                     f"{min(r['length_retained_percent'] for r in rows):.2f} | {2 * interval} |")
    lines += ["", "Confirmation is the ideal upper phase delay after crossing the off-route threshold, assuming two accurate scheduled fixes. Acquisition delay, missed fixes, the 50 m threshold and fix rejection can extend it.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Directory for full JSON and Markdown results")
    args = parser.parse_args()
    report = study()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "intervals.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
        (args.output / "intervals.md").write_text(markdown(report))
    print(markdown(report))
