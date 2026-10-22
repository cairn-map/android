package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class OfflineWorkflowTest {
  private PlanIgnStore store;

  @Before
  public void open() {
    Context c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("plan-ign.mbtiles");
    store = new PlanIgnStore(c);
    DownloadService.running = false;
  }

  @After
  public void close() {
    store.close();
  }

  private TilePlanner.Plan tiny() {
    return TilePlanner.area(6.869, 45.923, 6.8691, 45.9231, 8);
  }

  @Test
  public void downloadIsOnlyCompleteAfterAllTilesAreCommitted() throws Exception {
    TilePlanner.Plan plan = tiny();
    String id = store.create("A trek", plan);
    assertFalse(store.finish(id));
    assertEquals(0, store.packs().get(0).done);
    TilePlanner.Tile next;
    while ((next = store.next(id)) != null)
      store.completeTile(id, next, new IgnClient.Result(IgnClientTest.png(), "tag", null));
    assertTrue(store.finish(id));
    assertEquals("complete", store.packs().get(0).state);
    assertEquals(plan.tiles.size(), store.packs().get(0).done);
  }

  @Test
  public void parallelBatchesContainDistinctPendingTilesAndResumeOnlyUncommittedTiles()
      throws Exception {
    String id = store.create("Parallel", TilePlanner.area(6.86, 45.92, 6.88, 45.94, 17));
    List<TilePlanner.Tile> batch = store.next(id, 4);
    assertEquals(4, batch.size());
    assertEquals(4, new HashSet<>(batch).size());
    // Network completions may arrive in any order; only committed work advances progress.
    store.completeTile(id, batch.get(2), new IgnClient.Result(IgnClientTest.png(), null, null));
    assertEquals(1, store.packs().get(0).done);
    store.close();
    store = new PlanIgnStore(RuntimeEnvironment.getApplication());
    List<TilePlanner.Tile> resumed = store.next(id, 4);
    assertFalse(resumed.contains(batch.get(2)));
    assertTrue(resumed.contains(batch.get(0)));
    assertTrue(resumed.contains(batch.get(1)));
    assertTrue(resumed.contains(batch.get(3)));
    assertFalse(store.finish(id));
  }

  @Test
  public void partialDownloadSurvivesClosingAndReopening() throws Exception {
    String id = store.create("Partial", TilePlanner.area(6.86, 45.92, 6.88, 45.94, 17));
    TilePlanner.Tile first = store.next(id);
    store.completeTile(id, first, new IgnClient.Result(IgnClientTest.png(), null, null));
    store.state(id, "downloading", null);
    store.close();
    store = new PlanIgnStore(RuntimeEnvironment.getApplication());
    assertEquals(1, store.packs().get(0).done);
    assertEquals("paused", store.packs().get(0).state);
    assertNotEquals(first, store.next(id));
    assertFalse(store.finish(id));
  }

  @Test
  public void downloadsStartAtSelectedDetailAndLeaveOverviewTilesUntilLast() {
    TilePlanner.Plan plan =
        TilePlanner.corridor(List.of(new Point(45.92, 6.86), new Point(45.94, 6.88)), 100, 17);
    String id = store.create("Narrow corridor", plan);
    List<TilePlanner.Tile> ordered = store.next(id, TilePlanner.MAX_TILES);
    assertEquals(plan.tiles.size(), ordered.size());
    assertEquals(17, ordered.get(0).z);
    assertEquals(TilePlanner.MIN_ZOOM, ordered.get(ordered.size() - 1).z);
    for (int i = 1; i < ordered.size(); i++) assertTrue(ordered.get(i - 1).z >= ordered.get(i).z);
    assertEquals(ordered.size(), new HashSet<>(ordered).size());
  }

  @Test
  public void routeMenuSavesTheChosenCorridorWidthInsteadOfTheMapViewport() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    List<Point> route = List.of(new Point(45.90, 6.80), new Point(46.00, 6.95));
    try (Store routes = new Store(context)) {
      routes.saveRoute(route, "Width check");
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      java.lang.reflect.Field mapsField = MainActivity.class.getDeclaredField("mapStore");
      mapsField.setAccessible(true);
      PlanIgnStore savedMaps = (PlanIgnStore) mapsField.get(activity);
      for (String width : new String[] {"0.2", "2.0"}) {
        drain(activity);
        View root = activity.getWindow().getDecorView();
        find(root, "Routes").performClick();
        find(root, "Width check · selected").performClick();
        AlertDialog actions = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        actions.getListView().performItemClick(null, 2, 2);
        drain(activity);
        AlertDialog form = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        ((EditText) find(form.getWindow().getDecorView(), "1.0")).setText(width);
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain(activity);
        AlertDialog estimate = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        String message =
            ((TextView) estimate.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.contains("Corridor: " + width + " km on each side"));
        estimate.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain(activity);
        PlanIgnStore.Pack pack = savedMaps.packs().get(0);
        TilePlanner.Plan expected =
            TilePlanner.corridor(route, Double.parseDouble(width) * 1000, 17);
        assertEquals(expected.tiles.size(), pack.total);
        assertEquals(route.get(0).lat, pack.center.lat, 0);
        assertEquals(
            new HashSet<>(expected.tiles),
            new HashSet<>(savedMaps.next(pack.id, TilePlanner.MAX_TILES)));
      }
    }
  }

  private void drain(MainActivity activity) throws Exception {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("io");
    field.setAccessible(true);
    ((ExecutorService) field.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void removingAPackagePreservesTilesSharedWithAnother() throws Exception {
    TilePlanner.Plan plan = tiny();
    String a = store.create("A", plan), b = store.create("B", plan);
    TilePlanner.Tile tile = store.next(a);
    store.completeTile(a, tile, new IgnClient.Result(IgnClientTest.png(), "tag", null));
    store.remove(a);
    assertNotNull(store.cached(tile));
    store.remove(b);
    assertNull(store.cached(tile));
  }

  @Test
  public void conditionalRefreshKeepsBytesAndRequiresRechecking() throws Exception {
    String id = store.create("Refresh", tiny());
    TilePlanner.Tile tile = store.next(id);
    store.completeTile(id, tile, new IgnClient.Result(IgnClientTest.png(), "tag", null));
    assertTrue(store.finish(id));
    store.refresh(id);
    assertFalse(store.finish(id));
    store.completeTile(id, tile, new IgnClient.Result(null, "tag", null));
    assertTrue(store.finish(id));
    assertNotNull(store.cached(tile).bytes);
  }

  @Test
  public void startsOfflineAndRoutesToDownloadControlsWithoutLocationPermission() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View root = activity.getWindow().getDecorView();
      assertNull(find(root, "Offline"));
      TextView routes = find(root, "Routes");
      assertNotNull(routes);
      routes.performClick();
      assertNotNull(find(root, "Add files"));
      assertNull(find(root, "Import GPX"));
      assertNull(find(root, "Import OruxMaps sources XML"));
      find(root, "Routing data").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(
          ((TextView) dialog.findViewById(android.R.id.message))
              .getText()
              .toString()
              .contains("Walking engine is included"));
      dialog.dismiss();
    }
  }

  @Test
  public void removingAPackDoesNotDiscardUnrelatedViewedTiles() throws Exception {
    String id = store.create("A route", tiny());
    TilePlanner.Tile viewed = new TilePlanner.Tile(15, 1, 1);
    store.save(viewed, new IgnClient.Result(IgnClientTest.png(), null, null));
    store.remove(id);
    assertNotNull(store.cached(viewed));
  }

  @Test
  public void clearingViewedTilesKeepsDownloadedCorridorsComplete() throws Exception {
    String id = store.create("A route", tiny());
    TilePlanner.Tile tile = store.next(id), viewed = new TilePlanner.Tile(15, 1, 1);
    store.completeTile(id, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
    store.save(viewed, new IgnClient.Result(IgnClientTest.png(), null, null));
    store.clearViewed();
    assertNull(store.cached(viewed));
    assertNotNull(store.cached(tile));
    assertTrue(store.finish(id));
  }

  private TextView find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
