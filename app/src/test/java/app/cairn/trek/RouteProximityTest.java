package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RouteProximityTest {
  @Test
  public void exactDistanceMatchesExhaustiveSearchAcrossGlobalAndLocalRoutes() {
    Random random = new Random(73921);
    for (boolean local : new boolean[] {false, true}) {
      for (int fixture = 0; fixture < 12; fixture++) {
        List<Point> route = new ArrayList<>();
        double lat = random.nextDouble() * 160 - 80, lon = random.nextDouble() * 340 - 170;
        for (int i = 0; i < 513; i++) {
          route.add(new Point(local ? lat + random.nextDouble() * .1 : random.nextDouble() * 180 - 90,
              local ? lon + random.nextDouble() * .1 : random.nextDouble() * 360 - 180,
              0, 0, 5, i / 53));
        }
        RouteProximity proximity = new RouteProximity(route);
        for (int i = 0; i < 80; i++) {
          Point point = new Point(local ? lat + random.nextDouble() * .2 : random.nextDouble() * 180 - 90,
              local ? lon + random.nextDouble() * .2 : random.nextDouble() * 360 - 180);
          assertEquals(Geo.routeDistance(point, route), proximity.distance(point), 1e-6);
        }
      }
    }
  }

  @Test
  public void boundariesDatelinePolesAndDisconnectedSegmentsStayExact() {
    List<Point> route = new ArrayList<>();
    for (int i = 0; i < 129; i++)
      route.add(new Point(i % 3 == 0 ? 90 : i % 3 == 1 ? -90 : 0,
          i % 2 == 0 ? 179.99 : -179.99, 0, 0, 5, i / 32));
    RouteProximity proximity = new RouteProximity(route);
    for (double lat : new double[] {-90, -89.999, 0, 89.999, 90})
      for (double lon : new double[] {-180, -179.99, 0, 179.99, 180}) {
        Point point = new Point(lat, lon);
        assertEquals(Geo.routeDistance(point, route), proximity.distance(point), 1e-6);
      }
    assertEquals(Double.POSITIVE_INFINITY, new RouteProximity(List.of()).distance(new Point(0, 0)), 0);
    List<Point> disconnected = List.of(new Point(45, 6, 0, 0, 0, 0), new Point(45, 7, 0, 0, 0, 1));
    assertTrue(new RouteProximity(disconnected).distance(new Point(45, 6.5)) > 30000);
  }

  @Test
  public void longRouteChecksSkipDistantBlocksAndAllowBacktracking() {
    List<Point> route = new ArrayList<>();
    for (int i = 0; i < 10000; i++) route.add(new Point(45 + i * .00001, 6 + i * .00001));
    RouteProximity proximity = new RouteProximity(route);
    for (int index : new int[] {0, 9999, 5000, 32, 31, 0}) {
      Point point = route.get(index);
      assertEquals(0, proximity.distance(point), 1e-6);
      assertTrue("Bounded exact distance calculations: " + proximity.testedPoints,
          proximity.testedPoints < 400);
    }
  }
}
