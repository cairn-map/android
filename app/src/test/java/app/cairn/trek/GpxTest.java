package app.cairn.trek;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class GpxTest {
  private List<Point> parse(String xml) throws Exception {
    return Gpx.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void recordingRoundTripPreservesTimeElevationAndSegments() throws Exception {
    List<Point> points =
        List.of(
            new Point(45.1, 6.2, 1500, 1700000000000L, 5, 0, 7),
            new Point(45.2, 6.3, Double.NaN, 1700000010000L, 6, 1));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Gpx.write(out, points, "Rock & ice <route>");
    List<Point> result = Gpx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(2, result.size());
    assertEquals(1500, result.get(0).elevation, 0);
    assertEquals(5, result.get(0).accuracy, 0);
    assertEquals(7, result.get(0).verticalAccuracy, 0);
    assertTrue(Float.isNaN(result.get(1).verticalAccuracy));
    assertTrue(Double.isNaN(result.get(1).elevation));
    assertEquals(1700000000000L, result.get(0).time);
    assertNotEquals(result.get(0).segment, result.get(1).segment);
    assertEquals(0, Geo.length(result), 0);
    assertTrue(out.toString("UTF-8").contains("Rock &amp; ice &lt;route&gt;"));
  }

  @Test
  public void exportsSeaLevelWithOriginalGpsHeightAndDoesNotConvertAgainOnImport()
      throws Exception {
    Point gps =
        new Point(48.75, 2, 212, 1700000000000L, 5, 0, 7, Point.WGS84_ELLIPSOID, Double.NaN);
    ByteArrayOutputStream first = new ByteArrayOutputStream();
    Gpx.write(first, List.of(gps), "Recording");
    String xml = first.toString("UTF-8");
    assertTrue(xml.contains("<ele>167.19</ele>"));
    assertTrue(xml.contains("<cairn:ellipsoidHeight>212.0</cairn:ellipsoidHeight>"));
    Point restored = Gpx.read(new ByteArrayInputStream(first.toByteArray())).get(0);
    assertEquals(212, restored.elevation, 0);
    assertEquals(Point.WGS84_ELLIPSOID, restored.elevationDatum);
    assertEquals(167.19, restored.seaLevelElevation(), .001);
    ByteArrayOutputStream second = new ByteArrayOutputStream();
    Gpx.write(second, List.of(restored), "Recording");
    assertEquals(xml, second.toString("UTF-8"));
  }

  @Test
  public void unmarkedGpxElevationsKeepTheirStandardSeaLevelReference() throws Exception {
    Point point =
        parse(
                "<gpx creator='Cairn'><trk><name>Hike</name><trkseg>"
                    + "<trkpt lat='48.75' lon='2'><ele>165</ele></trkpt></trkseg></trk></gpx>")
            .get(0);
    assertEquals(Point.SEA_LEVEL, point.elevationDatum);
    assertEquals(165, point.seaLevelElevation(), 0);
  }

  @Test
  public void routeOnlyGpxSupported() throws Exception {
    assertEquals(
        2,
        parse("<gpx><rte><rtept lat='45' lon='6'/><rtept lat='46' lon='7'/></rte></gpx>").size());
  }

  @Test
  public void tracksPreferredOverDuplicateRoute() throws Exception {
    List<Point> result =
        parse(
            "<gpx><rte><rtept lat='1' lon='2'/></rte><trk><trkseg><trkpt lat='45'"
                + " lon='6'/></trkseg></trk></gpx>");
    assertEquals(1, result.size());
    assertEquals(45, result.get(0).lat, 0);
  }

  @Test
  public void unsafeXmlRejected() {
    assertThrows(
        Exception.class,
        () ->
            parse(
                "<!DOCTYPE gpx [<!ENTITY test SYSTEM"
                    + " 'file:///private'>]><gpx><name>&test;</name></gpx>"));
  }

  @Test
  public void emptyAndInvalidGpxRejected() {
    assertThrows(Exception.class, () -> parse("<gpx/>"));
    assertThrows(Exception.class, () -> parse("<gpx><rte><rtept lat='99' lon='0'/></rte></gpx>"));
  }
}
