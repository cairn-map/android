package app.cairn.trek;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TrackingService extends Service implements LocationListener {
  public static final String EVENT = "app.cairn.trek.FIX", STOP = "stop", UPDATE = "update";
  public static volatile boolean running = false;
  static final AtomicInteger fixObservers = new AtomicInteger();
  private Store store;
  private LocationManager manager;
  private HandlerThread worker;
  private String trekId;
  private List<Point> route = Collections.emptyList();
  private RouteProximity proximity = new RouteProximity(Collections.emptyList());
  private int outsideFixes = 0;
  private boolean warned = false;
  private long requestedInterval = -1;
  private long notifiedInterval = -1;

  @Override
  public void onCreate() {
    super.onCreate();
    stopService(new Intent(this, DownloadService.class));
    stopService(new Intent(this, RoutingDownloadService.class));
    store = new Store(this);
    manager = getSystemService(LocationManager.class);
    worker = new HandlerThread("CairnRecording");
    worker.start();
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel(
            "recording", "Track recording", NotificationManager.IMPORTANCE_LOW));
    notifications.createNotificationChannel(
        new NotificationChannel("guidance", "Route alerts", NotificationManager.IMPORTANCE_HIGH));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!running) {
      trekId = store.newTrek();
      running = true;
    }
    long interval = store.intervalMillis();
    if (notifiedInterval != interval) {
      startForeground(1, notification("GPS interval: " + interval / 1000 + " seconds"));
      notifiedInterval = interval;
    }
    new Handler(worker.getLooper())
        .post(
            () -> {
              if (!running) return;
              route =
                  store.prefs.getBoolean("follow", false)
                      ? store.read("route")
                      : Collections.emptyList();
              proximity = new RouteProximity(route);
              outsideFixes = 0;
              warned = false;
              try {
                long requested = store.intervalMillis();
                if (requestedInterval == requested) return;
                manager.removeUpdates(this);
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, requested, 0, this, worker.getLooper());
                requestedInterval = requested;
              } catch (SecurityException | IllegalArgumentException e) {
                sendStatus(e.getMessage());
                stopSelf();
              }
            });
    return START_NOT_STICKY;
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, TrackingService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "recording")
        .setSmallIcon(R.drawable.ic_cairn)
        .setContentTitle("Cairn · Recording your hike")
        .setContentText(text)
        .setShowWhen(false)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "Stop recording", stop).build())
        .build();
  }

  @Override
  public void onLocationChanged(Location l) {
    Trace.beginSection("Cairn.fix");
    try {
      recordFix(l);
    } finally {
      Trace.endSection();
    }
  }

  private void recordFix(Location l) {
    if (!running
        || !l.hasAccuracy()
        || l.getAccuracy() > 100
        || SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() > 120_000_000_000L)
      return;
    Point p =
        new Point(
            l.getLatitude(),
            l.getLongitude(),
            l.hasAltitude() ? l.getAltitude() : Double.NaN,
            l.getTime(),
            l.getAccuracy(),
            0,
            l.hasVerticalAccuracy() ? l.getVerticalAccuracyMeters() : Float.NaN);
    try {
      store.add(trekId, p);
    } catch (android.database.SQLException e) {
      sendStatus("Recording stopped: storage unavailable");
      stopSelf();
      return;
    }
    // The foreground notification describes recording state, never individual fixes.
    // Keep it static between user interval changes to avoid waking System UI.
    if (store.prefs.getBoolean("offRoute", true) && route.size() > 1 && p.accuracy <= 50) {
      double distance = proximity.distance(p);
      if (distance > Math.max(50, p.accuracy * 2)) outsideFixes++;
      else outsideFixes = 0;
      if (distance < 25) warned = false;
      if (outsideFixes >= 2 && !warned) {
        warned = true;
        getSystemService(NotificationManager.class)
            .notify(
                2,
                new Notification.Builder(this, "guidance")
                    .setSmallIcon(R.drawable.ic_cairn)
                    .setContentTitle("Check your route")
                    .setContentText(
                        "You appear to be "
                            + Math.round(distance)
                            + " m from your route. Open Cairn to check.")
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            0,
                            new Intent(this, MainActivity.class),
                            PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build());
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator())
          vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 180, 120, 180}, -1));
      }
    }
    // Hidden activities reload persisted fixes on resume; there is no receiver to wake now.
    if (fixObservers.get() > 0)
      sendBroadcast(
          new Intent(EVENT)
              .setPackage(getPackageName())
              .putExtra("lat", p.lat)
              .putExtra("lon", p.lon)
              .putExtra("time", p.time)
              .putExtra("accuracy", p.accuracy)
              .putExtra("verticalAccuracy", p.verticalAccuracy)
              .putExtra("elevation", p.elevation));
  }

  private void sendStatus(String message) {
    sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("error", message));
  }

  @Override
  public void onProviderDisabled(String provider) {
    sendStatus("GPS is switched off. Recording has stopped.");
    stopSelf();
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {}

  @Override
  public void onDestroy() {
    running = false;
    if (manager != null) manager.removeUpdates(this);
    if (worker != null) {
      new Handler(worker.getLooper())
          .post(
              () -> {
                manager.removeUpdates(this);
                store.close();
              });
      worker.quitSafely();
    }
    sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("stopped", true));
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
