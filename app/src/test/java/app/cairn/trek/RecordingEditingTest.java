package app.cairn.trek;

import static org.junit.Assert.*;

import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class RecordingEditingTest {
  private ActivityController<MainActivity> controller;
  private MainActivity activity;
  private String trekId, title, routeId;
  private final List<Point> recording =
      List.of(
          new Point(46, 7, 1200, 1000, 3, 0, 8, Point.WGS84_ELLIPSOID, 1155),
          new Point(46.001, 7.001, 1210, 2000, 4, 0),
          new Point(46.002, 7.002, 1220, 3000, 5, 1),
          new Point(46.003, 7.003, 1230, 4000, 6, 1));

  @Before
  public void setup() throws Exception {
    TrackingService.running = RoutingDownloadService.running = DownloadService.running = false;
    android.app.Application context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("treks.db");
    context.getSharedPreferences("settings", 0).edit().clear().commit();
    try (Store store = new Store(context)) {
      routeId = store.saveRoute(List.of(new Point(45, 6), new Point(45.1, 6.1)), "Guidance");
      trekId = store.newTrek();
      title = store.treks().get(trekId);
      for (Point point : recording) store.add(trekId, point);
      store.select(new Store.LibraryEntry(trekId, title, true, true));
    }
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    activity = controller.get();
    drain();
  }

  @After
  public void stop() {
    TrackingService.running = RoutingDownloadService.running = DownloadService.running = false;
    if (controller != null) controller.pause().stop().destroy();
  }

  private Object field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private Store store() throws Exception {
    return (Store) field("store");
  }

  private TrekMapView map() throws Exception {
    return (TrekMapView) field("map");
  }

  private void drain() throws Exception {
    for (int i = 0; i < 4; i++) {
      ((ExecutorService) field("io")).submit(() -> {}).get(10, TimeUnit.SECONDS);
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

  private void tap(String text) throws Exception {
    View view = find(activity.getWindow().getDecorView(), text);
    assertNotNull("Missing action: " + text, view);
    view.performClick();
    drain();
  }

  private void assertPoint(Point expected, Point actual) {
    assertEquals(expected.lat, actual.lat, 0);
    assertEquals(expected.lon, actual.lon, 0);
    assertEquals(expected.elevation, actual.elevation, 0);
    assertEquals(expected.elevationDatum, actual.elevationDatum);
    if (Double.isNaN(expected.mslElevation)) assertTrue(Double.isNaN(actual.mslElevation));
    else assertEquals(expected.mslElevation, actual.mslElevation, 0);
    if (Float.isNaN(expected.verticalAccuracy)) assertTrue(Float.isNaN(actual.verticalAccuracy));
    else assertEquals(expected.verticalAccuracy, actual.verticalAccuracy, 0);
    assertEquals(expected.time, actual.time);
    assertEquals(expected.accuracy, actual.accuracy, 0);
    assertEquals(expected.segment, actual.segment);
  }

  @Test
  public void recordingDraftKeepsQualityAndCanStillReadThePreviousFormat() throws Exception {
    android.app.Application context = RuntimeEnvironment.getApplication();
    String id = "quality-regression";
    try {
      RecordingDraft.write(context, id, recording);
      List<Point> restored = RecordingDraft.read(context, id);
      for (int i = 0; i < recording.size(); i++) assertPoint(recording.get(i), restored.get(i));
      Point legacy = recording.get(1);
      try (DataOutputStream output =
          new DataOutputStream(
              new FileOutputStream(new File(context.getFilesDir(), "recording-edit-" + id)))) {
        output.writeInt(1);
        output.writeDouble(legacy.lat);
        output.writeDouble(legacy.lon);
        output.writeDouble(legacy.elevation);
        output.writeLong(legacy.time);
        output.writeFloat(legacy.accuracy);
        output.writeInt(legacy.segment);
      }
      Point restoredLegacy = RecordingDraft.read(context, id).get(0);
      assertEquals(legacy.elevation, restoredLegacy.elevation, 0);
      assertEquals(legacy.time, restoredLegacy.time);
      assertEquals(Point.WGS84_ELLIPSOID, restoredLegacy.elevationDatum);
      Point oldQuality = recording.get(0);
      try (DataOutputStream output =
          new DataOutputStream(
              new FileOutputStream(new File(context.getFilesDir(), "recording-edit-" + id)))) {
        output.writeInt(-1);
        output.writeInt(1);
        output.writeDouble(oldQuality.lat);
        output.writeDouble(oldQuality.lon);
        output.writeDouble(oldQuality.elevation);
        output.writeLong(oldQuality.time);
        output.writeFloat(oldQuality.accuracy);
        output.writeInt(oldQuality.segment);
        output.writeFloat(oldQuality.verticalAccuracy);
      }
      restoredLegacy = RecordingDraft.read(context, id).get(0);
      assertEquals(oldQuality.elevation, restoredLegacy.elevation, 0);
      assertEquals(oldQuality.verticalAccuracy, restoredLegacy.verticalAccuracy, 0);
      assertEquals(Point.WGS84_ELLIPSOID, restoredLegacy.elevationDatum);
      assertTrue(Double.isNaN(restoredLegacy.mslElevation));
    } finally {
      RecordingDraft.delete(context, id);
    }
  }

  @Test
  public void pointEditsSaveTheSameRecordingAndRefreshTheCurrentTrackAndExport() throws Exception {
    tap("Edit recording");
    assertEquals(recording.size(), map().waypoints.size());
    assertNull(find(activity.getWindow().getDecorView(), "Routing preferences"));
    TrekMapView map = map();
    map.layout(0, 0, 1000, 1200);
    map.restoreCamera(new double[] {46, 7, 16});
    long down = SystemClock.uptimeMillis();
    long time = down;
    for (int action :
        new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
      MotionEvent event =
          MotionEvent.obtain(
              down, time, action, action == MotionEvent.ACTION_DOWN ? 500 : 550, 600, 0);
      map.onTouchEvent(event);
      event.recycle();
      time += 20;
    }
    Point moved = map.waypoints.get(0);
    assertNotEquals(recording.get(0).lon, moved.lon, 0);
    assertPoint(
        new Point(moved.lat, moved.lon, 1200, 1000, 3, 0, 8, Point.WGS84_ELLIPSOID, 1155), moved);
    map.onNodeDeleted.accept(1);
    // Insert within the second segment; new points carry no invented GPS measurements.
    map.onNodeAdded.accept(2, new Point(46.0025, 7.0025));
    List<Point> edited = new ArrayList<>(map.waypoints);
    assertEquals(1, edited.get(2).segment);
    assertEquals(0, edited.get(2).time);
    assertTrue(Double.isNaN(edited.get(2).elevation));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertNull(field("routingPrompt"));
    assertFalse((boolean) field("calculationRunning"));
    tap("Finish editing");
    assertFalse((boolean) field("planning"));
    assertEquals(title, store().treks().get(trekId));
    assertEquals(trekId, store().selectedLibraryId());
    assertEquals(1, store().treks().size());
    assertEquals(1, store().routes().size());
    assertEquals(routeId, store().prefs.getString("activeRouteId", ""));
    assertEquals(45, store().read("route").get(0).lat, 0);
    assertTrue(((Store.LibraryEntry) field("selectedEntry")).recording);
    for (int i = 0; i < edited.size(); i++) {
      assertPoint(edited.get(i), store().read(trekId).get(i));
      assertPoint(edited.get(i), map.track.get(i));
    }
    // A new point appended after the last GPS fix must not duplicate old points on resume.
    tap("Edit recording");
    map.onNodeAdded.accept(map.waypoints.size(), new Point(46.004, 7.004));
    tap("Finish editing");
    controller.pause().resume();
    drain();
    assertEquals(edited.size() + 1, map.track.size());
    tap("Export GPX");
    @SuppressWarnings("unchecked")
    List<Point> exported = (List<Point>) field("exportPoints");
    assertEquals(map.track.size(), exported.size());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Gpx.write(output, exported, title);
    List<Point> gpx = Gpx.read(new ByteArrayInputStream(output.toByteArray()));
    assertEquals(exported.size(), gpx.size());
    assertEquals(moved.time, gpx.get(0).time);
    assertEquals(1, gpx.get(1).segment);
  }

  @Test
  public void undoRestoresAWholeDragAndDeletedGpsDetailsAcrossRotation() throws Exception {
    tap("Edit recording");
    TrekMapView map = map();
    map.layout(0, 0, 1000, 1200);
    map.restoreCamera(new double[] {46, 7, 16});
    long down = SystemClock.uptimeMillis();
    int step = 0;
    for (int action :
        new int[] {
          MotionEvent.ACTION_DOWN,
          MotionEvent.ACTION_MOVE,
          MotionEvent.ACTION_MOVE,
          MotionEvent.ACTION_UP
        }) {
      MotionEvent event =
          MotionEvent.obtain(down, down + step * 20, action, 500 + step * 30, 600, 0);
      map.onTouchEvent(event);
      event.recycle();
      step++;
    }
    assertNotEquals(recording.get(0).lon, map.waypoints.get(0).lon, 0);
    map.onNodeDeleted.accept(2);
    controller.recreate();
    activity = controller.get();
    drain();
    tap("Undo last edit");
    assertPoint(recording.get(2), map().waypoints.get(2));
    tap("Undo last edit");
    for (int i = 0; i < recording.size(); i++)
      assertPoint(recording.get(i), map().waypoints.get(i));
    assertFalse(find(activity.getWindow().getDecorView(), "Undo last edit").isEnabled());
    assertFalse((boolean) field("editDirty"));
    tap("Finish editing");
    for (int i = 0; i < recording.size(); i++)
      assertPoint(recording.get(i), store().read(trekId).get(i));
  }

  @Test
  public void recreationKeepsUnsavedEditsAndAllGpsDetails() throws Exception {
    tap("Edit recording");
    String draftId = (String) field("newRouteId");
    map().onNodeDeleted.accept(1);
    controller.recreate();
    activity = controller.get();
    drain();
    assertTrue((boolean) field("planning"));
    assertEquals(3, map().waypoints.size());
    assertPoint(recording.get(0), map().waypoints.get(0));
    assertPoint(recording.get(2), map().waypoints.get(1));
    assertPoint(recording.get(3), map().waypoints.get(2));
    tap("Finish editing");
    assertEquals(3, store().read(trekId).size());
    assertPoint(recording.get(2), store().read(trekId).get(1));
    assertThrows(
        IOException.class, () -> RecordingDraft.read(RuntimeEnvironment.getApplication(), draftId));
  }

  @Test
  public void unavailableDraftFallsBackToTheSavedRecordingWithoutOverwritingIt() throws Exception {
    tap("Edit recording");
    map().onNodeDeleted.accept(1);
    Bundle state = new Bundle();
    controller.saveInstanceState(state);
    RecordingDraft.delete(activity, (String) field("newRouteId"));
    controller.pause().stop().destroy();
    controller =
        Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
    activity = controller.get();
    drain();
    assertFalse((boolean) field("editDirty"));
    assertEquals(recording.size(), map().waypoints.size());
    for (int i = 0; i < recording.size(); i++)
      assertPoint(recording.get(i), map().waypoints.get(i));
    tap("Finish editing");
    assertEquals(recording.size(), store().read(trekId).size());
  }

  @Test
  public void longRecordingDraftSurvivesRecreationWithoutFillingTheSavedStateBundle()
      throws Exception {
    tap("Edit recording");
    for (int i = recording.size(); i < 30000; i++)
      map().waypoints.add(new Point(46 + i * .000001, 7, 1300, i * 1000L, 3, 1));
    map().onNodeMoved.accept(0, map().waypoints.get(0));
    Bundle state = new Bundle();
    controller.saveInstanceState(state);
    Parcel parcel = Parcel.obtain();
    try {
      state.writeToParcel(parcel, 0);
      assertTrue("Full track must stay outside Binder state", parcel.dataSize() < 16000);
    } finally {
      parcel.recycle();
    }
    controller.recreate();
    activity = controller.get();
    drain();
    assertEquals(30000, map().waypoints.size());
    assertPoint(new Point(46.029999, 7, 1300, 29999000, 3, 1), map().waypoints.get(29999));
  }

  @Test
  public void finishingWithoutChangesPreservesEverySample() throws Exception {
    tap("Edit recording");
    tap("Finish editing");
    for (int i = 0; i < recording.size(); i++)
      assertPoint(recording.get(i), store().read(trekId).get(i));
    assertEquals(1, store().routes().size());
  }

  @Test
  public void activeRecordingIsProtectedButFinishedRecordingsCanBeEditedDuringAnotherHike()
      throws Exception {
    TrackingService.running = true;
    tap("Edit recording");
    assertFalse((boolean) field("planning"));
    assertThrows(IllegalStateException.class, () -> store().updateTrek(trekId, recording));
    store().prefs.edit().putString("currentTrek", "other-live-hike").apply();
    tap("Edit recording");
    map().onNodeDeleted.accept(0);
    tap("Finish editing");
    assertEquals(3, store().read(trekId).size());
    assertEquals("other-live-hike", store().prefs.getString("currentTrek", ""));
  }

  @Test
  public void singlePointCanBeSavedButEmptyRecordingCannotReplaceStoredSamples() throws Exception {
    tap("Edit recording");
    while (map().waypoints.size() > 1) map().onNodeDeleted.accept(1);
    tap("Finish editing");
    assertEquals(1, store().read(trekId).size());
    tap("Edit recording");
    map().onNodeDeleted.accept(0);
    tap("Finish editing");
    assertTrue((boolean) field("planning"));
    assertEquals(1, store().read(trekId).size());
    assertThrows(IllegalArgumentException.class, () -> store().updateTrek(trekId, List.of()));
  }

  @Test
  public void deletedRecordingCannotBeRecreatedByAnEditorSave() throws Exception {
    tap("Edit recording");
    map().onNodeDeleted.accept(1);
    store().deleteTrek(trekId);
    tap("Finish editing");
    assertTrue((boolean) field("planning"));
    assertFalse((boolean) field("savingEdit"));
    assertTrue(store().read(trekId).isEmpty());
    assertTrue(store().treks().isEmpty());
    assertEquals(1, store().routes().size());
  }
}
