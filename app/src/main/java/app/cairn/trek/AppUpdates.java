package app.cairn.trek;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;

/** A user-triggered check, download, verification and installation, with inline progress. */
final class AppUpdates {
  interface Listener {
    void changed(String status, boolean busy);
  }

  interface Verifier {
    void verify(File apk, UpdateManifest manifest, BooleanSupplier stop) throws Exception;
  }

  private final Activity activity;
  private final Supplier<ReleaseClient> clients;
  private final Verifier verifier;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private ReleaseClient client;
  private Future<?> transfer;
  private Listener listener;
  private volatile boolean active, canceled;
  private boolean busy, waitingForPermission;
  private volatile int generation;
  private String status = "";
  private int downloadPercent = -1;

  double fraction() {
    return downloadPercent < 0 ? -1 : downloadPercent / 100.0;
  }

  private final Runnable dismissStatus =
      () -> {
        status = "";
        publish();
      };

  AppUpdates(Activity activity) {
    this(
        activity,
        ReleaseClient::new,
        (apk, manifest, stop) -> ApkVerifier.verify(activity, apk, manifest, stop));
  }

  AppUpdates(Activity activity, Supplier<ReleaseClient> clients, Verifier verifier) {
    this.activity = activity;
    this.clients = clients;
    this.verifier = verifier;
  }

  void listen(Listener listener) {
    this.listener = listener;
    publish();
  }

  void resume() {
    active = true;
    if (waitingForPermission) {
      waitingForPermission = false;
      if (activity.getPackageManager().canRequestPackageInstalls()) update();
      else progress("Allow Cairn to install updates in Android settings, then tap Update again.");
    }
  }

  void pause() {
    active = false;
    if (busy) cancel();
  }

  void close() {
    pause();
    listener = null;
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
  }

  private boolean stop() {
    return canceled || !active || TrackingService.running || Thread.currentThread().isInterrupted();
  }

  void update() {
    if (!active || busy) return;
    if (TrackingService.running) {
      progress("Finish your hike recording before updating Cairn.");
      return;
    }
    canceled = false;
    busy = true;
    int epoch = ++generation;
    ReleaseClient operation = clients.get();
    client = operation;
    BooleanSupplier stopped = () -> epoch != generation || stop();
    progress("Checking for updates…");
    transfer =
        worker.submit(
            () -> {
              try {
                long installed = ApkVerifier.installedVersion(activity);
                UpdateFiles.discardInstalled(activity, installed);
                if (stopped.getAsBoolean()) throw new InterruptedIOException("Update canceled");
                UpdateManifest manifest;
                if (UpdateFiles.ready(activity)) {
                  manifest = UpdateFiles.read(activity);
                } else {
                  ReleaseClient.Release release = operation.latest(stopped);
                  manifest = release.manifest;
                  if (manifest.versionCode <= installed) {
                    complete(epoch, "You’re up to date · Cairn " + BuildConfig.VERSION_NAME, false);
                    return;
                  }
                  if (manifest.minSdk > Build.VERSION.SDK_INT)
                    throw new IOException("This update requires a newer Android version");
                  File directory = UpdateFiles.directory(activity);
                  if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IOException("Cannot create update folder");
                  if (new StatFs(directory.getPath()).getAvailableBytes()
                      < manifest.size + 20L * 1024 * 1024)
                    throw new IOException("Not enough space to download this update");
                  UpdateFiles.clear(activity);
                  File part = new File(directory, "update.apk.part");
                  boolean saved = false;
                  try {
                    report(epoch, "Downloading Cairn " + manifest.versionName + "…");
                    operation.download(
                        release,
                        part,
                        stopped,
                        percent -> report(epoch, "Downloading update…", percent));
                    report(epoch, "Verifying update…");
                    verifier.verify(part, manifest, stopped);
                    if (stopped.getAsBoolean()) throw new InterruptedIOException("Update canceled");
                    UpdateFiles.save(activity, part, manifest);
                    saved = true;
                  } finally {
                    part.delete();
                    if (!saved) UpdateFiles.clear(activity);
                  }
                  complete(epoch, "Update verified · opening Android installer…", true);
                  return;
                }
                // A canceled installer may leave a staged APK. Reverify it before every retry.
                report(epoch, "Verifying downloaded update…");
                try {
                  verifier.verify(UpdateFiles.apk(activity), manifest, stopped);
                } catch (Exception error) {
                  if (!stopped.getAsBoolean()) UpdateFiles.clear(activity);
                  throw error;
                }
                if (stopped.getAsBoolean()) throw new InterruptedIOException("Update canceled");
                complete(epoch, "Update verified · opening Android installer…", true);
              } catch (Exception error) {
                complete(
                    epoch,
                    stopped.getAsBoolean()
                        ? "Update canceled. Tap Update to retry."
                        : "Could not update: " + error.getMessage(),
                    false);
              }
            });
  }

  private void report(int epoch, String message) {
    report(epoch, message, -1);
  }

  private void report(int epoch, String message, int percent) {
    main.post(
        () -> {
          if (active && epoch == generation && !canceled) {
            progress(message, percent);
          }
        });
  }

  private void complete(int epoch, String message, boolean install) {
    main.post(
        () -> {
          if (!active || epoch != generation || canceled) return;
          busy = false;
          if (TrackingService.running) {
            progress("Finish your hike recording before updating Cairn.");
            return;
          }
          progress(message);
          if (install) launchInstaller();
        });
  }

  private void launchInstaller() {
    if (!active || TrackingService.running) return;
    try {
      if (!activity.getPackageManager().canRequestPackageInstalls()) {
        waitingForPermission = true;
        progress(
            "Allow Cairn updates in Android settings. Installation will continue when you return.");
        activity.startActivity(
            new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
      } else {
        activity.startActivity(installIntent(activity));
        progress("Confirm the update in Android’s installer.");
      }
    } catch (ActivityNotFoundException | SecurityException error) {
      waitingForPermission = false;
      progress("Android could not open update installation. Check install permission in Settings.");
    }
  }

  @SuppressWarnings("deprecation") // Explicit platform install flow; source hint is legacy only.
  static Intent installIntent(Context context) {
    Uri uri = UpdateFileProvider.uri(context);
    Intent intent =
        new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getPackageName())
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("Cairn update", uri));
    return intent;
  }

  private void cancel() {
    canceled = true;
    generation++;
    if (client != null) client.cancel();
    if (transfer != null) transfer.cancel(true);
    busy = false;
    progress("Update canceled. Tap Update to retry.");
  }

  private void progress(String message) {
    progress(message, -1);
  }

  private void progress(String message, int percent) {
    downloadPercent = busy ? percent : -1;
    main.removeCallbacks(dismissStatus);
    status = message;
    publish();
    if (!busy && !message.isEmpty()) {
      android.view.accessibility.AccessibilityManager accessibility =
          activity.getSystemService(android.view.accessibility.AccessibilityManager.class);
      int timeout = 6000;
      if (Build.VERSION.SDK_INT >= 29 && accessibility != null)
        timeout =
            accessibility.getRecommendedTimeoutMillis(
                timeout, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT);
      main.postDelayed(dismissStatus, timeout);
    }
  }

  private void publish() {
    if (listener != null) listener.changed(status, busy);
  }
}
