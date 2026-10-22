package app.cairn.trek;

import android.location.Address;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded, cancellable name search including OSM natural features. No location subscription. */
final class PhotonClient implements PlaceSearch.Lookup {
  interface Connections {
    HttpURLConnection open(URL url) throws IOException;
  }

  private final Connections connections;
  private HttpURLConnection active;
  private int generation;
  private final Map<String, List<Address>> cache = new LinkedHashMap<>(32, 0.75f, true);

  PhotonClient() {
    this(url -> (HttpURLConnection) url.openConnection());
  }

  PhotonClient(Connections connections) {
    this.connections = connections;
  }

  static String url(String query, Point center) {
    Uri.Builder url =
        Uri.parse("https://photon.komoot.io/api/")
            .buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("limit", "20");
    if (center != null) {
      url.appendQueryParameter("lat", Double.toString(center.lat))
          .appendQueryParameter("lon", Double.toString(center.lon))
          .appendQueryParameter("zoom", "8");
    }
    return url.build().toString();
  }

  @Override
  public List<Address> search(String query, Point center) throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Search canceled");
    String url = url(query, center);
    if (cache.containsKey(url)) return new ArrayList<>(cache.get(url));
    HttpURLConnection connection;
    int request;
    synchronized (this) {
      request = generation;
      connection = connections.open(new URL(url));
      active = connection;
    }
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(7000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty(
        "User-Agent",
        "Cairn/" + BuildConfig.VERSION_NAME + " (https://github.com/cairn-map/android)");
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
    try {
      synchronized (this) {
        if (request != generation || Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Search canceled");
      }
      if (connection.getResponseCode() != 200) throw new IOException("Place search unavailable");
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          synchronized (this) {
            if (request != generation) throw new InterruptedIOException("Search canceled");
          }
          if (output.size() + count > 512 * 1024)
            throw new IOException("Search response too large");
          output.write(buffer, 0, count);
        }
        List<Address> results = parse(output.toString(StandardCharsets.UTF_8.name()));
        // Keep only an in-memory cache while this activity lives; never create a place database.
        cache.put(url, results);
        if (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
        return new ArrayList<>(results);
      }
    } finally {
      synchronized (this) {
        if (active == connection) active = null;
      }
      connection.disconnect();
    }
  }

  static List<Address> parse(String json) throws IOException {
    try {
      JSONArray features = new JSONObject(json).getJSONArray("features");
      List<Address> results = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < features.length() && results.size() < 20; i++) {
        JSONObject feature = features.optJSONObject(i);
        if (feature == null) continue;
        JSONObject geometry = feature.optJSONObject("geometry"),
            p = feature.optJSONObject("properties");
        if (geometry == null || p == null || !"Point".equals(geometry.optString("type"))) continue;
        JSONArray coordinates = geometry.optJSONArray("coordinates");
        if (coordinates == null || coordinates.length() < 2) continue;
        double lon = coordinates.optDouble(0, Double.NaN),
            lat = coordinates.optDouble(1, Double.NaN);
        if (!Double.isFinite(lat)
            || !Double.isFinite(lon)
            || Math.abs(lat) > 90
            || Math.abs(lon) > 180) continue;
        String name = p.optString("name", "").trim();
        if (name.isEmpty()) name = p.optString("street", "").trim();
        if (name.isEmpty()) continue;
        String key =
            name.toLowerCase(Locale.ROOT)
                + ":"
                + Math.round(lat * 10000)
                + ":"
                + Math.round(lon * 10000);
        if (!seen.add(key)) continue;
        Address place = new Address(Locale.getDefault());
        place.setFeatureName(name);
        place.setLatitude(lat);
        place.setLongitude(lon);
        LinkedHashSet<String> label = new LinkedHashSet<>();
        label.add(name);
        if ("peak".equals(p.optString("osm_value"))) label.add("Mountain");
        for (String field : new String[] {"city", "state", "country"}) {
          String part = p.optString(field, "").trim();
          if (!part.isEmpty()) label.add(part);
        }
        place.setAddressLine(0, String.join(", ", label));
        results.add(place);
      }
      return results;
    } catch (JSONException error) {
      throw new IOException("Invalid search response", error);
    }
  }

  @Override
  public synchronized void cancel() {
    generation++;
    if (active != null) active.disconnect();
    active = null;
  }
}
