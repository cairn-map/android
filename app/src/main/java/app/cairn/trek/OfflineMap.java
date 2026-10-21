package app.cairn.trek;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import java.io.File;

public final class OfflineMap implements TileSource, AutoCloseable {
  private SQLiteDatabase database;
  public final int minZoom, maxZoom;
  public final String attribution;
  public final double centerLat, centerLon;
  private final boolean xyz;

  public OfflineMap(File file) {
    database =
        SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try {
      try (Cursor c = database.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)) {
        if (!c.moveToFirst()) throw new IllegalArgumentException("This map contains no tiles");
        byte[] sample = c.getBlob(0);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(sample, 0, sample.length, options);
        if (options.outWidth <= 0 || options.outWidth > 2048 || options.outHeight > 2048)
          throw new IllegalArgumentException(
              "Use raster MBTiles with PNG/JPEG tiles up to 2048 px");
      }
      int low = 0, high = 18;
      try (Cursor c =
          database.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null)) {
        if (c.moveToFirst()) {
          low = c.getInt(0);
          high = c.getInt(1);
        }
      }
      minZoom = Math.max(0, Math.min(19, low));
      maxZoom = Math.max(minZoom, Math.min(19, high));
      xyz = "xyz".equalsIgnoreCase(metadata("scheme", "tms"));
      attribution = metadata("attribution", metadata("name", "Imported offline map"));
      double lat = 45.9237, lon = 6.8694;
      try (Cursor c =
          database.rawQuery(
              "SELECT tile_column,tile_row FROM tiles WHERE zoom_level=? LIMIT 1",
              new String[] {"" + minZoom})) {
        if (c.moveToFirst()) {
          lon = Geo.lon(c.getInt(0) + 0.5, minZoom);
          int row = xyz ? c.getInt(1) : (1 << minZoom) - 1 - c.getInt(1);
          lat = Geo.lat(row + 0.5, minZoom);
        }
      }
      String[] bounds = metadata("bounds", "").split(",");
      if (bounds.length == 4) {
        lon = (Double.parseDouble(bounds[0]) + Double.parseDouble(bounds[2])) / 2;
        lat = (Double.parseDouble(bounds[1]) + Double.parseDouble(bounds[3])) / 2;
      }
      centerLat = Double.isFinite(lat) && Math.abs(lat) <= 85 ? lat : 45.9237;
      centerLon = Double.isFinite(lon) && Math.abs(lon) <= 180 ? lon : 6.8694;
    } catch (RuntimeException e) {
      database.close();
      database = null;
      throw e;
    }
  }

  private String metadata(String name, String fallback) {
    try (Cursor c =
        database.rawQuery("SELECT value FROM metadata WHERE name=? LIMIT 1", new String[] {name})) {
      return c.moveToFirst() ? c.getString(0) : fallback;
    } catch (android.database.SQLException e) {
      return fallback;
    }
  }

  public synchronized Bitmap tile(int z, int x, int y) {
    if (database == null) return null;
    int row = xyz ? y : (1 << z) - 1 - y;
    try (Cursor c =
        database.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT"
                + " 1",
            new String[] {"" + z, "" + x, "" + row})) {
      if (!c.moveToFirst()) return null;
      byte[] bytes = c.getBlob(0);
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.RGB_565;
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
  }

  @Override
  public synchronized void close() {
    if (database != null) {
      database.close();
      database = null;
    }
  }

  public int minZoom() {
    return minZoom;
  }

  public int maxZoom() {
    return maxZoom;
  }

  public double centerLat() {
    return centerLat;
  }

  public double centerLon() {
    return centerLon;
  }

  public String attribution() {
    return attribution;
  }
}
