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
  public void multipleFilesAndSelectionSurviveReopeningWithoutOverwritingEachOther() {
    String a, b;
    try (Store store = new Store(context)) {
      a = store.saveRoute(points(45), "First GPX");
      b = store.saveRoute(points(46), "Second GPX");
      assertEquals(2, store.routes().size());
      store.activateRoute(a);
      store.routeVisible(b, false);
      store.renameRoute(a, "Weekend");
    }
    try (Store store = new Store(context)) {
      assertEquals(2, store.routes().size());
      assertEquals(45, store.read("route").get(0).lat, 0);
      assertEquals(46, store.read(b).get(0).lat, 0);
      assertEquals(a, store.prefs.getString("activeRouteId", ""));
      assertFalse(store.routes().get(0).visible);
      assertEquals("Weekend", store.routes().get(1).title);
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
      assertEquals(2, store.read("route-legacy").size());
      assertEquals(2, store.read("route").size());
      assertEquals(1, store.read("trek-old").size());
      assertEquals("route-legacy", store.prefs.getString("activeRouteId", ""));
    }
  }
}
