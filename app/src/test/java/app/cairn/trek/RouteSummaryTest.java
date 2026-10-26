package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class RouteSummaryTest {
  private Point point(double lat, double elevation, int segment) {
    return new Point(lat, 6, elevation, 0, 0, segment);
  }

  @Test
  public void totalsDistanceAscentAndDescentWithoutJoiningSegments() {
    List<Point> points =
        List.of(
            point(45, 100, 0),
            point(45.01, 140, 0),
            point(45.02, 120, 0),
            point(46, 900, 1),
            point(46.01, 890, 1));
    RouteSummary summary = RouteSummary.from(points);
    assertEquals(Geo.length(points), summary.distance, .001);
    assertEquals(40, summary.ascent, 0);
    assertEquals(30, summary.descent, 0);
    assertTrue(summary.hasElevation);
    assertFalse(summary.partialElevation);
    assertTrue(summary.description().contains("Ascent 40"));
  }

  @Test
  public void missingElevationsAreNotZeroAndDoNotBridgeGaps() {
    RouteSummary summary =
        RouteSummary.from(
            List.of(
                point(45, -20, 0),
                point(45.01, 0, 0),
                point(45.02, Double.NaN, 0),
                point(45.03, 500, 0),
                point(45.04, 510, 0)));
    assertEquals(30, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertTrue(summary.partialElevation);
    assertTrue(summary.text().contains("partial"));
  }

  @Test
  public void greyStartDoesNotHideGainFromTheRemainingRoute() {
    List<Point> points =
        List.of(
            new Point(45, 6),
            point(45.01, 100, 0),
            point(45.02, 140, 0),
            point(45.03, 120, 0));
    ElevationProfile profile = new ElevationProfile();
    for (Point point : points) profile.add(point);
    assertTrue(profile.hasExcludedElevation);
    RouteSummary summary = RouteSummary.from(points);
    assertTrue(summary.partialElevation);
    assertEquals(40, summary.ascent, 0);
    assertEquals(20, summary.descent, 0);
    assertTrue(summary.profileText().contains("↑ 40 m   ↓ 20 m"));
  }

  @Test
  public void unknownIsDifferentFromFlatAndIsolatedElevations() {
    assertFalse(RouteSummary.from(List.of()).hasElevation);
    assertFalse(RouteSummary.from(List.of(point(45, 100, 0))).hasElevation);
    RouteSummary missing = RouteSummary.from(List.of(new Point(45, 6), new Point(45.01, 6)));
    assertTrue(missing.distance > 0);
    assertTrue(missing.text().contains("Elevation unavailable"));
    RouteSummary isolated = RouteSummary.from(List.of(point(45, 100, 0), point(46, 300, 1)));
    assertFalse(isolated.hasElevation);
    assertEquals(0, isolated.distance, 0);
    RouteSummary flat = RouteSummary.from(List.of(point(45, 0, 0), point(45.01, 0, 0)));
    assertTrue(flat.hasElevation);
    assertEquals(0, flat.ascent, 0);
    assertEquals(0, flat.descent, 0);
  }

  @Test
  public void profileTimeIncludesStopsAndSegmentGapsAndUpdatesWithAppends() {
    RouteSummary summary = new RouteSummary();
    summary.add(new Point(45, 6, 100, 1_000_000, 3, 0));
    summary.add(new Point(45.01, 6, 150, 1_600_000, 3, 0));
    assertTrue(summary.profileText().endsWith(" · 10m"));
    summary.add(new Point(46, 6, 200, 4_900_000, 3, 1));
    assertTrue(summary.profileText().endsWith(" · 1h 05m"));
    assertFalse(summary.profileText().contains("\n"));
    assertTrue(summary.timeDescription().contains("Elapsed time 1h 05m"));
    // An inserted point without a timestamp does not reset recorded elapsed time.
    summary.add(new Point(46.01, 6));
    assertTrue(summary.profileText().endsWith(" · 1h 05m"));
    assertEquals("25h 00m", RouteSummary.durationText(90_000_000));
  }

  @Test
  public void untimedRoutesDoNotInventADurationAndReversedEndpointsAreUnavailable() {
    RouteSummary untimed = RouteSummary.from(List.of(point(45, 100, 0), point(45.01, 150, 0)));
    assertEquals("", untimed.timeDescription());
    assertFalse(untimed.profileText().contains("0m"));
    RouteSummary reversed = RouteSummary.from(List.of(
        new Point(45, 6, 100, 2_000_000, 3, 0),
        new Point(45.01, 6, 150, 1_000_000, 3, 0)));
    assertEquals("", reversed.timeDescription());
  }

  @Test
  public void liveTotalsMatchALoadedRecording() {
    List<Point> points = List.of(point(45, 50, 0), point(45.01, 70, 0), point(45.02, 60, 0));
    RouteSummary live = new RouteSummary();
    for (Point point : points) live.add(point);
    RouteSummary loaded = RouteSummary.from(points);
    assertEquals(loaded.distance, live.distance, 0);
    assertEquals(loaded.ascent, live.ascent, 0);
    assertEquals(loaded.descent, live.descent, 0);
  }
}
