package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.*;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

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
      assertScreenOverlay(feedback.view(), first.getWindow(), topMargin);
      Object firstPanel = feedback.view().getParent();
      feedback.update("Downloading · 10%", true);
      feedback.show("Region ready", 3500);
      assertSame(firstPanel, feedback.view().getParent());
      AlertDialog second = CairnDialogs.drawer(activity).setTitle("Routing data").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertScreenOverlay(feedback.view(), second.getWindow(), topMargin);
      second.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertScreenOverlay(feedback.view(), first.getWindow(), topMargin);
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
      AlertDialog drawer = CairnDialogs.drawer(activity).setTitle("Battery & GPS").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      feedback.show("Position found", 3500);
      assertScreenOverlay(feedback.view(), drawer.getWindow(), topMargin);
      feedback.clear();
      assertEquals(View.GONE, feedback.view().getVisibility());
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      feedback.show("GPX exported", 3500);
      assertScreenOverlay(feedback.view(), drawer.getWindow(), topMargin);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
      assertEquals(View.GONE, feedback.view().getVisibility());
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      drawer.dismiss();
    }
  }

  private static void assertScreenOverlay(TextView banner, Window host, int topMargin) {
    assertNotSame(host.getDecorView(), banner.getParent());
    WindowManager.LayoutParams panel =
        (WindowManager.LayoutParams) banner.getRootView().getLayoutParams();
    assertSame(host.getDecorView().getWindowToken(), panel.token);
    assertEquals(
        Gravity.TOP | Gravity.CENTER_HORIZONTAL,
        panel.gravity & (Gravity.VERTICAL_GRAVITY_MASK | Gravity.HORIZONTAL_GRAVITY_MASK));
    assertEquals(0, panel.y);
    assertTrue((panel.flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);
    assertTrue((panel.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
    assertTrue((panel.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
    if (Build.VERSION.SDK_INT >= 30) assertEquals(0, panel.getFitInsetsTypes());
    assertEquals(topMargin, ((FrameLayout.LayoutParams) banner.getLayoutParams()).topMargin);
  }
}
