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
  public void corridorWidthAndRouteShapeChangeDetailedCoverage() {
    List<Point> route = List.of(new Point(45.90, 6.80), new Point(46.00, 6.95));
    TilePlanner.Plan narrow = TilePlanner.corridor(route, 100, 17);
    TilePlanner.Plan wide = TilePlanner.corridor(route, 2000, 17);
    Point besideRoute = new Point(45.96, 6.875);
    assertFalse(contains(narrow, besideRoute, 17));
    assertTrue(contains(wide, besideRoute, 17));
    assertTrue(wide.tiles.size() > narrow.tiles.size() * 3);
    // The corners of the route's bounding square are outside the corridor.
    assertFalse(contains(wide, new Point(45.90, 6.95), 17));
    assertFalse(contains(wide, new Point(46.00, 6.80), 17));
    assertTrue(new HashSet<>(wide.tiles).containsAll(narrow.tiles));
  }

  @Test
  public void navigationCorridorSavesOnlyZoomTenThroughSeventeen() {
    TilePlanner.Plan plan = TilePlanner.corridor(List.of(new Point(45, 6)), 100, 17);
    Set<Integer> levels = new HashSet<>();
    for (TilePlanner.Tile tile : plan.tiles) levels.add(tile.z);
    assertEquals(Set.of(10, 11, 12, 13, 14, 15, 16, 17), levels);
  }

  @Test
  public void aSinglePointDoesNotIncludeTilesOutsideTheBufferedFootprint() {
    int z = 17;
    Point center = new Point(Geo.lat(50000.5, z), Geo.lon(68000.5, z));
    TilePlanner.Plan plan = TilePlanner.corridor(List.of(center), 100, z);
    // At this latitude 100 m fits inside the central tile. The old half-diagonal
    // padding also selected the adjacent tiles although the buffer never touched them.
    long detailed = plan.tiles.stream().filter(tile -> tile.z == z).count();
    assertEquals(1, detailed);
  }

  @Test
  public void corridorCrossesTheDateLineWithoutSavingTheLongWayAround() {
    TilePlanner.Plan plan =
        TilePlanner.corridor(List.of(new Point(0, 179.99), new Point(0, -179.99)), 200, 17);
    assertTrue(contains(plan, new Point(0, 179.995), 17));
    assertTrue(contains(plan, new Point(0, -179.995), 17));
    assertFalse(contains(plan, new Point(0, 0), 17));
    assertTrue(plan.tiles.size() < 250);
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
  public void everyDetailLevelProducesCoverageIncludingWorldOverview() {
    Point point = new Point(45, 6);
    for (int zoom = 0; zoom <= 19; zoom++) {
      TilePlanner.Plan plan = TilePlanner.corridor(List.of(point), 100, zoom);
      assertTrue(contains(plan, point, zoom));
      for (TilePlanner.Tile tile : plan.tiles) {
        assertTrue(tile.z <= zoom);
        assertTrue(tile.z >= Math.min(zoom, TilePlanner.MIN_ZOOM));
      }
      assertFalse(TilePlanner.area(6, 45, 6.00001, 45.00001, zoom).tiles.isEmpty());
    }
    assertEquals(
        List.of(new TilePlanner.Tile(0, 0, 0)), TilePlanner.corridor(List.of(point), 100, 0).tiles);
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
