package app.cairn.trek;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit graph preparation only; cancellable and resumable without keeping the app open. */
public final class RoutingDownloadService extends Service {
  static final String EVENT = "app.cairn.trek.ROUTING_DOWNLOAD";
  static volatile boolean running;
  static volatile Outcome lastOutcome;
  static volatile String activeRequest;
  static volatile double fraction = -1;

  static final class Outcome {
    final String request, message;
    final boolean success;

    Outcome(String request, String message, boolean success) {
      this.request = request;
      this.message = message;
      this.success = success;
    }
  }

  private static volatile RoutingDownloadService activeInstance;
  private volatile boolean canceled;
  GraphDownloader client = new GraphDownloader();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "routing-data", "Routing downloads", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || "pause".equals(intent.getAction()) || TrackingService.running) {
      cancel();
      if (!running) stopSelf();
      return START_NOT_STICKY;
    }
    if (running) return START_NOT_STICKY;
    ArrayList<String> names = intent.getStringArrayListExtra("regions");
    if (names == null || names.isEmpty() || names.size() > 24) {
      stopSelf();
      return START_NOT_STICKY;
    }
    for (String name : names)
      if (!RoutingData.validName(name)) {
        stopSelf();
        return START_NOT_STICKY;
      }
    running = true;
    activeInstance = this;
    canceled = false;
    String request = intent.getStringExtra("request");
    activeRequest = request;
    fraction = -1;
    startForeground(4, notification("Preparing routing data…"));
    worker.execute(
        () -> {
          String message = "Routing data ready offline";
          boolean success = false;
          try {
            for (int index = 0; index < names.size(); index++) {
              String name = names.get(index);
              String regionProgress = "Routing data · " + (index + 1) + "/" + names.size();
              int completedRegions = index;
              fraction = index / (double) names.size();
              if (canceled || TrackingService.running) throw new InterruptedIOException("Paused");
              if (!new File(RoutingData.directory(this), name).isFile())
                client.download(
                    RoutingData.directory(this),
                    name,
                    () -> canceled || TrackingService.running,
                    (done, total) -> {
                      fraction =
                          (completedRegions + done / (double) Math.max(1, total)) / names.size();
                      String progress = regionProgress + " · downloading…";
                      getSystemService(NotificationManager.class).notify(4, notification(progress));
                      sendProgress(request, progress, false, false);
                    },
                    bytes -> {
                      android.os.storage.StorageManager storage =
                          getSystemService(android.os.storage.StorageManager.class);
                      if (storage.getAllocatableBytes(
                              storage.getUuidForPath(RoutingData.directory(this)))
                          < bytes) throw new IOException("Not enough free storage");
                    });
            }
            if (canceled || TrackingService.running) throw new InterruptedIOException("Paused");
            success = true;
          } catch (Exception e) {
            message =
                canceled || TrackingService.running
                    ? "Routing download paused"
                    : "Routing download paused: " + e.getMessage();
          }
          String outcome = message;
          boolean completed = success;
          new Handler(Looper.getMainLooper())
              .post(
                  () -> {
                    boolean ready = completed && !canceled && !TrackingService.running;
                    String finalMessage = completed && !ready ? "Routing download paused" : outcome;
                    if (activeInstance == this) {
                      running = false;
                      activeInstance = null;
                      activeRequest = null;
                      fraction = -1;
                    }
                    lastOutcome = new Outcome(request, finalMessage, ready);
                    sendProgress(request, finalMessage, true, ready);
                    sendBroadcast(
                        new Intent(DownloadService.EVENT)
                            .setPackage(getPackageName())
                            .putExtra("message", finalMessage)
                            .putExtra("finished", true));
                    stopSelf();
                  });
        });
    return START_NOT_STICKY;
  }

  private void sendProgress(String request, String message, boolean finished, boolean success) {
    sendBroadcast(
        new Intent(EVENT)
            .setPackage(getPackageName())
            .putExtra("request", request)
            .putExtra("message", message)
            .putExtra("fraction", fraction)
            .putExtra("finished", finished)
            .putExtra("success", success));
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this, 4, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            5,
            new Intent(this, RoutingDownloadService.class).setAction("pause"),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "routing-data")
        .setSmallIcon(R.drawable.ic_cairn)
        .setContentTitle("Cairn · Offline routing")
        .setContentText(text)
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(10000, (int) (Math.max(0, fraction) * 10000), fraction < 0)
        .addAction(new Notification.Action.Builder(null, "Pause", pause).build())
        .build();
  }

  private void cancel() {
    canceled = true;
    client.cancel();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    cancel();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancel();
    if (activeInstance == this) {
      running = false;
      activeInstance = null;
    }
    worker.shutdownNow();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
