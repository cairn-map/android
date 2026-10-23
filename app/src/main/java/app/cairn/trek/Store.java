package app.cairn.trek;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class Store extends SQLiteOpenHelper {
  public final SharedPreferences prefs;

  public Store(Context c) {
    super(c, "treks.db", null, 2);
    prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE points (id INTEGER PRIMARY KEY, collection TEXT NOT NULL, lat REAL, lon REAL,"
            + " elevation REAL, time INTEGER, accuracy REAL, segment INTEGER)");
    db.execSQL("CREATE INDEX collection_points ON points(collection,id)");
    db.execSQL("CREATE TABLE treks (id TEXT PRIMARY KEY, title TEXT NOT NULL)");
    createRoutes(db);
  }

  private void createRoutes(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE routes(id TEXT PRIMARY KEY,title TEXT NOT NULL,visible INTEGER NOT NULL"
            + " DEFAULT 1,created INTEGER NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    if (oldV == 1 && newV == 2) {
      createRoutes(db);
      db.execSQL(
          "INSERT INTO routes SELECT 'route-legacy','Imported route',1,0 WHERE EXISTS(SELECT 1 FROM"
              + " points WHERE collection='route')");
      db.execSQL(
          "INSERT INTO points(collection,lat,lon,elevation,time,accuracy,segment) SELECT"
              + " 'route-legacy',lat,lon,elevation,time,accuracy,segment FROM points WHERE"
              + " collection='route'");
      prefs.edit().putString("activeRouteId", "route-legacy").apply();
    } else throw new IllegalStateException("Unsupported database version");
  }

  public static final class RouteEntry {
    public final String id, title;
    public final boolean visible;

    RouteEntry(String id, String title, boolean visible) {
      this.id = id;
      this.title = title;
      this.visible = visible;
    }
  }

  public List<RouteEntry> routes() {
    List<RouteEntry> result = new ArrayList<>();
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,title,visible FROM routes ORDER BY created DESC,rowid DESC", null)) {
      while (cursor.moveToNext())
        result.add(new RouteEntry(cursor.getString(0), cursor.getString(1), cursor.getInt(2) != 0));
    }
    return result;
  }

  public static final class LibraryEntry {
    public final String id, title;
    public final boolean recording, visible;

    LibraryEntry(String id, String title, boolean recording, boolean visible) {
      this.id = id;
      this.title = title;
      this.recording = recording;
      this.visible = visible;
    }
  }

  public List<LibraryEntry> library() {
    List<LibraryEntry> entries = new ArrayList<>();
    for (RouteEntry route : routes())
      entries.add(new LibraryEntry(route.id, route.title, false, route.visible));
    for (Map.Entry<String, String> trek : treks().entrySet())
      entries.add(
          new LibraryEntry(
              trek.getKey(),
              trek.getValue(),
              true,
              !prefs.getBoolean("hiddenTrek:" + trek.getKey(), false)));
    return entries;
  }

  public String selectedLibraryId() {
    return prefs.getString("selectedLibraryId", prefs.getString("activeRouteId", ""));
  }

  public List<Point> select(LibraryEntry entry) {
    List<Point> points = entry.recording ? read(entry.id) : activateRoute(entry.id);
    if (points.isEmpty()) throw new IllegalArgumentException("This item has no points yet");
    libraryVisible(entry, true);
    prefs.edit().putString("selectedLibraryId", entry.id).apply();
    return points;
  }

  public void libraryVisible(LibraryEntry entry, boolean visible) {
    if (entry.recording) prefs.edit().putBoolean("hiddenTrek:" + entry.id, !visible).apply();
    else routeVisible(entry.id, visible);
    if (!visible && entry.id.equals(selectedLibraryId()))
      prefs.edit().putString("selectedLibraryId", "").apply();
  }

  private void clearLibrarySelection(String id) {
    if (id.equals(selectedLibraryId())) prefs.edit().putString("selectedLibraryId", "").apply();
  }

  public String saveRoute(List<Point> points, String title) {
    String id = "route-" + UUID.randomUUID();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues value = new ContentValues();
      value.put("id", id);
      value.put("title", title);
      value.put("visible", 1);
      value.put("created", System.currentTimeMillis());
      db.insertOrThrow("routes", null, value);
      for (Point point : points) add(id, point);
      replaceRoute(points);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    prefs
        .edit()
        .putString("activeRouteId", id)
        .putString("selectedLibraryId", id)
        .putBoolean("follow", false)
        .apply();
    return id;
  }

  public List<Point> activateRoute(String id) {
    List<Point> points = read(id);
    if (points.isEmpty()) throw new IllegalArgumentException("This route has no points");
    replaceRoute(points);
    prefs
        .edit()
        .putString("activeRouteId", id)
        .putString("selectedLibraryId", id)
        .putBoolean("follow", true)
        .apply();
    return points;
  }

  public void routeVisible(String id, boolean visible) {
    ContentValues value = new ContentValues();
    value.put("visible", visible ? 1 : 0);
    getWritableDatabase().update("routes", value, "id=?", new String[] {id});
  }

  public void renameRoute(String id, String title) {
    ContentValues value = new ContentValues();
    value.put("title", title);
    getWritableDatabase().update("routes", value, "id=?", new String[] {id});
  }

  public void deleteRoute(String id) {
    SQLiteDatabase db = getWritableDatabase();
    boolean active = id.equals(prefs.getString("activeRouteId", ""));
    db.beginTransaction();
    try {
      db.delete("points", "collection=?", new String[] {id});
      db.delete("routes", "id=?", new String[] {id});
      if (active) db.delete("points", "collection='route'", null);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    clearLibrarySelection(id);
    if (active) prefs.edit().remove("activeRouteId").putBoolean("follow", false).apply();
  }

  public void add(String collection, Point p) {
    ContentValues v = new ContentValues();
    v.put("collection", collection);
    v.put("lat", p.lat);
    v.put("lon", p.lon);
    if (Double.isFinite(p.elevation)) v.put("elevation", p.elevation);
    v.put("time", p.time);
    v.put("accuracy", p.accuracy);
    v.put("segment", p.segment);
    getWritableDatabase().insertOrThrow("points", null, v);
  }

  public void replaceRoute(List<Point> points) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("points", "collection=?", new String[] {"route"});
      for (Point p : points) add("route", p);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public List<Point> read(String collection) {
    List<Point> result = new ArrayList<>();
    if (collection == null) return result;
    try (Cursor c =
        getReadableDatabase()
            .query(
                "points",
                new String[] {"lat", "lon", "elevation", "time", "accuracy", "segment"},
                "collection=?",
                new String[] {collection},
                null,
                null,
                "id")) {
      while (c.moveToNext())
        result.add(
            new Point(
                c.getDouble(0),
                c.getDouble(1),
                c.isNull(2) ? Double.NaN : c.getDouble(2),
                c.getLong(3),
                c.getFloat(4),
                c.getInt(5)));
    }
    return result;
  }

  public String newTrek() {
    String id = "trek-" + System.currentTimeMillis();
    ContentValues values = new ContentValues();
    values.put("id", id);
    values.put("title", "Hike " + java.time.LocalDateTime.now().withNano(0));
    getWritableDatabase().insertOrThrow("treks", null, values);
    prefs.edit().putString("currentTrek", id).apply();
    return id;
  }

  public LinkedHashMap<String, String> treks() {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id,title FROM treks ORDER BY id DESC", null)) {
      while (c.moveToNext()) result.put(c.getString(0), c.getString(1));
    }
    return result;
  }

  public void deleteTrek(String id) {
    if (TrackingService.running && id.equals(prefs.getString("currentTrek", "")))
      throw new IllegalStateException("Finish recording before removing this hike.");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      // Only remove collections belonging to a recorded hike.
      if (db.delete("treks", "id=?", new String[] {id}) > 0)
        db.delete("points", "collection=?", new String[] {id});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    clearLibrarySelection(id);
    prefs.edit().remove("hiddenTrek:" + id).apply();
    if (id.equals(prefs.getString("currentTrek", ""))) prefs.edit().remove("currentTrek").apply();
  }

  public long intervalMillis() {
    return Math.max(1, Math.min(3600, prefs.getInt("intervalSeconds", 20))) * 1000L;
  }
}
