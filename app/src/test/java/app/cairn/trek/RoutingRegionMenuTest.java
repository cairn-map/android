package app.cairn.trek;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;

public class RoutingRegionMenuTest {
  @Test public void mapCenterIsDefaultWithoutPlanningOrRoute() {
    assertTrue(RoutingRegionMenu.usesMapCenter(false, false, false));
    assertTrue(RoutingRegionMenu.usesMapCenter(true, true, true));
    assertFalse(RoutingRegionMenu.usesMapCenter(false, true, false));
    assertFalse(RoutingRegionMenu.usesMapCenter(false, false, true));
    assertFalse(RoutingRegionMenu.usesMapCenter(false, true, true));
  }

  @Test public void mapCenterDoesNotOfferRedundantSwitch() {
    List<RoutingRegionMenu.Item> items =
        RoutingRegionMenu.items(true, new String[] {"E0_N45.rd5"});
    assertEquals(2, items.size());
    assertEquals("Discard paused downloads", items.get(0).label);
    assertEquals(RoutingRegionMenu.Action.DISCARD_PAUSED, items.get(0).action);
    assertEquals(RoutingRegionMenu.Action.REMOVE_REGION, items.get(1).action);
    assertEquals("E0_N45.rd5", items.get(1).region);
  }

  @Test public void routeModeOffersSwitchAndKeepsActionsDistinct() {
    List<RoutingRegionMenu.Item> items =
        RoutingRegionMenu.items(false, new String[] {"E0_N45.rd5", "E5_N45.rd5"});
    assertEquals(4, items.size());
    assertEquals("Switch to map center", items.get(0).label);
    assertEquals(RoutingRegionMenu.Action.USE_MAP_CENTER, items.get(0).action);
    assertEquals(RoutingRegionMenu.Action.DISCARD_PAUSED, items.get(1).action);
    assertEquals(RoutingRegionMenu.Action.REMOVE_REGION, items.get(2).action);
    assertEquals("E0_N45.rd5", items.get(2).region);
    assertEquals("E5_N45.rd5", items.get(3).region);
  }

  @Test public void nullDownloadsStillShowsOnlyAvailableActions() {
    assertEquals(1, RoutingRegionMenu.items(true, null).size());
    assertEquals(2, RoutingRegionMenu.items(false, null).size());
  }
}
