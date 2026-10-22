package app.cairn.trek;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.*;
import java.io.IOException;
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
                  places = lookup.search(query, focus);
                } catch (IOException error) {
                  primaryError = error;
                }
                if (request != generation) return;
                if (places == null || places.isEmpty()) {
                  if (Geocoder.isPresent()) places = geocoder.getFromLocationName(query, 10);
                  else if (primaryError != null) throw primaryError;
                }
                List<Address> usable = new ArrayList<>();
                if (places != null)
                  for (Address place : places)
                    if (place.hasLatitude()
                        && place.hasLongitude()
                        && Double.isFinite(place.getLatitude())
                        && Double.isFinite(place.getLongitude())
                        && Math.abs(place.getLatitude()) <= 90
                        && Math.abs(place.getLongitude()) <= 180) usable.add(place);
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
