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
  public void conditionalRefreshReadsOnlyValidatorsAndKeepsTheSavedPngOnNotModified()
      throws Exception {
    TilePlanner.Tile tile = new TilePlanner.Tile(8, 132, 91);
    byte[] png = IgnClientTest.png();
    store.save(tile, new IgnClient.Result(png, "etag", "modified"));
    PlanIgnStore.Cached validators = store.validators(tile);
    assertNull(validators.bytes);
    assertEquals("etag", validators.etag);
    assertEquals("modified", validators.modified);
    store.save(tile, new IgnClient.Result(null, validators.etag, validators.modified));
    assertArrayEquals(png, store.cached(tile).bytes);
    assertNull(store.validators(new TilePlanner.Tile(8, 133, 91)));
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
        View list = openLibrary(activity);
        View item = find(list, "Width check · selected");
        if (item == null) item = find(list, "Width check");
        item.performClick();
        drain(activity);
        View corridor = find(root, "Save offline corridor");
        if (corridor == null) corridor = find(root, "Offline corridor");
        corridor.performClick();
        drain(activity);
        AlertDialog form = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        if (form.getWindow().getDecorView().findViewWithTag("Map detail") == null) {
          find(form.getWindow().getDecorView(), "Save offline corridor").performClick();
          drain(activity);
          form = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        }
        SeekBar widthSlider =
            form.getWindow().getDecorView().findViewWithTag("Kilometres on each side");
        widthSlider.setProgress((int) Math.round(Double.parseDouble(width) * 10) - 1);
        assertEquals(99, widthSlider.getMax());
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        drain(activity);
        assertSame(form, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        String message =
            ((TextView) form.getWindow().getDecorView().findViewWithTag("Download estimate"))
                .getText()
                .toString();
        assertTrue(message.contains("Corridor: " + width + " km on each side"));
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
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

  @Test
  public void corridorSlidersSaveZoomTenThroughNineteenWithWordLabels() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    List<Point> points = List.of(new Point(45.9, 6.8), new Point(45.9001, 6.8001));
    try (Store routes = new Store(context)) {
      routes.saveRoute(points, "All details");
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      String[] labels = {
        "Regional overview",
        "Local overview",
        "Route overview",
        "Route planning",
        "Terrain detail",
        "Trail detail",
        "Walking navigation",
        "Junction detail",
        "Close-up detail",
        "Maximum detail"
      };
      for (int zoom = 10; zoom <= 19; zoom++) {
        drain(activity);
        java.lang.reflect.Method prepare =
            MainActivity.class.getDeclaredMethod(
                "prepareDownload", List.class, String.class, String.class);
        prepare.setAccessible(true);
        prepare.invoke(activity, points, "All details", "slider-route");
        drain(activity);
        AlertDialog form = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        View decor = form.getWindow().getDecorView();
        assertFalse(hasEditText(decor));
        SeekBar detail = decor.findViewWithTag("Map detail");
        SeekBar width = decor.findViewWithTag("Kilometres on each side");
        assertEquals(10, detail.getMin());
        assertEquals(19, detail.getMax());
        assertEquals(17, detail.getProgress());
        assertEquals(1, detail.getKeyProgressIncrement());
        width.setProgress(zoom % 2 == 0 ? 0 : 99);
        assertEquals(
            zoom % 2 == 0 ? "0.1 km on each side" : "10.0 km on each side",
            width.getContentDescription());
        width.setProgress(0);
        detail.setProgress(zoom);
        assertEquals("Map detail · " + labels[zoom - 10], detail.getContentDescription());
        assertNotNull(find(decor, "Map detail · " + labels[zoom - 10]));
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        drain(activity);
        assertSame(form, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        String message =
            ((TextView) form.getWindow().getDecorView().findViewWithTag("Download estimate"))
                .getText()
                .toString();
        assertTrue(message.contains(" · " + labels[zoom - 10] + "\n"));
        assertFalse(message.contains("zoom"));
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain(activity);
        java.lang.reflect.Field mapsField = MainActivity.class.getDeclaredField("mapStore");
        mapsField.setAccessible(true);
        PlanIgnStore maps = (PlanIgnStore) mapsField.get(activity);
        PlanIgnStore.Pack pack = maps.packs().get(0);
        assertEquals("All details · corridor", pack.name);
        assertEquals("slider-route", pack.routeId);
        assertEquals(
            new HashSet<>(TilePlanner.corridor(points, 100, zoom).tiles),
            new HashSet<>(maps.next(pack.id, TilePlanner.MAX_TILES)));
        maps.remove(pack.id);
        java.lang.reflect.Method reload = MainActivity.class.getDeclaredMethod("reloadLibrary");
        reload.setAccessible(true);
        reload.invoke(activity);
        drain(activity);
      }
    }
  }

  @Test
  public void corridorEstimateRejectsOversizedSelectionsAndIgnoresStaleResults() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      java.lang.reflect.Method prepare =
          MainActivity.class.getDeclaredMethod(
              "prepareDownload", List.class, String.class, String.class);
      prepare.setAccessible(true);
      List<Point> points = List.of(new Point(45, 6), new Point(45.1, 6.1));
      prepare.invoke(activity, points, "Live estimate", "live-route");
      drain(activity);
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = dialog.getWindow().getDecorView();
      TextView estimate = decor.findViewWithTag("Download estimate");
      SeekBar detail = decor.findViewWithTag("Map detail");
      SeekBar width = decor.findViewWithTag("Kilometres on each side");
      Button download = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      assertTrue(download.isEnabled());
      String initial = estimate.getText().toString();
      width.setProgress(1);
      assertFalse(download.isEnabled());
      assertEquals(initial, estimate.getText().toString());
      drain(activity);
      assertNotEquals(initial, estimate.getText().toString());
      assertTrue(estimate.getText().toString().contains("Corridor: 0.2 km"));
      assertTrue(download.isEnabled());
      detail.setProgress(19);
      width.setProgress(99);
      drain(activity);
      assertTrue(estimate.getText().toString().contains("20,000 tiles"));
      assertFalse(download.isEnabled());
      // A completed older result waiting on the UI queue cannot enable a changed selection.
      detail.setProgress(10);
      java.lang.reflect.Field executor = MainActivity.class.getDeclaredField("io");
      executor.setAccessible(true);
      ((ExecutorService) executor.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
      width.setProgress(0);
      detail.setProgress(17);
      assertFalse(download.isEnabled());
      drain(activity);
      TilePlanner.Plan expected = TilePlanner.corridor(points, 100, 17);
      String latest = estimate.getText().toString();
      assertTrue(latest.contains("Corridor: 0.1 km on each side · Junction detail"));
      assertTrue(
          latest.contains(String.format(Locale.getDefault(), "%,d tiles", expected.tiles.size())));
      assertTrue(download.isEnabled());
      width.setProgress(1);
      assertEquals(latest, estimate.getText().toString());
      String pending = estimate.getText().toString();
      dialog.dismiss();
      drain(activity);
      assertEquals(pending, estimate.getText().toString());
      assertFalse(download.isEnabled());
      assertSame(dialog, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }

  private boolean hasEditText(View view) {
    if (view instanceof EditText) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasEditText(group.getChildAt(i))) return true;
    }
    return false;
  }

  @Test
  public void routeIndicatorTracksCompletionAndSurvivesRename() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    String routeId;
    try (Store routes = new Store(context)) {
      routeId = routes.saveRoute(List.of(new Point(45, 6)), "With maps");
      routes.saveRoute(List.of(new Point(46, 7)), "Without maps");
    }
    String packId =
        store.create(
            "With maps · corridor",
            TilePlanner.corridor(List.of(new Point(45, 6)), 200, 17),
            routeId,
            200);
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View library = openLibrary(activity);
      assertNotNull(find(library, "Offline corridor incomplete for With maps"));
      assertNull(find(library, "Offline corridor ready for With maps"));
      View missing = find(library, "No offline corridor for Without maps");
      assertNotNull(missing);
      assertEquals(missing, ((ViewGroup) missing.getParent()).getChildAt(0));
      missing.performClick();
      drain(activity);
      AlertDialog selection = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(selection.getWindow().getDecorView().findViewWithTag("Map detail"));
      selection.dismiss();
      for (TilePlanner.Tile tile : store.next(packId, TilePlanner.MAX_TILES))
        store.completeTile(packId, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
      assertTrue(store.finish(packId));
      try (Store routes = new Store(context)) {
        routes.renameRoute(routeId, "Renamed walk");
      }
      activity.sendBroadcast(
          new Intent(DownloadService.EVENT)
              .setPackage(activity.getPackageName())
              .putExtra("finished", true));
      drain(activity);
      library = openLibrary(activity);
      View ready = find(library, "Offline corridor ready for Renamed walk");
      assertNotNull(ready);
      assertNull(find(library, "Offline corridor ready for Without maps"));
      ready.performClick();
      drain(activity);
      AlertDialog corridor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View content = corridor.getWindow().getDecorView();
      assertNotNull(content.findViewWithTag("Map detail"));
      assertEquals(17, ((SeekBar) content.findViewWithTag("Map detail")).getProgress());
      assertEquals(17, ((SeekBar) content.findViewWithTag("Map detail")).getMin());
      assertEquals(1, ((SeekBar) content.findViewWithTag("Kilometres on each side")).getProgress());
      assertNotNull(find(content, store.packs().get(0).description()));
      corridor.dismiss();
      java.lang.reflect.Method menu = MainActivity.class.getDeclaredMethod("showLibrary");
      menu.setAccessible(true);
      menu.invoke(activity);
      View maps =
          org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
              .getWindow()
              .getDecorView();
      assertNull(find(maps, "With maps · corridor\n" + store.packs().get(0).description()));
      org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
      find(openLibrary(activity), "Offline corridor ready for Renamed walk").performClick();
      drain(activity);
      AlertDialog saved = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View savedContent = saved.getWindow().getDecorView();
      assertNull(find(savedContent, "Check for updates"));
      assertNull(find(savedContent, "Resume download"));
      assertNull(find(savedContent, "Remove offline corridor"));
      assertEquals("Download", saved.getButton(AlertDialog.BUTTON_POSITIVE).getText());
      assertEquals(View.GONE, saved.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility());
      saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      drain(activity);
      assertFalse(saved.isShowing());
      assertSame(saved, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
      assertEquals(packId, store.packs().get(0).id);
      assertEquals(0, store.packs().get(0).done);
      store.remove(packId);
      activity.sendBroadcast(
          new Intent(DownloadService.EVENT)
              .setPackage(activity.getPackageName())
              .putExtra("finished", true));
      drain(activity);
      assertTrue(store.packs().isEmpty());
      assertNull(find(openLibrary(activity), "Offline corridor ready for Renamed walk"));
    }
  }

  @Test
  public void eachRouteKeepsOneCorridorAndNeverDowngradesSavedDetail() throws Exception {
    List<Point> points = List.of(new Point(45, 6));
    TilePlanner.Plan detailed = TilePlanner.corridor(points, 100, 17);
    String id = store.create("Walk", detailed, "walk", 100);
    for (TilePlanner.Tile tile : store.next(id, TilePlanner.MAX_TILES))
      store.completeTile(id, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
    assertTrue(store.finish(id));
    TilePlanner.Plan finer = TilePlanner.corridor(points, 100, 18);
    assertEquals(id, store.create("Walk", finer, "walk", 100));
    assertEquals(1, store.packs().size());
    PlanIgnStore.Pack pack = store.packs().get(0);
    assertEquals(18, pack.maxZoom);
    assertEquals(100, pack.radiusMeters, 0);
    assertTrue(pack.done > 0);
    assertTrue(pack.done < pack.total);
    assertEquals(id, store.create("Walk", TilePlanner.corridor(points, 100, 10), "walk"));
    assertEquals(18, store.packs().get(0).maxZoom);
    assertEquals(finer.tiles.size(), store.packs().get(0).total);
  }

  @Test
  public void versionTwoUpgradeKeepsTheFinestCorridorAndAllCachedTiles() throws Exception {
    String coarse = store.create("Old walk · corridor", tiny());
    TilePlanner.Plan fine = TilePlanner.corridor(List.of(new Point(45, 6)), 100, 17);
    String finest = store.create("Old walk · corridor", fine);
    for (String id : List.of(coarse, finest)) {
      for (TilePlanner.Tile tile : store.next(id, TilePlanner.MAX_TILES))
        store.completeTile(id, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
      assertTrue(store.finish(id));
    }
    android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase();
    db.execSQL("DROP INDEX corridor_routes");
    db.execSQL("UPDATE packs SET route_id='walk'");
    db.execSQL(
        "CREATE TABLE version_two_packs AS SELECT id,name,state,error,checked,lat,lon,route_id FROM"
            + " packs");
    db.execSQL("DROP TABLE packs");
    db.execSQL("ALTER TABLE version_two_packs RENAME TO packs");
    db.setVersion(2);
    store.close();
    store = new PlanIgnStore(RuntimeEnvironment.getApplication());
    assertEquals(1, store.packs().size());
    assertEquals(finest, store.packs().get(0).id);
    assertEquals(17, store.packs().get(0).maxZoom);
    assertEquals("complete", store.packs().get(0).state);
    for (TilePlanner.Tile tile : tiny().tiles) assertNotNull(store.cached(tile));
  }

  @Test
  public void legacyCorridorsLinkOnlyToUniqueNamesAndDetachedMapsStayUnlinked() {
    String unique = store.create("Unique · corridor", tiny());
    String ambiguous = store.create("Duplicate · corridor", tiny());
    List<Store.LibraryEntry> entries =
        List.of(
            new Store.LibraryEntry("a", "Unique", false, true),
            new Store.LibraryEntry("b", "Duplicate", false, true),
            new Store.LibraryEntry("c", "Duplicate", true, true));
    store.linkLegacyCorridors(entries);
    for (PlanIgnStore.Pack pack : store.packs()) {
      if (pack.id.equals(unique)) assertEquals("a", pack.routeId);
      if (pack.id.equals(ambiguous)) assertNull(pack.routeId);
    }
    store.detachRoute("a");
    store.linkLegacyCorridors(entries);
    assertEquals(
        "",
        store.packs().stream().filter(pack -> pack.id.equals(unique)).findFirst().get().routeId);
    assertNotNull(store.next(unique));
  }

  @Test
  public void mapDatabaseUpgradePreservesSavedTilesAndLinksLegacyCorridor() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    store.close();
    context.deleteDatabase("plan-ign.mbtiles");
    try (android.database.sqlite.SQLiteDatabase db =
        context.openOrCreateDatabase("plan-ign.mbtiles", 0, null)) {
      db.execSQL(
          "CREATE TABLE packs(id TEXT PRIMARY KEY,name TEXT,state TEXT,error TEXT,"
              + "checked INTEGER,lat REAL,lon REAL)");
      db.execSQL(
          "CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,"
              + "tile_data BLOB,etag TEXT,modified TEXT,checked INTEGER,"
              + "PRIMARY KEY(zoom_level,tile_column,tile_row))");
      db.execSQL(
          "CREATE TABLE pack_tiles(pack TEXT,z INTEGER,x INTEGER,y INTEGER,done INTEGER,"
              + "PRIMARY KEY(pack,z,x,y))");
      db.execSQL("INSERT INTO packs VALUES('old','Old walk · corridor','complete',NULL,1,45,6)");
      db.execSQL("INSERT INTO pack_tiles VALUES('old',8,132,91,1)");
      db.execSQL(
          "INSERT INTO tiles VALUES(8,132,91,?,NULL,NULL,1)", new Object[] {IgnClientTest.png()});
      db.setVersion(1);
    }
    store = new PlanIgnStore(context);
    store.linkLegacyCorridors(List.of(new Store.LibraryEntry("route", "Old walk", false, true)));
    PlanIgnStore.Pack pack = store.packs().get(0);
    assertEquals("route", pack.routeId);
    assertEquals("complete", pack.state);
    assertEquals(1, pack.done);
    assertNotNull(store.cached(new TilePlanner.Tile(8, 132, 91)));
  }

  private void drain(MainActivity activity) throws Exception {
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("io");
    field.setAccessible(true);
    for (int i = 0; i < 3; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((ExecutorService) field.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void deletingRouteCorridorsPreservesOtherAreasAndTheirSharedTiles() throws Exception {
    TilePlanner.Plan plan = tiny();
    String linked = store.create("Renamed route's corridor", plan, "route-a");
    String other = store.create("Other route", plan, "route-b");
    String standalone = store.create("Saved area", TilePlanner.area(7, 46, 7.001, 46.001, 10));
    TilePlanner.Tile tile = store.next(linked);
    store.completeTile(linked, tile, new IgnClient.Result(IgnClientTest.png(), null, null));
    store.removeRouteCorridors("route-a");
    assertFalse(store.packs().stream().anyMatch(pack -> pack.id.equals(linked)));
    assertTrue(store.packs().stream().anyMatch(pack -> pack.id.equals(other)));
    assertTrue(store.packs().stream().anyMatch(pack -> pack.id.equals(standalone)));
    assertTrue(store.next(linked, TilePlanner.MAX_TILES).isEmpty());
    assertNotNull(store.cached(tile));
    store.removeRouteCorridors("route-b");
    assertNull(store.cached(tile));
    store.removeRouteCorridors("route-a"); // Retrying after an interrupted deletion is safe.
    assertEquals(1, store.packs().size());
  }

  @Test
  public void deletingADownloadingCorridorRequiresPausingAndLeavesThePackIntact() {
    String linked = store.create("Downloading", tiny(), "route-a");
    DownloadService.running = true;
    DownloadService.activePack = linked;
    try {
      assertThrows(IllegalStateException.class, () -> store.removeRouteCorridors("route-a"));
      assertEquals(linked, store.packs().get(0).id);
    } finally {
      DownloadService.running = false;
      DownloadService.activePack = null;
    }
    store.removeRouteCorridors("route-a");
    assertTrue(store.packs().isEmpty());
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
  @LooperMode(LooperMode.Mode.PAUSED)
  public void corridorLoadingKeepsRoutesVisibleAndRefreshSwapsWithoutAnimations() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    try (Store routes = new Store(context)) {
      routes.saveRoute(List.of(new Point(45, 6)), "Steady drawer");
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View library = openLibrary(activity);
      AlertDialog routes = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      java.lang.reflect.Field executor = MainActivity.class.getDeclaredField("io");
      executor.setAccessible(true);
      ExecutorService io = (ExecutorService) executor.get(activity);
      CountDownLatch release = new CountDownLatch(1);
      io.submit(() -> release.await(5, TimeUnit.SECONDS));
      try {
        find(library, "No offline corridor for Steady drawer").performClick();
        assertTrue("Routes must cover the map while the corridor loads", routes.isShowing());
        assertSame(routes, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
      } finally {
        release.countDown();
      }
      drain(activity);
      AlertDialog corridor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotSame(routes, corridor);
      assertTrue(routes.isShowing());
      assertTrue(corridor.isShowing());
      assertEquals(0, corridor.getWindow().getAttributes().windowAnimations);

      CountDownLatch finish = new CountDownLatch(1);
      io.submit(() -> finish.await(5, TimeUnit.SECONDS));
      try {
        activity.sendBroadcast(
            new Intent(DownloadService.EVENT)
                .setPackage(activity.getPackageName())
                .putExtra("finished", true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(
            "Keep the old corridor visible until its replacement is ready", corridor.isShowing());
      } finally {
        finish.countDown();
      }
      drain(activity);
      AlertDialog refreshed = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotSame(corridor, refreshed);
      assertFalse(corridor.isShowing());
      assertTrue(refreshed.isShowing());
      assertEquals(0, refreshed.getWindow().getAttributes().windowAnimations);
      refreshed.dismiss();
      assertTrue("Closing the corridor returns to Routes", routes.isShowing());
    }
  }

  @Test
  @LooperMode(LooperMode.Mode.PAUSED)
  public void dismissingRoutesCancelsAPendingCorridorDrawer() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    try (Store routes = new Store(context)) {
      routes.saveRoute(List.of(new Point(45, 6)), "Canceled drawer");
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View library = openLibrary(activity);
      AlertDialog routes = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      java.lang.reflect.Field executor = MainActivity.class.getDeclaredField("io");
      executor.setAccessible(true);
      CountDownLatch release = new CountDownLatch(1);
      ((ExecutorService) executor.get(activity)).submit(() -> release.await(5, TimeUnit.SECONDS));
      try {
        find(library, "No offline corridor for Canceled drawer").performClick();
        routes.dismiss();
      } finally {
        release.countDown();
      }
      drain(activity);
      assertFalse(routes.isShowing());
      assertSame(routes, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
  }

  @Test
  public void startsOfflineAndRoutesToDownloadControlsWithoutLocationPermission() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View root = activity.getWindow().getDecorView();
      assertNull(find(root, "Offline"));
      assertNull(find(root, "Saved maps and downloads"));
      root = openLibrary(activity);
      assertNotNull(find(root, "Add files"));
      assertNull(find(root, "Import GPX"));
      assertNull(find(root, "Import OruxMaps sources XML"));
      View maps = find(root, "MAPS & ROUTING");
      assertNotNull(maps);
      ViewGroup content = (ViewGroup) maps.getParent();
      assertTrue(content.indexOfChild(maps) > content.indexOfChild(find(root, "RECORDED HIKES")));
      assertNotNull(find(root, "Clear viewed-map cache"));
      assertNotNull(find(root, "Default routing preferences"));
      assertNull(find(root, "Routing data"));
      find(root, "Clear all routing data").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(find(dialog.getWindow().getDecorView(), "Clear all routing data?"));
      assertEquals("Clear", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
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

  private View openLibrary(MainActivity activity) throws Exception {
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("libraryButton");
    field.setAccessible(true);
    ((View) field.get(activity)).performClick();
    java.lang.reflect.Field dialog = MainActivity.class.getDeclaredField("libraryDialog");
    dialog.setAccessible(true);
    return ((AlertDialog) dialog.get(activity)).getWindow().getDecorView();
  }

  private View find(View view, String text) {
    if (text.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
