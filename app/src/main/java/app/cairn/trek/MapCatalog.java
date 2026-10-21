package app.cairn.trek;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.xmlpull.v1.*;

/** Reads OruxMaps configuration as data. Only the supported public Plan IGN source is selected. */
final class MapCatalog {
  final int sources, supported;

  private MapCatalog(int sources, int supported) {
    this.sources = sources;
    this.supported = supported;
  }

  static byte[] bytes(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (out.size() + count > 32 * 1024 * 1024) throw new IOException("XML file exceeds 32 MB");
      out.write(buffer, 0, count);
    }
    return out.toByteArray();
  }

  static String root(byte[] bytes) throws Exception {
    XmlPullParser p = parser(bytes);
    int event;
    while ((event = p.nextToken()) != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.DOCDECL)
        throw new IOException("Document declarations are not supported");
      if (event == XmlPullParser.START_TAG) return p.getName();
    }
    throw new IOException("Empty XML document");
  }

  static MapCatalog read(byte[] bytes) throws Exception {
    if (!"onlinemapsources".equals(root(bytes)))
      throw new IOException("Expected an OruxMaps onlinemapsources XML file");
    XmlPullParser p = parser(bytes);
    int count = 0, supported = 0;
    boolean inSource = false, isPlan = false;
    for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.nextToken()) {
      if (event == XmlPullParser.DOCDECL)
        throw new IOException("Document declarations are not supported");
      if (p.getDepth() > 32) throw new IOException("XML nesting is too deep");
      if (event == XmlPullParser.START_TAG) {
        if (p.getDepth() == 2 && "onlinemapsource".equals(p.getName())) {
          if (++count > 512) throw new IOException("Map source limit: 512 entries");
          inSource = true;
          isPlan = false;
        } else if (inSource && p.getDepth() == 3 && "url".equals(p.getName())) {
          isPlan |= publicPlanIgn(p.nextText());
        }
      } else if (event == XmlPullParser.END_TAG
          && p.getDepth() == 2
          && "onlinemapsource".equals(p.getName())) {
        if (isPlan) supported++;
        inSource = false;
      }
    }
    if (count == 0) throw new IOException("No map sources found");
    return new MapCatalog(count, supported);
  }

  private static XmlPullParser parser(byte[] bytes) throws Exception {
    XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    p.setInput(new ByteArrayInputStream(bytes), null);
    return p;
  }

  private static boolean publicPlanIgn(String template) {
    try {
      URI uri = new URI(template.trim().replaceAll("\\{[^}]*}", "0"));
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !"data.geopf.fr".equalsIgnoreCase(uri.getHost())
          || !"/wmts".equals(uri.getPath())
          || uri.getRawUserInfo() != null
          || uri.getPort() != -1) return false;
      String query = uri.getRawQuery();
      if (query == null) return false;
      for (String pair : query.split("&")) {
        String[] parts = pair.split("=", 2);
        if (parts.length == 2
            && "LAYER".equalsIgnoreCase(parts[0])
            && "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"
                .equals(URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()))) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }
}
