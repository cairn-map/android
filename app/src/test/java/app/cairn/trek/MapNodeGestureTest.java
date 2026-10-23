package app.cairn.trek;

import static org.junit.Assert.*;

import android.os.*;
import android.view.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapNodeGestureTest {
  private TrekMapView map;
  private int moved, added, insertion, deleted;
  private long down;

  @Before
  public void setup() {
    map = new TrekMapView(RuntimeEnvironment.getApplication());
    map.layout(0, 0, 600, 800);
    map.restoreCamera(new double[] {45, 6, 14});
    map.waypoints = new ArrayList<>(List.of(new Point(45, 6)));
    map.editing = true;
    map.onNodeMoved = (index, original) -> moved++;
    map.onNodeDeleted =
        index -> {
          deleted++;
          map.waypoints.remove(index);
        };
    map.onNodeAdded =
        (index, point) -> {
          added++;
          insertion = index;
          map.waypoints.add(index, point);
        };
  }

  @After
  public void stop() {
    map.dispose();
  }

  private void touch(int action, float x, float y) {
    if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    map.onTouchEvent(event);
    event.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30));
  }

  @Test
  public void draggingANodeChangesItsCoordinatesWithoutPanningAndRoutesOnceOnRelease() {
    double[] camera = map.camera();
    Point original = map.waypoints.get(0);
    map.onNodeMoved =
        (index, before) -> {
          assertSame(original, before);
          moved++;
        };
    touch(MotionEvent.ACTION_DOWN, 300, 400);
    touch(MotionEvent.ACTION_MOVE, 380, 340);
    touch(MotionEvent.ACTION_MOVE, 420, 320);
    assertEquals(0, moved);
    touch(MotionEvent.ACTION_UP, 420, 320);
    assertEquals(1, moved);
    assertEquals(0, deleted);
    assertTrue(map.waypoints.get(0).lon > 6);
    assertTrue(map.waypoints.get(0).lat > 45);
    assertArrayEquals(camera, map.camera(), 0);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(0, added);
  }

  @Test
  public void canceledDragRestoresTheNodeAndDoesNotRequestRouting() {
    Point original = map.waypoints.get(0);
    touch(MotionEvent.ACTION_DOWN, 300, 400);
    touch(MotionEvent.ACTION_MOVE, 420, 320);
    touch(MotionEvent.ACTION_CANCEL, 420, 320);
    assertSame(original, map.waypoints.get(0));
    assertEquals(0, moved);
    assertEquals(0, deleted);
  }

  @Test
  public void nodeTapDeletesItAndMapTapAddsOnlyInEditMode() {
    touch(MotionEvent.ACTION_DOWN, 300, 400);
    touch(MotionEvent.ACTION_UP, 300, 400);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(0, added);
    assertEquals(1, deleted);
    assertTrue(map.waypoints.isEmpty());
    touch(MotionEvent.ACTION_DOWN, 100, 180);
    touch(MotionEvent.ACTION_UP, 100, 180);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(1, added);
    map.editing = false;
    map.waypoints.add(new Point(45, 6));
    touch(MotionEvent.ACTION_DOWN, 300, 400);
    touch(MotionEvent.ACTION_UP, 300, 400);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(1, deleted);
    touch(MotionEvent.ACTION_DOWN, 480, 650);
    touch(MotionEvent.ACTION_UP, 480, 650);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(1, added);
  }

  @Test
  public void tappingNearALegInsertsANodeBetweenItsEndpoints() {
    map.waypoints = new ArrayList<>(List.of(new Point(45, 5.99), new Point(45, 6.01)));
    touch(MotionEvent.ACTION_DOWN, 300, 400);
    touch(MotionEvent.ACTION_UP, 300, 400);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(1, added);
    assertEquals(1, insertion);
    assertEquals(6, map.waypoints.get(1).lon, 1e-6);
  }
}
