package app.cairn.trek;

/** Conservative GPS-only display estimates. Never changes the stored measurements. */
final class RecordingElevation {
  private Point previous;
  private double estimate = Double.NaN, estimateBand;
  private double candidateMinimum, candidateMaximum;
  private long candidateSince;
  private int candidateCount;

  double add(Point point) {
    double step =
        previous != null && previous.segment == point.segment ? Geo.distance(previous, point) : 0;
    return add(point, step);
  }

  double add(Point point, double step) {
    Point before = previous;
    previous = point;
    boolean reset =
        before == null
            || before.segment != point.segment
            || (point.time > 0 && before.time > 0 && point.time <= before.time);
    if (reset) clear();
    if (!Double.isFinite(point.elevation)
        || (!Float.isNaN(point.verticalAccuracy)
            && (!Float.isFinite(point.verticalAccuracy)
                || point.verticalAccuracy < 0
                || point.verticalAccuracy > 10))) return exclude();
    if (!reset) {
      // Do not count altitude drift while almost stationary or join abrupt vertical jumps.
      // A deliberately conservative walking-grade bound; excluded stretches remain partial.
      if (step < 1
          || (Double.isFinite(before.elevation)
              && Math.abs(point.elevation - before.elevation) > Math.max(5, step * .6)))
        return exclude();
    }
    double band = deadband(point);
    if (!Double.isFinite(estimate)) {
      // Good reported accuracy is usable immediately. Missing quality needs evidence of settling,
      // using only arriving samples: at least three within a 3 m range spanning ten seconds.
      if (Float.isNaN(point.verticalAccuracy)) {
        if (candidateCount == 0
            || Math.max(candidateMaximum, point.elevation)
                    - Math.min(candidateMinimum, point.elevation)
                > 3) {
          candidateMinimum = candidateMaximum = point.elevation;
          candidateSince = point.time;
          candidateCount = 0;
        }
        candidateMinimum = Math.min(candidateMinimum, point.elevation);
        candidateMaximum = Math.max(candidateMaximum, point.elevation);
        candidateCount++;
        if (candidateCount < 3
            || (point.time > 0 && candidateSince > 0 && point.time - candidateSince < 10_000))
          return Double.NaN;
      }
      estimate = point.elevation;
      estimateBand = band;
    } else {
      // The graph and totals both consume this same hysteresis-filtered signal. Move only the
      // part of a change outside the uncertainty band; do not draw suppressed raw fluctuations.
      double threshold = Math.max(estimateBand, band);
      double change = point.elevation - estimate;
      if (Math.abs(change) > threshold) {
        estimate += change - Math.copySign(threshold, change);
        estimateBand = band;
      }
    }
    return estimate;
  }

  private void clear() {
    estimate = Double.NaN;
    candidateCount = 0;
  }

  private double exclude() {
    clear();
    return Double.NaN;
  }

  static double deadband(Point point) {
    return Float.isFinite(point.verticalAccuracy) && point.verticalAccuracy >= 0
        ? Math.max(5, point.verticalAccuracy)
        : 10;
  }
}
