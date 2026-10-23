package app.cairn.trek;

import java.util.*;

/** Recover a manageable set of editable anchors for imported routes without saved nodes. */
final class RouteNodes {
  static List<Point> fromGeometry(List<Point> points) {
    if (points.size() < 3) return new ArrayList<>(points);
    TreeSet<Integer> indices = new TreeSet<>(Arrays.asList(0, points.size() - 1));
    while (indices.size() < 64) {
      double farthest = 75;
      int candidate = -1, start = 0;
      for (int end : indices) {
        for (int i = start + 1; i < end; i++) {
          double distance = Geo.segmentDistance(points.get(i), points.get(start), points.get(end));
          if (distance > farthest) {
            farthest = distance;
            candidate = i;
          }
        }
        start = end;
      }
      if (candidate < 0) break;
      indices.add(candidate);
    }
    List<Point> nodes = new ArrayList<>();
    for (int index : indices) nodes.add(points.get(index));
    return nodes;
  }
}
