package app.cairn.trek;

import static org.junit.Assert.*;

import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w393dp-h851dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapRenderingTest {
  @SuppressWarnings("unchecked")
  private LruCache<String, Bitmap> cache(TrekMapView view) throws Exception {
    Field field = TrekMapView.class.getDeclaredField("cache");
    field.setAccessible(true);
    return (LruCache<String, Bitmap>) field.get(view);
  }

  private Bitmap draw(TrekMapView view) {
    Bitmap bitmap = Bitmap.createBitmap(786, 1200, Bitmap.Config.ARGB_8888);
    view.layout(0, 0, 786, 1200);
    view.draw(new Canvas(bitmap));
    return bitmap;
  }

  private static class EmptySource implements TileSource {
    public Bitmap tile(int z, int x, int y) {
      return null;
    }

    public int minZoom() {
      return 2;
    }

    public int maxZoom() {
      return 19;
    }

    public double centerLat() {
      return 0.01;
    }

    public double centerLon() {
      return 0.01;
    }

    public String attribution() {
      return "Test";
    }
  }

  private ThreadPoolExecutor executor(TrekMapView view) throws Exception {
    Field field = TrekMapView.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (ThreadPoolExecutor) field.get(view);
  }

  private void drain(TrekMapView view) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (executor(view).getActiveCount() != 0 || !executor(view).getQueue().isEmpty()) {
      if (System.nanoTime() >= deadline) fail("Tile workers did not drain");
      Thread.sleep(5);
    }
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  }

  @Test
  public void goingOnlineKeepsTheBlurredBackdropUntilFullDetailReplacesIt() throws Exception {
    TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
    view.source(
        new EmptySource() {
          public Bitmap tile(int z, int x, int y) {
            Bitmap result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.BLUE);
            return result;
          }
        });
    try {
      int z = view.zoomLevel(),
          x = (int) Geo.x(view.center().lon, z),
          y = (int) Geo.y(view.center().lat, z);
      Bitmap old = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
      old.eraseColor(Color.RED);
      cache(view).put(z + "/" + x + "/" + y, old);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      view.reloadOnlineTiles();
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      drain(view);
      assertEquals(Color.BLUE, draw(view).getPixel(393, 600));
    } finally {
      view.dispose();
    }
  }

  @Test
  public void cameraChangesDropOldQueuedTilesAndLoadTheNewVisibleCenterFirst() throws Exception {
    assertQueuedTilesCanceled(false);
  }

  @Test
  public void sourceChangesDropOldQueuedTilesBeforeCancelingTheActiveRequest() throws Exception {
    assertQueuedTilesCanceled(true);
  }

  private void assertQueuedTilesCanceled(boolean replaceSource) throws Exception {
    TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
    CountDownLatch firstStarted = new CountDownLatch(4), release = new CountDownLatch(1);
    ThreadPoolExecutor worker = executor(view);
    List<TilePlanner.Tile> requests = Collections.synchronizedList(new ArrayList<>());
    view.source(
        new EmptySource() {
          public Bitmap tile(int z, int x, int y) {
            requests.add(new TilePlanner.Tile(z, x, y));
            if (firstStarted.getCount() > 0) {
              firstStarted.countDown();
              try {
                assertTrue("The first request was not canceled", release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
            }
            return null;
          }

          public void cancelRequests() {
            release.countDown();
            // Let the worker drain before cancellation returns. If the viewport has not
            // invalidated its generation and queue yet, obsolete tiles will be requested.
            try {
              worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            } catch (Exception error) {
              throw new AssertionError("Tile worker did not drain during cancellation", error);
            }
          }
        });
    try {
      draw(view);
      assertTrue("Four visible tiles must load concurrently", firstStarted.await(3, TimeUnit.SECONDS));
      if (replaceSource)
        view.source(
            new EmptySource() {
              public Bitmap tile(int z, int x, int y) {
                requests.add(new TilePlanner.Tile(z, x, y));
                return null;
              }

              public double centerLat() {
                return 46.1;
              }

              public double centerLon() {
                return 7.2;
              }
            });
      else view.center(new Point(46.1, 7.2));
      int oldRequests = requests.size();
      assertEquals("Queued obsolete tiles must never start", 4, oldRequests);
      draw(view);
      drain(view);
      int z = view.zoomLevel();
      List<TilePlanner.Tile> expected =
          TileViewport.ordered(z, Geo.x(7.2, z), Geo.y(46.1, z), 786, 1200, 512);
      assertTrue(requests.subList(oldRequests, Math.min(oldRequests + 4, requests.size()))
          .contains(expected.get(0)));
      for (int i = oldRequests; i < requests.size(); i++) assertTrue(expected.contains(requests.get(i)));
    } finally {
      release.countDown();
      view.dispose();
    }
  }

  @Test
  public void finerCachedTilesAreNotPaintedOverByTheCurrentOverview() throws Exception {
    TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
    view.source(new EmptySource());
    try {
      for (int delta = 0; delta <= 1; delta++) {
        int z = view.zoomLevel() + delta;
        int x = (int) Geo.x(view.center().lon, z), y = (int) Geo.y(view.center().lat, z);
        Bitmap tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        tile.eraseColor(delta == 0 ? Color.RED : Color.BLUE);
        cache(view).put(z + "/" + x + "/" + y, tile);
      }
      assertEquals(Color.BLUE, draw(view).getPixel(393, 600));
    } finally {
      view.dispose();
    }
  }

  @Test
  public void cachedTilesStayVisibleWhenZoomingInAndOut() throws Exception {
    TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
    view.source(
        new TileSource() {
          public Bitmap tile(int z, int x, int y) {
            return null;
          }

          public int minZoom() {
            return 2;
          }

          public int maxZoom() {
            return 19;
          }

          public double centerLat() {
            return 0.01;
          }

          public double centerLon() {
            return 0.01;
          }

          public String attribution() {
            return "Test";
          }
        });
    try {
      Bitmap tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
      tile.eraseColor(Color.RED);
      int z = view.zoomLevel(),
          x = (int) Geo.x(view.center().lon, z),
          y = (int) Geo.y(view.center().lat, z);
      cache(view).put(z + "/" + x + "/" + y, tile);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      view.zoom(1);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      view.zoom(-2);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      view.zoom(5);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
      view.zoom(-7);
      assertEquals(Color.RED, draw(view).getPixel(393, 600));
    } finally {
      view.dispose();
    }
  }

  @Test
  public void fractionalPinchKeepsTheGeographicFocusFixed() {
    TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      view.layout(0, 0, 786, 1200);
      double[] before = view.bounds();
      double anchorLon = before[0] + (before[2] - before[0]) * 0.75;
      view.scaleBy(1.37, 786 * 0.75f, 600);
      double[] after = view.bounds();
      assertEquals(anchorLon, after[0] + (after[2] - after[0]) * 0.75, 0.000001);
      assertEquals(1.37, (before[2] - before[0]) / (after[2] - after[0]), 0.0001);
      TrekMapView restored = new TrekMapView(RuntimeEnvironment.getApplication());
      try {
        restored.layout(0, 0, 786, 1200);
        restored.restoreCamera(view.camera());
        assertArrayEquals(view.bounds(), restored.bounds(), 0.000001);
      } finally {
        restored.dispose();
      }
    } finally {
      view.dispose();
    }
  }

  @Test
  public void compactMapLayoutFitsAndHasNoZoomButtons() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      root.measure(
          View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1550, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 786, 1550);
      TextView gps = find(root, "GPS · 60s"), hike = find(root, "Start hike");
      assertNotNull(gps);
      assertNotNull(hike);
      assertTrue(gps.getWidth() < 300);
      assertTrue(hike.getWidth() < 300);
      TrekMapView map = map(root);
      assertNotNull(map);
      assertEquals(root.getHeight(), map.getHeight());
      assertEquals(0, map.getTop());
      assertNull(find(root, "Cairn"));
      assertNull(find(root, "Offline"));
      View mode = description(root, "Offline maps.");
      assertNotNull(mode);
      int[] location = new int[2];
      mode.getLocationOnScreen(location);
      assertTrue(location[0] > root.getWidth() / 2);
      assertNull(find(root, "+"));
      assertNull(find(root, "−"));
      Bitmap bitmap = Bitmap.createBitmap(786, 1550, Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      File file = new File("build/test-previews/cairn-0.4.png");
      file.getParentFile().mkdirs();
      try (OutputStream out = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
    }
  }

  private TrekMapView map(View view) {
    if (view instanceof TrekMapView) return (TrekMapView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TrekMapView found = map(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private View description(View view, String prefix) {
    if (view.getContentDescription() != null
        && view.getContentDescription().toString().startsWith(prefix)) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = description(((ViewGroup) view).getChildAt(i), prefix);
        if (found != null) return found;
      }
    return null;
  }

  private TextView find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView found = find(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }
}
