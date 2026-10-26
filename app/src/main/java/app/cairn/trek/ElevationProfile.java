package app.cairn.trek;

import java.util.ArrayList;
import java.util.List;

/** Distance-indexed stored geometry. Appending a live fix does not rescan the track. */
final class ElevationProfile {
  final List<Point> points = new ArrayList<>();
  private double[] distances = new double[64];
  double distance, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
  long geometryKey = 1;
  boolean hasExcludedElevation;
  private final RecordingElevation recordingElevation;

  ElevationProfile() {
    this(false);
  }

  ElevationProfile(boolean recording) {
    recordingElevation = recording ? new RecordingElevation() : null;
  }

  void add(Point point) {
    int index = points.size();
    double step =
        index > 0 && points.get(index - 1).segment == point.segment
            ? Geo.distance(points.get(index - 1), point)
            : 0;
    if (recordingElevation != null || point.elevationDatum == Point.WGS84_ELLIPSOID)
      point =
          new Point(
              point.lat,
              point.lon,
              recordingElevation == null
                  ? point.seaLevelElevation()
                  : recordingElevation.add(point, step),
              point.time,
              point.accuracy,
              point.segment,
              point.verticalAccuracy);
    // Stable across reloads, and updated only with the existing per-point profile work.
    geometryKey = 31 * geometryKey + Double.doubleToLongBits(point.lat);
    geometryKey = 31 * geometryKey + Double.doubleToLongBits(point.lon);
    geometryKey = 31 * geometryKey + Double.doubleToLongBits(point.elevation);
    geometryKey = 31 * geometryKey + point.segment;
    if (index == distances.length)
      distances = java.util.Arrays.copyOf(distances, distances.length * 2);
    distance += step;
    distances[index] = distance;
    points.add(point);
    if (Double.isFinite(point.elevation)) {
      minimum = Math.min(minimum, point.elevation);
      maximum = Math.max(maximum, point.elevation);
    } else hasExcludedElevation = true;
  }

  boolean hasElevation() {
    return Double.isFinite(minimum);
  }

  double fraction(int index) {
    return distance > 0
        ? distances[index] / distance
        : index / (double) Math.max(1, points.size() - 1);
  }

  private int rightIndex(double target) {
    int low = 0, high = points.size();
    // Upper bound skips zero-length edges and never interpolates across a segment gap.
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (distances[middle] <= target) low = middle + 1;
      else high = middle;
    }
    return Math.min(low, points.size() - 1);
  }

  Point at(double fraction) {
    if (points.isEmpty()) return null;
    fraction = Math.max(0, Math.min(1, fraction));
    if (fraction == 0) return points.get(0);
    if (fraction == 1) return points.get(points.size() - 1);
    if (distance == 0) return points.get((int) Math.round(fraction * (points.size() - 1)));
    double target = fraction * distance;
    int right = rightIndex(target), left = Math.max(0, right - 1);
    Point a = points.get(left), b = points.get(right);
    if (target == distances[left]) return a;
    double length = distances[right] - distances[left];
    if (length <= 0 || a.segment != b.segment) return a;
    double ratio = (target - distances[left]) / length;
    double elevation =
        Double.isFinite(a.elevation) && Double.isFinite(b.elevation)
            ? a.elevation + ratio * (b.elevation - a.elevation)
            : Double.NaN;
    return new Point(
        a.lat + ratio * (b.lat - a.lat),
        Geo.wrap(a.lon + ratio * Geo.wrap(b.lon - a.lon)),
        elevation,
        a.time > 0 && b.time > 0 ? a.time + Math.round(ratio * (b.time - a.time)) : 0,
        0,
        a.segment);
  }
}
