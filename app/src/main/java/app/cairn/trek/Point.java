package app.cairn.trek;

public final class Point {
  public static final int SEA_LEVEL = 0, WGS84_ELLIPSOID = 1;
  public final double lat, lon, elevation;
  public final long time;
  public final float accuracy;
  public final float verticalAccuracy;
  public final int segment;
  public final int elevationDatum;
  public final double mslElevation;

  public Point(double lat, double lon) {
    this(lat, lon, Double.NaN, 0, 0, 0);
  }

  public Point(double lat, double lon, double elevation, long time, float accuracy, int segment) {
    this(lat, lon, elevation, time, accuracy, segment, Float.NaN);
  }

  public Point(
      double lat,
      double lon,
      double elevation,
      long time,
      float accuracy,
      int segment,
      float verticalAccuracy) {
    this(lat, lon, elevation, time, accuracy, segment, verticalAccuracy, SEA_LEVEL, Double.NaN);
  }

  public Point(
      double lat,
      double lon,
      double elevation,
      long time,
      float accuracy,
      int segment,
      float verticalAccuracy,
      int elevationDatum,
      double mslElevation) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || lat < -90
        || lat > 90
        || lon < -180
        || lon > 180) throw new IllegalArgumentException("Invalid geographic coordinates");
    if (elevationDatum != SEA_LEVEL && elevationDatum != WGS84_ELLIPSOID)
      throw new IllegalArgumentException("Unknown elevation reference");
    this.lat = lat;
    this.lon = lon;
    this.elevation = elevation;
    this.time = time;
    this.accuracy = accuracy;
    this.verticalAccuracy = verticalAccuracy;
    this.segment = segment;
    this.elevationDatum = elevationDatum;
    this.mslElevation = mslElevation;
  }

  /** Stored elevation is preserved in its explicit reference; UI/export use this conversion. */
  public double seaLevelElevation() {
    if (elevationDatum == SEA_LEVEL) return elevation;
    if (Double.isFinite(mslElevation)) return mslElevation;
    if (!Double.isFinite(elevation)) return Double.NaN;
    return elevation - Geoid.separation(lat, lon);
  }
}
