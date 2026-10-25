package app.cairn.trek;

import java.util.ArrayList;
import java.util.List;

/** Distance-indexed stored geometry. Appending a live fix does not rescan the track. */
final class ElevationProfile {
  final List<Point> points = new ArrayList<>();
  private double[] distances = new double[64];
  double distance, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;

  void add(Point point) {
    int index = points.size();
    if (index == distances.length)
      distances = java.util.Arrays.copyOf(distances, distances.length * 2);
    if (index > 0 && points.get(index - 1).segment == point.segment)
      distance += Geo.distance(points.get(index - 1), point);
    distances[index] = distance;
    points.add(point);
    if (Double.isFinite(point.elevation)) {
      minimum = Math.min(minimum, point.elevation);
      maximum = Math.max(maximum, point.elevation);
    }
  }

  boolean hasElevation() {
    return Double.isFinite(minimum);
  }

  double fraction(int index) {
    return distance > 0
        ? distances[index] / distance
        : index / (double) Math.max(1, points.size() - 1);
  }

  Point at(double fraction) {
    if (points.isEmpty()) return null;
    fraction = Math.max(0, Math.min(1, fraction));
    if (fraction == 0) return points.get(0);
    if (fraction == 1) return points.get(points.size() - 1);
    if (distance == 0) return points.get((int) Math.round(fraction * (points.size() - 1)));
    double target = fraction * distance;
    int low = 0, high = points.size();
    // Upper bound skips zero-length edges and never interpolates across a segment gap.
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (distances[middle] <= target) low = middle + 1;
      else high = middle;
    }
    int right = Math.min(low, points.size() - 1), left = Math.max(0, right - 1);
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
