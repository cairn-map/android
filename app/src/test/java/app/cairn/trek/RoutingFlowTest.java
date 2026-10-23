package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
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
public class RoutingFlowTest {
  private ActivityController<MainActivity> controller;
  private MainActivity activity;
  private android.app.Application context;

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    TrackingService.running = RoutingDownloadService.running = DownloadService.running = false;
    RoutingDownloadService.lastOutcome = null;
    context.deleteDatabase("treks.db");
    context.getSharedPreferences("settings", 0).edit().clear().commit();
    for (File file : RoutingData.directory(context).listFiles()) assertTrue(file.delete());
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    activity = controller.get();
    drain();
    set("planning", true);
    points().add(new Point(50.002515, 8.720897));
    points().add(new Point(49.997510, 8.723658));
    invoke("showPage", new Class<?>[] {String.class}, "Map");
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

  private void set(String name, Object value) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(activity, value);
  }

  @SuppressWarnings("unchecked")
  private List<Point> points() throws Exception {
    return (List<Point>) field("waypoints");
  }

  private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    method.invoke(activity, args);
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

  private void tap(String text) {
    View view = find(activity.getWindow().getDecorView(), text);
    assertNotNull("Missing planner action: " + text, view);
    view.performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private void changeNodes() {
    try {
      TrekMapView map = (TrekMapView) field("map");
      map.onNodeMoved.accept(0, points().get(0));
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private AlertDialog prompt() {
    changeNodes();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(dialog.isShowing());
    assertNotNull(find(dialog.getWindow().getDecorView(), "Download routing data?"));
    return dialog;
  }

  private Intent confirm() {
    prompt().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    Intent start = Shadows.shadowOf(context).getNextStartedService();
    assertNotNull(start);
    assertEquals(RoutingDownloadService.class.getName(), start.getComponent().getClassName());
    assertEquals(
        Arrays.asList("E5_N45.rd5", "E5_N50.rd5"), start.getStringArrayListExtra("regions"));
    assertNotNull(start.getStringExtra("request"));
    return start;
  }

  private void event(String request, boolean finished, boolean success, String message) {
    context.sendBroadcast(
        new Intent(RoutingDownloadService.EVENT)
            .setPackage(context.getPackageName())
            .putExtra("request", request)
            .putExtra("finished", finished)
            .putExtra("success", success)
            .putExtra("message", message));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private void installGraphs() throws Exception {
    for (String name : new String[] {"E5_N45.rd5", "E5_N50.rd5"})
      try (InputStream input = getClass().getResourceAsStream("/routing/" + name)) {
        Files.copy(
            input,
            new File(RoutingData.directory(context), name).toPath(),
            StandardCopyOption.REPLACE_EXISTING);
      }
  }

  private void drain() throws Exception {
    for (int i = 0; i < 4; i++) {
      ((ExecutorService) field("io")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      EmbeddedRouter router = (EmbeddedRouter) field("router");
      Field worker = EmbeddedRouter.class.getDeclaredField("worker");
      worker.setAccessible(true);
      ((ExecutorService) worker.get(router)).submit(() -> {}).get(15, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private void assertSavedRoute() throws Exception {
    drain();
    assertTrue("Automatic routing leaves the editor open", (boolean) field("planning"));
    assertTrue(((List<?>) field("plannedRoute")).size() > 2);
    tap("Finish editing");
    drain();
    assertFalse((boolean) field("planning"));
    try (Store store = new Store(context)) {
      assertEquals(1, store.routes().size());
      List<Point> route = store.read("route");
      assertTrue(route.size() > 2);
      assertTrue(Geo.distance(route.get(0), new Point(50.002515, 8.720897)) < 100);
      assertTrue(Geo.distance(route.get(route.size() - 1), new Point(49.997510, 8.723658)) < 100);
    }
  }

  @Test
  public void editsDuringCalculationDiscardTheOldResultAndFinishSavesTheLatestNodes()
      throws Exception {
    installGraphs();
    changeNodes();
    assertTrue((boolean) field("calculationRunning"));
    Point latest = new Point(49.9976, 8.7236);
    points().set(1, latest);
    ((TrekMapView) field("map")).onNodeMoved.accept(1, latest);
    tap("Finish editing");
    drain();
    assertFalse((boolean) field("planning"));
    Store store = (Store) field("store");
    assertEquals(1, store.routes().size());
    List<Point> nodes = store.routeNodes(store.routes().get(0).id);
    assertEquals(latest.lat, nodes.get(1).lat, 0);
    assertEquals(latest.lon, nodes.get(1).lon, 0);
    assertTrue(Geo.distance(store.read("route").get(store.read("route").size() - 1), latest) < 100);
  }

  @Test
  public void savedRouteReopensWithItsOriginalNodesAndEditsUpdateTheSameLibraryItem()
      throws Exception {
    installGraphs();
    changeNodes();
    assertSavedRoute();
    Store store = (Store) field("store");
    String id = store.routes().get(0).id;
    tap("Edit route");
    drain();
    assertTrue((boolean) field("planning"));
    assertEquals(2, points().size());
    Point latest = new Point(49.9976, 8.7236);
    points().set(1, latest);
    changeNodes();
    drain();
    tap("Finish editing");
    drain();
    assertEquals(1, store.routes().size());
    assertEquals(id, store.routes().get(0).id);
    assertEquals(latest.lat, store.routeNodes(id).get(1).lat, 0);
    assertEquals(id, store.selectedLibraryId());
  }

  @Test
  public void downloadRequestedByFinishingEditSavesAutomaticallyWhenReady() throws Exception {
    set("editDirty", true);
    tap("Finish editing");
    Intent start = confirm();
    installGraphs();
    event(start.getStringExtra("request"), true, true, "Ready");
    drain();
    assertFalse((boolean) field("planning"));
    assertEquals(1, ((Store) field("store")).routes().size());
  }

  @Test
  public void downloadFailureWhileFinishingUnlocksTheNodesForEditing() throws Exception {
    set("editDirty", true);
    tap("Finish editing");
    Intent start = confirm();
    event(start.getStringExtra("request"), true, false, "Routing download paused");
    assertTrue(((TrekMapView) field("map")).editing);
    assertFalse((boolean) field("finishEditing"));
  }

  @Test
  public void missingDataNeedsOneConfirmationAndNotNowKeepsPlanningPoints() throws Exception {
    AlertDialog dialog = prompt();
    assertEquals("Download & route", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText());
    assertNull(Shadows.shadowOf(context).getNextStartedService());
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(2, points().size());
    assertNull(field("pendingRoute"));
    assertNull(Shadows.shadowOf(context).getNextStartedService());
  }

  @Test
  @Config(sdk = {26, 35})
  public void installedDataCalculatesImmediatelyEvenWhileRecording() throws Exception {
    installGraphs();
    TrackingService.running = true;
    changeNodes();
    assertNull(field("routingPrompt"));
    assertNull(Shadows.shadowOf(context).getNextStartedService());
    assertSavedRoute();
  }

  @Test
  @Config(sdk = {28, 35})
  public void confirmedDownloadAutomaticallyCalculatesOriginalPointsAndShowsProgress()
      throws Exception {
    Intent start = confirm();
    event(start.getStringExtra("request"), false, false, "Routing data · 1/2 · 50% · 10/20 MB");
    assertEquals("Routing data · 1/2 · 50% · 10/20 MB", ((TextView) field("planStatus")).getText());
    assertNull(find(activity.getWindow().getDecorView(), "Undo"));
    ((TrekMapView) field("map")).onLongPress.at(new Point(48, 6));
    assertEquals(2, points().size());
    installGraphs();
    event(start.getStringExtra("request"), true, true, "Ready");
    // A repeated completion must not save the route twice.
    event(start.getStringExtra("request"), true, true, "Ready");
    assertSavedRoute();
  }

  @Test
  public void mapDownloadAndOtherRoutingRequestsCannotResumeThePendingRoute() throws Exception {
    confirm();
    installGraphs();
    context.sendBroadcast(new Intent(DownloadService.EVENT).putExtra("finished", true));
    event("unrelated-request", true, true, "Ready");
    assertNotNull(field("pendingRoute"));
    assertFalse((boolean) field("calculationRunning"));
  }

  @Test
  public void failedDownloadRetainsPointsAndRouteCanResumeAfterAnotherConfirmation()
      throws Exception {
    Intent start = confirm();
    event(start.getStringExtra("request"), true, false, "Routing download paused: No space");
    assertNull(field("pendingRoute"));
    assertEquals(2, points().size());
    assertTrue(((TrekMapView) field("map")).editing);
    assertNotEquals(start.getStringExtra("request"), confirm().getStringExtra("request"));
  }

  @Test
  public void cancelPausesTheTransferAndIgnoresItsLateCompletion() throws Exception {
    Intent start = confirm();
    tap("Pause routing download");
    assertEquals("pause", Shadows.shadowOf(context).getNextStartedService().getAction());
    installGraphs();
    event(start.getStringExtra("request"), true, true, "Ready");
    drain();
    assertTrue((boolean) field("planning"));
    assertEquals(2, points().size());
    assertNull(field("pendingRoute"));
    assertTrue(((Store) field("store")).routes().isEmpty());
  }

  @Test
  public void backgroundCompletionWaitsUntilTheActivityResumes() throws Exception {
    Intent start = confirm();
    controller.pause();
    installGraphs();
    event(start.getStringExtra("request"), true, true, "Ready");
    assertFalse((boolean) field("calculationRunning"));
    controller.resume();
    assertSavedRoute();
  }

  @Test
  public void recreationRetainsTheRequestedRouteUntilDownloadCompletes() throws Exception {
    Intent start = confirm();
    RoutingDownloadService.running = true;
    Bundle state = new Bundle();
    controller.pause().saveInstanceState(state).stop().destroy();
    controller =
        Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
    activity = controller.get();
    assertEquals(2, points().size());
    assertEquals(start.getStringExtra("request"), field("routingRequest"));
    installGraphs();
    RoutingDownloadService.running = false;
    event(start.getStringExtra("request"), true, true, "Ready");
    assertSavedRoute();
  }

  @Test
  public void aPausedBackgroundDownloadReturnsToRouteWithoutStartingCalculation() throws Exception {
    confirm();
    controller.pause().resume();
    assertNull(field("pendingRoute"));
    assertFalse((boolean) field("calculationRunning"));
    assertEquals(2, points().size());
    assertTrue(((TrekMapView) field("map")).editing);
  }

  @Test
  public void notificationPauseIsRespectedEvenIfTheLastFileFinishedWhileHidden() throws Exception {
    Intent start = confirm();
    controller.pause();
    installGraphs();
    RoutingDownloadService.lastOutcome =
        new RoutingDownloadService.Outcome(
            start.getStringExtra("request"), "Routing download paused", false);
    controller.resume();
    drain();
    assertTrue((boolean) field("planning"));
    assertNull(field("pendingRoute"));
    assertTrue(((Store) field("store")).routes().isEmpty());
  }

  @Test
  public void missingDataDuringRecordingNeverStartsPreparation() throws Exception {
    TrackingService.running = true;
    changeNodes();
    assertNull(field("routingPrompt"));
    assertNull(Shadows.shadowOf(context).getNextStartedService());
    assertFalse((boolean) field("calculationRunning"));
    assertEquals(2, points().size());
  }

  @Test
  public void successSignalStillRequiresAllRegionsToBeInstalled() throws Exception {
    Intent start = confirm();
    event(start.getStringExtra("request"), true, true, "Ready");
    assertNull(field("pendingRoute"));
    assertFalse((boolean) field("calculationRunning"));
    assertEquals(2, points().size());
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk = 35, qualifiers = "w393dp-h851dp-xhdpi")
  public void downloadConfirmationFitsThePhoneAndShowsBothActions() throws Exception {
    AlertDialog dialog = prompt();
    View decor = dialog.getWindow().getDecorView();
    int width = 722;
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST));
    int height = decor.getMeasuredHeight();
    decor.layout(0, 0, width, height);
    assertTrue(height < 1500);
    for (int button : new int[] {AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
      TextView action = dialog.getButton(button);
      assertEquals(View.VISIBLE, action.getVisibility());
      assertTrue(action.getWidth() > 0);
      assertEquals(0, action.getLayout().getEllipsisCount(0));
    }
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888);
    decor.draw(new android.graphics.Canvas(bitmap));
    File preview = new File("build/test-previews/download-and-route.png");
    preview.getParentFile().mkdirs();
    try (OutputStream output = new FileOutputStream(preview)) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    }
    dialog.dismiss();
  }
}
