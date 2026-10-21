package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TileViewportTest {
  @Test
  public void requestsOnlyIntersectingTilesAndStartsAtTheCenter() {
    List<TilePlanner.Tile> tiles = TileViewport.ordered(4, 8.5, 7.5, 700, 1100, 256);
    assertEquals(new TilePlanner.Tile(4, 8, 7), tiles.get(0));
    double previous = -1;
    for (TilePlanner.Tile tile : tiles) {
      assertTrue(tile.x < 8.5 + 350.0 / 256 && tile.x + 1 > 8.5 - 350.0 / 256);
      assertTrue(tile.y < 7.5 + 550.0 / 256 && tile.y + 1 > 7.5 - 550.0 / 256);
      double distance = Math.pow(tile.x + 0.5 - 8.5, 2) + Math.pow(tile.y + 0.5 - 7.5, 2);
      assertTrue(distance >= previous);
      previous = distance;
    }
  }

  @Test
  public void exactScreenEdgesDoNotAddAnInvisibleRowOrColumn() {
    assertEquals(4, TileViewport.ordered(4, 8, 8, 512, 512, 256).size());
  }

  @Test
  public void wrapsVisibleTilesAcrossTheDateLine() {
    List<TilePlanner.Tile> tiles = TileViewport.ordered(3, 0, 4, 512, 512, 256);
    assertEquals(4, tiles.size());
    assertTrue(tiles.stream().allMatch(tile -> tile.x == 0 || tile.x == 7));
  }
}
