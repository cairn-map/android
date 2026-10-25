package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class UnifiedLibraryTest {
  private String routeId, trekId, trekTitle;
  private final List<Point> recording = List.of(new Point(46, 7), new Point(46.2, 7.3));

  @Before
  public void setup() {
    TrackingService.running = false;
    DownloadService.running = false;
    RuntimeEnvironment.getApplication().deleteDatabase("plan-ign.mbtiles");
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      routeId = store.saveRoute(List.of(new Point(45, 6), new Point(45.2, 6.3)), "Saved route");
      trekId = store.newTrek();
      trekTitle = store.treks().get(trekId);
      for (Point point : recording) store.add(trekId, point);
    }
  }

  private Object field(MainActivity activity, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void drain(MainActivity activity) throws Exception {
    for (int i = 0; i < 4; i++) {
      ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private View find(View view, String text) {
    if (text.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private View library(MainActivity activity) throws Exception {
    ((View) field(activity, "libraryButton")).performClick();
    return ((AlertDialog) field(activity, "libraryDialog")).getWindow().getDecorView();
  }

  private void assertFits(TrekMapView map, List<Point> points) {
    double[] camera = map.camera();
    double density = map.getResources().getDisplayMetrics().density;
    double world = 256 * density * Math.pow(2, camera[2]);
    for (Point point : points) {
      double x = map.getWidth() / 2.0 + Geo.wrap(point.lon - camera[1]) / 360 * world;
      double y = map.getHeight() / 2.0 + (Geo.y(point.lat, 0) - Geo.y(camera[0], 0)) * world;
      assertTrue(x >= 32 * density - .01);
      assertTrue(x <= map.getWidth() - 96 * density + .01);
      assertTrue(y >= Math.max(32 * density, map.topOverlay + 16 * density) - .01);
      assertTrue(y <= map.getHeight() - map.bottomOverlay - 32 * density + .01);
    }
  }

  @Test
  public void recordingTapFitsHighlightsAndOffersActionsInsteadOfExporting() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.layout(0, 0, 786, 1400);
      map.restoreCamera(new double[] {48, 2, 18});
      View list = library(activity);
      for (String action : List.of("Save offline corridor", "Export GPX", "Hide"))
        assertNull(find(list, action));
      find(list, trekTitle).performClick();
      drain(activity);
      map.layout(0, 0, 786, 1400);
      Store store = (Store) field(activity, "store");
      assertEquals(trekId, store.selectedLibraryId());
      assertEquals(trekId, map.selectedPathId);
      assertEquals("Map", field(activity, "currentPage"));
      assertNull(field(activity, "exportPoints"));
      assertFits(map, recording);
      View root = activity.getWindow().getDecorView();
      assertNull(find(root, trekTitle));
      LinearLayout actions = (LinearLayout) field(activity, "selectionPanel");
      assertNull(actions.getBackground());
      assertEquals(5, actions.getChildCount());
      assertEquals(LinearLayout.HORIZONTAL, actions.getOrientation());
      assertEquals(0, ((ViewGroup) actions.getParent()).indexOfChild(actions));
      for (String action : List.of("Save offline corridor", "Export GPX", "Hide")) {
        View icon = find(root, action);
        assertTrue(icon instanceof ImageButton);
        assertEquals(action, icon.getTooltipText());
      }
      list = library(activity);
      assertNotNull(find(list, trekTitle + " · selected"));
      for (String action : List.of("Save offline corridor", "Export GPX", "Hide"))
        assertNull(find(list, action));
      ((AlertDialog) field(activity, "libraryDialog")).dismiss();
      find(root, "Export GPX").performClick();
      List<?> exported = (List<?>) field(activity, "exportPoints");
      assertEquals(recording.size(), exported.size());
      assertEquals(46, ((Point) exported.get(0)).lat, 0);
      Intent intent = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
      assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
      assertEquals("application/gpx+xml", intent.getType());
    }
  }

  @Test
  public void currentRecordingUsesTheLiveTrackWithoutASecondLibraryCopy() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      TrekMapView.LibraryPath current =
          map.libraryPaths.stream().filter(path -> path.entry.id.equals(trekId)).findFirst().get();
      assertTrue(current.points.isEmpty());
      assertEquals(recording.size(), map.track.size());
      find(library(activity), trekTitle).performClick();
      drain(activity);
      controller.pause().resume();
      drain(activity);
      find(activity.getWindow().getDecorView(), "Export GPX").performClick();
      assertEquals(recording.size(), ((List<?>) field(activity, "exportPoints")).size());
    }
  }

  @Test
  public void hidingAndReselectingEitherKindPersistsVisibilityAndSelectionAcrossResume()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      for (String title : List.of("Saved route", trekTitle)) {
        View list = library(activity);
        View item = find(list, title);
        if (item == null) item = find(list, title + " · selected");
        item.performClick();
        drain(activity);
        find(activity.getWindow().getDecorView(), "Hide").performClick();
        drain(activity);
        assertEquals("", ((Store) field(activity, "store")).selectedLibraryId());
        assertNull(find(activity.getWindow().getDecorView(), "Hide"));
        controller.pause().resume();
        drain(activity);
        View hidden = find(library(activity), title + " · hidden");
        assertNotNull(hidden);
        hidden.performClick();
        drain(activity);
        String expected = title.equals("Saved route") ? routeId : trekId;
        TrekMapView map = (TrekMapView) field(activity, "map");
        assertEquals(expected, map.selectedPathId);
        assertTrue(
            ((Store) field(activity, "store"))
                .library().stream()
                    .filter(entry -> entry.id.equals(expected))
                    .findFirst()
                    .get()
                    .visible);
        controller.pause().resume();
        drain(activity);
        assertEquals(expected, map.selectedPathId);
      }
    }
  }

  @Test
  public void selectedRecordingCorridorUsesRecordingGeometryRatherThanGuidanceRoute()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      find(library(activity), trekTitle).performClick();
      drain(activity);
      find(activity.getWindow().getDecorView(), "Save offline corridor").performClick();
      drain(activity);
      AlertDialog form = ShadowAlertDialog.getLatestAlertDialog();
      ((SeekBar) form.getWindow().getDecorView().findViewWithTag("Kilometres on each side"))
          .setProgress(1);
      drain(activity);
      form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      drain(activity);
      PlanIgnStore maps = (PlanIgnStore) field(activity, "mapStore");
      PlanIgnStore.Pack pack = maps.packs().get(0);
      TilePlanner.Plan expected = TilePlanner.corridor(recording, 200, 17);
      assertEquals(expected.tiles.size(), pack.total);
      assertEquals(recording.get(0).lat, pack.center.lat, 0);
      assertEquals(
          new HashSet<>(expected.tiles), new HashSet<>(maps.next(pack.id, TilePlanner.MAX_TILES)));
    }
  }

  @Test
  public void selectionRecapUsesSelectedGeometryAndHidesWithSelection() throws Exception {
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      store.updateRoute(
          routeId,
          List.of(
              new Point(45, 6, 100, 0, 0, 0),
              new Point(45.01, 6, 150, 0, 0, 0),
              new Point(45.02, 6, 130, 0, 0, 0)),
          List.of());
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TextView recap = (TextView) field(activity, "selectionRecap");
      assertEquals(View.VISIBLE, recap.getVisibility());
      assertTrue(recap.getText().toString().contains("↑ 50 m"));
      assertTrue(recap.getText().toString().contains("↓ 20 m"));
      assertTrue(recap.getContentDescription().toString().contains("Saved route"));
      controller.pause().resume();
      drain(activity);
      assertTrue(
          ((TextView) field(activity, "selectionRecap")).getText().toString().contains("↑ 50 m"));
      find(library(activity), trekTitle).performClick();
      drain(activity);
      recap = (TextView) field(activity, "selectionRecap");
      assertTrue(recap.getText().toString().contains("Elevation unavailable"));
      find(activity.getWindow().getDecorView(), "Hide").performClick();
      drain(activity);
      assertEquals(View.GONE, ((View) field(activity, "selectionRecap")).getVisibility());
    }
  }

  @Test
  public void rightSwipeDeletesSelectedRouteAndLeavesRecordingIntact() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      PlanIgnStore maps = (PlanIgnStore) field(activity, "mapStore");
      String corridor =
          maps.create(
              "Saved route · corridor",
              TilePlanner.area(
                  6, 45, 6.001, 45.001, 10)); // Legacy linkage is resolved before deletion.
      TilePlanner.Tile tile = maps.next(corridor);
      maps.completeTile(corridor, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
      View item = find(library(activity), "Saved route · selected");
      SwipeToRemoveView swipe = (SwipeToRemoveView) item.getParent().getParent();
      swipe.measure(
          View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
      swipe.layout(0, 0, 400, 96);
      for (int action :
          new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
        MotionEvent event =
            MotionEvent.obtain(
                1000, 1000, action, action == MotionEvent.ACTION_DOWN ? 40 : 260, 32, 0);
        swipe.dispatchTouchEvent(event);
        event.recycle();
      }
      assertTrue(((View) item.getParent()).getTranslationX() > 0);
      drain(activity);
      Store store = (Store) field(activity, "store");
      assertTrue(store.routes().isEmpty());
      assertTrue(maps.packs().isEmpty());
      assertNull(maps.cached(tile));
      assertTrue(store.read(routeId).isEmpty());
      assertEquals("", store.selectedLibraryId());
      assertEquals(recording.size(), store.read(trekId).size());
      TrekMapView map = (TrekMapView) field(activity, "map");
      assertEquals("", map.selectedPathId);
      assertFalse(map.libraryPaths.stream().anyMatch(path -> path.entry.id.equals(routeId)));
    }
  }

  @Test
  public void confirmedMapTapSelectsAnotherRecording() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.layout(0, 0, 786, 1400);
      map.restoreCamera(new double[] {recording.get(0).lat, recording.get(0).lon, 12});
      long time = android.os.SystemClock.uptimeMillis();
      for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
        MotionEvent event = MotionEvent.obtain(time, time + action * 20, action, 393, 700, 0);
        map.onTouchEvent(event);
        event.recycle();
      }
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
      drain(activity);
      assertEquals(trekId, map.selectedPathId);
      assertEquals(trekId, ((Store) field(activity, "store")).selectedLibraryId());
    }
  }

  @Test
  public void blankMapTapDeselectsAndKeepsVisibleRouteAndGuidance() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      Store store = (Store) field(activity, "store");
      assertEquals(routeId, map.selectedPathId);
      List<Point> guidance = map.route;
      map.layout(0, 0, 786, 1400);
      map.restoreCamera(new double[] {40, 0, 12});
      long time = android.os.SystemClock.uptimeMillis();
      for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
        MotionEvent event = MotionEvent.obtain(time, time + action * 20, action, 393, 700, 0);
        map.onTouchEvent(event);
        event.recycle();
      }
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
      drain(activity);
      assertEquals("", map.selectedPathId);
      assertEquals("", store.selectedLibraryId());
      assertNull(field(activity, "selectedEntry"));
      assertTrue(
          store.library().stream().anyMatch(entry -> entry.id.equals(routeId) && entry.visible));
      assertSame(guidance, map.route);
      assertNull(find(activity.getWindow().getDecorView(), "Save offline corridor"));
      assertNotNull(find(activity.getWindow().getDecorView(), "Create route"));
      View library = library(activity);
      assertNull(find(library, "Create route"));
      assertNotNull(find(library, "Add files"));
    }
  }
}
