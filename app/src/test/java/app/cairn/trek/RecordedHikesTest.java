package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class RecordedHikesTest {
  private String id, title;

  @Before
  public void setup() {
    TrackingService.running = false;
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      id = store.newTrek();
      title = store.treks().get(id);
      store.add(id, new Point(46, 6, 100, 1000, 5, 0));
    }
  }

  private Object field(MainActivity activity, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void drain(MainActivity activity) throws Exception {
    for (int i = 0; i < 3; i++) {
      ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private View find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void touch(View view, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(1000, 1000, action, x, y, 0);
    view.dispatchTouchEvent(event);
    event.recycle();
  }

  @Test
  public void swipeDeletesTheRecordingAndItsMapTrailWithoutConfirmation() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      assertEquals(1, ((TrekMapView) field(activity, "map")).track.size());
      find(activity.getWindow().getDecorView(), "Routes").performClick();
      View recording = find(activity.getWindow().getDecorView(), title + "  ↗");
      SwipeToRemoveView swipe = (SwipeToRemoveView) recording.getParent();
      swipe.measure(
          View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
      swipe.layout(0, 0, 400, 96);
      View remove = find(swipe, "Remove");
      assertEquals(View.INVISIBLE, remove.getVisibility());
      AlertDialog previousDialog = ShadowAlertDialog.getLatestAlertDialog();
      touch(swipe, MotionEvent.ACTION_DOWN, 300, 32);
      touch(swipe, MotionEvent.ACTION_MOVE, 100, 32);
      touch(swipe, MotionEvent.ACTION_UP, 100, 32);
      assertTrue(recording.getTranslationX() < 0);
      assertSame(previousDialog, ShadowAlertDialog.getLatestAlertDialog());
      drain(activity);
      Store store = (Store) field(activity, "store");
      assertFalse(store.treks().containsKey(id));
      assertTrue(store.read(id).isEmpty());
      assertTrue(((TrekMapView) field(activity, "map")).track.isEmpty());
      assertNull(find(activity.getWindow().getDecorView(), title + "  ↗"));
    }
  }

  @Test
  public void shortVerticalAndCancelledSwipesDoNotDeleteAnything() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      find(activity.getWindow().getDecorView(), "Routes").performClick();
      View recording = find(activity.getWindow().getDecorView(), title + "  ↗");
      SwipeToRemoveView swipe = (SwipeToRemoveView) recording.getParent();
      swipe.measure(
          View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
      swipe.layout(0, 0, 400, 96);
      touch(swipe, MotionEvent.ACTION_DOWN, 300, 32);
      touch(swipe, MotionEvent.ACTION_MOVE, 240, 32);
      touch(swipe, MotionEvent.ACTION_UP, 240, 32);
      assertEquals(0, recording.getTranslationX(), 0);
      touch(swipe, MotionEvent.ACTION_DOWN, 300, 32);
      touch(swipe, MotionEvent.ACTION_MOVE, 290, 90);
      touch(swipe, MotionEvent.ACTION_CANCEL, 290, 90);
      assertEquals(0, recording.getTranslationX(), 0);
      touch(swipe, MotionEvent.ACTION_DOWN, 300, 32);
      touch(swipe, MotionEvent.ACTION_MOVE, 100, 32);
      touch(swipe, MotionEvent.ACTION_CANCEL, 100, 32);
      assertEquals(0, recording.getTranslationX(), 0);
      assertEquals(View.INVISIBLE, find(swipe, "Remove").getVisibility());
      assertEquals(1, ((Store) field(activity, "store")).treks().size());
    }
  }

  @Test
  public void activeHikeDoesNotOfferSwipeRemoval() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrackingService.running = true;
      find(activity.getWindow().getDecorView(), "Routes").performClick();
      View recording = find(activity.getWindow().getDecorView(), title + "  ↗");
      assertFalse(recording.getParent() instanceof SwipeToRemoveView);
    } finally {
      TrackingService.running = false;
    }
  }
}
