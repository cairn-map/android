package app.cairn.trek;

import java.util.*;

/** Conservative Web Mercator coverage, with a hard bound on CPU, storage and job size. */
public final class TilePlanner {
  public static final int MIN_ZOOM = 10, MAX_TILES = 20000;
  private static final double EARTH_CIRCUMFERENCE = 40075016.686;

  public static final class Tile implements Comparable<Tile> {
    public final int z, x, y;

    public Tile(int z, int x, int y) {
      if (z < 0 || z > 19 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z))
        throw new IllegalArgumentException("Invalid tile");
      this.z = z;
      this.x = x;
      this.y = y;
    }

    @Override
    public boolean equals(Object value) {
      if (!(value instanceof Tile)) return false;
      Tile t = (Tile) value;
      return z == t.z && x == t.x && y == t.y;
    }

    @Override
    public int hashCode() {
      return Objects.hash(z, x, y);
    }

    @Override
    public int compareTo(Tile t) {
      int c = Integer.compare(z, t.z);
      if (c == 0) c = Integer.compare(y, t.y);
      return c == 0 ? Integer.compare(x, t.x) : c;
    }
  }

  public static final class Plan {
    public final List<Tile> tiles;
    public final Point center;

    Plan(Set<Tile> tiles, Point center) {
      List<Tile> sorted = new ArrayList<>(tiles);
      Collections.sort(sorted);
      this.tiles = Collections.unmodifiableList(sorted);
      this.center = center;
    }

    public long estimatedBytes() {
      return tiles.size() * 75000L;
    }
  }

  private TilePlanner() {}

  private static void detail(int z) {
    if (z < 0 || z > 19) throw new IllegalArgumentException("Invalid map detail");
  }

  private static void add(Set<Tile> result, int z, int x, int y) {
    if (y < 0 || y >= (1 << z)) return;
    result.add(new Tile(z, Math.floorMod(x, 1 << z), y));
    if (result.size() > MAX_TILES)
      throw new IllegalArgumentException(
          "This selection exceeds 20,000 tiles. Choose a smaller area, narrower corridor, or less"
              + " detail.");
  }

  public static Plan area(double west, double south, double east, double north, int maxZoom) {
    detail(maxZoom);
    if (!Double.isFinite(west)
        || !Double.isFinite(east)
        || !Double.isFinite(south)
        || !Double.isFinite(north)
        || north <= south
        || south < -85
        || north > 85
        || east == west
        || Math.abs(east - west) > 360)
      throw new IllegalArgumentException("Choose a valid map area between 85° south and north");
    if (east < west) east += 360;
    Set<Tile> result = new HashSet<>();
    for (int z = Math.min(MIN_ZOOM, maxZoom); z <= maxZoom; z++) {
      int x0 = (int) Math.floor(Geo.x(west, z)), x1 = (int) Math.floor(Geo.x(east, z));
      int y0 = Math.max(0, (int) Math.floor(Geo.y(north, z))),
          y1 = Math.min((1 << z) - 1, (int) Math.floor(Geo.y(south, z)));
      if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) + result.size() > MAX_TILES)
        throw new IllegalArgumentException(
            "This area is too large at this detail. Zoom in or choose less detail.");
      for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) add(result, z, x, y);
    }
    return new Plan(result, new Point((south + north) / 2, Geo.wrap((west + east) / 2)));
  }

  public static Plan corridor(List<Point> route, double radiusMeters, int maxZoom) {
    detail(maxZoom);
    if (route.isEmpty()
        || !Double.isFinite(radiusMeters)
        || radiusMeters < 100
        || radiusMeters > 10000)
      throw new IllegalArgumentException(
          "Import a GPX and choose a corridor width of 0.1–10 km on each side");
    Set<Tile> highest = new HashSet<>();
    for (int i = 0; i < route.size(); i++) {
      Point a = route.get(i),
          b = i + 1 < route.size() && route.get(i + 1).segment == a.segment ? route.get(i + 1) : a;
      double ax = Geo.x(a.lon, maxZoom), ay = Geo.y(a.lat, maxZoom);
      double bx = ax + Geo.wrap(b.lon - a.lon) / 360 * (1 << maxZoom), by = Geo.y(b.lat, maxZoom);
      double maxLat =
          Math.min(85, Math.max(Math.abs(a.lat), Math.abs(b.lat)) + radiusMeters / 110000);
      double radius =
          radiusMeters / EARTH_CIRCUMFERENCE / Math.cos(Math.toRadians(maxLat)) * (1 << maxZoom);
      int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay)));
      if (steps > 100000)
        throw new IllegalArgumentException("Route segment too long; use a smaller GPX area");
      for (int step = 0; step < steps; step++) {
        double x0 = ax + (bx - ax) * step / steps, y0 = ay + (by - ay) * step / steps;
        double x1 = ax + (bx - ax) * (step + 1) / steps, y1 = ay + (by - ay) * (step + 1) / steps;
        double padding = radius;
        int left = (int) Math.floor(Math.min(x0, x1) - padding),
            right = (int) Math.floor(Math.max(x0, x1) + padding);
        int top = Math.max(0, (int) Math.floor(Math.min(y0, y1) - padding)),
            bottom = Math.min((1 << maxZoom) - 1, (int) Math.floor(Math.max(y0, y1) + padding));
        for (int x = left; x <= right; x++)
          for (int y = top; y <= bottom; y++)
            if (intersects(x, y, x0, y0, x1, y1, radius)) add(highest, maxZoom, x, y);
      }
    }
    Set<Tile> all = new HashSet<>();
    for (Tile t : highest)
      for (int z = Math.min(MIN_ZOOM, maxZoom); z <= maxZoom; z++)
        add(all, z, t.x >> (maxZoom - z), t.y >> (maxZoom - z));
    return new Plan(all, route.get(0));
  }

  /** The buffered segment must actually touch the tile, not just its bounding square. */
  private static boolean intersects(
      int x, int y, double ax, double ay, double bx, double by, double radius) {
    double dx = bx - ax, dy = by - ay;
    double enter = 0, exit = 1;
    if (dx == 0) {
      if (ax < x || ax > x + 1) exit = -1;
    } else {
      double first = (x - ax) / dx, second = (x + 1 - ax) / dx;
      enter = Math.max(enter, Math.min(first, second));
      exit = Math.min(exit, Math.max(first, second));
    }
    if (dy == 0) {
      if (ay < y || ay > y + 1) exit = -1;
    } else {
      double first = (y - ay) / dy, second = (y + 1 - ay) / dy;
      enter = Math.max(enter, Math.min(first, second));
      exit = Math.min(exit, Math.max(first, second));
    }
    if (enter <= exit) return true;
    if (Math.hypot(
                Math.max(x - ax, Math.max(0, ax - x - 1)),
                Math.max(y - ay, Math.max(0, ay - y - 1)))
            <= radius
        || Math.hypot(
                Math.max(x - bx, Math.max(0, bx - x - 1)),
                Math.max(y - by, Math.max(0, by - y - 1)))
            <= radius) return true;
    return distance(x, y, ax, ay, bx, by) <= radius
        || distance(x + 1, y, ax, ay, bx, by) <= radius
        || distance(x, y + 1, ax, ay, bx, by) <= radius
        || distance(x + 1, y + 1, ax, ay, bx, by) <= radius;
  }

  private static double distance(double x, double y, double ax, double ay, double bx, double by) {
    double dx = bx - ax, dy = by - ay;
    double t =
        dx * dx + dy * dy == 0
            ? 0
            : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
    return Math.hypot(x - ax - t * dx, y - ay - t * dy);
  }
}
