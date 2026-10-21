package app.cairn.trek;

import java.util.List;

public final class Geo {
  private static final double R = 6371008.8;

  private Geo() {}

  public static double distance(Point a, Point b) {
    double p = Math.toRadians(b.lat - a.lat), l = Math.toRadians(b.lon - a.lon);
    double v =
        Math.sin(p / 2) * Math.sin(p / 2)
            + Math.cos(Math.toRadians(a.lat))
                * Math.cos(Math.toRadians(b.lat))
                * Math.sin(l / 2)
                * Math.sin(l / 2);
    return 2 * R * Math.asin(Math.sqrt(Math.min(1, v)));
  }

  public static double length(List<Point> points) {
    double total = 0;
    for (int i = 1; i < points.size(); i++)
      if (points.get(i).segment == points.get(i - 1).segment)
        total += distance(points.get(i - 1), points.get(i));
    return total;
  }

  public static double segmentDistance(Point p, Point a, Point b) {
    double cos = Math.cos(Math.toRadians(p.lat));
    double ax = Math.toRadians(wrap(a.lon - p.lon)) * cos * R,
        ay = Math.toRadians(a.lat - p.lat) * R;
    double bx = Math.toRadians(wrap(b.lon - p.lon)) * cos * R,
        by = Math.toRadians(b.lat - p.lat) * R;
    double dx = bx - ax, dy = by - ay;
    double t =
        dx * dx + dy * dy == 0
            ? 0
            : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)));
    return Math.hypot(ax + t * dx, ay + t * dy);
  }

  public static double routeDistance(Point p, List<Point> route) {
    double best = Double.POSITIVE_INFINITY;
    for (int i = 0; i < route.size(); i++) {
      best = Math.min(best, distance(p, route.get(i)));
      if (i > 0 && route.get(i).segment == route.get(i - 1).segment)
        best = Math.min(best, segmentDistance(p, route.get(i - 1), route.get(i)));
    }
    return best;
  }

  public static double wrap(double degrees) {
    return ((degrees + 180) % 360 + 360) % 360 - 180;
  }

  public static double x(double lon, int z) {
    return (lon + 180) / 360 * (1 << z);
  }

  public static double y(double lat, int z) {
    double r = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
    return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z);
  }

  public static double lon(double x, int z) {
    return x / (1 << z) * 360 - 180;
  }

  public static double lat(double y, int z) {
    return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / (1 << z)))));
  }
}
