package app.cairn.trek;

import static org.junit.Assert.*;

import android.location.Address;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhotonClientTest {
  private static class Response extends HttpURLConnection {
    final String body;
    final int code;
    boolean disconnected;

    Response(URL url, int code, String body) {
      super(url);
      this.code = code;
      this.body = body;
    }

    public int getResponseCode() {
      return code;
    }

    public InputStream getInputStream() {
      return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void connect() {}

    public boolean usingProxy() {
      return false;
    }

    public void disconnect() {
      disconnected = true;
    }
  }

  @Test
  public void successfulSearchIsCachedWithoutAnotherNetworkRequest() throws Exception {
    java.util.concurrent.atomic.AtomicInteger requests =
        new java.util.concurrent.atomic.AtomicInteger();
    Response[] response = new Response[1];
    PhotonClient client =
        new PhotonClient(
            url -> {
              requests.incrementAndGet();
              return response[0] =
                  new Response(
                      url,
                      200,
                      """
                      {"features":[{"properties":{"name":"Mont Thabor"},
                      "geometry":{"type":"Point","coordinates":[6.563,45.115]}}]}
                      """);
            });
    assertEquals(1, client.search("Thabor", null).size());
    assertTrue(response[0].disconnected);
    assertTrue(response[0].getRequestProperty("User-Agent").startsWith("Cairn/"));
    assertFalse(response[0].getInstanceFollowRedirects());
    assertEquals(1, client.search("Thabor", null).size());
    assertEquals(1, requests.get());
    client.cancel();
  }

  @Test
  public void busyAndOversizedResponsesFailAndDisconnectWithoutAutomaticRetries() throws Exception {
    for (int status : new int[] {429, 200}) {
      Response[] response = new Response[1];
      PhotonClient client =
          new PhotonClient(
              url -> response[0] = new Response(url, status, " ".repeat(512 * 1024 + 1)));
      assertThrows(IOException.class, () -> client.search("Thabor", null));
      assertTrue(response[0].disconnected);
    }
  }

  @Test
  public void queryIsEncodedAndMapCenterIsASoftBiasWithoutGeographicRestrictions() {
    Uri url = Uri.parse(PhotonClient.url("Thabor & col", new Point(45.1, 6.5)));
    assertEquals("Thabor & col", url.getQueryParameter("q"));
    assertEquals("40", url.getQueryParameter("limit"));
    assertEquals("45.1", url.getQueryParameter("lat"));
    assertEquals("6.5", url.getQueryParameter("lon"));
    assertNull(url.getQueryParameter("bbox"));
    assertNull(url.getQueryParameter("countrycode"));
  }

  @Test
  public void naturalFeaturesAreNamedDisambiguatedAndDeduplicated() throws Exception {
    List<Address> places =
        PhotonClient.parse(
            """
{"features":[
  {"properties":{"name":"Mont Thabor","osm_value":"peak","city":"Valmeinier","country":"France"},
   "geometry":{"type":"Point","coordinates":[6.563,45.115]}},
  {"properties":{"name":"Mont Thabor"},"geometry":{"type":"Point","coordinates":[6.563,45.115]}},
  {"properties":{"name":"Invalid"},"geometry":{"type":"Point","coordinates":[6,95]}},
  {"properties":{"name":"Missing"},"geometry":{"type":"Point","coordinates":[]}},
  {"properties":{"name":"Area"},"geometry":{"type":"Polygon","coordinates":[]}},
  {"geometry":{"type":"Point","coordinates":[6,45]}}
]}
""");
    assertEquals(1, places.size());
    assertEquals("Mont Thabor, Mountain, Valmeinier, France", PlaceSearch.label(places.get(0)));
    assertEquals(45.115, places.get(0).getLatitude(), 1e-6);
    assertEquals(6.563, places.get(0).getLongitude(), 1e-6);
  }

  @Test(expected = IOException.class)
  public void invalidResponseFailsSoTheDeviceFallbackCanRun() throws Exception {
    PhotonClient.parse("unavailable");
  }

  @Test
  public void streetAddressesKeepHouseNumbersPostcodesAndCityForDisambiguation() throws Exception {
    Address address =
        PhotonClient.parse(
                """
                {"features":[{"properties":{"street":"Rue de Rivoli","housenumber":"12",
                  "postcode":"75004","city":"Paris","country":"France"},
                  "geometry":{"type":"Point","coordinates":[2.35,48.85]}}]}
                """)
            .get(0);
    assertEquals("12 Rue de Rivoli", address.getFeatureName());
    assertEquals("Paris", address.getLocality());
    assertEquals("75004", address.getPostalCode());
    assertEquals("12 Rue de Rivoli, 75004, Paris, France", PlaceSearch.label(address));
  }

  @Test
  public void namedPlacesKeepTheirNameWhenTheyAlsoHaveAStreetAddress() throws Exception {
    Address cafe =
        PhotonClient.parse(
                """
                {"features":[{"properties":{"name":"Café des Alpes","street":"Rue de Rivoli",
                  "housenumber":"12","city":"Paris"},
                  "geometry":{"type":"Point","coordinates":[2.35,48.85]}}]}
                """)
            .get(0);
    assertEquals("Café des Alpes", cafe.getFeatureName());
    assertEquals("Café des Alpes, 12 Rue de Rivoli, Paris", PlaceSearch.label(cafe));
  }
}
