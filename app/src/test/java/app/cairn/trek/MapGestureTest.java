package app.cairn.trek;

import static org.junit.Assert.*;

import android.os.*;
import android.view.MotionEvent;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapGestureTest {
  private TrekMapView map;
  private long down;

  @Before
  public void setup() {
    map = new TrekMapView(RuntimeEnvironment.getApplication());
    map.layout(0, 0, 600, 1000);
  }

  @After
  public void close() {
    map.dispose();
  }

  private void touch(int action, float x, float y) {
    if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    map.onTouchEvent(event);
    event.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
  }

  private void doublePress() {
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    touch(MotionEvent.ACTION_UP, 200, 300);
    touch(MotionEvent.ACTION_DOWN, 200, 300);
  }

  @Test
  public void doubleTapZoomsOnlyWhenTheSecondPressIsReleased() {
    double[] before = map.camera();
    doublePress();
    assertArrayEquals(before, map.camera(), 1e-10);
    touch(MotionEvent.ACTION_UP, 200, 300);
    assertEquals(before[2] + 1, map.camera()[2], 1e-10);
  }

  @Test
  public void holdingTheSecondPressDoesNotZoomOrAddAPlanningPoint() {
    int[] planningPoints = {0};
    map.onLongPress = point -> planningPoints[0]++;
    double[] before = map.camera();
    doublePress();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertArrayEquals(before, map.camera(), 1e-10);
    assertEquals(0, planningPoints[0]);
    touch(MotionEvent.ACTION_UP, 200, 300);
    assertEquals(before[2] + 1, map.camera()[2], 1e-10);
  }

  @Test
  public void doubleTapDragScalesContinuouslyWithoutAnExtraZoomOnRelease() {
    double[] before = map.camera();
    doublePress();
    assertArrayEquals(before, map.camera(), 1e-10);
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    double[] dragged = map.camera();
    assertTrue(dragged[2] > before[2]);
    touch(MotionEvent.ACTION_UP, 200, 480);
    assertArrayEquals(dragged, map.camera(), 1e-10);
  }

  @Test
  public void cancelledSecondPressDoesNotZoomAndNextDoubleTapStillWorks() {
    double[] before = map.camera();
    doublePress();
    touch(MotionEvent.ACTION_CANCEL, 200, 300);
    assertArrayEquals(before, map.camera(), 1e-10);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    doublePress();
    touch(MotionEvent.ACTION_UP, 200, 300);
    assertEquals(before[2] + 1, map.camera()[2], 1e-10);
  }

  @Test
  public void doubleTapDragUpZoomsOutWithoutAnExtraZoomOnRelease() {
    double[] before = map.camera();
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 200);
    touch(MotionEvent.ACTION_MOVE, 200, 120);
    double[] dragged = map.camera();
    assertTrue(dragged[2] < before[2]);
    touch(MotionEvent.ACTION_UP, 200, 120);
    assertArrayEquals(dragged, map.camera(), 1e-10);
  }

  @Test
  public void singleTapDoesNotZoomAndOrdinaryDragStillPans() {
    double[] before = map.camera();
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    touch(MotionEvent.ACTION_UP, 200, 300);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertArrayEquals(before, map.camera(), 1e-10);
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    touch(MotionEvent.ACTION_MOVE, 300, 450);
    touch(MotionEvent.ACTION_UP, 300, 450);
    assertEquals(before[2], map.camera()[2], 1e-10);
    assertNotEquals(before[0], map.camera()[0], 1e-10);
    assertNotEquals(before[1], map.camera()[1], 1e-10);
  }
}
