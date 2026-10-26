package app.cairn.trek;

import static org.junit.Assert.*;

import org.junit.Test;

public class ElevationProfileTest {
  private Point point(double lat, double elevation, int segment) {
    return new Point(lat, 6, elevation, 0, 0, segment);
  }

  @Test
  public void gradesFollowTheLocalEdgeIncludingEndpointsAndDownhill() {
    ElevationProfile profile = new ElevationProfile();
    Point a = point(45, 100, 0), b = point(45.01, 200, 0), c = point(45.02, 150, 0);
    profile.add(a);
    profile.add(b);
    profile.add(c);
    assertEquals(10000 / Geo.distance(a, b), profile.gradeAt(0), .000001);
    assertEquals(profile.gradeAt(0), profile.gradeAt(.25), .000001);
    assertEquals(-5000 / Geo.distance(b, c), profile.gradeAt(.75), .000001);
    assertEquals(profile.gradeAt(.75), profile.gradeAt(1), .000001);
    assertTrue(Double.isNaN(profile.gradeAt(Double.NaN)));
  }

  @Test
  public void gradesDoNotInventMissingHeightsStationarySlopesOrSegmentConnections() {
    ElevationProfile profile = new ElevationProfile();
    assertTrue(Double.isNaN(profile.gradeAt(0)));
    profile.add(point(45, 100, 0));
    profile.add(point(45, 200, 0));
    assertTrue(Double.isNaN(profile.gradeAt(.5)));
    profile.add(point(46, 300, 1));
    profile.add(point(46.01, Double.NaN, 1));
    assertTrue(Double.isNaN(profile.gradeAt(0)));
    assertTrue(Double.isNaN(profile.gradeAt(.5)));
    assertTrue(Double.isNaN(profile.gradeAt(1)));
    profile.add(point(47, 500, 2));
    assertTrue(Double.isNaN(profile.gradeAt(1)));
    ElevationProfile flat = new ElevationProfile();
    flat.add(point(45, 100, 0));
    flat.add(point(45.01, 100, 0));
    assertEquals(0, flat.gradeAt(.5), 0);
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
}
