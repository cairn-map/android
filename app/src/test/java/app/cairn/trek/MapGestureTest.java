package app.cairn.trek;

import static org.junit.Assert.*;

import android.os.*;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
    touch(action, x, y, 40);
  }

  private void touch(int action, float x, float y, int interval) {
    if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    map.onTouchEvent(event);
    event.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(interval));
  }

  private void doublePress() {
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    touch(MotionEvent.ACTION_UP, 200, 300);
    touch(MotionEvent.ACTION_DOWN, 200, 300);
  }

  private void coast(int milliseconds) {
    for (int elapsed = 0; elapsed < milliseconds; elapsed += 16) {
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
      map.computeScroll();
    }
  }

  private void twoFingers(int action, float firstX, float secondX) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[i] = new MotionEvent.PointerCoords();
      coordinates[i].x = i == 0 ? firstX : secondX;
      coordinates[i].y = 300;
      coordinates[i].pressure = 1;
      coordinates[i].size = 1;
    }
    MotionEvent event =
        MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            2,
            properties,
            coordinates,
            0,
            0,
            1,
            1,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0);
    map.onTouchEvent(event);
    event.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
  }

  private Point underSecondTap() {
    double[] camera = map.camera();
    double world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, camera[2]);
    return new Point(
        Geo.lat(Geo.y(camera[0], 0) + (300 - map.getHeight() / 2.0) / world, 0),
        Geo.lon(Geo.x(camera[1], 0) + (200 - map.getWidth() / 2.0) / world, 0));
  }

  @Test
  @Config(qualifiers = "xhdpi")
  public void sensitivityUsesDensityIndependentFingerDistance() {
    double before = map.camera()[2];
    doublePress();
    float distance =
        160 * map.getResources().getDisplayMetrics().density
            + ViewConfiguration.get(map.getContext()).getScaledTouchSlop();
    touch(MotionEvent.ACTION_MOVE, 200, 300 + distance);
    assertEquals(before + 1, map.camera()[2], 1e-6);
  }

  @Test
  public void addingASecondFingerSwitchesToPinchWithoutAnExtraZoomOrCoast() {
    doublePress();
    double before = map.camera()[2];
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
    twoFingers(MotionEvent.ACTION_MOVE, 180, 420);
    twoFingers(MotionEvent.ACTION_MOVE, 100, 500);
    assertTrue(map.camera()[2] > before);
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100, 500);
    double pinched = map.camera()[2];
    touch(MotionEvent.ACTION_UP, 100, 300);
    coast(1000);
    assertEquals(pinched, map.camera()[2], 1e-10);
  }

  @Test
  public void equalFingerDistancesProduceEqualZoomChangesWithoutAcceleration() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    double first = map.camera()[2];
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    double second = map.camera()[2];
    touch(MotionEvent.ACTION_MOVE, 200, 560);
    double third = map.camera()[2];
    double distance = 160 * map.getResources().getDisplayMetrics().density;
    assertEquals(80 / distance, second - first, 1e-6);
    assertEquals(second - first, third - second, 1e-6);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    assertEquals(second, map.camera()[2], 1e-6);
  }

  @Test
  public void reversingAcrossTheSecondTapRespondsInsideTheOriginalTouchSlop() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    float halfSlop = ViewConfiguration.get(map.getContext()).getScaledTouchSlop() / 2f;
    touch(MotionEvent.ACTION_MOVE, 200, 300 + halfSlop);
    double above = map.camera()[2];
    touch(MotionEvent.ACTION_MOVE, 200, 300 - halfSlop);
    double below = map.camera()[2];
    double distance = 160 * map.getResources().getDisplayMetrics().density;
    assertEquals(-2 * halfSlop / distance, below - above, 1e-6);
    touch(MotionEvent.ACTION_MOVE, 200, 300 + halfSlop);
    assertEquals(above, map.camera()[2], 1e-6);
    touch(MotionEvent.ACTION_MOVE, 200, 300 - halfSlop);
    assertEquals(below, map.camera()[2], 1e-6);
  }

  @Test
  public void releasingJustAfterAReversalNeverCoastsInThePreviousDirection() {
    double[] start = map.camera();
    for (int direction : new int[] {-1, 1}) {
      map.restoreCamera(start);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      doublePress();
      for (int i = 1; i <= 8; i++)
        touch(MotionEvent.ACTION_MOVE, 200, 300 + direction * i * 20, 10);
      float reversed = 300 + direction * 159;
      touch(MotionEvent.ACTION_MOVE, 200, reversed, 10);
      touch(MotionEvent.ACTION_UP, 200, reversed, 10);
      double released = map.camera()[2];
      coast(500);
      assertTrue(
          "Momentum must not undo the reversal", direction * (map.camera()[2] - released) <= 1e-6);
    }
  }

  @Test
  public void sustainedReverseMotionCoastsInTheNewDirection() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    touch(MotionEvent.ACTION_MOVE, 200, 440);
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 360);
    touch(MotionEvent.ACTION_UP, 200, 360);
    double released = map.camera()[2];
    coast(500);
    assertTrue(map.camera()[2] < released);
  }

  @Test
  public void oneFastMoveAndManySmallMovesReachTheSameZoom() {
    double[] start = map.camera();
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 540);
    double fast = map.camera()[2];
    touch(MotionEvent.ACTION_CANCEL, 200, 540);
    map.restoreCamera(start);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    doublePress();
    for (int y = 320; y <= 540; y += 20) touch(MotionEvent.ACTION_MOVE, 200, y);
    assertEquals(fast, map.camera()[2], 1e-6);
  }

  @Test
  public void quickZoomCoastsBrieflyAndNewTouchStopsItImmediately() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    touch(MotionEvent.ACTION_UP, 200, 480);
    double released = map.camera()[2];
    coast(80);
    assertTrue(map.camera()[2] > released);
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    double stopped = map.camera()[2];
    coast(1000);
    assertEquals(stopped, map.camera()[2], 1e-10);
    touch(MotionEvent.ACTION_UP, 200, 300);
    coast(1000);
    assertEquals(stopped, map.camera()[2], 1e-10);
  }

  @Test
  public void momentumDeceleratesAndSettlesWithoutAnExtraZoomStep() {
    Point anchor = underSecondTap();
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    touch(MotionEvent.ACTION_UP, 200, 480);
    double released = map.camera()[2];
    coast(64);
    double early = map.camera()[2];
    coast(64);
    double later = map.camera()[2];
    assertTrue(early - released > later - early);
    coast(1000);
    double settled = map.camera()[2];
    assertTrue(settled > released);
    assertTrue(settled - released < 1);
    coast(1000);
    assertEquals(settled, map.camera()[2], 1e-10);
    assertEquals(anchor.lat, underSecondTap().lat, 1e-5);
    assertEquals(anchor.lon, underSecondTap().lon, 1e-5);
  }

  @Test
  public void holdingStillOrCancellingEndsQuickZoomWithoutMomentum() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    touch(MotionEvent.ACTION_UP, 200, 480);
    double stopped = map.camera()[2];
    coast(1000);
    assertEquals(stopped, map.camera()[2], 1e-10);
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    touch(MotionEvent.ACTION_CANCEL, 200, 480);
    stopped = map.camera()[2];
    coast(1000);
    assertEquals(stopped, map.camera()[2], 1e-10);
  }

  @Test
  public void upwardCoastStaysWithinTheMinimumZoomAndPauseStopsMotion() {
    map.restoreCamera(new double[] {45.9, 6.8, 2.5});
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 270);
    touch(MotionEvent.ACTION_MOVE, 200, 240);
    touch(MotionEvent.ACTION_UP, 200, 240);
    double released = map.camera()[2];
    coast(1000);
    assertTrue(map.camera()[2] < released);
    assertTrue(map.camera()[2] >= 2);
    for (double coordinate : map.camera()) assertTrue(Double.isFinite(coordinate));
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    touch(MotionEvent.ACTION_MOVE, 200, 480);
    touch(MotionEvent.ACTION_UP, 200, 480);
    map.stopMotion();
    double stopped = map.camera()[2];
    coast(1000);
    assertEquals(stopped, map.camera()[2], 1e-10);
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
  public void invalidQuickScaleFactorsAndFocusDoNotCorruptTheCamera() {
    double[] before = map.camera();
    for (double factor : new double[] {0, -0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
      map.scaleBy(factor, 200, 300);
      assertArrayEquals(before, map.camera(), 1e-10);
    }
    map.scaleBy(2, Float.NaN, 300);
    map.scaleBy(2, 200, Float.POSITIVE_INFINITY);
    assertArrayEquals(before, map.camera(), 1e-10);
    map.scaleBy(Double.MIN_VALUE, 200, 300);
    assertEquals(2, map.camera()[2], 1e-10);
    map.scaleBy(Double.MAX_VALUE, 200, 300);
    assertEquals(19, map.camera()[2], 1e-10);
    for (double coordinate : map.camera()) assertTrue(Double.isFinite(coordinate));
  }

  @Test
  public void fastUpwardQuickScaleLeavesAFiniteCameraAndUsableMap() {
    doublePress();
    touch(MotionEvent.ACTION_MOVE, 200, 200);
    touch(MotionEvent.ACTION_MOVE, 200, -3000);
    touch(MotionEvent.ACTION_UP, 200, -3000);
    for (double coordinate : map.camera()) assertTrue(Double.isFinite(coordinate));
    assertTrue(map.camera()[2] >= 2);
    assertTrue(map.camera()[2] <= 19);
    for (double bound : map.bounds()) assertTrue(Double.isFinite(bound));
    map.zoom(1);
    for (double coordinate : map.camera()) assertTrue(Double.isFinite(coordinate));
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
