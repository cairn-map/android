package app.cairn.trek;

/** Conservative GPS-only display estimates. Never changes the stored measurements. */
final class RecordingElevation {
  private Point previous;
  private long segmentStart;

  double add(Point point) {
    Point before = previous;
    previous = point;
    boolean reset =
        before == null
            || before.segment != point.segment
            || (point.time > 0 && before.time > 0 && point.time <= before.time);
    if (reset || (segmentStart <= 0 && point.time > 0)) segmentStart = point.time;
    // Altitude can converge long after the first horizontal fix. No timer or extra fix request.
    if (point.time > 0 && segmentStart > 0 && point.time - segmentStart < 60_000) return Double.NaN;
    if (!Double.isFinite(point.elevation)
        || (!Float.isNaN(point.verticalAccuracy)
            && (!Float.isFinite(point.verticalAccuracy)
                || point.verticalAccuracy < 0
                || point.verticalAccuracy > 10))) return Double.NaN;
    if (!reset) {
      double distance = Geo.distance(before, point);
      // Do not count altitude drift while almost stationary or join abrupt vertical jumps.
      // A deliberately conservative walking-grade bound; excluded stretches remain partial.
      if (distance < 1
          || (Double.isFinite(before.elevation)
              && Math.abs(point.elevation - before.elevation) > Math.max(5, distance * .6)))
        return Double.NaN;
    }
    return point.elevation;
  }

  static double deadband(Point point) {
    return Float.isFinite(point.verticalAccuracy) && point.verticalAccuracy >= 0
        ? Math.max(5, point.verticalAccuracy)
        : 10;
  }
}
