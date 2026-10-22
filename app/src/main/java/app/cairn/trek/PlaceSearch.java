package app.cairn.trek;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.*;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Place lookups while the search is open; never subscribes to device location. */
final class PlaceSearch implements AutoCloseable {
  interface Lookup {
    List<Address> search(String query, Point center) throws IOException;

    default void cancel() {}
  }

  interface Callback {
    void results(List<Address> places);

    void unavailable(String reason);
  }

  private final Geocoder geocoder;
  private final Lookup lookup;
  private Point center;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Future<?> pending;
  private volatile int generation;

  PlaceSearch(Context context) {
    this(context, new PhotonClient());
  }

  PlaceSearch(Context context, Lookup lookup) {
    geocoder = new Geocoder(context.getApplicationContext(), Locale.getDefault());
    this.lookup = lookup;
  }

  void near(Point center) {
    this.center = center;
  }

  void suggest(String query, Callback callback) {
    cancel();
    String text = query.trim();
    if (text.length() < 2) return;
    main.postDelayed(() -> search(text, callback), 350);
  }

  @SuppressWarnings("deprecation")
  void search(String query, Callback callback) {
    cancel();
    int request = generation;
    Point focus = center;
    Address coordinates = coordinates(query);
    if (coordinates != null) {
      callback.results(Collections.singletonList(coordinates));
      return;
    }
    main.postDelayed(
        () -> {
          if (request != generation) return;
          cancel();
          callback.unavailable("Search timed out. Check your internet connection and try again.");
        },
        30000);
    pending =
        worker.submit(
            () -> {
              try {
                if (request != generation) return;
                List<Address> places = Collections.emptyList();
                IOException primaryError = null;
                try {
                  places = rank(query, focus, lookup.search(query, focus));
                } catch (IOException error) {
                  primaryError = error;
                }
                if (request != generation) return;
                if (places == null || places.isEmpty()) {
                  if (Geocoder.isPresent()) places = geocoder.getFromLocationName(query, 10);
                  else if (primaryError != null) throw primaryError;
                }
                List<Address> usable = rank(query, focus, places);
                main.post(
                    () -> {
                      if (request != generation) return;
                      cancel();
                      callback.results(usable);
                    });
              } catch (IOException | RuntimeException error) {
                main.post(
                    () -> {
                      if (request != generation) return;
                      cancel();
                      callback.unavailable(
                          "Search unavailable. Check your internet connection and try again.");
                    });
              }
            });
  }

  /** Text relevance dominates; distance is only a soft tie-breaker among good matches. */
  static List<Address> rank(String query, Point center, List<Address> places) {
    List<Address> ranked = new ArrayList<>();
    if (places == null) return ranked;
    for (Address place : places) {
      if (place == null
          || !place.hasLatitude()
          || !place.hasLongitude()
          || !Double.isFinite(place.getLatitude())
          || !Double.isFinite(place.getLongitude())
          || Math.abs(place.getLatitude()) > 90
          || Math.abs(place.getLongitude()) > 180) continue;
      ranked.add(place);
    }
    String text = normalized(query);
    ranked.sort(
        Comparator.comparingDouble((Address place) -> relevance(text, place, center)).reversed());
    List<Address> unique = new ArrayList<>();
    for (Address place : ranked) {
      boolean duplicate = false;
      for (Address other : unique) {
        if (normalized(place.getFeatureName()).equals(normalized(other.getFeatureName()))
            && Geo.distance(
                    new Point(place.getLatitude(), place.getLongitude()),
                    new Point(other.getLatitude(), other.getLongitude()))
                < 100) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) unique.add(place);
      if (unique.size() == 10) break;
    }
    return unique;
  }

  private static double relevance(String query, Address place, Point center) {
    String name = normalized(place.getFeatureName());
    String label = normalized(label(place));
    double score =
        name.equals(query) ? 240 : name.startsWith(query) ? 170 : name.contains(query) ? 130 : 0;
    for (String token : query.split(" ")) {
      boolean nameMatch = Arrays.stream(name.split(" ")).anyMatch(word -> word.startsWith(token));
      boolean labelMatch = Arrays.stream(label.split(" ")).anyMatch(word -> word.startsWith(token));
      score += nameMatch ? 35 : labelMatch ? 20 : -100;
    }
    String kind = place.getExtras() == null ? "" : place.getExtras().getString("kind", "");
    switch (kind) {
      case "city":
        score += 50;
        break;
      case "town":
        score += 40;
        break;
      case "peak":
        score += 35;
        break;
      case "village":
        score += 25;
        break;
      case "hamlet":
        score += 15;
        break;
    }
    if (center != null) {
      double km = Geo.distance(center, new Point(place.getLatitude(), place.getLongitude())) / 1000;
      score += 20 / (1 + km / 100);
    }
    return score;
  }

  static String normalized(String text) {
    if (text == null) return "";
    return Normalizer.normalize(
            text.toLowerCase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae"),
            Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim();
  }

  static Address coordinates(String query) {
    String[] parts = query.trim().split("\\s*[,;]\\s*");
    if (parts.length != 2) return null;
    try {
      double lat = Double.parseDouble(parts[0]), lon = Double.parseDouble(parts[1]);
      if (!Double.isFinite(lat)
          || !Double.isFinite(lon)
          || Math.abs(lat) > 90
          || Math.abs(lon) > 180) return null;
      Address place = new Address(Locale.getDefault());
      place.setLatitude(lat);
      place.setLongitude(lon);
      return place;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  static String label(Address place) {
    if (place.getMaxAddressLineIndex() >= 0 && place.getAddressLine(0) != null)
      return place.getAddressLine(0);
    LinkedHashSet<String> parts = new LinkedHashSet<>();
    for (String part :
        new String[] {
          place.getFeatureName(), place.getLocality(), place.getAdminArea(), place.getCountryName()
        }) if (part != null && !part.trim().isEmpty()) parts.add(part);
    return parts.isEmpty()
        ? String.format(
            Locale.getDefault(), "%.5f, %.5f", place.getLatitude(), place.getLongitude())
        : String.join(", ", parts);
  }

  void cancel() {
    generation++;
    main.removeCallbacksAndMessages(null);
    if (pending != null) pending.cancel(true);
    pending = null;
    lookup.cancel();
  }

  @Override
  public void close() {
    cancel();
    worker.shutdownNow();
  }
}
