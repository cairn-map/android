package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.*;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppFeedbackTest {
  @Test
  public void independentNoticesQueueDuplicatesCollapseAndProgressReturnsAfterTheQueue() {
    try (ActivityController<Activity> controller =
            Robolectric.buildActivity(Activity.class).setup();
        AppFeedback feedback = new AppFeedback(controller.get())) {
      feedback.update("Downloading · 10%", true);
      feedback.show("GPX exported", 3500);
      feedback.show("GPX exported", 3500);
      feedback.show("Region ready", 3500);
      feedback.update("Downloading · 80%", true);
      assertEquals("GPX exported", feedback.view().getText().toString());
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
      assertEquals("Region ready", feedback.view().getText().toString());
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
      assertEquals("Downloading · 80%", feedback.view().getText().toString());
      feedback.update("Update ready", false);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
      assertEquals(View.GONE, feedback.view().getVisibility());
    }
  }

  @Test
  public void oneBannerStaysAtScreenTopAboveContentSizedAndNestedDrawers() {
    try (ActivityController<Activity> controller =
            Robolectric.buildActivity(Activity.class).setup();
        AppFeedback feedback = new AppFeedback(controller.get())) {
      Activity activity = controller.get();
      feedback.show("Position found", 6000);
      int topMargin = ((FrameLayout.LayoutParams) feedback.view().getLayoutParams()).topMargin;
      AlertDialog first = CairnDialogs.drawer(activity).setTitle("Saved maps").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertScreenOverlay(feedback.view(), activity.getWindow(), topMargin);
      Object firstPanel = feedback.view().getParent();
      feedback.update("Downloading · 10%", true);
      feedback.show("Region ready", 3500);
      assertSame(firstPanel, feedback.view().getParent());
      AlertDialog second = CairnDialogs.drawer(activity).setTitle("Routing data").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertScreenOverlay(feedback.view(), activity.getWindow(), topMargin);
      second.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertScreenOverlay(feedback.view(), activity.getWindow(), topMargin);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6000));
      assertEquals("Region ready", feedback.view().getText().toString());
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
      assertEquals("Downloading · 10%", feedback.view().getText().toString());
      first.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      feedback.clear();
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
      assertEquals(View.GONE, feedback.view().getVisibility());
    }
  }

  @Test
  public void feedbackCreatedAfterOpeningADrawerDisappearsWhenClearedOrExpired() {
    try (ActivityController<Activity> controller =
            Robolectric.buildActivity(Activity.class).setup();
        AppFeedback feedback = new AppFeedback(controller.get())) {
      Activity activity = controller.get();
      int topMargin = ((FrameLayout.LayoutParams) feedback.view().getLayoutParams()).topMargin;
      AlertDialog drawer = CairnDialogs.drawer(activity).setTitle("GPS").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      feedback.show("Position found", 3500);
      assertScreenOverlay(feedback.view(), activity.getWindow(), topMargin);
      feedback.clear();
      assertEquals(View.GONE, feedback.view().getVisibility());
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      feedback.show("GPX exported", 3500);
      assertScreenOverlay(feedback.view(), activity.getWindow(), topMargin);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
      assertEquals(View.GONE, feedback.view().getVisibility());
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      drawer.dismiss();
    }
  }

  @Test
  @Config(sdk = 35)
  public void screenCoordinatesCountSystemBarsAndCutoutsOnceAboveDrawers() throws Exception {
    // Robolectric does not run WindowManager's frame calculation for added windows.
    // Run Android's real WindowLayout as well as laying out the banner: checking
    // only its margin missed the cutout-induced movement of the window itself.
    for (int safeTop : new int[] {24, 56}) {
      try (ActivityController<Activity> controller =
              Robolectric.buildActivity(Activity.class).setup();
          AppFeedback feedback = new AppFeedback(controller.get())) {
        Activity activity = controller.get();
        setActivityTopInset(activity, safeTop);
        feedback.show("Position found", 6000);
        int mapTop = ((FrameLayout.LayoutParams) feedback.view().getLayoutParams()).topMargin;
        AlertDialog drawer = CairnDialogs.drawer(activity).setTitle("GPS").show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        setActivityTopInset(activity, safeTop);
        feedback.update("Downloading", true);
        assertOverlayTop(feedback.view(), new Rect(0, 0, 1080, 2400), safeTop, 0, mapTop);
        // A keyboard, a resized task and a nonzero task origin must not add an offset.
        assertOverlayTop(feedback.view(), new Rect(100, 200, 900, 1400), safeTop, 400, mapTop);
        drawer.dismiss();
      }
    }
  }

  private static void setActivityTopInset(Activity activity, int top) {
    Object root =
        ReflectionHelpers.callInstanceMethod(
            activity.getWindow().getDecorView(), "getViewRootImpl");
    ReflectionHelpers.setField(
        root,
        "mLastWindowInsets",
        new WindowInsets.Builder().setSystemWindowInsets(Insets.of(0, top, 0, 0)).build());
  }

  private static void assertOverlayTop(
      TextView banner, Rect bounds, int safeTop, int keyboardHeight, int expectedTop)
      throws Exception {
    Class<?> stateClass = Class.forName("android.view.InsetsState");
    Object state = stateClass.getConstructor().newInstance();
    stateClass.getMethod("setDisplayFrame", Rect.class).invoke(state, bounds);
    addInsetSource(
        state,
        1,
        WindowInsets.Type.statusBars(),
        new Rect(bounds.left, bounds.top, bounds.right, bounds.top + 24));
    addInsetSource(
        state,
        2,
        WindowInsets.Type.ime(),
        new Rect(bounds.left, bounds.bottom - keyboardHeight, bounds.right, bounds.bottom));
    if (safeTop > 24) {
      DisplayCutout cutout =
          new DisplayCutout(
              new Rect(0, safeTop, 0, 0),
              java.util.List.of(
                  new Rect(bounds.centerX() - 40, 0, bounds.centerX() + 40, safeTop)));
      stateClass.getMethod("setDisplayCutout", DisplayCutout.class).invoke(state, cutout);
      addInsetSource(
          state,
          3,
          WindowInsets.Type.displayCutout(),
          new Rect(bounds.left, bounds.top, bounds.right, bounds.top + safeTop));
    }
    Rect cutoutSafe = new Rect();
    stateClass.getMethod("getDisplayCutoutSafe", Rect.class).invoke(state, cutoutSafe);
    Class<?> framesClass = Class.forName("android.window.ClientWindowFrames");
    Object frames = framesClass.getConstructor().newInstance();
    Class<?> layoutClass = Class.forName("android.view.WindowLayout");
    WindowManager.LayoutParams params =
        (WindowManager.LayoutParams) banner.getRootView().getLayoutParams();
    layoutClass
        .getMethod(
            "computeFrames",
            WindowManager.LayoutParams.class,
            stateClass,
            Rect.class,
            Rect.class,
            int.class,
            int.class,
            int.class,
            int.class,
            float.class,
            framesClass)
        .invoke(
            layoutClass.getConstructor().newInstance(),
            params,
            state,
            cutoutSafe,
            bounds,
            bounds.top == 0 ? 1 : 5,
            bounds.width(),
            120,
            WindowInsets.Type.systemBars() | WindowInsets.Type.ime(),
            1f,
            frames);
    Rect frame = (Rect) framesClass.getField("frame").get(frames);
    View overlay = banner.getRootView();
    overlay.measure(
        View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.EXACTLY));
    overlay.layout(0, 0, frame.width(), frame.height());
    assertEquals(
        "Banner's screen position with top inset " + safeTop,
        bounds.top + expectedTop,
        frame.top + banner.getTop());
  }

  private static void addInsetSource(Object state, int id, int type, Rect frame) throws Exception {
    Object source =
        state
            .getClass()
            .getMethod("getOrCreateSource", int.class, int.class)
            .invoke(state, id, type);
    source.getClass().getMethod("setFrame", Rect.class).invoke(source, frame);
    source.getClass().getMethod("setVisible", boolean.class).invoke(source, true);
  }

  private static void assertScreenOverlay(TextView banner, Window host, int topMargin) {
    assertNotSame(host.getDecorView(), banner.getParent());
    WindowManager.LayoutParams panel =
        (WindowManager.LayoutParams) banner.getRootView().getLayoutParams();
    // APPLICATION_PANEL would inherit a bottom drawer's frame, regardless of its
    // gravity and LAYOUT_IN_SCREEN flag. This must be a sibling application window.
    assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION, panel.type);
    assertNotNull(host.getAttributes().token);
    assertNotSame(host.getDecorView().getApplicationWindowToken(), host.getAttributes().token);
    assertSame(host.getAttributes().token, panel.token);
    assertEquals(
        Gravity.TOP | Gravity.CENTER_HORIZONTAL,
        panel.gravity & (Gravity.VERTICAL_GRAVITY_MASK | Gravity.HORIZONTAL_GRAVITY_MASK));
    assertEquals(0, panel.y);
    assertEquals(0, panel.flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
    assertTrue((panel.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
    assertTrue((panel.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
    assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING, panel.softInputMode);
    if (Build.VERSION.SDK_INT >= 30)
      assertEquals(
          WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout(),
          panel.getFitInsetsTypes());
    assertEquals(topMargin, ((FrameLayout.LayoutParams) banner.getLayoutParams()).topMargin);
  }
}
