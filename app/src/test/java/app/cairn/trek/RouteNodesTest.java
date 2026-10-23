package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RouteNodesTest {
  @Test
  public void importedGeometryRetainsEndpointsAndTurnsWithoutThousandsOfAnchors() {
    List<Point> geometry = new ArrayList<>();
    for (int i = 0; i <= 1000; i++) geometry.add(new Point(45, 6 + i * .00001));
    Point turn = geometry.get(geometry.size() - 1);
    for (int i = 1; i <= 1000; i++) geometry.add(new Point(45 + i * .00001, 6.01));
    List<Point> nodes = RouteNodes.fromGeometry(geometry);
    assertEquals(3, nodes.size());
    assertSame(geometry.get(0), nodes.get(0));
    assertSame(turn, nodes.get(1));
    assertSame(geometry.get(geometry.size() - 1), nodes.get(2));
  }

  @Test
  public void complexImportedRouteHasBoundedOrderedAnchorsAndKeepsItsLoopEndpoints() {
    List<Point> geometry = new ArrayList<>();
    for (int i = 0; i <= 1000; i++) geometry.add(new Point(45 + i * .001, 6 + i % 2 * .01));
    geometry.add(geometry.get(0));
    List<Point> nodes = RouteNodes.fromGeometry(geometry);
    assertTrue(nodes.size() <= 64);
    assertSame(geometry.get(0), nodes.get(0));
    assertSame(geometry.get(0), nodes.get(nodes.size() - 1));
    int last = -1;
    for (int i = 0; i < nodes.size() - 1; i++) {
      int index = geometry.indexOf(nodes.get(i));
      assertTrue(index > last);
      last = index;
    }
  }
}
