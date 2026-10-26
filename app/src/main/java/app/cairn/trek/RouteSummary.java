package app.cairn.trek;

import java.util.List;
import java.util.Locale;

/** Totals from stored geometry, without bridging segment boundaries or missing elevations. */
final class RouteSummary {
  double distance, ascent, descent;
  boolean hasElevation, partialElevation;
  private Point previous;

  static RouteSummary from(List<Point> points) {
    RouteSummary summary = new RouteSummary();
    for (Point point : points) summary.add(point);
    return summary;
  }

  void add(Point point) {
    if (!Double.isFinite(point.elevation)) partialElevation = true;
    if (previous != null && previous.segment == point.segment) {
      distance += Geo.distance(previous, point);
      if (Double.isFinite(previous.elevation) && Double.isFinite(point.elevation)) {
        hasElevation = true;
        double change = point.elevation - previous.elevation;
        if (change > 0) ascent += change;
        else descent -= change;
      }
    }
    previous = point;
  }

  String text() {
    String length = String.format(Locale.getDefault(), "%.1f km", distance / 1000);
    if (!hasElevation) return length + " · Elevation unavailable";
    return length
        + String.format(Locale.getDefault(), " · ↑ %.0f m   ↓ %.0f m", ascent, descent)
        + (partialElevation ? " · partial" : "");
  }

  String description() {
    if (!hasElevation)
      return String.format(
          Locale.getDefault(), "Distance %.1f km. Elevation unavailable.", distance / 1000);
    return String.format(
            Locale.getDefault(),
            "Distance %.1f km. Ascent %.0f m. Descent %.0f m.",
            distance / 1000,
            ascent,
            descent)
        + (partialElevation ? " Partial elevation data." : "");
  }
}
