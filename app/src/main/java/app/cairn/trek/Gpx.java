package app.cairn.trek;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public final class Gpx {
  private static final String QUALITY_NS = "https://cairn-map.github.io/gpx/1";

  private Gpx() {}

  public static List<Point> read(InputStream input) throws Exception {
    XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    parser.setInput(new BoundedInputStream(input, 32L * 1024 * 1024), null);
    List<Point> tracks = new ArrayList<>(), routes = new ArrayList<>();
    boolean pointOpen = false, routePoint = false;
    double lat = 0, lon = 0, ele = Double.NaN;
    double ellipsoidHeight = Double.NaN;
    long time = 0;
    float accuracy = 0, verticalAccuracy = Float.NaN;
    int segment = -1;
    for (int type = parser.getEventType();
        type != XmlPullParser.END_DOCUMENT;
        type = parser.nextToken()) {
      if (type == XmlPullParser.DOCDECL)
        throw new IOException("GPX document declarations are not supported");
      if (type == XmlPullParser.START_TAG) {
        String tag = parser.getName();
        if ("trkseg".equals(tag)) segment++;
        if ("trkpt".equals(tag) || "rtept".equals(tag)) {
          pointOpen = true;
          routePoint = "rtept".equals(tag);
          lat = Double.parseDouble(parser.getAttributeValue(null, "lat"));
          lon = Double.parseDouble(parser.getAttributeValue(null, "lon"));
          ele = Double.NaN;
          ellipsoidHeight = Double.NaN;
          time = 0;
          accuracy = 0;
          verticalAccuracy = Float.NaN;
        } else if (pointOpen && "ele".equals(tag)) ele = Double.parseDouble(parser.nextText());
        else if (pointOpen && "time".equals(tag)) {
          try {
            time = Instant.parse(parser.nextText()).toEpochMilli();
          } catch (java.time.format.DateTimeParseException ignored) {
          }
        } else if (pointOpen && QUALITY_NS.equals(parser.getNamespace())) {
          if ("verticalAccuracy".equals(tag))
            verticalAccuracy = Float.parseFloat(parser.nextText());
          else if ("horizontalAccuracy".equals(tag)) accuracy = Float.parseFloat(parser.nextText());
          else if ("ellipsoidHeight".equals(tag))
            ellipsoidHeight = Double.parseDouble(parser.nextText());
        }
      } else if (type == XmlPullParser.END_TAG
          && ("trkpt".equals(parser.getName()) || "rtept".equals(parser.getName()))) {
        Point point =
            Double.isFinite(ellipsoidHeight)
                ? new Point(
                    lat,
                    lon,
                    ellipsoidHeight,
                    time,
                    accuracy,
                    Math.max(0, segment),
                    verticalAccuracy,
                    Point.WGS84_ELLIPSOID,
                    ele)
                : new Point(lat, lon, ele, time, accuracy, Math.max(0, segment), verticalAccuracy);
        (routePoint ? routes : tracks).add(point);
        pointOpen = false;
        if (tracks.size() + routes.size() > 100000)
          throw new IOException("GPX limit: 100,000 points");
      }
    }
    List<Point> result = tracks.isEmpty() ? routes : tracks;
    if (result.isEmpty()) throw new IOException("No track or route points found in GPX");
    return result;
  }

  public static void write(OutputStream output, List<Point> points, String title)
      throws IOException {
    if (points.isEmpty()) throw new IOException("There are no points to export yet");
    Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    writer.write(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<gpx version=\"1.1\" creator=\"Cairn\""
            + " xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:cairn=\""
            + QUALITY_NS
            + "\"><trk><name>"
            + escape(title)
            + "</name>");
    int segment = Integer.MIN_VALUE;
    for (Point p : points) {
      if (segment != p.segment) {
        if (segment != Integer.MIN_VALUE) writer.write("</trkseg>");
        writer.write("<trkseg>");
        segment = p.segment;
      }
      writer.write("<trkpt lat=\"" + p.lat + "\" lon=\"" + p.lon + "\">");
      double seaLevel = p.seaLevelElevation();
      if (Double.isFinite(seaLevel)) writer.write("<ele>" + seaLevel + "</ele>");
      if (p.time > 0) writer.write("<time>" + Instant.ofEpochMilli(p.time) + "</time>");
      boolean rawGpsHeight =
          p.elevationDatum == Point.WGS84_ELLIPSOID && Double.isFinite(p.elevation);
      if ((Float.isFinite(p.accuracy) && p.accuracy > 0)
          || Float.isFinite(p.verticalAccuracy)
          || rawGpsHeight) {
        writer.write("<extensions>");
        if (Float.isFinite(p.accuracy) && p.accuracy > 0)
          writer.write("<cairn:horizontalAccuracy>" + p.accuracy + "</cairn:horizontalAccuracy>");
        if (Float.isFinite(p.verticalAccuracy))
          writer.write(
              "<cairn:verticalAccuracy>" + p.verticalAccuracy + "</cairn:verticalAccuracy>");
        if (rawGpsHeight)
          writer.write("<cairn:ellipsoidHeight>" + p.elevation + "</cairn:ellipsoidHeight>");
        writer.write("</extensions>");
      }
      writer.write("</trkpt>\n");
    }
    writer.write("</trkseg></trk></gpx>\n");
    writer.flush();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static class BoundedInputStream extends FilterInputStream {
    private long remaining;

    BoundedInputStream(InputStream in, long max) {
      super(in);
      remaining = max;
    }

    @Override
    public int read() throws IOException {
      if (remaining-- <= 0) throw new IOException("GPX exceeds 32 MB");
      return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) throw new IOException("GPX exceeds 32 MB");
      int n = in.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) remaining -= n;
      return n;
    }
  }
}
