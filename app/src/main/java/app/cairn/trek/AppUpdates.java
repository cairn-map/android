package app.cairn.trek;

import static app.cairn.trek.AppPalette.*;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Inline, user-initiated updates. Leaving Routes cancels any network operation. */
final class AppUpdates {
  private final Activity activity;
  private final LinearLayout content;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private ReleaseClient client;
  private Future<?> transfer;
  private ReleaseClient.Release offered;
  private TextView status;
  private Button checkButton, downloadButton, installButton, cancelButton, discardButton;
  private volatile boolean active, canceled;
  private boolean busy;
  private int generation;

  AppUpdates(Activity activity) {
    this.activity = activity;
    content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    checkButton = button("Check for updates", this::checkForUpdate);
    content.addView(checkButton);
    status = text("", 14, MUTED, false);
    status.setVisibility(View.GONE);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    content.addView(status);
    downloadButton = button("Download update", this::download);
    installButton = button("Install downloaded update", this::install);
    cancelButton = button("Cancel", () -> cancel("Canceled. Nothing will restart automatically."));
    discardButton = button("Discard downloaded update", () -> {
      UpdateFiles.clear(activity);
      showStatus("Downloaded update removed.");
      refresh();
    });
    for (Button button : new Button[] {downloadButton, installButton, cancelButton, discardButton})
      content.addView(button);
    refresh();
  }

  View view() {
    if (content.getParent() != null) ((ViewGroup) content.getParent()).removeView(content);
    resume();
    return content;
  }

  void resume() {
    active = true;
    refresh(); // Local state only; never contacts the release server.
  }

  void pause() {
    active = false;
    if (busy) cancel("Update canceled. Tap a button to try again.");
  }

  void close() {
    pause();
    if (client != null) client.cancel();
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
  }

  private boolean stop() {
    return canceled || !active || TrackingService.running || Thread.currentThread().isInterrupted();
  }

  private boolean recording() {
    if (!TrackingService.running) return false;
    showStatus("Finish your hike recording before checking, downloading or installing an update.");
    return true;
  }

  private interface Work { Runnable run() throws Exception; }

  private void start(String label, Work action) {
    if (busy || recording()) return;
    busy = true;
    canceled = false;
    int epoch = ++generation;
    showStatus(label);
    refresh();
    transfer = worker.submit(() -> {
      try {
        if (stop()) throw new InterruptedIOException("Update canceled");
        Runnable completed = action.run();
        main.post(() -> {
          if (!active || epoch != generation) return;
          busy = false;
          completed.run();
          refresh();
        });
      } catch (Exception error) {
        main.post(() -> {
          if (!active || epoch != generation) return;
          busy = false;
          showStatus(stop() ? "Update canceled. Tap a button to try again."
              : "Could not complete the update: " + error.getMessage());
          refresh();
        });
      }
    });
  }

  private void checkForUpdate() {
    if (busy || recording()) return;
    offered = null;
    client = new ReleaseClient();
    start("Checking the latest public Cairn release…", () -> {
      ReleaseClient.Release release = client.latest(this::stop);
      long current = ApkVerifier.installedVersion(activity);
      return () -> {
        if (release.manifest.versionCode <= current) {
          showStatus("You’re up to date · Cairn " + BuildConfig.VERSION_NAME);
        } else if (release.manifest.minSdk > Build.VERSION.SDK_INT) {
          showStatus("Cairn " + release.manifest.versionName + " requires a newer Android version.");
        } else {
          offered = release;
          showStatus(String.format(Locale.getDefault(), "Cairn %s is available · %.1f MB\n\n%s",
              release.manifest.versionName, release.manifest.size / 1e6, release.notes));
        }
      };
    });
  }

