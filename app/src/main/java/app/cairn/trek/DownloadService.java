package app.cairn.trek;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

public final class DownloadService extends Service {
  public static final String EVENT = "app.cairn.trek.DOWNLOAD", PAUSE = "pause";
  public static volatile boolean running = false;
  public static volatile String activePack;
  private volatile boolean cancelled = false;
  private final IgnClient client = new IgnClient();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "downloads", "Offline map downloads", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || PAUSE.equals(intent.getAction())) {
      cancelled = true;
      client.cancel();
      if (!running) stopSelf();
      return START_NOT_STICKY;
    }
    if (running) return START_NOT_STICKY;
    String id = intent.getStringExtra("pack");
    if (id == null || TrackingService.running) {
      stopSelf();
      return START_NOT_STICKY;
    }
    running = true;
    activePack = id;
    cancelled = false;
    startForeground(3, notification("Preparing your map…", 0, 0));
    worker.execute(() -> download(id));
    return START_NOT_STICKY;
  }

  private void download(String id) {
    String outcome = "paused", error = null;
    int done = 0, total = 0;
    ExecutorService transfers = Executors.newFixedThreadPool(4);
    try (PlanIgnStore store = new PlanIgnStore(this)) {
      try {
        PlanIgnStore.Pack pack = null;
        for (PlanIgnStore.Pack candidate : store.packs())
          if (candidate.id.equals(id)) pack = candidate;
        if (pack == null) throw new java.io.IOException("Map package no longer exists");
        done = pack.done;
        total = pack.total;
        store.state(id, "downloading", null);
        long lastProgress = 0;
        while (!cancelled && !TrackingService.running) {
          java.util.List<TilePlanner.Tile> batch = store.next(id, 4);
          if (batch.isEmpty()) break;
          CompletionService<Runnable> completed = new ExecutorCompletionService<>(transfers);
          for (TilePlanner.Tile tile : batch) {
            PlanIgnStore.Cached cached = store.cached(tile);
            completed.submit(() -> {
              IgnClient.Result result = client.fetch(
                  tile, cached == null ? null : cached.etag,
                  cached == null ? null : cached.modified,
                  () -> cancelled || TrackingService.running);
              // Commit on the coordinator only, after confirming the job is still active.
              return () -> store.completeTile(id, tile, result);
            });
          }
          for (int i = 0; i < batch.size() && !cancelled && !TrackingService.running; i++) {
            Future<Runnable> result;
            do {
              result = completed.poll(200, TimeUnit.MILLISECONDS);
            } while (result == null && !cancelled && !TrackingService.running);
            if (cancelled || TrackingService.running) break;
            result.get().run();
            done++;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastProgress >= 1000) {
              lastProgress = now;
              String message = "Plan IGN · " + done + " / " + total + " tiles";
              getSystemService(NotificationManager.class)
                  .notify(3, notification(message, done, total));
              progress(message, done, total, false);
            }
          }
        }
        if (!cancelled && !TrackingService.running && store.finish(id)) outcome = "complete";
      } catch (Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        if (!cancelled && !TrackingService.running) error = cause.getMessage();
      } finally {
        transfers.shutdownNow();
        client.cancel();
      }
      store.state(id, outcome, error);
    } catch (Exception e) {
      error = e.getMessage();
    } finally {
      transfers.shutdownNow();
      String message =
          "complete".equals(outcome)
              ? "Plan IGN map ready offline"
              : error == null ? "Map download paused" : error;
      int finalDone = done, finalTotal = total;
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                running = false;
                activePack = null;
                progress(message, finalDone, finalTotal, true);
                stopSelf();
              });
    }
  }

  private Notification notification(String text, int done, int total) {
    PendingIntent open =
        PendingIntent.getActivity(
            this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            3,
            new Intent(this, DownloadService.class).setAction(PAUSE),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "downloads")
        .setSmallIcon(R.drawable.ic_cairn)
        .setContentTitle("Cairn · Saving Plan IGN")
        .setContentText(text)
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(total, done, total == 0)
        .addAction(new Notification.Action.Builder(null, "Pause", pause).build())
        .build();
  }

  private void progress(String text, int done, int total, boolean finished) {
    sendBroadcast(
        new Intent(EVENT)
            .setPackage(getPackageName())
            .putExtra("message", text)
            .putExtra("done", done)
            .putExtra("total", total)
            .putExtra("finished", finished));
  }

  @Override
  public void onTimeout(int startId, int type) {
    cancelled = true;
    client.cancel();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancelled = true;
    client.cancel();
    worker.shutdown();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
