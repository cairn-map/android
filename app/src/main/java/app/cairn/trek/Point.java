package app.cairn.trek;

public final class Point {
  public final double lat, lon, elevation;
  public final long time;
  public final float accuracy;
  public final float verticalAccuracy;
  public final int segment;

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
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || lat < -90
        || lat > 90
        || lon < -180
        || lon > 180) throw new IllegalArgumentException("Invalid geographic coordinates");
    this.lat = lat;
    this.lon = lon;
    this.elevation = elevation;
    this.time = time;
    this.accuracy = accuracy;
    this.verticalAccuracy = verticalAccuracy;
    this.segment = segment;
  }
}