  private void download() {
    if (busy || offered == null || recording()) return;
    ReleaseClient.Release release = offered;
    client = new ReleaseClient();
    int epoch = generation + 1;
    start("Downloading Cairn " + release.manifest.versionName + "…", () -> {
      File directory = UpdateFiles.directory(activity);
      if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update folder");
      if (new StatFs(directory.getPath()).getAvailableBytes() < release.manifest.size + 20L * 1024 * 1024)
        throw new IOException("Not enough space to download this update");
      UpdateFiles.clear(activity);
      File part = new File(directory, "update.apk.part");
      boolean saved = false;
      try {
        client.download(release, part, this::stop, percent -> main.post(() -> {
          if (active && epoch == generation) showStatus("Downloading… " + percent + "%");
        }));
        ApkVerifier.verify(activity, part, release.manifest, this::stop);
        if (stop()) throw new InterruptedIOException("Update canceled");
        UpdateFiles.save(activity, part, release.manifest);
        saved = true;
        return () -> showStatus("Cairn " + release.manifest.versionName
            + " is downloaded and verified. Tap Install downloaded update to continue.");
      } finally {
        part.delete();
        if (!saved) UpdateFiles.clear(activity);
      }
    });
  }

  private void install() {
    start("Verifying downloaded update…", () -> {
      UpdateManifest update = UpdateFiles.read(activity);
      ApkVerifier.verify(activity, UpdateFiles.apk(activity), update, this::stop);
      return this::launchInstaller;
    });
  }

  private void launchInstaller() {
    if (recording()) return;
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      showStatus("Allow Cairn to install updates in Android settings, return here, then tap Install downloaded update again.");
      new AlertDialog.Builder(activity)
          .setTitle("Allow Cairn updates")
          .setMessage("Android needs your permission to install updates from Cairn. This does not enable background checks.")
          .setNegativeButton("Later", null)
          .setPositiveButton("Open settings", (dialog, which) -> {
            try {
              activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                  Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException error) {
              showStatus("Open Android settings → Apps → Special app access → Install unknown apps → Cairn.");
            }
          }).show();
      return;
    }
    try {
      activity.startActivity(installIntent(activity));
      showStatus("Confirm the update in Android’s installer. If canceled, tap Install downloaded update to try again.");
    } catch (ActivityNotFoundException | SecurityException error) {
      showStatus("Android could not open the installer. Check permission to install updates.");
    }
  }

  static Intent installIntent(Context context) {
    Uri uri = UpdateFileProvider.uri(context);
    Intent intent = new Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newRawUri("Cairn update", uri));
    return intent;
  }

  private void cancel(String message) {
    canceled = true;
    generation++;
    if (client != null) client.cancel();
    if (transfer != null) transfer.cancel(true);
    busy = false;
    showStatus(message);
    refresh();
  }

  private void showStatus(String message) {
    status.setText(message);
    status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void refresh() {
    // Called when Routes reopens, including after Android installs a new version.
    // A canceled installation retains a newer staged APK for retry.
    try {
      long installed = ApkVerifier.installedVersion(activity);
      if (UpdateFiles.discardInstalled(activity, installed)) {
        offered = null;
        showStatus("You’re up to date · Cairn " + BuildConfig.VERSION_NAME);
      }
      if (offered != null && offered.manifest.versionCode <= installed) offered = null;
    } catch (IOException error) {
      // Preserve staged data when installed package state cannot be read.
    }
    status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    checkButton.setEnabled(!busy);
    downloadButton.setVisibility(offered == null ? View.GONE : View.VISIBLE);
    downloadButton.setEnabled(!busy);
    boolean ready = UpdateFiles.ready(activity);
    installButton.setVisibility(ready ? View.VISIBLE : View.GONE);
    installButton.setEnabled(!busy);
    discardButton.setVisibility(ready ? View.VISIBLE : View.GONE);
    discardButton.setEnabled(!busy);
    cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
    view.setPadding(0, 0, 0, dp(16));
    return view;
  }

  private Button button(String title, Runnable action) {
    Button view = new Button(activity);
    view.setText(title);
    view.setAllCaps(false);
    view.setTextColor(ACCENT);
    view.setTextSize(15);
    view.setMinHeight(dp(52));
    view.setStateListAnimator(null);
    GradientDrawable fill = new GradientDrawable();
    fill.setColor(SURFACE);
    fill.setCornerRadius(dp(16));
    fill.setStroke(dp(1), OUTLINE);
    GradientDrawable mask = new GradientDrawable();
    mask.setColor(TEXT);
    mask.setCornerRadius(dp(16));
    view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), fill, mask));
    view.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(10);
    view.setLayoutParams(params);
    return view;
  }

  private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
