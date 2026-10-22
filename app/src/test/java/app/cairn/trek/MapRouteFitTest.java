package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapRouteFitTest {
  private TrekMapView map;

  @Before
  public void setup() {
    map = new TrekMapView(RuntimeEnvironment.getApplication());
  }

  @After
  public void close() {
    map.dispose();
  }

  private void assertVisible(List<Point> points) {
    double[] camera = map.camera();
    double density = map.getResources().getDisplayMetrics().density;
    double world = 256 * density * Math.pow(2, camera[2]);
    for (Point point : points) {
      double x = map.getWidth() / 2.0 + Geo.wrap(point.lon - camera[1]) / 360 * world;
      double y = map.getHeight() / 2.0 + (Geo.y(point.lat, 0) - Geo.y(camera[0], 0)) * world;
      assertTrue("Left edge: " + x, x >= 32 * density - 0.01);
      assertTrue("Right controls: " + x, x <= map.getWidth() - 96 * density + 0.01);
      assertTrue("Top edge: " + y, y >= 32 * density - 0.01);
      double bottom = Math.max(96 * density, map.bottomOverlay + 32 * density);
      assertTrue("Bottom controls: " + y, y <= map.getHeight() - bottom + 0.01);
    }
  }

  @Test
  public void fitsWideTallAndLoopingRoutesInPortraitAndLandscape() {
    for (int[] size : new int[][] {{600, 1000}, {1000, 600}}) {
      map.layout(0, 0, size[0], size[1]);
      for (List<Point> points :
          Arrays.asList(
              Arrays.asList(new Point(45, 5), new Point(45, 8)),
              Arrays.asList(new Point(44, 6), new Point(47, 6)),
              Arrays.asList(new Point(70, 5), new Point(72, 8), new Point(70, 5.01)))) {
        map.restoreCamera(new double[] {0, 0, 18});
        map.fitRoute(points);
        assertTrue(map.camera()[2] < 18);
        assertVisible(points);
        map.restoreCamera(new double[] {0, 0, 2});
        map.fitRoute(points);
        assertTrue(map.camera()[2] > 2);
        assertVisible(points);
      }
    }
  }

  @Test
  public void dateLineRoutesUseTheShortLongitudeSpan() {
    map.layout(0, 0, 600, 1000);
    List<Point> points = Arrays.asList(new Point(45, 179.8), new Point(45.1, -179.8));
    map.fitRoute(points);
    assertTrue(Math.abs(map.center().lon) > 179);
    assertTrue(map.camera()[2] > 8);
    assertVisible(points);
  }

  @Test
  public void defersUntilLayoutAndKeepsClearOfThePlanningPanel() {
    List<Point> points = Arrays.asList(new Point(45, 6), new Point(45.2, 6.1));
    map.bottomOverlay = 260;
    map.fitRoute(points);
    map.layout(0, 0, 600, 1000);
    assertVisible(points);
  }

  @Test
  public void emptyAndSinglePointRoutesKeepAFiniteCamera() {
    map.layout(0, 0, 600, 1000);
    double[] before = map.camera();
    map.fitRoute(Collections.emptyList());
    assertArrayEquals(before, map.camera(), 0);
    List<Point> points = Arrays.asList(new Point(45, 6), new Point(45, 6));
    map.fitRoute(points);
    assertEquals(19, map.camera()[2], 0);
    assertVisible(points);
    map.fitRoute(Collections.singletonList(points.get(0)));
    assertVisible(points);
  }
}
