package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecordingElevationTest {
  private Point point(double meters, double elevation, int seconds, float verticalAccuracy) {
    return new Point(
        45 + meters / 111195, 6, elevation, 1000000L + seconds * 1000L, 3, 0, verticalAccuracy);
  }

  @Test
  public void startupDropAndStationaryDriftLeaveGapsWithoutChangingRawPoints() {
    List<Point> points =
        List.of(
            point(0, 229, 0, 3),
            point(10, 229, 5, 3),
            point(11, 216, 6, 3),
            point(80, 207, 60, 3),
            point(90, 207, 65, 3),
            point(90.1, 202, 70, 3),
            point(100, 202, 75, 3),
            point(110, 202, 80, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(0, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertEquals(Geo.length(points), summary.distance, .001);
    assertTrue(summary.partialElevation);
    assertTrue(summary.text().contains("GPS estimate"));
    ElevationProfile profile = new ElevationProfile(true);
    for (Point point : points) profile.add(point);
    assertTrue(Double.isNaN(profile.points.get(0).elevation));
    assertTrue(Double.isNaN(profile.points.get(5).elevation));
    assertEquals(229, points.get(0).elevation, 0);
    assertEquals(202, points.get(5).elevation, 0);
  }

  @Test
  public void smallNoiseIsIgnoredButSlowSustainedClimbingAndDescendingStillCount() {
    List<Point> points = new ArrayList<>();
    points.add(point(0, 100, 0, 3));
    for (int i = 0; i <= 30; i++) points.add(point(100 + i * 10, 100 + i, 60 + i * 5, 3));
    for (int i = 1; i <= 20; i++) points.add(point(400 + i * 10, 130 - i, 210 + i * 5, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(30, summary.ascent, 0);
    assertEquals(20, summary.descent, 0);
    RouteSummary live = new RouteSummary(true);
    for (Point point : points) live.add(point);
    assertEquals(summary.ascent, live.ascent, 0);
    assertEquals(summary.descent, live.descent, 0);

    List<Point> noise = new ArrayList<>();
    noise.add(point(0, 100, 0, Float.NaN));
    for (int i = 0; i < 50; i++)
      noise.add(point(100 + i * 10, 100 + (i % 3), 60 + i * 5, Float.NaN));
    RouteSummary flat = RouteSummary.from(noise, true);
    assertEquals(0, flat.ascent, 0);
    assertEquals(0, flat.descent, 0);
  }

  @Test
  public void poorVerticalAccuracyAndAbruptJumpsAreNotBridged() {
    List<Point> points =
        List.of(
            point(0, 100, 0, 3),
            point(100, 100, 60, 3),
            point(110, 100, 65, 3),
            point(120, 130, 70, 40),
            point(130, 100, 75, 3),
            point(140, 100, 80, 3),
            point(141.2, 91.3, 81, 3),
            point(151.2, 91.3, 86, 3),
            point(161.2, 91.3, 91, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(0, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertTrue(summary.partialElevation);
    // Planned/imported route geometry continues to use its supplied elevations exactly.
    assertTrue(RouteSummary.from(points).descent > 30);
  }

  @Test
  public void shortRecordingsAndNewSegmentsDoNotClaimReliableElevation() {
    Point first = point(0, 100, 0, 3), last = point(10, 80, 10, 3);
    assertFalse(RouteSummary.from(List.of(first, last), true).hasElevation);
    RecordingElevation filter = new RecordingElevation();
    filter.add(first);
    assertEquals(100, filter.add(point(100, 100, 60, 3)), 0);
    assertTrue(Double.isNaN(filter.add(new Point(46, 7, 300, first.time + 70000, 3, 1, 3))));
  }
}
