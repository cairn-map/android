package app.cairn.trek;

/** Logarithmic slider positions, with enough resolution to round-trip every whole second. */
final class GpsInterval {
  static final int STEPS = 30000;

  static int seconds(int progress) {
    return (int)
        Math.round(Math.exp(Math.log(3600) * Math.max(0, Math.min(STEPS, progress)) / STEPS));
  }

  static int progress(int seconds) {
    return (int)
        Math.round(STEPS * Math.log(Math.max(1, Math.min(3600, seconds))) / Math.log(3600));
  }
}
