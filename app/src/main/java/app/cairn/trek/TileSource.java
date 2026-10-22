package app.cairn.trek;

import android.graphics.Bitmap;

public interface TileSource {
  Bitmap tile(int z, int x, int y);

  default Bitmap tile(int z, int x, int y, java.util.function.BooleanSupplier obsolete) {
    return obsolete.getAsBoolean() ? null : tile(z, x, y);
  }

  default void cancelRequests() {}

  default void cancelObsoleteRequests() {}

  int minZoom();

  int maxZoom();

  double centerLat();

  double centerLon();

  String attribution();
}
