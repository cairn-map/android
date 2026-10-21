package app.cairn.trek;

import java.util.*;

/** Current visible tiles only, ordered from the screen center outwards. */
final class TileViewport {
  static List<TilePlanner.Tile> ordered(
      int z, double cx, double cy, int width, int height, double size) {
    List<TilePlanner.Tile> result = new ArrayList<>();
    int n = 1 << z;
    int left = (int) Math.floor(cx - width / 2.0 / size),
        right = (int) Math.ceil(cx + width / 2.0 / size) - 1;
    int top = (int) Math.floor(cy - height / 2.0 / size),
        bottom = (int) Math.ceil(cy + height / 2.0 / size) - 1;
    for (int x = left; x <= right; x++)
      for (int y = Math.max(0, top); y <= Math.min(n - 1, bottom); y++)
        result.add(new TilePlanner.Tile(z, Math.floorMod(x, n), y));
    result.sort(
        Comparator.comparingDouble(
            tile -> {
              double dx = tile.x + 0.5 - cx;
              dx -= Math.rint(dx / n) * n;
              double dy = tile.y + 0.5 - cy;
              return dx * dx + dy * dy;
            }));
    return result;
  }
}
