package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class GeoidTest {
  @Test
  public void knownGlobalReferenceValuesRetainTheCorrectHemisphereAndSign() {
    // Independent reference samples from PROJ's original EGM96 GTX, before cm quantization.
    assertEquals(44.81219, Geoid.separation(48.75, 2), .006);
    assertEquals(-32.67907, Geoid.separation(40.75, -74), .006);
    assertEquals(36.81247, Geoid.separation(35.75, 139.75), .006);
    assertEquals(31.01492, Geoid.separation(-34, 18.5), .006);
    assertEquals(17.16158, Geoid.separation(0, 0), .006);
    assertEquals(13.60625, Geoid.separation(90, 0), .006);
    assertEquals(-29.53385, Geoid.separation(-90, 0), .006);
  }

  @Test
  public void interpolationWrapsAtTheDateLineAndHandlesPolesAndInvalidCoordinates() {
    assertEquals(Geoid.separation(20, -180), Geoid.separation(20, 180), 0);
    assertEquals(Geoid.separation(20, -179.999999), Geoid.separation(20, 179.999999), .001);
    assertTrue(Double.isFinite(Geoid.separation(89.9999, 179.99)));
    assertTrue(Double.isFinite(Geoid.separation(-89.9999, -179.99)));
    assertTrue(Double.isNaN(Geoid.separation(91, 0)));
    assertTrue(Double.isNaN(Geoid.separation(0, Double.NaN)));
  }

  @Test
  public void seaLevelConversionPreservesRawGpsAndUsesAvailableAndroidMslHeight() {
    Point gps = new Point(48.75, 2, 212, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, Double.NaN);
    assertEquals(167.19, gps.seaLevelElevation(), .001);
    assertEquals(212, gps.elevation, 0);
    Point supplied = new Point(48.75, 2, 212, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, 166.5);
    assertEquals(166.5, supplied.seaLevelElevation(), 0);
    Point seaLevel = new Point(48.75, 2, 165, 0, 0, 0);
    assertEquals(165, seaLevel.seaLevelElevation(), 0);
    Point negativeSeparation =
        new Point(40.75, -74, -20, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, Double.NaN);
    assertEquals(12.68, negativeSeparation.seaLevelElevation(), .001);
    Point missing =
        new Point(48.75, 2, Double.NaN, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, Double.NaN);
    assertTrue(Double.isNaN(missing.seaLevelElevation()));
    Point mslOnly = new Point(48.75, 2, Double.NaN, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, 166.5);
    assertEquals(166.5, mslOnly.seaLevelElevation(), 0);
  }

  @Test
  public void recordedAndImportedProfilesAndTotalsUseSeaLevelWithoutDoubleConversion() {
    Point first = new Point(48.75, 2, 212, 1000, 3, 0, 3, Point.WGS84_ELLIPSOID, 166);
    Point last = new Point(48.751, 2, 222, 6000, 3, 0, 3, Point.WGS84_ELLIPSOID, 176);
    ElevationProfile recording = new ElevationProfile(true);
    recording.add(first);
    recording.add(last);
    assertEquals(166, recording.points.get(0).elevation, 0);
    assertEquals(171, recording.points.get(1).elevation, 0);
    assertEquals(5, RouteSummary.from(List.of(first, last), true).ascent, .001);
    ElevationProfile imported = new ElevationProfile();
    imported.add(first);
    imported.add(last);
    assertEquals(166, imported.points.get(0).elevation, 0);
    assertEquals(176, imported.points.get(1).elevation, 0);
    assertEquals(10, RouteSummary.from(List.of(first, last)).ascent, .001);
    assertEquals(212, first.elevation, 0);
  }
}
