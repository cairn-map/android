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
            new Point(45.1, 6.2, 1500, 1700000000000L, 5, 0),
            new Point(45.2, 6.3, Double.NaN, 1700000010000L, 6, 1));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Gpx.write(out, points, "Rock & ice <route>");
    List<Point> result = Gpx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(2, result.size());
    assertEquals(1500, result.get(0).elevation, 0);
    assertTrue(Double.isNaN(result.get(1).elevation));
    assertEquals(1700000000000L, result.get(0).time);
    assertNotEquals(result.get(0).segment, result.get(1).segment);
    assertEquals(0, Geo.length(result), 0);
    assertTrue(out.toString("UTF-8").contains("Rock &amp; ice &lt;route&gt;"));
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
