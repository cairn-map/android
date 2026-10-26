package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RouteLibraryTest {
  private Context context;

  private List<Point> points(double latitude) {
    return Arrays.asList(new Point(latitude, 6), new Point(latitude + 0.01, 6.01));
  }

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("treks.db");
    context.getSharedPreferences("settings", 0).edit().clear().commit();
  }

  @Test
  public void routePreferencesSurviveReopeningAndStayIndependentOfDefaultsAndOtherRoutes() {
    Map<String, String> first, second;
    String a, b;
    try (Store store = new Store(context)) {
      first = RoutingPreferences.read(store.prefs);
      a = store.saveRoute("route-a", points(45), "A", points(45), first);
      store.prefs.edit().putBoolean("brouter.consider_forest", true).commit();
      second = RoutingPreferences.read(store.prefs);
      b = store.saveRoute("route-b", points(46), "B", points(46), second);
      String imported = store.saveRoute(points(47), "Imported");
      assertNull(store.routePreferences(imported));
      store.updateRoute(a, points(45.1), points(45.1));
      assertEquals(first, store.routePreferences(a));
    }
    try (Store store = new Store(context)) {
      assertEquals(first, store.routePreferences(a));
      assertEquals(second, store.routePreferences(b));
      store.updateRoute(a, points(48), points(48), second);
      assertEquals(second, store.routePreferences(a));
      store.prefs.edit().putBoolean("brouter.allow_steps", false).commit();
      assertEquals(second, store.routePreferences(a));
      assertEquals(second, store.routePreferences(b));
      store.deleteRoute(a);
      assertNull(store.routePreferences(a));
      assertEquals(second, store.routePreferences(b));
    }
  }

  @Test
  public void versionTwoUpgradeFreezesDefaultsAndPreservesRouteGeometryNodesAndGuidance() {
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putInt("brouter.SAC_scale_limit", 2)
        .putBoolean("brouter.allow_steps", false)
        .commit();
    try (SQLiteDatabase db = context.openOrCreateDatabase("treks.db", 0, null)) {
      db.execSQL(
          "CREATE TABLE points(id INTEGER PRIMARY KEY,collection TEXT,lat REAL,lon REAL,"
              + "elevation REAL,time INTEGER,accuracy REAL,segment INTEGER)");
      db.execSQL("CREATE TABLE treks(id TEXT PRIMARY KEY,title TEXT)");
      db.execSQL(
          "CREATE TABLE routes(id TEXT PRIMARY KEY,title TEXT NOT NULL,"
              + "visible INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL)");
      db.execSQL("INSERT INTO routes VALUES('old','Weekend',0,123)");
      db.execSQL(
          "INSERT INTO points VALUES(1,'old',45,6,NULL,0,0,0),"
              + "(2,'nodes:old',45,6,NULL,0,0,0),(3,'route',45,6,NULL,0,0,0)");
      db.setVersion(2);
    }
    Map<String, String> frozen;
    try (Store store = new Store(context)) {
      assertEquals("Weekend", store.routes().get(0).title);
      assertTrue(store.routes().get(0).visible);
      assertEquals(45, store.read("old").get(0).lat, 0);
      assertEquals(45, store.routeNodes("old").get(0).lat, 0);
      assertEquals(45, store.read("route").get(0).lat, 0);
      frozen = store.routePreferences("old");
      assertEquals(RoutingPreferences.read(store.prefs), frozen);
      store.prefs.edit().putInt("brouter.SAC_scale_limit", 6).commit();
    }
    try (Store store = new Store(context)) {
      assertEquals(frozen, store.routePreferences("old"));
    }
  }

  @Test
  public void retryingAnInterruptedDraftSaveDoesNotCreateADuplicateRouteOrNodes() {
    try (Store store = new Store(context)) {
      String id = "route-test-draft";
      store.saveRoute(id, points(45), "Weekend", points(45));
      store.saveRoute(id, points(46), "Weekend", points(46));
      assertEquals(1, store.routes().size());
      assertEquals(2, store.read(id).size());
      assertEquals(2, store.routeNodes(id).size());
      assertEquals(46, store.read("route").get(0).lat, 0);
    }
  }

  @Test
  public void editedRouteKeepsIdentityNameAndGuidanceAndRetainsNodesAfterReopening() {
    String id;
    try (Store store = new Store(context)) {
      id = store.saveRoute(points(45), "Weekend", points(45));
      store.activateRoute(id);
      store.updateRoute(id, points(46), points(46));
    }
    try (Store store = new Store(context)) {
      assertEquals(1, store.routes().size());
      assertEquals(id, store.routes().get(0).id);
      assertEquals("Weekend", store.routes().get(0).title);
      assertEquals(id, store.selectedLibraryId());
      assertTrue(store.prefs.getBoolean("follow", false));
      assertEquals(46, store.read(id).get(0).lat, 0);
      assertEquals(46, store.read("route").get(0).lat, 0);
      assertEquals(46, store.routeNodes(id).get(0).lat, 0);
      store.deleteRoute(id);
      assertTrue(store.routeNodes(id).isEmpty());
    }
  }

  @Test
  public void multipleFilesAndSelectionSurviveReopeningWithoutOverwritingEachOther() {
    String a, b;
    try (Store store = new Store(context)) {
      a = store.saveRoute(points(45), "First GPX");
      b = store.saveRoute(points(46), "Second GPX");
      assertEquals(2, store.routes().size());
      store.activateRoute(a);
      store
          .getWritableDatabase()
          .execSQL("UPDATE routes SET visible=0 WHERE id=?", new Object[] {b});
      store.renameRoute(a, "Weekend");
    }
    try (Store store = new Store(context)) {
      assertEquals(2, store.routes().size());
      assertEquals(45, store.read("route").get(0).lat, 0);
      assertEquals(46, store.read(b).get(0).lat, 0);
      assertEquals(a, store.prefs.getString("activeRouteId", ""));
      assertTrue(store.prefs.getBoolean("follow", false));
      assertTrue(store.routes().get(0).visible);
      assertEquals("Weekend", store.routes().get(1).title);
    }
  }

  @Test
  public void guidanceCopiesPreservePointOrderGapsAndNullableElevation() {
    List<Point> geometry =
        List.of(
            new Point(45, 6, Double.NaN, 3000, 4, 0),
            new Point(45.01, 6.02, 1200, 1000, 5, 2),
            new Point(45.02, 6.03, Double.NaN, 1000, 6, 2));
    try (Store store = new Store(context)) {
      String id = store.saveRoute(geometry, "Segments");
      store.saveRoute(points(46), "Other");
      store.activateRoute(id);
      List<Point> guidance = store.read("route");
      assertEquals(geometry.size(), guidance.size());
      for (int i = 0; i < geometry.size(); i++) {
        Point expected = geometry.get(i), actual = guidance.get(i);
        assertEquals(expected.lat, actual.lat, 0);
        assertEquals(expected.lon, actual.lon, 0);
        assertEquals(expected.elevation, actual.elevation, 0);
        assertEquals(expected.time, actual.time);
        assertEquals(expected.accuracy, actual.accuracy, 0);
        assertEquals(expected.segment, actual.segment);
      }
    }
  }

  @Test
  public void deletingTheSelectedRouteRetainsOtherGpxFilesAndRecordings() {
    try (Store store = new Store(context)) {
      String a = store.saveRoute(points(45), "A"), b = store.saveRoute(points(46), "B");
      store.add("trek-existing", new Point(47, 6));
      store.prefs.edit().putBoolean("follow", true).commit();
      store.deleteRoute(b);
      assertEquals(1, store.routes().size());
      assertEquals(2, store.read(a).size());
      assertTrue(store.read("route").isEmpty());
      assertEquals(1, store.read("trek-existing").size());
      assertFalse(store.prefs.getBoolean("follow", true));
    }
  }

  @Test
  public void deletingARecordingRemovesItsPointsAndCurrentTrackButRetainsRoutesAndOtherHikes() {
    try (Store store = new Store(context)) {
      String route = store.saveRoute(points(45), "Keep route");
      String hike = store.newTrek();
      store.add(hike, new Point(46, 6));
      store.add("trek-other", new Point(47, 7));
      store.deleteTrek(hike);
      assertFalse(store.treks().containsKey(hike));
      assertTrue(store.read(hike).isEmpty());
      assertFalse(store.prefs.contains("currentTrek"));
      assertEquals(2, store.read(route).size());
      assertEquals(2, store.read("route").size());
      assertEquals(1, store.read("trek-other").size());
      store.deleteTrek(route);
      assertEquals(2, store.read(route).size());
    }
    try (Store reopened = new Store(context)) {
      assertTrue(reopened.treks().isEmpty());
    }
  }

  @Test
  public void activeRecordingCannotBeDeletedUntilRecordingFinishes() {
    try (Store store = new Store(context)) {
      String hike = store.newTrek();
      store.add(hike, new Point(46, 6));
      TrackingService.running = true;
      assertThrows(IllegalStateException.class, () -> store.deleteTrek(hike));
      assertTrue(store.treks().containsKey(hike));
      assertEquals(1, store.read(hike).size());
      TrackingService.running = false;
      store.deleteTrek(hike);
      assertTrue(store.treks().isEmpty());
      assertTrue(store.read(hike).isEmpty());
    } finally {
      TrackingService.running = false;
    }
  }

  @Test
  public void versionThreeUpgradePreservesRawAltitudeAndUnknownVerticalAccuracy() {
    try (SQLiteDatabase db = context.openOrCreateDatabase("treks.db", 0, null)) {
      db.execSQL(
          "CREATE TABLE points(id INTEGER PRIMARY KEY,collection TEXT,lat REAL,lon REAL,"
              + "elevation REAL,time INTEGER,accuracy REAL,segment INTEGER)");
      db.execSQL("CREATE TABLE treks(id TEXT PRIMARY KEY,title TEXT)");
      db.execSQL(
          "CREATE TABLE routes(id TEXT PRIMARY KEY,title TEXT NOT NULL,visible INTEGER,"
              + "created INTEGER,routing_preferences TEXT)");
      db.execSQL("INSERT INTO points VALUES(1,'trek-old',45,6,229,1000,5,0)");
      db.setVersion(3);
    }
    try (Store store = new Store(context)) {
      Point old = store.read("trek-old").get(0);
      assertEquals(229, old.elevation, 0);
      assertTrue(Float.isNaN(old.verticalAccuracy));
      store.add("trek-old", new Point(45.01, 6, 216.492, 2000, 5, 0, 25));
    }
    try (Store store = new Store(context)) {
      Point saved = store.read("trek-old").get(1);
      assertEquals(216.492, saved.elevation, 0);
      assertEquals(25, saved.verticalAccuracy, 0);
    }
  }

  @Test
  public void upgradesThePreviousSingleRouteDatabaseWithoutLosingHikes() {
    try (SQLiteDatabase db = context.openOrCreateDatabase("treks.db", 0, null)) {
      db.execSQL(
          "CREATE TABLE points(id INTEGER PRIMARY KEY,collection TEXT,lat REAL,lon REAL,elevation"
              + " REAL,time INTEGER,accuracy REAL,segment INTEGER)");
      db.execSQL("CREATE TABLE treks(id TEXT PRIMARY KEY,title TEXT)");
      db.execSQL(
          "INSERT INTO points"
              + " VALUES(1,'route',45,6,NULL,0,0,0),(2,'route',45.1,6.1,NULL,0,0,0),(3,'trek-old',46,7,NULL,1000,5,0)");
      db.setVersion(1);
    }
    try (Store store = new Store(context)) {
      assertEquals(1, store.routes().size());
      assertEquals(RoutingPreferences.read(store.prefs), store.routePreferences("route-legacy"));
      assertEquals(2, store.read("route-legacy").size());
      assertEquals(2, store.read("route").size());
      assertEquals(1, store.read("trek-old").size());
      assertEquals("route-legacy", store.prefs.getString("activeRouteId", ""));
    }
  }
}
