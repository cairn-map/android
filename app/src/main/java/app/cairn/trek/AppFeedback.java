package app.cairn.trek;

import android.app.Activity;
import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.util.*;

/** One foreground feedback banner across the map and dialogs. Progress replaces, notices queue. */
final class AppFeedback implements AutoCloseable {
  private static final Map<Activity, AppFeedback> owners = new WeakHashMap<>();
  private final Activity activity;
  private final TextView banner;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<Window> windows = new ArrayList<>();
  private final ArrayDeque<Notice> queue = new ArrayDeque<>();
  private FrameLayout overlay;
  private Window overlayHost;
  private Notice current;
  private String progress = "", lastUpdate = "";
  private final Runnable advance =
      () -> {
        current = null;
        next();
      };

  private static final class Notice {
    final String text;
    final int duration;
    final String key;

    Notice(String text, int duration, String key) {
      this.text = text;
      this.duration = duration;
      this.key = key;
    }
  }

  AppFeedback(Activity activity) {
    this.activity = activity;
    owners.put(activity, this);
    windows.add(activity.getWindow());
    banner = new TextView(activity);
    banner.setTextSize(14);
    banner.setTextColor(AppPalette.TEXT);
    banner.setGravity(Gravity.CENTER);
    banner.setPadding(dp(24), dp(16), dp(24), dp(16));
    banner.setElevation(dp(16));
    banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    GradientDrawable background = new GradientDrawable();
    background.setColor(AppPalette.SURFACE);
    background.setCornerRadius(dp(24));
    background.setStroke(dp(1), AppPalette.OUTLINE);
    banner.setBackground(background);
    render();
  }

  TextView view() {
    return banner;
  }

  void show(String text, int duration) {
    show(text, duration, "");
  }

  void show(String text, int duration, String key) {
    if (text == null || text.isEmpty()) return;
    if (current != null && current.text.equals(text)) return;
    if (!key.isEmpty()) {
      queue.removeIf(notice -> notice.key.equals(key));
      if (current != null && current.key.equals(key)) {
        main.removeCallbacks(advance);
        current = new Notice(text, duration, key);
        render();
        schedule();
        return;
      }
    }
    for (Notice notice : queue) if (notice.text.equals(text)) return;
    // Avoid a long backlog after a burst of file imports or repeated taps.
    if (queue.size() == 5) queue.removeFirst();
    queue.addLast(new Notice(text, duration, key));
    if (current == null) next();
  }

  void update(String text, boolean busy) {
    progress = busy ? text : "";
    if (!busy && !text.isEmpty() && !text.equals(lastUpdate)) show(text, 6000, "update");
    lastUpdate = text;
    render();
  }

  private void next() {
    current = queue.pollFirst();
    render();
    schedule();
  }

  private void schedule() {
    if (current == null) return;
    int duration = current.duration;
    AccessibilityManager accessibility = activity.getSystemService(AccessibilityManager.class);
    if (Build.VERSION.SDK_INT >= 29 && accessibility != null)
      duration =
          accessibility.getRecommendedTimeoutMillis(
              duration, AccessibilityManager.FLAG_CONTENT_TEXT);
    main.postDelayed(advance, duration);
  }

  private void render() {
    Window window = windows.get(windows.size() - 1);
    String text = current == null ? progress : current.text;
    banner.setText(text);
    banner.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    // A subwindow inherits its parent drawer's frame even with LAYOUT_IN_SCREEN.
    // Use a sibling application window so every banner has the same screen origin.
    boolean overDialog = windows.size() > 1 && !text.isEmpty();
    if (overlay != null && (!overDialog || overlayHost != window)) {
      activity.getWindowManager().removeViewImmediate(overlay);
      overlay = null;
      overlayHost = null;
    }
    FrameLayout decor;
    if (overDialog) {
      if (overlay == null) {
        overlay = new FrameLayout(activity);
        overlayHost = window;
      }
      decor = overlay;
    } else decor = (FrameLayout) activity.getWindow().getDecorView();
    if (banner.getParent() != decor) {
      if (banner.getParent() != null) ((ViewGroup) banner.getParent()).removeView(banner);
      FrameLayout.LayoutParams params =
          new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      params.setMargins(dp(24), dp(12), dp(24), 0);
      decor.addView(banner, params);
    }
    WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) banner.getLayoutParams();
    params.topMargin = dp(12) + (insets == null ? 0 : insets.getSystemWindowInsetTop());
    banner.setLayoutParams(params);
    banner.bringToFront();
    if (overDialog && overlay.getParent() == null) {
      WindowManager.LayoutParams panel =
          new WindowManager.LayoutParams(
              -1,
              -2,
              WindowManager.LayoutParams.TYPE_APPLICATION,
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                  | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
              PixelFormat.TRANSLUCENT);
      panel.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
      panel.token = activity.getWindow().getDecorView().getApplicationWindowToken();
      if (Build.VERSION.SDK_INT >= 30) panel.setFitInsetsTypes(0);
      activity.getWindowManager().addView(overlay, panel);
    }
  }

  private static AppFeedback owner(Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) return owners.get(context);
      context = ((ContextWrapper) context).getBaseContext();
    }
    return null;
  }

  static void opened(Context context, Window window) {
    AppFeedback feedback = owner(context);
    if (feedback == null) return;
    feedback.windows.add(window);
    feedback.render();
  }

  static void closed(Context context, Window window) {
    AppFeedback feedback = owner(context);
    if (feedback == null) return;
    feedback.windows.remove(window);
    feedback.render();
  }

  void clear() {
    main.removeCallbacksAndMessages(null);
    current = null;
    queue.clear();
    progress = "";
    render();
  }

  @Override
  public void close() {
    clear();
    owners.remove(activity);
    if (banner.getParent() != null) ((ViewGroup) banner.getParent()).removeView(banner);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
