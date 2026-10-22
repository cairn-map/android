package app.cairn.trek;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import java.util.function.BooleanSupplier;

/** Compose saved finer tiles over a base tile without fetching another zoom level. */
final class TilePyramid {
  static Bitmap detail(
      SQLiteDatabase db, int z, int x, int y, boolean xyz, Bitmap base,
      BooleanSupplier obsolete) {
    Bitmap result = null;
    Canvas canvas = null;
    Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    // Bound decoding and bitmap memory even when a wide viewport overlaps a large pack.
    int remaining = 64;
    for (int level = z + 1; level <= Math.min(19, z + 8) && remaining > 0; level++) {
      if (obsolete.getAsBoolean()) break;
      int factor = 1 << (level - z), left = x * factor, top = y * factor;
      int row0 = xyz ? top : (1 << level) - top - factor;
      try (Cursor c = db.rawQuery(
          "SELECT tile_column,tile_row,tile_data FROM tiles WHERE zoom_level=?"
              + " AND tile_column>=? AND tile_column<? AND tile_row>=? AND tile_row<?"
              + " ORDER BY tile_column,tile_row LIMIT ?",
          new String[] {"" + level, "" + left, "" + (left + factor), "" + row0,
              "" + (row0 + factor), "" + remaining})) {
        while (!obsolete.getAsBoolean() && c.moveToNext()) {
          remaining--;
          byte[] bytes = c.getBlob(2);
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inPreferredConfig = Bitmap.Config.RGB_565;
          options.inSampleSize = Math.max(1, factor / 2);
          Bitmap child = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
          if (child == null) continue;
          if (result == null) {
            result = Bitmap.createBitmap(512, 512,
                base == null ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            canvas = new Canvas(result);
            if (base != null)
              canvas.drawBitmap(base, null, new Rect(0, 0, 512, 512), filter);
          }
          int childY = xyz ? c.getInt(1) : (1 << level) - 1 - c.getInt(1);
          float size = 512f / factor;
          float dx = (c.getInt(0) - left) * size, dy = (childY - top) * size;
          canvas.drawBitmap(child, null, new RectF(dx, dy, dx + size, dy + size), filter);
          child.recycle();
        }
      }
    }
    if (result == null) return base;
    if (base != null) base.recycle();
    return result;
  }
}
