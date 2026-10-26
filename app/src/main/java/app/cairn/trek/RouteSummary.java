package app.cairn.trek;

import java.util.List;
import java.util.Locale;

/** Totals from stored geometry, without bridging segment boundaries or missing elevations. */
final class RouteSummary {
  double distance, ascent, descent;
  boolean hasElevation, partialElevation;
  private Point previous;
  private final RecordingElevation recordingElevation;
  private double previousElevation = Double.NaN, elevationAnchor = Double.NaN, anchorDeadband;

  RouteSummary() {
    this(false);
  }

  RouteSummary(boolean recording) {
    recordingElevation = recording ? new RecordingElevation() : null;
  }

  static RouteSummary from(List<Point> points) {
    return from(points, false);
  }

  static RouteSummary from(List<Point> points, boolean recording) {
    RouteSummary summary = new RouteSummary(recording);
    for (Point point : points) summary.add(point);
    return summary;
  }

  void add(Point point) {
    double elevation = recordingElevation == null ? point.elevation : recordingElevation.add(point);
    if (!Double.isFinite(elevation)) partialElevation = true;
    boolean connected = previous != null && previous.segment == point.segment;
    if (connected) {
      distance += Geo.distance(previous, point);
      if (Double.isFinite(previousElevation) && Double.isFinite(elevation)) {
        hasElevation = true;
        double change =
            elevation - (recordingElevation == null ? previousElevation : elevationAnchor);
        if (recordingElevation == null
            || Math.abs(change) >= Math.max(anchorDeadband, RecordingElevation.deadband(point))) {
          if (change > 0) ascent += change;
          else descent -= change;
          elevationAnchor = elevation;
          anchorDeadband = RecordingElevation.deadband(point);
        }
      }
    }
    if (!connected || !Double.isFinite(previousElevation) || !Double.isFinite(elevation)) {
      elevationAnchor = elevation;
      anchorDeadband = RecordingElevation.deadband(point);
    }
    previousElevation = elevation;
    previous = point;
  }

  String text() {
    String length = String.format(Locale.getDefault(), "%.1f km", distance / 1000);
    if (!hasElevation) return length + " · Elevation unavailable";
    return length
        + String.format(Locale.getDefault(), " · ↑ %.0f m   ↓ %.0f m", ascent, descent)
        + (recordingElevation != null ? " · GPS estimate" : "")
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
        + (recordingElevation != null
            ? " GPS elevation estimate; small changes and unreliable stretches are excluded."
            : "")
        + (partialElevation ? " Partial elevation data." : "");
  }
}
