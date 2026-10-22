package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {28, 35},
    qualifiers = "w393dp-h851dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class CairnDialogsTest {
  private ActivityController<Activity> controller;
  private Activity activity;
  private AlertDialog dialog;

  @Before
  public void setup() {
    controller = Robolectric.buildActivity(Activity.class).setup();
    activity = controller.get();
  }

  @After
  public void close() {
    if (dialog != null) dialog.dismiss();
    controller.close();
  }

  private View show(AlertDialog.Builder builder) {
    dialog = builder.create();
    boolean[] shown = {false};
    dialog.setOnShowListener(d -> shown[0] = true);
    dialog.show();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(shown[0]);
    assertEquals(R.style.CairnDrawerAnimation, dialog.getWindow().getAttributes().windowAnimations);
    View decor = dialog.getWindow().getDecorView();
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    decor.layout(0, 0, 393, 800);
    return ((FrameLayout) dialog.findViewById(android.R.id.content)).getChildAt(0);
  }

  private void touch(View view, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0);
    view.dispatchTouchEvent(event);
    event.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
  }

  @Test
  @Config(qualifiers = "w960dp-h1280dp")
  public void compactDrawersAndFixedMenusWrapTheirContentOnTablets() {
    View swipe = show(CairnDialogs.drawer(activity).setTitle("GPS").setMessage("Settings"));
    assertEquals(
        560 * activity.getResources().getDisplayMetrics().density,
        dialog.getWindow().getAttributes().width,
        1);
    assertEquals(-2, dialog.getWindow().getAttributes().height);
    assertTrue(swipe.getHeight() < 800);
    dialog.dismiss();
    show(
        CairnDialogs.dialog(activity)
            .setTitle("Actions")
            .setItems(new String[] {"Show", "Export"}, (d, which) -> {}));
    assertTrue(dialog.getWindow().getAttributes().width > 0);
    assertEquals(-2, dialog.getWindow().getAttributes().height);
  }

  @Test
  public void heightAndWidthAreIndependentAndCallerShowListenersStillRun() {
    show(CairnDialogs.drawer(activity).fullHeight().setTitle("Variable list"));
    assertTrue(dialog.getWindow().getAttributes().width > 0);
    assertEquals(-1, dialog.getWindow().getAttributes().height);
    dialog.dismiss();
    show(
        CairnDialogs.drawer(activity)
            .fullWidth()
            .setTitle("Nested actions")
            .setItems(new String[] {"Rename", "Remove"}, (d, which) -> {}));
    assertEquals(-1, dialog.getWindow().getAttributes().width);
    assertEquals(-2, dialog.getWindow().getAttributes().height);
    dialog.dismiss();
    show(CairnDialogs.drawer(activity).fullHeight().fullWidth().setTitle("Library"));
    assertEquals(-1, dialog.getWindow().getAttributes().width);
    assertEquals(-1, dialog.getWindow().getAttributes().height);
  }

  @Test
  public void swipeOnTheTitleClosesTheDrawerAndRunsCancellationCleanup() {
    View swipe =
        show(CairnDialogs.drawer(activity).setTitle("Saved maps").setMessage("Map content"));
    int[] canceled = {0};
    dialog.setOnCancelListener(d -> canceled[0]++);
    touch(swipe, MotionEvent.ACTION_DOWN, 60, 40);
    touch(swipe, MotionEvent.ACTION_MOVE, 60, 160);
    touch(swipe, MotionEvent.ACTION_UP, 60, 160);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertFalse(dialog.isShowing());
    assertEquals(1, canceled[0]);
  }

  @Test
  public void contentCanScrollAndTapBeforeSwipingDownAtTheTopToClose() {
    ScrollView scroll = new ScrollView(activity);
    LinearLayout rows = new LinearLayout(activity);
    rows.setOrientation(LinearLayout.VERTICAL);
    int[] clicks = {0};
    for (int i = 0; i < 50; i++) {
      Button button = new Button(activity);
      button.setText("Row " + i);
      button.setOnClickListener(v -> clicks[0]++);
      rows.addView(button, new LinearLayout.LayoutParams(-1, 48));
    }
    scroll.addView(rows);
    View swipe =
        show(
            CairnDialogs.drawer(activity)
                .fullHeight()
                .fullWidth()
                .setTitle("Routes")
                .setView(scroll));
    scroll.getChildAt(0).performClick();
    int[] location = new int[2];
    rows.getChildAt(0).getLocationOnScreen(location);
    int[] origin = new int[2];
    swipe.getLocationOnScreen(origin);
    float x = location[0] - origin[0] + 30;
    float y = location[1] - origin[1] + 20;
    touch(swipe, MotionEvent.ACTION_DOWN, x, y);
    touch(swipe, MotionEvent.ACTION_UP, x, y);
    assertEquals(1, clicks[0]);
    scroll.scrollTo(0, 300);
    assertTrue(scroll.canScrollVertically(-1));
    touch(swipe, MotionEvent.ACTION_DOWN, x, y);
    touch(swipe, MotionEvent.ACTION_MOVE, x, y + 100);
    touch(swipe, MotionEvent.ACTION_UP, x, y + 100);
    assertTrue(dialog.isShowing());
    scroll.scrollTo(0, 0);
    touch(swipe, MotionEvent.ACTION_DOWN, x, y);
    touch(swipe, MotionEvent.ACTION_MOVE, x, y + 120);
    touch(swipe, MotionEvent.ACTION_UP, x, y + 120);
    assertFalse(dialog.isShowing());
  }

  @Test
  public void cancelledOrShortDragRestoresTheDrawer() {
    View swipe = show(CairnDialogs.drawer(activity).setTitle("GPS").setMessage("Settings"));
    touch(swipe, MotionEvent.ACTION_DOWN, 60, 40);
    touch(swipe, MotionEvent.ACTION_MOVE, 60, 140);
    touch(swipe, MotionEvent.ACTION_CANCEL, 60, 140);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    assertTrue(dialog.isShowing());
    assertEquals(0, swipe.getTranslationY(), 0.01);
  }
}
