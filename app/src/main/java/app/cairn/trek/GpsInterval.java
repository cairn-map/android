package app.cairn.trek;

/** Logarithmic slider positions, with enough resolution to round-trip every whole second. */
final class GpsInterval {
  static final int STEPS = 30000;
  static final int[] TICKS = {1, 5, 20, 60, 180, 600, 3600};

  static int nearestTick(int position) {
    int nearest = 0;
    for (int seconds : TICKS) {
      int tick = progress(seconds);
      if (Math.abs(position - tick) < Math.abs(position - nearest)) nearest = tick;
    }
    return nearest;
  }

  static int seconds(int progress) {
    return (int)
        Math.round(Math.exp(Math.log(3600) * Math.max(0, Math.min(STEPS, progress)) / STEPS));
  }

  static int progress(int seconds) {
    return (int)
        Math.round(STEPS * Math.log(Math.max(1, Math.min(3600, seconds))) / Math.log(3600));
  }
}
