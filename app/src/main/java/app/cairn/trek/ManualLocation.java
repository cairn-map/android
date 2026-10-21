package app.cairn.trek;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;

/** A foreground-only, bounded locate request, separate from hike recording. */
final class ManualLocation {
  interface Callback {
    void found(Point point, boolean cached);

    void unavailable(String reason);
  }

  private final Context context;
  private final LocationManager manager;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LocationListener listener;
  private Runnable timeout;
  private boolean hasResult;
  private int generation;

  ManualLocation(Context context) {
    this.context = context;
    manager = context.getSystemService(LocationManager.class);
  }

  @SuppressWarnings("deprecation")
  void start(Callback callback) {
    cancel();
    int request = generation;
    hasResult = false;
    boolean fine =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    List<String> providers = new ArrayList<>();
    for (String provider :
        new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
      if (!fine && LocationManager.GPS_PROVIDER.equals(provider)) continue;
      if (manager.getAllProviders().contains(provider) && manager.isProviderEnabled(provider))
        providers.add(provider);
    }
    Location best = null;
    for (String provider : providers) {
      try {
        Location location = manager.getLastKnownLocation(provider);
        if (!usable(location)) continue;
        if (best == null || location.getAccuracy() < best.getAccuracy()) best = location;
      } catch (SecurityException ignored) {
      }
    }
    if (best != null) {
      hasResult = true;
      callback.found(point(best), true);
      if (ageMillis(best) <= 30000 && best.getAccuracy() <= 50) return;
    }
    if (providers.isEmpty()) {
      if (!hasResult)
        callback.unavailable(
            "No location provider is available. Enable device location. Indoor positioning also"
                + " needs a network location provider on your phone.");
      return;
    }
    listener =
        new LocationListener() {
          @Override
          public void onLocationChanged(Location location) {
            if (request != generation) return;
            if (!usable(location)) return;
            hasResult = true;
            callback.found(point(location), false);
            // Approximate permission can produce kilometre-scale positions. Still show them
            // honestly.
            if (location.getAccuracy() <= 1000 || !fine) cancel();
          }

          @Override
          public void onProviderEnabled(String provider) {}

          @Override
          public void onProviderDisabled(String provider) {}

          @Override
          public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
    boolean subscribed = false;
    for (String provider : providers) {
      try {
        manager.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper());
        subscribed = true;
      } catch (IllegalArgumentException | SecurityException ignored) {
      }
    }
    if (!subscribed) {
      cancel();
      if (!hasResult)
        callback.unavailable(
            "Location is unavailable. Check device location settings and permission.");
      return;
    }
    timeout =
        () -> {
          if (request != generation) return;
          boolean received = hasResult;
          cancel();
          if (!received)
            callback.unavailable(
                "No position available yet. Satellite GPS may not work indoors; Wi-Fi or mobile"
                    + " positioning depends on your phone's location services. Try near a window or"
                    + " outdoors.");
        };
    handler.postDelayed(timeout, 30000);
  }

  static long ageMillis(Location location) {
    long elapsed = location.getElapsedRealtimeNanos();
    if (elapsed > 0) return Math.max(0, (SystemClock.elapsedRealtimeNanos() - elapsed) / 1000000);
    return Math.max(0, System.currentTimeMillis() - location.getTime());
  }

  static boolean usable(Location location) {
    return location != null
        && location.hasAccuracy()
        && location.getAccuracy() > 0
        && location.getAccuracy() <= 20000
        && ageMillis(location) <= 120000
        && Double.isFinite(location.getLatitude())
        && Double.isFinite(location.getLongitude());
  }

  private static Point point(Location location) {
    return new Point(
        location.getLatitude(),
        location.getLongitude(),
        Double.NaN,
        System.currentTimeMillis() - ageMillis(location),
        location.getAccuracy(),
        0);
  }

  void cancel() {
    generation++;
    if (timeout != null) {
      handler.removeCallbacks(timeout);
      timeout = null;
    }
    if (listener != null) {
      try {
        manager.removeUpdates(listener);
      } catch (SecurityException ignored) {
      }
      listener = null;
    }
  }
}
