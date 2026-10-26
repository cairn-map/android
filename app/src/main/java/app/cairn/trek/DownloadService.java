package app.cairn.trek;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

public final class DownloadService extends Service {
  public static final String EVENT = "app.cairn.trek.DOWNLOAD", PAUSE = "pause";
  public static volatile boolean running = false;
  public static volatile String activePack;
  static volatile int completedTiles, totalTiles;
  private volatile boolean cancelled = false;
  private volatile Thread coordinator;
  IgnClient client = new IgnClient();
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
      cancelDownload();
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
    completedTiles = totalTiles = 0;
    cancelled = false;
    startForeground(3, notification("Preparing your map…", 0, 0));
    worker.execute(() -> download(id));
    return START_NOT_STICKY;
  }

  private void download(String id) {
    coordinator = Thread.currentThread();
    String outcome = "paused", error = null;
    int done = 0, total = 0;
    ExecutorService transfers = Executors.newFixedThreadPool(IgnClient.PARALLELISM);
    try (PlanIgnStore store = new PlanIgnStore(this)) {
      try {
        PlanIgnStore.Pack pack = null;
        for (PlanIgnStore.Pack candidate : store.packs())
          if (candidate.id.equals(id)) pack = candidate;
        if (pack == null) throw new java.io.IOException("Map package no longer exists");
        done = pack.done;
        total = pack.total;
        store.state(id, "downloading", null);
        progress("Saving offline map…", done, total, false);
        long lastProgress = 0;
        // Snapshot only unfinished coordinates; no bytes or database handles go to workers.
        java.util.Iterator<TilePlanner.Tile> remaining =
            store.next(id, TilePlanner.MAX_TILES).iterator();
        CompletionService<Runnable> completed = new ExecutorCompletionService<>(transfers);
        int inFlight = 0;
        while (!cancelled && !TrackingService.running) {
          while (inFlight < IgnClient.PARALLELISM
              && remaining.hasNext()
              && !cancelled
              && !TrackingService.running) {
            TilePlanner.Tile tile = remaining.next();
            PlanIgnStore.Cached cached = store.validators(tile);
            completed.submit(
                () -> {
                  IgnClient.Result result =
                      client.fetch(
                          tile,
                          cached == null ? null : cached.etag,
                          cached == null ? null : cached.modified,
                          () -> cancelled || TrackingService.running);
                  // Commit on the coordinator only, after confirming the job is still active.
                  return () -> store.completeTile(id, tile, result);
                });
            inFlight++;
          }
          if (inFlight == 0) break;
          Future<Runnable> result = completed.take();
          if (cancelled || TrackingService.running) break;
          result.get().run();
          inFlight--;
          done++;
          long now = android.os.SystemClock.elapsedRealtime();
          if (now - lastProgress >= 1000) {
            lastProgress = now;
            String message = "Saving offline map…";
            getSystemService(NotificationManager.class)
                .notify(3, notification(message, done, total));
            progress(message, done, total, false);
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
      coordinator = null;
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
                progress(message, finalDone, finalTotal, true);
                activePack = null;
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
    completedTiles = done;
    totalTiles = total;
    sendBroadcast(
        new Intent(EVENT)
            .setPackage(getPackageName())
            .putExtra("message", text)
            .putExtra("pack", activePack)
            .putExtra("done", done)
            .putExtra("total", total)
            .putExtra("finished", finished));
  }

  @Override
  public void onTimeout(int startId, int type) {
    cancelDownload();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancelDownload();
    worker.shutdown();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  private void cancelDownload() {
    cancelled = true;
    Thread thread = coordinator;
    if (thread != null) thread.interrupt();
    client.cancel();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
