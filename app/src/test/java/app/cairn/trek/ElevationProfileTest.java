package app.cairn.trek;

import static org.junit.Assert.*;

import org.junit.Test;

public class ElevationProfileTest {
  private Point point(double lat, double elevation, int segment) {
    return new Point(lat, 6, elevation, 0, 0, segment);
  }

  @Test
  public void interpolatesByDistanceAndKeepsExactEndpoints() {
    ElevationProfile profile = new ElevationProfile();
    Point start = point(45, 100, 0), end = point(45.04, 300, 0);
    profile.add(start);
    profile.add(point(45.01, 200, 0));
    profile.add(end);
    assertSame(start, profile.at(0));
    assertSame(end, profile.at(1));
    assertEquals(45.02, profile.at(.5).lat, .000001);
    assertEquals(233.333, profile.at(.5).elevation, .001);
    assertEquals(Geo.distance(start, end), profile.distance, .001);
    assertEquals(.25, profile.fraction(1), .000001);
  }

  @Test
  public void segmentGapsAndUnknownElevationsAreNeverInvented() {
    ElevationProfile profile = new ElevationProfile();
    profile.add(point(45, -50, 0));
    profile.add(point(45.01, 0, 0));
    profile.add(point(46, Double.NaN, 1));
    profile.add(point(46.01, 1000, 1));
    assertEquals(Geo.distance(point(45, 0, 0), point(45.02, 0, 0)), profile.distance, .001);
    assertEquals(45.005, profile.at(.25).lat, .000001);
    assertEquals(46.005, profile.at(.75).lat, .000001);
    assertTrue(Double.isNaN(profile.at(.75).elevation));
    assertEquals(-50, profile.minimum, 0);
    assertEquals(1000, profile.maximum, 0);
  }

  @Test
  public void emptySingleFlatAndStationaryTracksRemainInspectable() {
    ElevationProfile profile = new ElevationProfile();
    assertNull(profile.at(.5));
    assertFalse(profile.hasElevation());
    Point a = point(45, 0, 0), b = point(45, 100, 0);
    profile.add(a);
    assertSame(a, profile.at(.5));
    assertTrue(profile.hasElevation());
    profile.add(b);
    assertSame(b, profile.at(1));
    assertEquals(0, profile.distance, 0);
    assertEquals(1, profile.fraction(1), 0);
    profile.add(point(45.01, 100, 0));
    assertTrue(profile.distance > 0);
    assertEquals(100, profile.at(.5).elevation, 0);
  }

  @Test
  public void interpolationCrossesTheDateLineLocally() {
    ElevationProfile profile = new ElevationProfile();
    profile.add(new Point(45, 179.9, 100, 1000, 0, 0));
    profile.add(new Point(45, -179.9, 200, 3000, 0, 0));
    Point middle = profile.at(.5);
    assertEquals(180, Math.abs(middle.lon), .00001);
    assertEquals(150, middle.elevation, 0);
    assertEquals(2000, middle.time);
  }

  private Point meters(double distance, double elevation, int segment) {
    return point(Math.toDegrees(distance / 6371008.8), elevation, segment);
  }

  @Test
  public void shortInternalGapHasSeparateEndpointsWithoutChangingCursorOrSamples() {
    ElevationProfile profile = new ElevationProfile();
    Point missing = meters(10, Double.NaN, 0);
    profile.add(meters(0, 100, 0));
    profile.add(missing);
    assertTrue(profile.connectors.isEmpty()); // Still a trailing gap.
    profile.add(meters(40, 140, 0));
    assertEquals(1, profile.connectors.size());
    assertEquals(0, profile.connectors.get(0).first);
    assertEquals(2, profile.connectors.get(0).last);
    assertEquals(.25, profile.fraction(1), .000001);
    assertSame(missing, profile.points.get(1));
    assertTrue(Double.isNaN(profile.at(.25).elevation));
    assertTrue(Double.isNaN(profile.at(.75).elevation));
    assertEquals(100, profile.minimum, 0);
    assertEquals(140, profile.maximum, 0);
  }

  @Test
  public void displayGapLimitIncludesFiftyMetersAndUsesCumulativeDistance() {
    for (double length : new double[] {49.99, 50, 50.01}) {
      ElevationProfile profile = new ElevationProfile();
      profile.add(meters(0, 100, 0));
      profile.add(meters(length / 2, Double.NaN, 0));
      profile.add(meters(length, 200, 0));
      assertEquals(length <= 50 ? 1 : 0, profile.connectors.size());
    }
    ElevationProfile detour = new ElevationProfile();
    detour.add(meters(0, 100, 0));
    detour.add(meters(40, Double.NaN, 0));
    detour.add(meters(10, 200, 0));
    assertEquals(70, detour.distance, .000001);
    assertTrue(detour.connectors.isEmpty());
  }

  @Test
  public void leadingTrailingAndCrossSegmentGapsHaveNoConnectors() {
    ElevationProfile profile = new ElevationProfile();
    profile.add(meters(0, Double.NaN, 0));
    profile.add(meters(5, 100, 0));
    profile.add(meters(10, Double.NaN, 0));
    profile.add(meters(15, Double.NaN, 1));
    // Even returning to the earlier segment ID must not bridge intervening boundaries.
    profile.add(meters(20, 200, 0));
    profile.add(meters(25, Double.NaN, 0));
    assertTrue(profile.connectors.isEmpty());
  }

  @Test
  public void singleMissingDuplicateAndAlternatingSamplesRemainSafe() {
    ElevationProfile profile = new ElevationProfile();
    profile.add(meters(0, Double.NaN, 0));
    assertTrue(profile.connectors.isEmpty());
    profile.add(meters(0, 100, 0));
    profile.add(meters(0, Double.NaN, 0));
    profile.add(meters(0, 200, 0));
    assertTrue(profile.connectors.isEmpty()); // Never draw an inferred vertical spike.
    profile.add(meters(0, Double.NaN, 0));
    profile.add(meters(20, 220, 0));
    profile.add(meters(25, Double.NaN, 0));
    profile.add(meters(40, 240, 0));
    assertEquals(2, profile.connectors.size());
    assertEquals(3, profile.connectors.get(0).first);
    assertEquals(5, profile.connectors.get(0).last);
    assertEquals(5, profile.connectors.get(1).first);
    assertEquals(7, profile.connectors.get(1).last);
  }
}
