package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TilePlannerTest {
  private boolean contains(TilePlanner.Plan plan, Point p, int zoom) {
    return plan.tiles.contains(
        new TilePlanner.Tile(
            zoom, (int) Math.floor(Geo.x(p.lon, zoom)), (int) Math.floor(Geo.y(p.lat, zoom))));
  }

  @Test
  public void areaIncludesCornersAndEveryDetailLevel() {
    TilePlanner.Plan plan = TilePlanner.area(6.86, 45.92, 6.88, 45.94, 17);
    for (int z = TilePlanner.MIN_ZOOM; z <= 17; z++) {
      assertTrue(contains(plan, new Point(45.92, 6.86), z));
      assertTrue(contains(plan, new Point(45.94, 6.88), z));
    }
    assertEquals(plan.tiles.size(), new HashSet<>(plan.tiles).size());
  }

  @Test
  public void sparseDiagonalRouteKeepsCoverageBetweenWaypoints() {
    Point a = new Point(45.90, 6.80), b = new Point(46.00, 6.95);
    TilePlanner.Plan plan = TilePlanner.corridor(List.of(a, b), 500, 17);
    for (int i = 0; i <= 100; i++) {
      double t = i / 100.0;
      Point center = new Point(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t);
      assertTrue(contains(plan, center, 17));
      for (double angle : new double[] {0, Math.PI / 2, Math.PI, 3 * Math.PI / 2}) {
        Point edge =
            new Point(
                center.lat + 490 / 111195 * Math.cos(angle),
                center.lon + 490 / 111195 / Math.cos(Math.toRadians(center.lat)) * Math.sin(angle));
        assertTrue(contains(plan, edge, 17));
      }
    }
  }

  @Test
  public void disconnectedSegmentsDoNotDownloadTheGap() {
    List<Point> route = List.of(new Point(45, 6, 0, 0, 0, 0), new Point(46, 7, 0, 0, 0, 1));
    TilePlanner.Plan plan = TilePlanner.corridor(route, 200, 17);
    assertTrue(contains(plan, route.get(0), 17));
    assertTrue(contains(plan, route.get(1), 17));
    assertFalse(contains(plan, new Point(45.5, 6.5), 17));
  }

  @Test
  public void dateLineWrapDoesNotDownloadTheRestOfTheWorld() {
    TilePlanner.Plan plan = TilePlanner.area(179.99, -0.01, -179.99, 0.01, 15);
    assertTrue(contains(plan, new Point(0, 179.995), 15));
    assertTrue(contains(plan, new Point(0, -179.995), 15));
    assertTrue(plan.tiles.size() < 100);
  }

  @Test
  public void globalAndOversizedSelectionsFailBeforeAllocatingHugeJobs() {
    assertThrows(IllegalArgumentException.class, () -> TilePlanner.area(-180, -80, 180, 80, 19));
    assertThrows(
        IllegalArgumentException.class,
        () -> TilePlanner.corridor(List.of(new Point(45, 6), new Point(46, 7)), 10000, 19));
  }

  @Test
  public void invalidSelectionsFailClearly() {
    assertThrows(IllegalArgumentException.class, () -> TilePlanner.corridor(List.of(), 1000, 17));
    assertThrows(IllegalArgumentException.class, () -> TilePlanner.area(0, 0, 1, 1, 20));
    assertThrows(IllegalArgumentException.class, () -> TilePlanner.area(0, 10, 1, 9, 17));
    assertThrows(
        IllegalArgumentException.class,
        () -> TilePlanner.corridor(List.of(new Point(45, 6)), Double.NaN, 17));
  }
}
