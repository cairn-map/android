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
  static volatile boolean running;
  private static volatile RoutingDownloadService activeInstance;
  private volatile boolean canceled;
  private final GraphDownloader client = new GraphDownloader();
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
    startForeground(4, notification("Preparing routing data…"));
    worker.execute(
        () -> {
          String message = "Routing data ready offline";
          try {
            for (String name : names) {
              if (canceled || TrackingService.running) throw new InterruptedIOException("Paused");
              if (!new File(RoutingData.directory(this), name).isFile())
                client.download(
                    RoutingData.directory(this),
                    name,
                    () -> canceled || TrackingService.running,
                    text ->
                        getSystemService(NotificationManager.class).notify(4, notification(text)),
                    bytes -> {
                      android.os.storage.StorageManager storage =
                          getSystemService(android.os.storage.StorageManager.class);
                      if (storage.getAllocatableBytes(
                              storage.getUuidForPath(RoutingData.directory(this)))
                          < bytes) throw new IOException("Not enough free storage");
                    });
            }
          } catch (Exception e) {
            message =
                canceled || TrackingService.running
                    ? "Routing download paused"
                    : "Routing download paused: " + e.getMessage();
          }
          String outcome = message;
          new Handler(Looper.getMainLooper())
              .post(
                  () -> {
                    if (activeInstance == this) {
                      running = false;
                      activeInstance = null;
                    }
                    sendBroadcast(
                        new Intent(DownloadService.EVENT)
                            .setPackage(getPackageName())
                            .putExtra("message", outcome)
                            .putExtra("finished", true));
                    stopSelf();
                  });
        });
    return START_NOT_STICKY;
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
