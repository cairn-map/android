package app.cairn.trek;

import android.content.Context;
import android.graphics.*;

public final class IgnMap implements TileSource, AutoCloseable {
  private final PlanIgnStore store;
  private final IgnClient client = new IgnClient();
  private volatile boolean online = false, closed = false;
  private volatile String lastError;
  private volatile int generation;

  public IgnMap(Context context) {
    store = new PlanIgnStore(context);
  }

  public void online(boolean value) {
    online = value;
    lastError = null;
    if (!value) client.cancel();
  }

  public boolean online() {
    return online;
  }

  public String lastError() {
    return lastError;
  }

  public Bitmap tile(int z, int x, int y) {
    return tile(z, x, y, () -> false);
  }

  @Override
  public Bitmap tile(int z, int x, int y, java.util.function.BooleanSupplier obsolete) {
    if (obsolete.getAsBoolean()) return null;
    int request = generation;
    TilePlanner.Tile tile = new TilePlanner.Tile(z, x, y);
    PlanIgnStore.Cached cached;
    synchronized (this) {
      if (closed) return null;
      cached = store.cached(tile);
    }
    if (cached == null && online && !TrackingService.running) {
      try {
        IgnClient.Result result =
            client.fetch(
                tile,
                null,
                null,
                () ->
                    obsolete.getAsBoolean()
                        || request != generation
                        || !online
                        || closed
                        || TrackingService.running);
        synchronized (this) {
          if (closed) return null;
          store.save(tile, result);
          cached = store.cached(tile);
        }
        lastError = null;
      } catch (java.io.IOException | RuntimeException failure) {
        if (online && request == generation) lastError = failure.getMessage();
      }
    }
    if (cached == null) return online ? null : enlargedParent(tile);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.RGB_565;
    return BitmapFactory.decodeByteArray(cached.bytes, 0, cached.bytes.length, options);
  }

  @Override
  public void cancelRequests() {
    generation++;
    client.cancel();
  }

  /** Preserve usable geographic context when zoomed beyond saved detail. */
  private Bitmap enlargedParent(TilePlanner.Tile tile) {
    for (int delta = 1; delta <= 4 && tile.z - delta >= 0; delta++) {
      PlanIgnStore.Cached cached;
      synchronized (this) {
        if (closed) return null;
        cached =
            store.cached(new TilePlanner.Tile(tile.z - delta, tile.x >> delta, tile.y >> delta));
      }
      if (cached == null) continue;
      Bitmap parent = BitmapFactory.decodeByteArray(cached.bytes, 0, cached.bytes.length);
      if (parent == null) continue;
      int subdivisions = 1 << delta, size = parent.getWidth() / subdivisions;
      int left = (tile.x & (subdivisions - 1)) * size, top = (tile.y & (subdivisions - 1)) * size;
      Bitmap result = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565);
      Canvas canvas = new Canvas(result);
      Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
      canvas.drawBitmap(
          parent, new Rect(left, top, left + size, top + size), new Rect(0, 0, 256, 256), filter);
      parent.recycle();
      return result;
    }
    return null;
  }

  public int minZoom() {
    return 0;
  }

  public int maxZoom() {
    return 19;
  }

  public double centerLat() {
    return 45.9237;
  }

  public double centerLon() {
    return 6.8694;
  }

  public String attribution() {
    return "© IGN";
  }

  public synchronized void close() {
    closed = true;
    online(false);
    store.close();
  }
}
