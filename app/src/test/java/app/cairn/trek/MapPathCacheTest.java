package app.cairn.trek;

import static org.junit.Assert.*;

import android.graphics.*;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapPathCacheTest {
  private static final class CountingList extends ArrayList<Point> {
    int reads;
    @Override public Point get(int index) { reads++; return super.get(index); }
  }

  private Bitmap draw(TrekMapView map) {
    Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    map.layout(0, 0, 256, 256);
    map.draw(new Canvas(bitmap));
    return bitmap;
  }

  private Map<?, ?> cache(TrekMapView map) throws Exception {
    Field field = TrekMapView.class.getDeclaredField("projectedLines");
    field.setAccessible(true);
    return (Map<?, ?>) field.get(map);
  }

  @Test
  public void redrawsAndAppendsDoNotReprojectTheWholeMultiDayTrack() throws Exception {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    CountingList points = new CountingList();
    for (int i = 0; i < 10000; i++) points.add(new Point(45.9237 + i * .000001, 6.8694));
    map.route = points;
    map.track = points;
    try {
      draw(map).recycle();
      assertTrue(points.reads < 10030); // The route outline shares the same path.
      points.reads = 0;
      draw(map).recycle();
      assertTrue(points.reads < 20);
      points.add(new Point(45.9338, 6.8695));
      points.reads = 0;
      Bitmap appended = draw(map);
      assertTrue(points.reads < 20);
      cache(map).clear();
      Bitmap rebuilt = draw(map);
      assertTrue(appended.sameAs(rebuilt));
      appended.recycle();
      rebuilt.recycle();
      points.reads = 0;
      map.zoom(1);
      draw(map).recycle();
      assertTrue(points.reads >= points.size());
    } finally {
      map.dispose();
    }
  }

  @Test
  public void truncationReplacementSegmentsAndRemovedOverlaysMatchFreshPaths() throws Exception {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    List<Point> points = new ArrayList<>(List.of(new Point(45.9237, 6.868),
        new Point(45.924, 6.869), new Point(45.922, 6.870, 0, 0, 0, 1)));
    map.track = points;
    try {
      draw(map).recycle();
      points.remove(2);
      assertFreshPixels(map);
      points.add(new Point(45.922, 6.870, 0, 0, 0, 2));
      assertFreshPixels(map);
      points.set(2, new Point(45.925, 6.872));
      assertFreshPixels(map);
      map.track = new ArrayList<>(points);
      map.otherRoutes = List.of(points);
      draw(map).recycle();
      assertEquals(2, cache(map).size());
      map.otherRoutes = List.of();
      draw(map).recycle();
      assertEquals(1, cache(map).size());
      points.clear();
      map.track = points;
      draw(map).recycle();
      assertTrue(cache(map).isEmpty());
    } finally {
      map.dispose();
    }
    assertTrue(cache(map).isEmpty());
  }

  private void assertFreshPixels(TrekMapView map) throws Exception {
    Bitmap cached = draw(map);
    cache(map).clear();
    Bitmap fresh = draw(map);
    assertTrue(cached.sameAs(fresh));
    cached.recycle();
    fresh.recycle();
  }
}
