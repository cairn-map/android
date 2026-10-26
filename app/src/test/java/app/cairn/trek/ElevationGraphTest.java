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

  private void invoke(MainActivity activity, String name) throws Exception {
    java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  private void assertCleared(MainActivity activity) throws Exception {
    assertNull(((ElevationGraph) field(activity, "elevationGraph")).selectedPoint());
    assertNull(field(field(activity, "map"), "profilePoint"));
    assertEquals("", ((TextView) field(activity, "profileDetails")).getText().toString());
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
      assertNull(graph.selectedPoint());
      assertNull(field(map, "profilePoint"));
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
      assertTrue(details.getText().toString().contains("km"));
      assertTrue(details.getText().toString().contains("%"));
      assertFalse(details.getText().toString().contains("45.01000"));
      assertFalse(graph.getContentDescription().toString().contains("45.01000"));
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
      assertEquals(0, map.topOverlay);
      assertEquals(
          Math.round(12 * map.getResources().getDisplayMetrics().density),
          ((android.widget.FrameLayout.LayoutParams)
                  ((View) field(activity, "controlRail")).getLayoutParams())
              .topMargin);
    }
  }

  @Test
  public void switchingToARecordingResetsTheCursorAndEditingKeepsTheGraph() throws Exception {
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
      assertNull(field(map, "profilePoint"));
      assertEquals("", ((TextView) field(activity, "profileDetails")).getText().toString());
      graph.setProgress(7500);
      assertTrue(
          ((TextView) field(activity, "profileDetails"))
              .getText()
              .toString()
              .contains("— m · 75%"));
      controller.recreate();
      activity = controller.get();
      drain(activity);
      assertEquals(7500, ((ElevationGraph) field(activity, "elevationGraph")).getProgress());
      java.lang.reflect.Method edit = MainActivity.class.getDeclaredMethod("toggleEditing");
      edit.setAccessible(true);
      edit.invoke(activity);
      drain(activity);
      assertEquals(View.VISIBLE, ((View) field(activity, "selectionInfo")).getVisibility());
      graph = (ElevationGraph) field(activity, "elevationGraph");
      assertNull(graph.selectedPoint());
      graph.setProgress(graph.getMax());
      assertEquals(46.01, graph.selectedPoint().lat, 0);
      assertEquals(46.01, ((Point) field(field(activity, "map"), "profilePoint")).lat, 0);
    }
  }

  @Test
  public void recordingEditsAndUndoRefreshTheGraphAndTotals() throws Exception {
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      String id = store.newTrek();
      for (Point point : points)
        store.add(
            id,
            new Point(
                point.lat,
                point.lon,
                point.elevation,
                point.time,
                point.accuracy,
                point.segment,
                3));
      store.select(new Store.LibraryEntry(id, "Recording", true, true));
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      java.lang.reflect.Method edit = MainActivity.class.getDeclaredMethod("toggleEditing");
      edit.setAccessible(true);
      edit.invoke(activity);
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      graph.setProgress(graph.getMax() / 2);
      double[] camera = map.camera();
      map.onNodeDeleted.accept(1);
      assertNull(graph.selectedPoint());
      assertNull(field(map, "profilePoint"));
      assertEquals("", ((TextView) field(activity, "profileDetails")).getText().toString());
      assertArrayEquals(camera, map.camera(), 0);
      graph.setProgress(graph.getMax() / 2);
      assertEquals(122.5, graph.selectedPoint().elevation, .001);
      assertTrue(
          ((TextView) field(activity, "selectionRecap")).getText().toString().contains("↑ 45 m"));
      java.lang.reflect.Method undo = MainActivity.class.getDeclaredMethod("undoNode");
      undo.setAccessible(true);
      undo.invoke(activity);
      assertNull(graph.selectedPoint());
      assertNull(field(map, "profilePoint"));
      graph.setProgress(graph.getMax() / 2);
      assertEquals(195, graph.selectedPoint().elevation, .001);
      assertTrue(
          ((TextView) field(activity, "selectionRecap")).getText().toString().contains("↑ 95 m"));
      // The stored recording stays unchanged until the editor is saved.
      try (Store store = new Store(RuntimeEnvironment.getApplication())) {
        assertEquals(3, store.read(store.selectedLibraryId()).size());
      }
    }
  }

  @Test
  public void identicalReloadKeepsTheCursorButChangedGeometryClearsItWithoutPanning()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      layout(activity.getWindow().getDecorView(), 786, 1550);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      graph.setProgress(5000);
      invoke(activity, "reloadLibrary");
      drain(activity);
      assertEquals(5000, graph.getProgress());
      assertEquals(200, graph.selectedPoint().elevation, .001);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.center(new Point(40, 2));
      double[] camera = map.camera();
      Store store = (Store) field(activity, "store");
      store.updateRoute(
          store.selectedLibraryId(),
          List.of(points.get(0), new Point(45.015, 6, 400, 0, 0, 0), points.get(2)),
          Collections.emptyList());
      invoke(activity, "reloadLibrary");
      drain(activity);
      assertCleared(activity);
      assertEquals(0, graph.getProgress());
      assertArrayEquals(camera, map.camera(), 0);
      // Selecting the same zero endpoint still activates a fresh cursor.
      touch(graph, MotionEvent.ACTION_DOWN, graph.getPaddingLeft());
      assertNotNull(graph.selectedPoint());
    }
  }

  @Test
  public void movingAnEditNodeClearsTheCursorBeforeRoutingFinishes() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      invoke(activity, "toggleEditing");
      drain(activity);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      graph.setProgress(5000);
      TrekMapView map = (TrekMapView) field(activity, "map");
      double[] camera = map.camera();
      map.onNodeMoved.accept(0, map.waypoints.get(0));
      assertCleared(activity);
      assertArrayEquals(camera, map.camera(), 0);
      assertFalse(((List<?>) field(activity, "plannedRoute")).isEmpty());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void appendingALiveFixClearsTheCursor() throws Exception {
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      String id = store.newTrek();
      for (Point point : points) store.add(id, point);
      store.select(new Store.LibraryEntry(id, "Recording", true, true));
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      ((ElevationGraph) field(activity, "elevationGraph")).setProgress(5000);
      ((List<Point>) field(activity, "track")).add(new Point(45.03, 6, 300, 0, 0, 0));
      invoke(activity, "refreshStatus");
      assertCleared(activity);
    }
  }

  @Test
  public void completionUsesDistanceForCursorAndGpsEvenWithoutElevation() {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    // Uneven spacing: the middle stored point is only one quarter of the route distance.
    graph.bind(List.of(new Point(45, 6), new Point(45.01, 6), new Point(45.04, 6)));
    graph.setCursorActive(true);
    for (int percent : new int[] {0, 25, 50, 75, 100}) {
      graph.setProgress(percent * 100);
      assertTrue(graph.pointText(true).endsWith("— m · " + percent + "%"));
      graph.setGpsPosition(
          new Point(45 + .04 * percent / 100, 6, 100, System.currentTimeMillis(), 5, 0));
      assertTrue(graph.pointText(false).endsWith("— m · " + percent + "%"));
    }
  }

  @Test
  public void gpsProgressIsIndependentAndHidesDistantInaccurateOrStaleFixes() {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    graph.bind(points);
    long now = System.currentTimeMillis();
    graph.setGpsPosition(new Point(45.005, 6, 100, now, 5, 0));
    assertEquals(Geo.length(points) / 4, graph.gpsDistance(), .01);
    assertTrue(graph.pointText(false).contains("150 m"));
    assertTrue(graph.pointText(false).endsWith("25%"));
    assertNull(graph.selectedPoint());
    graph.setCursorActive(true);
    graph.setProgress(5000);
    assertTrue(graph.pointText(true).endsWith("50%"));
    Point inspected = graph.selectedPoint();
    graph.setGpsPosition(new Point(45.015, 6, 100, now, 5, 0));
    assertEquals(Geo.length(points) * .75, graph.gpsDistance(), .01);
    assertTrue(graph.pointText(false).contains("175 m"));
    assertTrue(graph.pointText(false).endsWith("75%"));
    assertEquals(inspected.lat, graph.selectedPoint().lat, 0);
    for (Point invalid :
        Arrays.asList(
            new Point(46, 6, 100, now, 5, 0),
            new Point(45.005, 6, 100, now, 100, 0),
            new Point(45.005, 6, 100, now - 121_000, 5, 0),
            null)) {
      graph.setGpsPosition(invalid);
      assertTrue(Double.isNaN(graph.gpsDistance()));
      assertEquals("", graph.pointText(false));
      assertNotNull(graph.selectedPoint());
    }
    graph.bind(List.of(new Point(45, 6, 0, 0, 0, 0), new Point(45, 7, 0, 0, 0, 1)));
    graph.setGpsPosition(new Point(45, 6.5, 100, now, 5, 0));
    assertTrue(Double.isNaN(graph.gpsDistance()));
  }

  @Test
  public void liveAppendAndNewSelectionRefreshTheProfile() {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    List<Point> live = new ArrayList<>(points);
    graph.bind(live);
    graph.setCursorActive(true);
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
  public void excludedSectionsAreShadedAndSmallChangesUseATwentyMeterScale() throws Exception {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    List<Point> recording = new ArrayList<>();
    for (int i = 0; i < 6; i++)
      recording.add(new Point(45 + i * .0001, 6, 100 + i % 2, 1000000 + i * 5000, 3, 0));
    graph.bind(recording, true);
    layout(graph, 786, 200);
    assertEquals(20, graph.axisMaximum() - graph.axisMinimum(), .000001);
    assertEquals("Above sea level · shaded: uncertain elevation excluded", graph.statusText());
    graph.setCursorActive(true);
    graph.setProgress(0);
    assertTrue(Double.isNaN(graph.selectedPoint().elevation));
    graph.setProgress(graph.getMax());
    assertTrue(Double.isFinite(graph.selectedPoint().elevation));
    graph.setCursorActive(false);
    Bitmap bitmap = Bitmap.createBitmap(786, 200, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(AppPalette.SELECTION);
    graph.draw(canvas);
    float plot = graph.getWidth() - graph.getPaddingLeft() - graph.getPaddingRight();
    int y = graph.getPaddingTop() + 8;
    assertNotEquals(
        "Excluded elevation must be visibly shaded",
        AppPalette.SELECTION,
        bitmap.getPixel(graph.getPaddingLeft() + Math.round(plot * .1f), y));
    assertEquals(
        AppPalette.SELECTION, bitmap.getPixel(graph.getPaddingLeft() + Math.round(plot * .8f), y));
    graph.bind(List.of(new Point(45, 6, 100, 0, 0, 0), new Point(45.01, 6, 250, 0, 0, 0)));
    assertEquals(150, graph.axisMaximum() - graph.axisMinimum(), 0);
    assertEquals("Elevation above sea level", graph.statusText());
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void shortGapDrawsDistanceWeightedDashesOverShadingAndKeepsCursorUnavailable()
      throws Exception {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    graph.bind(
        List.of(
            new Point(45, 6, 100, 0, 0, 0),
            new Point(45.0001, 6, Double.NaN, 0, 0, 0),
            new Point(45.0004, 6, 140, 0, 0, 0)));
    layout(graph, 786, 200);
    Bitmap bitmap = Bitmap.createBitmap(786, 200, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(AppPalette.SELECTION);
    graph.draw(canvas);
    float width = graph.getWidth() - graph.getPaddingLeft() - graph.getPaddingRight();
    float height = graph.getHeight() - graph.getPaddingTop() - graph.getPaddingBottom();
    int shaded = bitmap.getPixel(graph.getPaddingLeft() + 20, graph.getPaddingTop() + 10);
    assertNotEquals(AppPalette.SELECTION, shaded);
    int stroke = 0, gaps = 0;
    // A quarter of the route is at the missing sample: distance interpolation is 110 m,
    // not the index-weighted 120 m. Check the actual native Canvas pixels along that line.
    for (int offset = 20; offset < width - 20; offset++) {
      int x = graph.getPaddingLeft() + offset;
      int y = Math.round(graph.getPaddingTop() + height * (1 - offset / width));
      if (bitmap.getPixel(x, y) == shaded) gaps++;
      else stroke++;
    }
    assertTrue("Connector must be drawn along the distance-weighted slope", stroke > 100);
    assertTrue("Connector must have visible dash gaps", gaps > 50);
    graph.setCursorActive(true);
    graph.setProgress(2500);
    assertTrue(Double.isNaN(graph.selectedPoint().elevation));
    assertTrue(graph.pointText(true).contains("— m"));
    assertTrue(graph.statusText().contains("shaded: elevation unavailable"));
    assertTrue(graph.statusText().contains("dashed: display-only estimate"));

    // Rebinding a longer gap removes all inferred strokes from the cached path.
    graph.setCursorActive(false);
    graph.bind(
        List.of(
            new Point(45, 6, 100, 0, 0, 0),
            new Point(45.0001, 6, Double.NaN, 0, 0, 0),
            new Point(45.001, 6, 140, 0, 0, 0)));
    canvas.drawColor(AppPalette.SELECTION);
    graph.draw(canvas);
    for (int offset = 20; offset < width - 20; offset++) {
      int x = graph.getPaddingLeft() + offset;
      int y = Math.round(graph.getPaddingTop() + height * (1 - offset / width));
      assertEquals(shaded, bitmap.getPixel(x, y));
    }
    assertFalse(graph.statusText().contains("dashed"));
  }

  @Test
  public void liveRecordingConnectorAppearsOnlyAfterAnAcceptedEndpointArrives() {
    ElevationGraph graph = new ElevationGraph(RuntimeEnvironment.getApplication());
    List<Point> live = new ArrayList<>();
    live.add(new Point(45, 6, 100, 1000000, 3, 0, 3));
    live.add(new Point(45.0001, 6, 104, 1005000, 3, 0, 40));
    graph.bind(live, true);
    assertEquals("Above sea level · shaded: uncertain elevation excluded", graph.statusText());
    live.add(new Point(45.0003, 6, 110, 1010000, 3, 0, 3));
    graph.bind(live, true);
    assertTrue(graph.statusText().contains("shaded: uncertain elevation excluded"));
    assertTrue(graph.statusText().contains("dashed: display-only estimate"));
    graph.setCursorActive(true);
    graph.setProgress(5000);
    assertTrue(Double.isNaN(graph.selectedPoint().elevation));
    graph.setProgress(graph.getMax());
    assertEquals(110, graph.selectedPoint().elevation, 0);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void unresolvedRecordingRendersClearlyInPortrait() throws Exception {
    renderRecordingConfidence(786, 1550, "recording-elevation-confidence-portrait");
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void unresolvedRecordingRendersClearlyInLandscape() throws Exception {
    renderRecordingConfidence(1280, 640, "recording-elevation-confidence-landscape");
  }

  private void renderRecordingConfidence(int width, int height, String name) throws Exception {
    List<Point> recording = new ArrayList<>();
    for (int i = 0; i < 15; i++)
      recording.add(
          new Point(
              45 + i * .0001, 6, i < 3 ? 130 - i * 8 : 100 + i % 2, 1000000 + i * 5000, 3, 0));
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      String id = store.newTrek();
      for (Point point : recording) store.add(id, point);
      store.select(new Store.LibraryEntry(id, "Short level walk", true, true));
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View root = activity.getWindow().getDecorView();
      layout(root, width, height);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      TextView recap = (TextView) field(activity, "selectionRecap");
      assertTrue(recap.getText().toString().contains("↑ — m"));
      assertFalse(recap.getText().toString().contains("↑ 0 m"));
      assertEquals(1, recap.getLineCount());
      assertEquals(2, ((android.view.ViewGroup) field(activity, "selectionInfo")).getChildCount());
      assertTrue(graph.getContentDescription().toString().contains("excluded"));
      View panel = (View) field(activity, "selectionInfo");
      View rail = (View) field(activity, "controlRail");
      assertTrue(rail.getTop() >= panel.getBottom());
      assertTrue(
          rail.getHeight() >= Math.round(48 * graph.getResources().getDisplayMetrics().density));
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      File output = new File("build/test-previews/" + name + ".png");
      output.getParentFile().mkdirs();
      try (OutputStream stream = new FileOutputStream(output)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
      }
      android.location.LocationManager manager =
          activity.getSystemService(android.location.LocationManager.class);
      assertTrue(
          Shadows.shadowOf(manager)
              .getLocationRequests(android.location.LocationManager.GPS_PROVIDER)
              .isEmpty());
      assertTrue(
          Shadows.shadowOf(manager)
              .getLocationRequests(android.location.LocationManager.NETWORK_PROVIDER)
              .isEmpty());
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void profileAndMarkerRenderInPortrait() throws Exception {
    renderPreview(786, 1550, "route-elevation-portrait", false, false);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void largeTextReadoutStaysOnOneLineAndCanScroll() throws Exception {
    RuntimeEnvironment.setFontScale(1.5f);
    try {
      renderPreview(786, 1550, "route-elevation-large-text", false, true);
    } finally {
      RuntimeEnvironment.setFontScale(1);
    }
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void profileAndMarkerRenderInLandscape() throws Exception {
    renderPreview(1280, 640, "route-elevation-landscape", false, false);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void editorProfileAndMarkerRenderInPortrait() throws Exception {
    renderPreview(786, 1550, "route-elevation-editor-portrait", true, false);
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void editorProfileAndMarkerRenderInLandscape() throws Exception {
    renderPreview(1280, 640, "route-elevation-editor-landscape", true, false);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void gpsProgressAndInspectionRenderIndependently() throws Exception {
    renderPreview(786, 1550, "route-elevation-gps-progress", false, true);
  }

  private void renderPreview(int width, int height, String name, boolean editing, boolean gps)
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      if (editing) {
        java.lang.reflect.Method edit = MainActivity.class.getDeclaredMethod("toggleEditing");
        edit.setAccessible(true);
        edit.invoke(activity);
        drain(activity);
      }
      View root = activity.getWindow().getDecorView();
      layout(root, width, height);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.fitRoute(points);
      ElevationGraph graph = (ElevationGraph) field(activity, "elevationGraph");
      if (gps) {
        map.position = new Point(45.005, 6, 150, System.currentTimeMillis(), 5, 0);
        invoke(activity, "refreshStatus");
        assertNull(graph.selectedPoint());
        assertTrue(
            ((TextView) field(activity, "profileDetails")).getText().toString().startsWith("GPS "));
      }
      touch(
          graph,
          MotionEvent.ACTION_DOWN,
          (graph.getPaddingLeft() + graph.getWidth() - graph.getPaddingRight()) / 2f);
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      if (gps) {
        int[] origin = new int[2];
        graph.getLocationInWindow(origin);
        int gpsX =
            origin[0]
                + graph.getPaddingLeft()
                + Math.round(
                    (graph.getWidth() - graph.getPaddingLeft() - graph.getPaddingRight()) / 4f);
        assertEquals(
            AppPalette.ACCENT, bitmap.getPixel(gpsX, origin[1] + graph.getPaddingTop() + 10));
      }
      assertTrue(graph.getHeight() > 0);
      View panel = (View) field(activity, "selectionInfo");
      View parent = (View) panel.getParent();
      int margin = Math.round(12 * graph.getResources().getDisplayMetrics().density);
      assertEquals(margin, panel.getTop());
      assertEquals(margin, panel.getLeft());
      assertEquals(parent.getWidth() - margin, panel.getRight());
      assertEquals(panel.getWidth(), graph.getWidth());
      TextView details = (TextView) field(activity, "profileDetails");
      TextView recap = (TextView) field(activity, "selectionRecap");
      assertEquals(1, recap.getLineCount());
      assertEquals(1, details.getLineCount());
      assertSame(recap.getParent(), details.getParent());
      assertTrue(details.getLeft() >= recap.getRight());
      assertEquals(recap.getTop(), details.getTop());
      assertTrue(((View) details.getParent()).getBottom() <= graph.getTop());
      View rail = (View) field(activity, "controlRail");
      assertTrue(rail.getTop() >= panel.getBottom());
      assertTrue(rail.getHeight() > 0);
      assertEquals(panel.getBottom(), map.topOverlay);
      assertEquals(0, map.leftOverlay);
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
