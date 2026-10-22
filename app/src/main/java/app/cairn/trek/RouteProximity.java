package app.cairn.trek;

import java.util.List;

/** Exact route distance with conservative block bounds; never assumes forward progress. */
final class RouteProximity {
  private static final int BLOCK = 32;
  private static final double METERS_PER_DEGREE = Math.PI * 6371008.8 / 180;
  private final Point[] points;
  private final Bounds[] blocks;
  private final double[] lowerBounds;
  private int nearest;
  int testedPoints;

  RouteProximity(List<Point> route) {
    points = route.toArray(new Point[0]);
    blocks = new Bounds[(points.length + BLOCK - 1) / BLOCK];
    lowerBounds = new double[blocks.length];
    for (int block = 0; block < blocks.length; block++) {
      Bounds bounds = new Bounds();
      // Include the predecessor so boundary-crossing segments cannot be pruned.
      for (int i = Math.max(0, block * BLOCK - 1);
          i < Math.min(points.length, (block + 1) * BLOCK); i++) bounds.include(points[i]);
      blocks[block] = bounds;
    }
  }

  double distance(Point point) {
    testedPoints = 0;
    if (blocks.length == 0) return Double.POSITIVE_INFINITY;
    double cos = Math.max(0, Math.cos(Math.toRadians(point.lat)));
    int candidate = nearest;
    double minimum = Double.POSITIVE_INFINITY;
    for (int block = 0; block < blocks.length; block++) {
      lowerBounds[block] = blocks[block].lowerBound(point, cos);
      if (lowerBounds[block] < minimum) {
        minimum = lowerBounds[block];
        candidate = block;
      }
    }
    double best = scan(point, candidate, Double.POSITIVE_INFINITY);
    nearest = candidate;
    for (int block = 0; block < blocks.length; block++) {
      if (block == candidate || lowerBounds[block] > best) continue;
      double distance = scan(point, block, best);
      if (distance < best) {
        best = distance;
        nearest = block;
      }
    }
    return best;
  }

  private double scan(Point point, int block, double best) {
    for (int i = block * BLOCK; i < Math.min(points.length, (block + 1) * BLOCK); i++) {
      testedPoints++;
      best = Math.min(best, Geo.distance(point, points[i]));
      if (i > 0 && points[i].segment == points[i - 1].segment)
        best = Math.min(best, Geo.segmentDistance(point, points[i - 1], points[i]));
    }
    return best;
  }

  private static final class Bounds {
    double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180, minCos;

    void include(Point point) {
      minLat = Math.min(minLat, point.lat);
      maxLat = Math.max(maxLat, point.lat);
      minLon = Math.min(minLon, point.lon);
      maxLon = Math.max(maxLon, point.lon);
      minCos = Math.max(0, Math.cos(Math.toRadians(Math.max(Math.abs(minLat), Math.abs(maxLat)))));
    }

    double lowerBound(Point point, double cos) {
      double latitude = Math.max(0, Math.max(minLat - point.lat, point.lat - maxLat))
          * METERS_PER_DEGREE;
      // Geo.segmentDistance wraps each endpoint independently. If a block crosses that
      // discontinuity, latitude alone remains a safe bound, including at the dateline.
      if (minLon - point.lon <= -180 || maxLon - point.lon >= 180) return latitude;
      double gap = Math.max(0, Math.max(minLon - point.lon, point.lon - maxLon));
      double planar = gap * METERS_PER_DEGREE * cos;
      // Haversine's longitude term gives a chord bound for vertices; use the smaller
      // bound to cover both spherical vertices and the local planar segment metric.
      double chord = 2 * 6371008.8 * Math.sqrt(cos * minCos) * Math.sin(Math.toRadians(gap) / 2);
      return Math.max(latitude, Math.min(planar, chord)) * (1 - 1e-12);
    }
  }
}
