package app.cairn.trek;

import java.util.List;
import java.util.Locale;

/** Totals from stored geometry, without bridging segment boundaries or missing elevations. */
final class RouteSummary {
  double distance, ascent, descent;
  boolean hasElevation, partialElevation;
  private Point previous;
  private final RecordingElevation recordingElevation;
  private double previousElevation = Double.NaN;
  private boolean hasRawElevation;

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
    boolean connected = previous != null && previous.segment == point.segment;
    double step = connected ? Geo.distance(previous, point) : 0;
    double elevation =
        recordingElevation == null ? point.elevation : recordingElevation.add(point, step);
    if (Double.isFinite(point.elevation)) hasRawElevation = true;
    if (!Double.isFinite(elevation)) partialElevation = true;
    if (connected) {
      distance += step;
      if (Double.isFinite(previousElevation) && Double.isFinite(elevation)) {
        hasElevation = true;
        double change = elevation - previousElevation;
        if (change > 0) ascent += change;
        else descent -= change;
      }
    }
    previousElevation = elevation;
    previous = point;
  }

  String text() {
    String length = String.format(Locale.getDefault(), "%.1f km", distance / 1000);
    if (unresolved()) return length + " · Elevation change not reliably resolved";
    if (!hasElevation) return length + " · Elevation unavailable";
    if (recordingElevation != null)
      return length
          + " · ↑ "
          + compactEstimate(ascent)
          + "   ↓ "
          + compactEstimate(descent)
          + " · GPS estimate"
          + (partialElevation ? " · sections excluded" : "");
    return length
        + String.format(Locale.getDefault(), " · ↑ %.0f m   ↓ %.0f m", ascent, descent)
        + (partialElevation ? " · partial" : "");
  }

  String description() {
    if (unresolved())
      return String.format(
              Locale.getDefault(),
              "Distance %.1f km. Elevation change not reliably resolved from GPS measurements.",
              distance / 1000)
          + (partialElevation ? " Uncertain elevation sections are excluded." : "");
    if (recordingElevation != null && hasElevation)
      return String.format(
              Locale.getDefault(),
              "Distance %.1f km. GPS ascent estimate %s. GPS descent estimate %s.",
              distance / 1000,
              estimateText(ascent),
              estimateText(descent))
          + " Changes within the altitude uncertainty band are suppressed."
          + (partialElevation ? " Uncertain elevation sections are excluded." : "");
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

  private boolean unresolved() {
    return recordingElevation != null
        && hasRawElevation
        && (!hasElevation || (ascent < .5 && descent < .5));
  }

  private String estimateText(double value) {
    return value < .5
        ? "not reliably resolved"
        : String.format(Locale.getDefault(), "%.0f m", value);
  }

  private String compactEstimate(double value) {
    return value < .5 ? "—" : estimateText(value);
  }
}
