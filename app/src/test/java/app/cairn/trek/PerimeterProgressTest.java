package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Animatable;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {28, 35},
    qualifiers = "xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PerimeterProgressTest {
  @Before
  public void controlAnimationFrames() {
    org.robolectric.shadows.ShadowChoreographer.setPaused(true);
  }

  @Test
  public void progressFollowsBothButtonShapesAndLeavesTheContentClear() throws Exception {
    for (int width : new int[] {96, 360}) {
      PerimeterProgress progress =
          new PerimeterProgress(RuntimeEnvironment.getApplication(), width == 96 ? 24 : 16);
      progress.layout(0, 0, width, 96);
      progress.showProgress(0.5);
      // Render the final level directly: animation timing is verified separately by lifecycle.
      progress.getProgressDrawable().setLevel(5000);
      Bitmap bitmap = Bitmap.createBitmap(width, 96, Bitmap.Config.ARGB_8888);
      progress.draw(new Canvas(bitmap));
      assertEquals(Color.TRANSPARENT, bitmap.getPixel(width / 2, 48));
      assertEquals(AppPalette.ACCENT, bitmap.getPixel(width - 5, 48));
      assertEquals(AppPalette.OUTLINE, bitmap.getPixel(5, 48));
      File file = new File("build/test-previews/progress-outline-" + width + ".png");
      file.getParentFile().mkdirs();
      try (OutputStream output = new FileOutputStream(file)) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
      bitmap.recycle();
    }
  }

  @Test
  public void animationStopsOnHideDetachAndCompletionAndDoesNotInterceptTheButton() {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Button button = new Button(activity);
      button.setText("Pause download");
      button.setFocusable(false);
      int[] clicks = {0};
      button.setOnClickListener(view -> clicks[0]++);
      button.setOnTouchListener(
          (view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return true;
          });
      ProgressButton control = new ProgressButton(button);
      FrameLayout root = new FrameLayout(activity);
      root.addView(control, new FrameLayout.LayoutParams(320, 100));
      activity.setContentView(root);
      control.progress(true, -1);
      root.measure(
          View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 400, 400);
      Animatable animation = (Animatable) control.bar.getIndeterminateDrawable();
      Bitmap bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
      root.draw(new Canvas(bitmap));
      assertTrue(animation.isRunning());
      long down = android.os.SystemClock.uptimeMillis();
      for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
        MotionEvent event =
            MotionEvent.obtain(
                down,
                down + action * 20,
                action,
                button.getWidth() / 2f,
                button.getHeight() / 2f,
                0);
        assertTrue(
            "Touch at button "
                + button.getWidth()
                + "x"
                + button.getHeight()
                + " in control "
                + control.getWidth()
                + "x"
                + control.getHeight(),
            control.dispatchTouchEvent(event));
        event.recycle();
      }
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(1, clicks[0]);
      control.setVisibility(View.GONE);
      assertFalse(animation.isRunning());
      control.setVisibility(View.VISIBLE);
      root.draw(new Canvas(bitmap));
      assertTrue(animation.isRunning());
      control.progress(true, 0.4);
      assertFalse(animation.isRunning());
      assertEquals(4000, control.bar.getProgress());
      control.progress(true, -1);
      root.draw(new Canvas(bitmap));
      assertTrue(animation.isRunning());
      root.removeView(control);
      assertFalse(animation.isRunning());
      control.progress(false, 0);
      assertEquals(View.GONE, control.bar.getVisibility());
      bitmap.recycle();
    }
  }

  @Test
  public void reducedMotionKeepsAStaticIndicatorWithoutStartingAnAnimation() {
    float scale =
        org.robolectric.util.ReflectionHelpers.getStaticField(
            android.animation.ValueAnimator.class, "sDurationScale");
    org.robolectric.util.ReflectionHelpers.setStaticField(
        android.animation.ValueAnimator.class, "sDurationScale", 0f);
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      PerimeterProgress progress = new PerimeterProgress(controller.get(), 24);
      controller.get().setContentView(progress);
      progress.layout(0, 0, 96, 96);
      progress.showProgress(-1);
      Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
      progress.draw(new Canvas(bitmap));
      assertTrue(progress.isIndeterminate());
      assertFalse(((Animatable) progress.getIndeterminateDrawable()).isRunning());
      assertEquals(AppPalette.ACCENT, bitmap.getPixel(60, 7));
      bitmap.recycle();
    } finally {
      org.robolectric.util.ReflectionHelpers.setStaticField(
          android.animation.ValueAnimator.class, "sDurationScale", scale);
    }
  }
}
