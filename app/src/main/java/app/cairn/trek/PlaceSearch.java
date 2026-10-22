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
          callback.unavailable("Search timed out");
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
                      callback.unavailable("Search unavailable");
                    });
              }
            });
  }

  /** Favor hiking destinations among relevant matches; distance remains a soft tie-breaker. */
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
    }
    return unique;
  }

  private static double relevance(String query, Address place, Point center) {
    String name = normalized(place.getFeatureName());
    String label = normalized(label(place));
    double score =
        name.equals(query) ? 240 : name.startsWith(query) ? 170 : name.contains(query) ? 130 : 0;
    boolean matchesAllTokens = !query.isEmpty();
    boolean matchesName = false;
    for (String token : query.split(" ")) {
      boolean nameMatch = Arrays.stream(name.split(" ")).anyMatch(word -> word.startsWith(token));
      boolean labelMatch = Arrays.stream(label.split(" ")).anyMatch(word -> word.startsWith(token));
      score += nameMatch ? 35 : labelMatch ? 20 : -100;
      matchesAllTokens &= nameMatch || labelMatch;
      matchesName |= nameMatch;
    }
    String kind = place.getExtras() == null ? "" : place.getExtras().getString("kind", "");
    // A partial mountain/hut name should beat an exact urban namesake. Require all query
    // tokens so that this preference cannot override an explicit country or address qualifier.
    if (matchesAllTokens && matchesName && hikingDestination(place, kind)) score += 200;
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

  private static boolean hikingDestination(Address place, String kind) {
    String category =
        place.getExtras() == null ? "" : place.getExtras().getString("category", "");
    if ("natural".equals(category)) return true;
    switch (kind) {
      case "peak":
      case "mountain_range":
      case "saddle":
      case "ridge":
      case "valley":
      case "volcano":
      case "glacier":
      case "cliff":
      case "cave_entrance":
      case "waterfall":
      case "spring":
      case "hot_spring":
      case "water":
      case "wood":
      case "forest":
      case "alpine_hut":
      case "wilderness_hut":
      case "shelter":
      case "trailhead":
      case "path":
      case "footway":
      case "hiking":
      case "foot":
      case "viewpoint":
      case "camp_site":
      case "picnic_site":
      case "national_park":
      case "protected_area":
      case "nature_reserve":
      case "park":
        return true;
      default:
        return false;
    }
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
