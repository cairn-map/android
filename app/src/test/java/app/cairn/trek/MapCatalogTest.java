package app.cairn.trek;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class MapCatalogTest {
  private byte[] xml(String url) {
    return ("<onlinemapsources><onlinemapsource><url><![CDATA["
            + url
            + "]]></url></onlinemapsource></onlinemapsources>")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void recognizesOnlyPublicPlanIgnWithoutFetchingUrls() throws Exception {
    String url =
        "https://data.geopf.fr/wmts?LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&TILECOL={$x}&TILEROW={$y}&TILEMATRIX={$z}";
    MapCatalog catalog = MapCatalog.read(xml(url));
    assertEquals(1, catalog.sources);
    assertEquals(1, catalog.supported);
    assertEquals(
        0,
        MapCatalog.read(xml(url.replace("data.geopf.fr", "data.geopf.fr.example.org"))).supported);
    assertEquals(0, MapCatalog.read(xml(url.replace("PLANIGNV2", "MAPS"))).supported);
  }

  @Test
  public void rejectsDocumentDeclarations() throws Exception {
    byte[] bytes =
        "<!DOCTYPE onlinemapsources [<!ENTITY x SYSTEM 'file:///never-read'>]><onlinemapsources>&x;</onlinemapsources>"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> MapCatalog.read(bytes));
  }

  @Test
  public void distinguishesGpxAndRejectsOtherXml() throws Exception {
    assertEquals(
        "gpx",
        MapCatalog.root(
            "<?xml version='1.0'?><gpx xmlns='http://www.topografix.com/GPX/1/1'/>"
                .getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IOException.class, () -> MapCatalog.read("<html/>".getBytes(StandardCharsets.UTF_8)));
  }
}
