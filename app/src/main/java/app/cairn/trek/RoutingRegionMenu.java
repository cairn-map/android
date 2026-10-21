package app.cairn.trek;

import java.util.ArrayList;
import java.util.List;

/** Labels and actions for the routing-region dialog, independent of list positions. */
final class RoutingRegionMenu {
  enum Action { USE_MAP_CENTER, DISCARD_PAUSED, REMOVE_REGION }

  static final class Item {
    final String label;
    final Action action;
    final String region;

    Item(String label, Action action, String region) {
      this.label = label;
      this.action = action;
      this.region = region;
    }
  }

  private RoutingRegionMenu() {}

  static boolean usesMapCenter(boolean aroundMap, boolean hasPlanningPoints, boolean hasRoute) {
    return aroundMap || (!hasPlanningPoints && !hasRoute);
  }

  static List<Item> items(boolean usingMapCenter, String[] downloaded) {
    List<Item> items = new ArrayList<>();
    if (!usingMapCenter)
      items.add(new Item("Switch to map center", Action.USE_MAP_CENTER, null));
    items.add(new Item("Discard paused downloads", Action.DISCARD_PAUSED, null));
    if (downloaded != null)
      for (String name : downloaded)
        items.add(new Item("Remove " + name, Action.REMOVE_REGION, name));
    return items;
  }
}
