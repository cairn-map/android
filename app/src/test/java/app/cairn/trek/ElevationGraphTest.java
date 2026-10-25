package app.cairn.trek;

import static org.junit.Assert.*;

import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
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
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class ElevationGraphTest {
  private final List<Point> points =
      List.of(
          new Point(45, 6, 100, 0, 0, 0),
          new Point(45.01, 6, 200, 0, 0, 0),
          new Point(45.02, 6, 150, 0, 0, 0));

  @Before
  public void setup() {
    TrackingService.running = false;
    DownloadService.running = false;
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().deleteDatabase("plan-ign.mbtiles");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      store.saveRoute(points, "Alpine route");
    }
  }

  private Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private void drain(MainActivity activity) throws Exception {
    for (int i = 0; i < 4; i++) {
      ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private void layout(View root, int width, int height) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
  }

  private void touch(ElevationGraph graph, int action, float x) {
    MotionEvent event = MotionEvent.obtain(0, 20, action, x, graph.getHeight() / 2f, 0);
    assertTrue(graph.onTouchEvent(event));
    event.recycle();
  }

  @Test
  @Config(sdk = {26, 28, 35})
  public void draggingAndAccessibleStepsUpdateDetailsAndMapWithoutChangingGps() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      layout(activity.getWindow().getDecorView(), 786, 1550);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      TrekMapView map = (TrekMapView) field(activity, "map");
      Point gps = new Point(44, 5);
      map.position = gps;
      assertEquals(View.VISIBLE, ((View) field(activity, "selectionInfo")).getVisibility());
      float left = graph.getPaddingLeft();
      float right = graph.getWidth() - graph.getPaddingRight();
      touch(graph, MotionEvent.ACTION_DOWN, left);
      touch(graph, MotionEvent.ACTION_MOVE, (left + right) / 2);
      Point selected = (Point) field(map, "profilePoint");
      assertEquals(45.01, selected.lat, .00001);
      TextView details = (TextView) field(activity, "profileDetails");
      assertTrue(details.getText().toString().contains("200 m"));
      assertTrue(details.getText().toString().contains("45.01000"));
      touch(graph, MotionEvent.ACTION_UP, right + 50);
      assertEquals(45.02, ((Point) field(map, "profilePoint")).lat, .000001);
      assertSame(gps, map.position);
      map.center(new Point(40, 2));
      touch(graph, MotionEvent.ACTION_DOWN, right);
      touch(graph, MotionEvent.ACTION_UP, right);
      assertTrue("Tapping the same endpoint must reveal it after panning", map.center().lat > 44);
      assertTrue(
          graph.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
      assertTrue(graph.getProgress() < graph.getMax());
      assertTrue(graph.getContentDescription().toString().contains("km"));
      controller.pause().resume();
      drain(activity);
      assertTrue(((ElevationGraph) field(activity, "elevationGraph")).getProgress() > 0);
      map.onPathSelected.accept(null);
      assertNull(field(map, "profilePoint"));
      assertEquals(View.GONE, ((View) field(activity, "selectionInfo")).getVisibility());
    }
  }

  @Test
  public void switchingToARecordingResetsTheCursorAndEditingClearsIt() throws Exception {
    String recording;
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      recording = store.newTrek();
      store.add(recording, new Point(46, 7));
      store.add(recording, new Point(46.01, 7));
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      ((ElevationGraph) field(activity, "elevationGraph")).setProgress(5000);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.onPathSelected.accept(recording);
      drain(activity);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      assertEquals(0, graph.getProgress());
      assertEquals(46, ((Point) field(map, "profilePoint")).lat, 0);
      assertTrue(
          ((TextView) field(activity, "profileDetails"))
              .getText()
              .toString()
              .contains("Elevation unavailable"));
      graph.setProgress(7500);
      controller.recreate();
      activity = controller.get();
      drain(activity);
      assertEquals(7500, ((ElevationGraph) field(activity, "elevationGraph")).getProgress());
      java.lang.reflect.Method edit = MainActivity.class.getDeclaredMethod("toggleEditing");
      edit.setAccessible(true);
      edit.invoke(activity);
      drain(activity);
      assertEquals(View.GONE, ((View) field(activity, "selectionInfo")).getVisibility());
      assertNull(field(field(activity, "map"), "profilePoint"));
    }
  }

  @Test
  public void liveAppendAndNewSelectionRefreshTheProfile() {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    List<Point> live = new ArrayList<>(points);
    graph.bind(live);
    graph.setProgress(graph.getMax());
    assertEquals(150, graph.selectedPoint().elevation, 0);
    live.add(new Point(45.03, 6, 300, 0, 0, 0));
    graph.bind(live);
    assertEquals(300, graph.selectedPoint().elevation, 0);
    graph.bind(List.of(new Point(46, 7)));
    assertTrue(Double.isNaN(graph.selectedPoint().elevation));
    assertEquals(46, graph.selectedPoint().lat, 0);
    graph.bind(List.of());
    assertNull(graph.selectedPoint());
    assertFalse(graph.isEnabled());
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void profileAndMarkerRenderInPortrait() throws Exception {
    renderPreview(786, 1550, "route-elevation-portrait");
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void profileAndMarkerRenderInLandscape() throws Exception {
    renderPreview(1280, 640, "route-elevation-landscape");
  }

  private void renderPreview(int width, int height, String name) throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View root = activity.getWindow().getDecorView();
      layout(root, width, height);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.fitRoute(points);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      graph.setProgress(graph.getMax() / 2);
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      assertTrue(graph.getHeight() > 0);
      assertTrue(map.topOverlay < map.getHeight() - map.bottomOverlay);
      Point selected = (Point) field(map, "profilePoint");
      double[] camera = map.camera();
      double world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, camera[2]);
      int x =
          (int) Math.round(map.getWidth() / 2.0 + Geo.wrap(selected.lon - camera[1]) / 360 * world);
      int y =
          (int)
              Math.round(
                  map.getHeight() / 2.0 + (Geo.y(selected.lat, 0) - Geo.y(camera[0], 0)) * world);
      int[] location = new int[2];
      map.getLocationInWindow(location);
      assertEquals(AppPalette.ACCENT, bitmap.getPixel(x + location[0], y + location[1]));
      File file = new File("build/test-previews/" + name + ".png");
      file.getParentFile().mkdirs();
      try (OutputStream output = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
    }
  }
}
