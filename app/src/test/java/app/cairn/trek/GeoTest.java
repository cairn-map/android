package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class GeoTest {
  @Test
  public void projectionRoundTrip() {
    for (int z = 2; z < 19; z++) {
      assertEquals(45.9237, Geo.lat(Geo.y(45.9237, z), z), 1e-7);
      assertEquals(6.8694, Geo.lon(Geo.x(6.8694, z), z), 1e-7);
    }
  }

  @Test
  public void segmentsDoNotCreateImaginaryConnections() {
    List<Point> route = List.of(new Point(45, 6, 0, 0, 0, 0), new Point(45, 7, 0, 0, 0, 1));
    assertEquals(0, Geo.length(route), 0.001);
    assertTrue(Geo.routeDistance(new Point(45, 6.5), route) > 30000);
  }

  @Test
  public void nearestSegmentUsesInteriorAndEndpoints() {
    Point a = new Point(0, 0), b = new Point(0, 0.01);
    assertEquals(111.195, Geo.segmentDistance(new Point(0.001, 0.005), a, b), 0.1);
    assertEquals(111.195, Geo.segmentDistance(new Point(0, 0.011), a, b), 0.1);
  }

  @Test
  public void zeroLengthSegmentIsSafe() {
    assertEquals(0, Geo.segmentDistance(new Point(45, 6), new Point(45, 6), new Point(45, 6)), 0);
  }

  @Test
  public void invalidCoordinatesRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Point(Double.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> new Point(91, 0));
  }
}
