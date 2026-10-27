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
  public void accurateAltitudeIsUsableFromTheFirstFixWithoutWaitingAMinute() {
    List<Point> points = List.of(point(0, 100, 0, 3), point(10, 102, 5, 3), point(20, 108, 10, 3));
    ElevationProfile profile = new ElevationProfile(true);
    for (Point point : points) profile.add(point);
    assertEquals(100, profile.points.get(0).elevation, 0);
    assertEquals(100, profile.points.get(1).elevation, 0);
    assertEquals(103, profile.points.get(2).elevation, 0);
    RouteSummary summary = RouteSummary.from(points, true);
    assertFalse(summary.partialElevation);
    assertEquals(3, summary.ascent, .001);
    assertTrue(summary.text().contains("↑ 3 m"));
    assertFalse(summary.text().contains("↓ 0 m"));
    assertTrue(summary.description().contains("descent estimate not reliably resolved"));
    assertTrue(summary.profileText().contains("↑ 3 m   ↓ — m"));
    assertConsistent(points);
  }

  @Test
  public void missingQualityWaitsForObservedStabilityRatherThanAnElapsedMinute() {
    List<Point> points =
        List.of(
            point(0, 229, 0, Float.NaN),
            point(10, 225, 5, Float.NaN),
            point(11.2, 216.3, 6, Float.NaN),
            point(20, 212, 10, Float.NaN),
            point(30, 211, 15, Float.NaN),
            point(40, 211.5, 20, Float.NaN),
            point(50, 211.8, 25, Float.NaN));
    ElevationProfile profile = new ElevationProfile(true);
    for (Point point : points) profile.add(point);
    assertTrue(Double.isNaN(profile.points.get(0).elevation));
    assertEquals(211.5, profile.points.get(5).elevation, 0);
    assertTrue(profile.hasExcludedElevation);
    assertEquals(229, points.get(0).elevation, 0);
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(Geo.length(points), summary.distance, .001);
    assertTrue(summary.text().contains("Elevation change not reliably resolved"));
    assertTrue(summary.profileText().endsWith("↑ — m   ↓ — m · 0 min"));
    assertFalse(summary.text().contains("↑ 0 m"));
    assertConsistent(points);
  }

  @Test
  public void sustainedSlopesUseTheSameFilteredSignalInGraphAndLiveOrLoadedTotals() {
    List<Point> points = new ArrayList<>();
    for (int i = 0; i <= 30; i++) points.add(point(i * 10, 100 + i, i * 5, 3));
    for (int i = 1; i <= 20; i++) points.add(point(300 + i * 10, 130 - i, 150 + i * 5, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    // The uncertainty band suppresses the beginning of a trend and small reversals.
    assertEquals(25, summary.ascent, .001);
    assertEquals(10, summary.descent, .001);
    assertConsistent(points);
    RouteSummary live = new RouteSummary(true);
    for (Point point : points) live.add(point);
    assertEquals(summary.ascent, live.ascent, 0);
    assertEquals(summary.descent, live.descent, 0);
  }

  @Test
  public void noiseAndStationaryDriftDoNotProduceFalseFlatGroundTotals() {
    List<Point> points = new ArrayList<>();
    for (int i = 0; i < 20; i++) points.add(point(i * 10, 100 + i % 3, i * 5, 3));
    points.add(point(190.1, 98, 100, 3));
    points.add(point(200, 98, 105, 3));
    points.add(point(210, 99, 110, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(0, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertFalse(summary.partialElevation);
    assertTrue(summary.text().contains("not reliably resolved"));
    assertConsistent(points);
  }

  @Test
  public void subMeterWalkingAndStairsStayContinuousAndResolveASustainedClimb() {
    List<Point> points = new ArrayList<>();
    for (int i = 0; i <= 40; i++) points.add(point(i * .5, 100 + i * .3, i, 3));
    ElevationProfile profile = new ElevationProfile(true);
    for (Point point : points) profile.add(point);
    assertFalse(profile.hasExcludedElevation);
    assertTrue(profile.connectors.isEmpty());
    assertTrue(profile.points.get(40).elevation > 105);
    assertTrue(RouteSummary.from(points, true).ascent > 5);
    assertConsistent(points);
  }

  @Test
  public void stationaryJitterHoldsTheEstimateWithoutCountingAltitudeDrift() {
    List<Point> points = new ArrayList<>();
    for (int i = 0; i < 40; i++) points.add(point(i % 2 * .5, 100 + i * .2, i, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(0, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertFalse(summary.partialElevation);
    assertConsistent(points);
  }

  @Test
  public void poorQualitySpikesMissingAltitudeAndSegmentGapsNeverBridgeTotals() {
    List<Point> points =
        List.of(
            point(0, 100, 0, 3),
            point(10, 100, 5, 3),
            point(20, 130, 10, 40),
            point(30, 100, 15, 3),
            point(40, 100, 20, 3),
            point(41.2, 91.3, 21, 3),
            point(51.2, 91.3, 26, 3),
            point(61.2, Double.NaN, 31, 3),
            new Point(46, 7, 300, 1040000, 3, 1, 3),
            new Point(46.001, 7, 300, 1045000, 3, 1, 3));
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(0, summary.ascent, 0);
    assertEquals(0, summary.descent, 0);
    assertTrue(summary.partialElevation);
    assertConsistent(points);
    assertTrue(RouteSummary.from(points).descent > 30);
  }

  @Test
  public void unresolvedAltitudeIsDifferentFromHavingNoAltitudeAtAll() {
    assertTrue(
        RouteSummary.from(List.of(point(0, 100, 0, Float.NaN), point(10, 101, 1, Float.NaN)), true)
            .text()
            .contains("not reliably resolved"));
    assertTrue(
        RouteSummary.from(List.of(new Point(45, 6), new Point(45.01, 6)), true)
            .text()
            .contains("Elevation unavailable"));
    RecordingElevation filter = new RecordingElevation();
    assertTrue(Double.isNaN(filter.add(point(0, 100, 0, Float.POSITIVE_INFINITY))));
    assertEquals(100, filter.add(point(10, 100, 5, 3)), 0);
    assertEquals(300, filter.add(new Point(46, 7, 300, 1060000, 3, 1, 3)), 0);
  }

  @Test
  public void displayConnectorLeavesRejectedMeasurementsAndTotalsUntouched() {
    List<Point> points =
        List.of(
            point(0, 100, 0, 3),
            point(10, 106, 5, 3),
            point(20, 110, 10, 40),
            point(30, 114, 15, 3),
            point(40, 120, 20, 3));
    ElevationProfile profile = new ElevationProfile(true);
    RecordingElevation filter = new RecordingElevation();
    for (Point point : points) {
      profile.add(point);
      assertEquals(filter.add(point), profile.points.get(profile.points.size() - 1).elevation, 0);
    }
    assertEquals(1, profile.connectors.size());
    assertEquals(1, profile.connectors.get(0).first);
    assertEquals(3, profile.connectors.get(0).last);
    assertTrue(Double.isNaN(profile.points.get(2).elevation));
    assertTrue(Double.isNaN(profile.at(.5).elevation));
    assertEquals(110, points.get(2).elevation, 0);
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(2, summary.ascent, .000001);
    assertEquals(0, summary.descent, 0);
    assertTrue(summary.partialElevation);
    assertConsistent(points);
  }

  private void assertConsistent(List<Point> points) {
    ElevationProfile profile = new ElevationProfile(true);
    for (Point point : points) profile.add(point);
    RouteSummary visibleGeometry = RouteSummary.from(profile.points);
    RouteSummary summary = RouteSummary.from(points, true);
    assertEquals(visibleGeometry.ascent, summary.ascent, .000001);
    assertEquals(visibleGeometry.descent, summary.descent, .000001);
    assertEquals(visibleGeometry.distance, summary.distance, .000001);
  }
}
