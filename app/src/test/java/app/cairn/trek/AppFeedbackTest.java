package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.*;
import android.os.Looper;
import android.view.View;
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
  public void oneBannerFollowsNestedDrawersAndReturnsToTheMap() {
    try (ActivityController<Activity> controller =
            Robolectric.buildActivity(Activity.class).setup();
        AppFeedback feedback = new AppFeedback(controller.get())) {
      Activity activity = controller.get();
      feedback.show("Position found", 6000);
      AlertDialog first = CairnDialogs.drawer(activity).setTitle("Saved maps").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertSame(first.getWindow().getDecorView(), feedback.view().getParent());
      AlertDialog second = CairnDialogs.drawer(activity).setTitle("Routing data").show();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertSame(second.getWindow().getDecorView(), feedback.view().getParent());
      second.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertSame(first.getWindow().getDecorView(), feedback.view().getParent());
      first.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertSame(activity.getWindow().getDecorView(), feedback.view().getParent());
      feedback.clear();
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
      assertEquals(View.GONE, feedback.view().getVisibility());
    }
  }
}
