package app.cairn.trek;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class PlanIgnStore extends SQLiteOpenHelper {
  public static final class Cached {
    public final byte[] bytes;
    public final String etag, modified;

    Cached(byte[] bytes, String etag, String modified) {
      this.bytes = bytes;
      this.etag = etag;
      this.modified = modified;
    }
  }

  public static final class Pack {
    public final String id, name, state, error, routeId;
    public final int done, total;
    public final long checked, bytes;
    public final Point center;

    Pack(
        String id,
        String name,
        String state,
        String error,
        int done,
        int total,
        long checked,
        long bytes,
        Point center,
        String routeId) {
      this.id = id;
      this.name = name;
      this.state = state;
      this.error = error;
      this.done = done;
      this.total = total;
      this.checked = checked;
      this.bytes = bytes;
      this.center = center;
      this.routeId = routeId;
    }

    public String description() {
      return done
          + " / "
          + total
          + " tiles · "
          + ("complete".equals(state) ? "Ready offline" : state)
          + ("complete".equals(state)
              ? " · checked "
                  + java.time.Instant.ofEpochMilli(checked)
                      .atZone(java.time.ZoneId.systemDefault())
                      .toLocalDate()
              : "");
    }
  }

  public PlanIgnStore(Context context) {
    super(context, "plan-ign.mbtiles", null, 2);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT)");
    db.execSQL(
        "INSERT INTO metadata VALUES ('name','Plan"
            + " IGN'),('format','png'),('scheme','xyz'),('attribution','© IGN · Plan IGN · Licence"
            + " Ouverte 2.0'),('minzoom','0'),('maxzoom','19')");
    db.execSQL(
        "CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB"
            + " NOT NULL,etag TEXT,modified TEXT,checked INTEGER NOT NULL,PRIMARY"
            + " KEY(zoom_level,tile_column,tile_row))");
    db.execSQL(
        "CREATE TABLE packs(id TEXT PRIMARY KEY,name TEXT NOT NULL,state TEXT NOT NULL,error"
            + " TEXT,checked INTEGER NOT NULL,lat REAL,lon REAL,route_id TEXT)");
    db.execSQL("CREATE INDEX corridor_routes ON packs(route_id)");
    db.execSQL(
        "CREATE TABLE pack_tiles(pack TEXT,z INTEGER,x INTEGER,y INTEGER,done INTEGER NOT NULL"
            + " DEFAULT 0,PRIMARY KEY(pack,z,x,y))");
    db.execSQL("CREATE INDEX tile_references ON pack_tiles(z,x,y)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from == 1 && to == 2) {
      db.execSQL("ALTER TABLE packs ADD COLUMN route_id TEXT");
      db.execSQL("CREATE INDEX corridor_routes ON packs(route_id)");
    } else throw new IllegalStateException("Unsupported map database version");
  }

  public Cached cached(TilePlanner.Tile t) {
    return cached(t, true);
  }

  /** Conditional requests need validators only, not another copy of the saved PNG. */
  public Cached validators(TilePlanner.Tile t) {
    return cached(t, false);
  }

  private Cached cached(TilePlanner.Tile t, boolean bytes) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT "
                    + (bytes ? "tile_data" : "NULL")
                    + ",etag,modified FROM tiles WHERE zoom_level=? AND tile_column=? AND"
                    + " tile_row=?",
                args(t))) {
      return c.moveToFirst() ? new Cached(c.getBlob(0), c.getString(1), c.getString(2)) : null;
    }
  }

  public android.graphics.Bitmap detail(
      TilePlanner.Tile tile,
      android.graphics.Bitmap base,
      java.util.function.BooleanSupplier obsolete) {
    return TilePyramid.detail(getReadableDatabase(), tile.z, tile.x, tile.y, true, base, obsolete);
  }

  private static String[] args(TilePlanner.Tile t) {
    return new String[] {"" + t.z, "" + t.x, "" + t.y};
  }

  public void save(TilePlanner.Tile t, IgnClient.Result result) {
    SQLiteDatabase db = getWritableDatabase();
    ContentValues v = new ContentValues();
    v.put("checked", System.currentTimeMillis());
    if (result.bytes == null) {
      if (db.update("tiles", v, "zoom_level=? AND tile_column=? AND tile_row=?", args(t)) != 1)
        throw new IllegalStateException("Missing cached tile for HTTP 304");
      return;
    }
    v.put("zoom_level", t.z);
    v.put("tile_column", t.x);
    v.put("tile_row", t.y);
    v.put("tile_data", result.bytes);
    v.put("etag", result.etag);
    v.put("modified", result.modified);
    if (db.insertWithOnConflict("tiles", null, v, SQLiteDatabase.CONFLICT_REPLACE) < 0)
      throw new SQLiteException("Could not store map tile. Check free space.");
  }

  public String create(String name, TilePlanner.Plan plan) {
    return create(name, plan, null);
  }

  public String create(String name, TilePlanner.Plan plan, String routeId) {
    String id = UUID.randomUUID().toString();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues p = new ContentValues();
      p.put("id", id);
      p.put("name", name);
      p.put("state", "paused");
      p.put("checked", 0);
      p.put("lat", plan.center.lat);
      p.put("lon", plan.center.lon);
      p.put("route_id", routeId);
      db.insertOrThrow("packs", null, p);
      try (SQLiteStatement insert =
          db.compileStatement("INSERT INTO pack_tiles(pack,z,x,y,done) VALUES(?,?,?,?,0)")) {
        for (TilePlanner.Tile tile : plan.tiles) {
          insert.bindString(1, id);
          insert.bindLong(2, tile.z);
          insert.bindLong(3, tile.x);
          insert.bindLong(4, tile.y);
          insert.executeInsert();
        }
      }
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public TilePlanner.Tile next(String id) {
    List<TilePlanner.Tile> tiles = next(id, 1);
    return tiles.isEmpty() ? null : tiles.get(0);
  }

  public List<TilePlanner.Tile> next(String id, int limit) {
    List<TilePlanner.Tile> tiles = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT z,x,y FROM pack_tiles WHERE pack=? AND done=0 ORDER BY z DESC,y,x LIMIT ?",
                new String[] {id, "" + limit})) {
      while (c.moveToNext()) tiles.add(new TilePlanner.Tile(c.getInt(0), c.getInt(1), c.getInt(2)));
    }
    return tiles;
  }

  public void completeTile(String id, TilePlanner.Tile tile, IgnClient.Result result) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      save(tile, result);
      ContentValues v = new ContentValues();
      v.put("done", 1);
      db.update(
          "pack_tiles",
          v,
          "pack=? AND z=? AND x=? AND y=?",
          new String[] {id, "" + tile.z, "" + tile.x, "" + tile.y});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void state(String id, String state, String error) {
    ContentValues v = new ContentValues();
    v.put("state", state);
    v.put("error", error);
    getWritableDatabase().update("packs", v, "id=?", new String[] {id});
  }

  public boolean finish(String id) {
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor c =
        db.rawQuery(
            "SELECT COUNT(*) FROM pack_tiles p LEFT JOIN tiles t ON p.z=t.zoom_level AND"
                + " p.x=t.tile_column AND p.y=t.tile_row WHERE p.pack=? AND (p.done=0 OR"
                + " t.tile_data IS NULL)",
            new String[] {id})) {
      c.moveToFirst();
      if (c.getInt(0) != 0) return false;
    }
    ContentValues v = new ContentValues();
    v.put("state", "complete");
    v.putNull("error");
    v.put("checked", System.currentTimeMillis());
    db.update("packs", v, "id=?", new String[] {id});
    return true;
  }

  public void refresh(String id) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("done", 0);
      db.update("pack_tiles", v, "pack=?", new String[] {id});
      state(id, "paused", null);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void remove(String id) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.execSQL(
          "DELETE FROM tiles WHERE EXISTS(SELECT 1 FROM pack_tiles p WHERE p.pack=? AND"
              + " p.z=tiles.zoom_level AND p.x=tiles.tile_column AND p.y=tiles.tile_row) AND NOT"
              + " EXISTS(SELECT 1 FROM pack_tiles q WHERE q.pack<>? AND q.z=tiles.zoom_level AND"
              + " q.x=tiles.tile_column AND q.y=tiles.tile_row)",
          new String[] {id, id});
      db.delete("pack_tiles", "pack=?", new String[] {id});
      db.delete("packs", "id=?", new String[] {id});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void clearViewed() {
    getWritableDatabase()
        .execSQL(
            "DELETE FROM tiles WHERE NOT EXISTS(SELECT 1 FROM pack_tiles p WHERE"
                + " p.z=tiles.zoom_level AND p.x=tiles.tile_column AND p.y=tiles.tile_row)");
  }

  public List<Pack> packs() {
    List<Pack> result = new ArrayList<>();
    SQLiteDatabase db = getReadableDatabase();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,name,state,error,checked,lat,lon,route_id FROM packs ORDER BY rowid DESC",
            null)) {
      while (c.moveToNext()) {
        String id = c.getString(0);
        int total = 0, done = 0;
        long bytes = 0;
        try (Cursor counts =
            db.rawQuery(
                "SELECT COUNT(*),COALESCE(SUM(CASE WHEN p.done=1 AND t.tile_data IS NOT NULL THEN 1"
                    + " ELSE 0 END),0),COALESCE(SUM(LENGTH(t.tile_data)),0) FROM pack_tiles p LEFT"
                    + " JOIN tiles t ON p.z=t.zoom_level AND p.x=t.tile_column AND p.y=t.tile_row"
                    + " WHERE p.pack=?",
                new String[] {id})) {
          counts.moveToFirst();
          total = counts.getInt(0);
          done = counts.getInt(1);
          bytes = counts.getLong(2);
        }
        String state = c.getString(2);
        if ("complete".equals(state) && done != total) state = "incomplete";
        if ("downloading".equals(state) && !DownloadService.running) state = "paused";
        result.add(
            new Pack(
                id,
                c.getString(1),
                state,
                c.getString(3),
                done,
                total,
                c.getLong(4),
                bytes,
                new Point(c.getDouble(5), c.getDouble(6)),
                c.getString(7)));
      }
    }
    return result;
  }

  /** Older versions named corridors after their route. Ambiguous names stay in Saved maps. */
  public void linkLegacyCorridors(List<Store.LibraryEntry> entries) {
    Map<String, List<String>> routes = new HashMap<>();
    for (Store.LibraryEntry entry : entries)
      routes.computeIfAbsent(entry.title + " · corridor", key -> new ArrayList<>()).add(entry.id);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (Map.Entry<String, List<String>> route : routes.entrySet()) {
        if (route.getValue().size() != 1) continue;
        ContentValues value = new ContentValues();
        value.put("route_id", route.getValue().get(0));
        db.update("packs", value, "route_id IS NULL AND name=?", new String[] {route.getKey()});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void detachRoute(String routeId) {
    ContentValues value = new ContentValues();
    value.put("route_id", "");
    getWritableDatabase().update("packs", value, "route_id=?", new String[] {routeId});
  }
}
