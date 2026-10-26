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
    // Robolectric's legacy tracker retains old coordinates after clear(). Empty
    // the platform pool so synthetic clocks restarting between tests stay isolated.
    Object pool =
        org.robolectric.util.ReflectionHelpers.getStaticField(
            android.view.VelocityTracker.class, "sPool");
    while (org.robolectric.util.ReflectionHelpers.callInstanceMethod(pool, "acquire") != null) {}
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
    touch(action, x, y, interval, 0);
  }

  private void touch(int action, float x, float y, int interval, int pointerId) {
    if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
    MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
    properties.id = pointerId;
    properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
    coordinates.x = x;
    coordinates.y = y;
    coordinates.pressure = coordinates.size = 1;
    MotionEvent event =
        MotionEvent.obtain(
            down, SystemClock.uptimeMillis(), action, 1,
            new MotionEvent.PointerProperties[] {properties},
            new MotionEvent.PointerCoords[] {coordinates},
            0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
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

  @Test
  public void tappingSearchPinRemovesItWithoutSelectingRoutesOrAddingAnEditNode() {
    map.center(new Point(45, 6));
    int[] callbacks = {0};
    map.onPathSelected = id -> callbacks[0]++;
    map.onNodeAdded = (index, point) -> callbacks[0]++;
    map.selectedPathId = "selected";
    float density = map.getResources().getDisplayMetrics().density;
    for (boolean editing : new boolean[] {false, true}) {
      map.editing = editing;
      map.searchMarker = map.center();
      touch(MotionEvent.ACTION_DOWN, 300, 500 - 26 * density);
      touch(MotionEvent.ACTION_UP, 300, 500 - 26 * density);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
      assertNull(map.searchMarker);
      assertEquals(0, callbacks[0]);
    }
    map.editing = false;
    map.searchMarker = map.center();
    touch(MotionEvent.ACTION_DOWN, 100, 700);
    touch(MotionEvent.ACTION_UP, 100, 700);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    assertNotNull(map.searchMarker);
    assertEquals(1, callbacks[0]);
  }

  @Test
  @Config(sdk = {28, 35})
  public void longQuickZoomKeepsUpdatingOnEveryMoveBeyondTheLongPressTimeout() {
    double[] start = map.camera();
    for (int direction : new int[] {-1, 1}) {
      map.restoreCamera(start);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      doublePress();
      float y = 300 + direction * 40;
      touch(MotionEvent.ACTION_MOVE, 200, y, 16);
      Point anchor = underSecondTap();
      double distance = 160 * map.getResources().getDisplayMetrics().density;
      int duration = ViewConfiguration.getLongPressTimeout() * 3;
      for (int elapsed = 0; elapsed < duration; elapsed += 16) {
        double before = map.camera()[2];
        y += direction * 2;
        touch(MotionEvent.ACTION_MOVE, 200, y, 16);
        assertEquals(
            "Zoom froze after " + elapsed + " ms",
            direction * 2 / distance,
            map.camera()[2] - before,
            1e-6);
      }
      assertEquals(anchor.lat, underSecondTap().lat, 1e-5);
      assertEquals(anchor.lon, underSecondTap().lon, 1e-5);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
      double held = map.camera()[2];
      touch(MotionEvent.ACTION_UP, 200, y);
      coast(1000);
      assertEquals("Release must not apply a deferred zoom", held, map.camera()[2], 1e-10);
    }
  }

  @Test
  @Config(sdk = {28, 35})
  public void quickZoomCanStartAfterHoldingTheSecondPressPastTheLongPressTimeout() {
    int[] planningPoints = {0};
    map.onLongPress = point -> planningPoints[0]++;
    doublePress();
    Shadows.shadowOf(Looper.getMainLooper())
        .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() * 2L));
    double held = map.camera()[2];
    touch(MotionEvent.ACTION_MOVE, 200, 400);
    assertTrue("A held double-tap must still respond before release", map.camera()[2] > held);
    double moved = map.camera()[2];
    touch(MotionEvent.ACTION_MOVE, 200, 440);
    assertTrue(map.camera()[2] > moved);
    assertEquals(0, planningPoints[0]);
  }

  @Test
  @Config(sdk = {28, 35})
  public void ordinaryLongPressStillAddsAPlanningPointAfterQuickZoomEndsOrCancels() {
    int[] planningPoints = {0};
    map.onLongPress = point -> planningPoints[0]++;
    for (int ending : new int[] {MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
      doublePress();
      touch(MotionEvent.ACTION_MOVE, 200, 400);
      touch(ending, 200, 400);
      coast(1000);
      touch(MotionEvent.ACTION_DOWN, 200, 300);
      Shadows.shadowOf(Looper.getMainLooper())
          .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() * 2L));
      touch(MotionEvent.ACTION_UP, 200, 300);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }
    assertEquals(2, planningPoints[0]);
  }

  private void twoFingers(int action, float firstX, float secondX) {
    twoFingers(action, firstX, secondX, 40);
  }

  private void twoFingers(int action, float firstX, float secondX, int interval) {
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
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(interval));
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
  public void addingASecondFingerSwitchesToPinchAndUsesThePinchReleaseMomentum() {
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
    assertTrue(map.camera()[2] > pinched);
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
    coast(2400);
    double settled = map.camera()[2];
    assertTrue(settled > released);
    assertTrue("A brisk flick should carry more than one extra zoom level", settled - released > 1);
    assertTrue("Even the fastest flick remains controlled", settled - released < 3);
    coast(1000);
    assertEquals(settled, map.camera()[2], 1e-10);
    assertEquals(anchor.lat, underSecondTap().lat, 1e-5);
    assertEquals(anchor.lon, underSecondTap().lon, 1e-5);
  }

  @Test
  @Config(sdk = {28, 35})
  public void aFastFlickCoastsFartherThanASlowDragInEitherDirection() {
    double[] start = {45.9, 6.8, 10};
    for (int direction : new int[] {-1, 1}) {
      double[] travel = new double[2];
      for (int speed = 0; speed < 2; speed++) {
        map.restoreCamera(start);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        doublePress();
        for (int i = 1; i <= 6; i++)
          touch(MotionEvent.ACTION_MOVE, 200, 300 + direction * i * 20, speed == 0 ? 60 : 10);
        touch(MotionEvent.ACTION_UP, 200, 300 + direction * 120, 0);
        double released = map.camera()[2];
        coast(2400);
        travel[speed] = direction * (map.camera()[2] - released);
      }
      assertTrue(travel[0] > 0);
      assertTrue("Momentum must respond to release speed", travel[1] > travel[0] * 2);
      assertTrue(travel[1] > 1);
    }
  }

  @Test
  public void zoomCoastCoversTheSameDistanceAtDifferentFrameRates() {
    double[] levels = new double[2];
    for (int run = 0; run < 2; run++) {
      map.restoreCamera(new double[] {45.9, 6.8, 10});
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      doublePress();
      touch(MotionEvent.ACTION_MOVE, 200, 400);
      touch(MotionEvent.ACTION_MOVE, 200, 480);
      touch(MotionEvent.ACTION_UP, 200, 480, 0);
      int interval = run == 0 ? 16 : 64;
      for (int elapsed = 0; elapsed < 640; elapsed += interval) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(interval));
        map.computeScroll();
      }
      levels[run] = map.camera()[2];
    }
    assertEquals(levels[0], levels[1], 1e-9);
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
    Point anchor = underSecondTap();
    doublePress();
    assertArrayEquals(before, map.camera(), 1e-10);
    touch(MotionEvent.ACTION_UP, 200, 300, 0);
    assertArrayEquals(before, map.camera(), 1e-10);
    coast(64);
    assertTrue(map.camera()[2] > before[2]);
    assertTrue(map.camera()[2] < before[2] + 1);
    assertEquals(anchor.lat, underSecondTap().lat, 1e-5);
    assertEquals(anchor.lon, underSecondTap().lon, 1e-5);
    coast(160);
    assertEquals(before[2] + 1, map.camera()[2], 1e-10);
    assertEquals(anchor.lat, underSecondTap().lat, 1e-5);
    assertEquals(anchor.lon, underSecondTap().lon, 1e-5);
  }

  @Test
  public void newTouchInterruptsDoubleTapTransitionAndMaximumZoomIsRespected() {
    doublePress();
    touch(MotionEvent.ACTION_UP, 200, 300, 0);
    coast(64);
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    double[] stopped = map.camera();
    coast(300);
    assertArrayEquals(stopped, map.camera(), 1e-10);
    touch(MotionEvent.ACTION_CANCEL, 200, 300);
    map.restoreCamera(new double[] {45.9, 6.8, 18.75});
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    doublePress();
    touch(MotionEvent.ACTION_UP, 200, 300);
    coast(240);
    assertEquals(19, map.camera()[2], 1e-10);
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
    coast(240);
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
    coast(240);
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

  private void swipe(int interval) {
    touch(MotionEvent.ACTION_DOWN, 200, 300, interval);
    for (int i = 1; i <= 6; i++)
      touch(MotionEvent.ACTION_MOVE, 200 + i * 20, 300 + i * 10, interval);
    touch(MotionEvent.ACTION_UP, 320, 360, 0);
  }

  @Test
  @Config(sdk = {28, 35})
  public void panCoastsInTheReleaseDirectionAndANewTouchStopsIt() {
    swipe(16);
    double[] released = map.camera();
    coast(64);
    assertTrue(map.camera()[0] > released[0]);
    assertTrue(
        java.util.Arrays.toString(released) + " -> " + java.util.Arrays.toString(map.camera()),
        map.camera()[1] < released[1]);
    assertEquals(released[2], map.camera()[2], 1e-10);
    touch(MotionEvent.ACTION_DOWN, 320, 360, 0);
    double[] stopped = map.camera();
    coast(2000);
    assertArrayEquals(stopped, map.camera(), 1e-10);
  }

  @Test
  public void fastPanTravelsFartherAndEventuallySettles() {
    double[] start = map.camera();
    double[] travel = new double[2];
    for (int run = 0; run < 2; run++) {
      map.restoreCamera(start);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      swipe(run == 0 ? 60 : 10);
      double released = map.camera()[1];
      coast(3000);
      travel[run] = released - map.camera()[1];
      double[] settled = map.camera();
      coast(1000);
      assertArrayEquals(settled, map.camera(), 1e-10);
    }
    assertTrue(travel[0] > 0);
    assertTrue(travel[1] > travel[0] * 2);
  }

  @Test
  public void heldOrCancelledPanHasNoMomentumAndProgrammaticCenterStopsACoast() {
    for (int ending : new int[] {MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
      touch(MotionEvent.ACTION_DOWN, 200, 300);
      touch(MotionEvent.ACTION_MOVE, 300, 400);
      touch(MotionEvent.ACTION_MOVE, 400, 500);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
      touch(ending, 400, 500);
      double[] stopped = map.camera();
      coast(2000);
      assertArrayEquals(stopped, map.camera(), 1e-10);
    }
    swipe(10);
    coast(64);
    map.center(new Point(48, 2));
    double[] centered = map.camera();
    coast(2000);
    assertArrayEquals(centered, map.camera(), 1e-10);
  }

  @Test
  public void twoFingerDragCoastsWithoutQueuingZoomOut() {
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
    double[] before = map.camera();
    twoFingers(MotionEvent.ACTION_MOVE, 240, 440);
    twoFingers(MotionEvent.ACTION_MOVE, 280, 480);
    assertTrue(map.camera()[1] < before[1]);
    assertEquals(before[2], map.camera()[2], 1e-10);
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 280, 480);
    touch(MotionEvent.ACTION_UP, 280, 300);
    double[] released = map.camera();
    coast(2000);
    assertTrue(map.camera()[1] < released[1]);
    assertEquals(released[2], map.camera()[2], 1e-10);
  }

  @Test
  @Config(sdk = {28, 35})
  public void twoFingerTapAnimatesZoomOutWithoutMovingItsAnchor() {
    touch(MotionEvent.ACTION_DOWN, 200, 300, 20);
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
    double[] before = map.camera();
    double world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, before[2]);
    double anchorY = Geo.y(before[0], 0) + (300 - map.getHeight() / 2.0) / world;
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
    touch(MotionEvent.ACTION_UP, 200, 300, 0);
    coast(64);
    assertTrue(map.camera()[2] < before[2]);
    assertTrue(map.camera()[2] > before[2] - 1);
    coast(240);
    assertEquals(before[2] - 1, map.camera()[2], 1e-10);
    double[] after = map.camera();
    world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, after[2]);
    assertEquals(anchorY, Geo.y(after[0], 0) + (300 - map.getHeight() / 2.0) / world, 1e-9);
    assertEquals(before[1], after[1], 1e-10);
  }

  private void pinch(int interval, int direction) {
    touch(MotionEvent.ACTION_DOWN, 100, 300, interval);
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        100,
        500,
        interval);
    for (int i = 1; i <= 5; i++) {
      float half = 200 + direction * i * 20;
      twoFingers(MotionEvent.ACTION_MOVE, 300 - half, 300 + half, interval);
    }
  }

  private void releasePinch(float half) {
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        300 - half,
        300 + half,
        10);
    touch(MotionEvent.ACTION_UP, 300 - half, 300, 0);
  }

  private Point pinchAnchor() {
    double[] camera = map.camera();
    double world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, camera[2]);
    return new Point(
        Geo.lat(Geo.y(camera[0], 0) + (300 - map.getHeight() / 2.0) / world, 0), camera[1]);
  }

  @Test
  @Config(sdk = {28, 35})
  public void pinchCoastsInEitherDirectionAroundItsAnchorAndStopsOnANewTouch() {
    for (int direction : new int[] {-1, 1}) {
      map.restoreCamera(new double[] {45.9, 6.8, 10});
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      pinch(20, direction);
      Point anchor = pinchAnchor();
      releasePinch(200 + direction * 100);
      double released = map.camera()[2];
      coast(80);
      assertTrue(direction * (map.camera()[2] - released) > 0);
      assertEquals(anchor.lat, pinchAnchor().lat, 1e-5);
      assertEquals(anchor.lon, pinchAnchor().lon, 1e-5);
      touch(MotionEvent.ACTION_DOWN, 300, 300, 0);
      double[] stopped = map.camera();
      coast(2000);
      assertArrayEquals(stopped, map.camera(), 1e-10);
      touch(MotionEvent.ACTION_CANCEL, 300, 300);
    }
  }

  @Test
  @Config(sdk = {28, 35})
  public void staggeredReleaseKeepsPinchMomentumDespiteTrailingFingerJitter() {
    for (int lifted : new int[] {0, 1}) {
      map.restoreCamera(new double[] {45.9, 6.8, 10});
      pinch(20, 1);
      twoFingers(
          MotionEvent.ACTION_POINTER_UP | (lifted << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
          0,
          600,
          0);
      double released = map.camera()[2];
      float remaining = lifted == 0 ? 600 : 0;
      touch(MotionEvent.ACTION_MOVE, remaining + 1, 301, 160, 1 - lifted);
      coast(80);
      double coasting = map.camera()[2];
      assertTrue(coasting > released);
      touch(MotionEvent.ACTION_UP, remaining + 1, 301, 0, 1 - lifted);
      coast(80);
      assertTrue(map.camera()[2] > coasting);
    }
  }

  @Test
  @Config(sdk = {28, 35})
  public void eitherRemainingFingerTakesOverPanningWithoutZoomingOrTapCallbacks() {
    int[] callbacks = {0};
    map.onLongPress = point -> callbacks[0]++;
    map.onPathSelected = id -> callbacks[0]++;
    map.selectedPathId = "selected";
    for (int lifted : new int[] {0, 1}) {
      map.restoreCamera(new double[] {45.9, 6.8, 10});
      pinch(20, 1);
      twoFingers(
          MotionEvent.ACTION_POINTER_UP | (lifted << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
          0, 600, 0);
      double[] released = map.camera();
      float remaining = lifted == 0 ? 600 : 0;
      touch(MotionEvent.ACTION_MOVE, remaining + 80, 360, 20, 1 - lifted);
      double[] moved = map.camera();
      double world = 256 * map.getResources().getDisplayMetrics().density * Math.pow(2, released[2]);
      assertEquals(released[1] - 80 / world * 360, moved[1], 1e-8);
      assertEquals(Geo.lat(Geo.y(released[0], 0) - 60 / world, 0), moved[0], 1e-8);
      assertEquals(released[2], moved[2], 1e-10);
      coast(ViewConfiguration.getLongPressTimeout() * 2);
      assertArrayEquals(moved, map.camera(), 1e-10);
      touch(MotionEvent.ACTION_MOVE, remaining + 120, 400, 20, 1 - lifted);
      assertTrue(map.camera()[1] < moved[1]);
      touch(MotionEvent.ACTION_MOVE, remaining + 160, 440, 20, 1 - lifted);
      double[] beforeRelease = map.camera();
      touch(MotionEvent.ACTION_UP, remaining + 160, 440, 0, 1 - lifted);
      coast(2000);
      assertTrue(map.camera()[1] < beforeRelease[1]);
      assertEquals(released[2], map.camera()[2], 1e-10);
      assertEquals(0, callbacks[0]);
    }
  }

  @Test
  public void heldTwoFingerPanDoesNotCoastOrZoomOut() {
    touch(MotionEvent.ACTION_DOWN, 200, 300);
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 400);
    twoFingers(MotionEvent.ACTION_MOVE, 260, 460);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 260, 460);
    touch(MotionEvent.ACTION_MOVE, 280, 320);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    touch(MotionEvent.ACTION_UP, 280, 320);
    double[] released = map.camera();
    coast(2000);
    assertArrayEquals(released, map.camera(), 1e-10);
  }

  @Test
  public void heldOrCancelledPinchHasNoMomentum() {
    for (int end : new int[] {MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
      pinch(20, 1);
      double[] stopped = map.camera();
      if (end == MotionEvent.ACTION_UP) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        releasePinch(300);
      } else touch(end, 0, 300);
      coast(2000);
      assertArrayEquals(stopped, map.camera(), 1e-10);
    }
  }

  @Test
  public void reversedPinchCoastsInItsNewDirectionAndStaysWithinZoomLimits() {
    pinch(20, 1);
    twoFingers(MotionEvent.ACTION_MOVE, 20, 580, 20);
    twoFingers(MotionEvent.ACTION_MOVE, 40, 560, 20);
    releasePinch(260);
    double released = map.camera()[2];
    coast(2000);
    assertTrue(map.camera()[2] < released);
    map.restoreCamera(new double[] {45.9, 6.8, 18.9});
    pinch(10, 1);
    releasePinch(300);
    coast(2000);
    assertEquals(19, map.camera()[2], 1e-10);
    for (double coordinate : map.camera()) assertTrue(Double.isFinite(coordinate));
  }
}
