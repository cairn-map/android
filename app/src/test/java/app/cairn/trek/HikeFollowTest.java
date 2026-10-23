package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.location.LocationManager;
import android.os.*;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class HikeFollowTest {
  private ActivityController<MainActivity> controller;
  private MainActivity activity;
  private TrekMapView map;
  private long fixTime;

  private Object field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void invoke(String name) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  private void settle() throws Exception {
    ((ExecutorService) field("io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  @Before
  public void setup() throws Exception {
    TrackingService.running = true;
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
    controller = Robolectric.buildActivity(MainActivity.class).setup().windowFocusChanged(true);
    activity = controller.get();
    settle();
    map = (TrekMapView) field("map");
    map.source(null);
    map.layout(0, 0, 800, 1200);
    map.restoreCamera(new double[] {48, 2, 14.5});
  }

  @After
  public void cleanup() {
    TrackingService.running = false;
    if (controller != null) controller.close();
  }

  private void fix(double lat, double lon) throws Exception {
    ((BroadcastReceiver) field("fixes"))
        .onReceive(
            activity,
            new Intent(TrackingService.EVENT)
                .putExtra("lat", lat)
                .putExtra("lon", lon)
                .putExtra("time", ++fixTime)
                .putExtra("accuracy", 5f));
  }

  private void touch(int action) {
    long now = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(now, now, action, 100, 300, 0);
    activity.dispatchTouchEvent(event);
    event.recycle();
  }

  private void waitSeconds(int seconds) {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
  }

  private void assertCenter(double lat, double lon) {
    assertEquals(lat, map.center().lat, 0.000001);
    assertEquals(lon, map.center().lon, 0.000001);
  }

  private void clickGps() {
    ArrayList<View> buttons = new ArrayList<>();
    activity.getWindow().getDecorView().findViewsWithText(
        buttons, "Find my location", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
    assertEquals(1, buttons.size());
    buttons.get(0).performClick();
  }

  @Test
  public void followsEveryRecordingFixWithoutChangingZoomOrRequestingLocation() throws Exception {
    fix(45, 6);
    assertCenter(45, 6);
    fix(45.01, 6.01);
    assertCenter(45.01, 6.01);
    assertEquals(14.5, map.camera()[2], 0.000001);
    assertNull(field("manualLocation"));
    LocationManager manager = activity.getSystemService(LocationManager.class);
    assertTrue(Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
    assertTrue(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty());
  }

  @Test
  public void waitsUntilThirtySecondsAfterReleaseThenFollowsTheLatestFix() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    map.restoreCamera(new double[] {48, 2, 13});
    waitSeconds(40);
    fix(45.01, 6.01);
    assertCenter(48, 2);
    touch(MotionEvent.ACTION_UP);
    waitSeconds(29);
    fix(45.02, 6.02);
    assertCenter(48, 2);
    waitSeconds(1);
    assertCenter(45.02, 6.02);
    fix(45.03, 6.03);
    assertCenter(45.03, 6.03);
  }

  @Test
  public void anotherInteractionRestartsTheIdleDelay() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    waitSeconds(20);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_CANCEL);
    fix(46, 7);
    waitSeconds(29);
    assertCenter(45, 6);
    waitSeconds(1);
    assertCenter(46, 7);
  }

  @Test
  public void interactingAsRecordingStartsStillPausesTheFirstFix() throws Exception {
    TrackingService.running = false;
    invoke("refreshStatus");
    TrackingService.running = true;
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    fix(45, 6);
    assertCenter(48, 2);
    waitSeconds(30);
    assertCenter(45, 6);
  }

  @Test
  public void locateResumesImmediatelyAndKeepsFollowing() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    fix(46, 7);
    assertCenter(45, 6);
    clickGps();
    assertCenter(46, 7);
    fix(47, 8);
    assertCenter(47, 8);
  }

  @Test
  public void gpsButtonBeforeTheFirstFixResumesWithTheNextScheduledFix() throws Exception {
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    clickGps();
    fix(45, 6);
    assertCenter(45, 6);
    assertNull(field("manualLocation"));
  }

  @Test
  public void idleCallbackDoesNotMoveTheMapBehindADrawer() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    controller.windowFocusChanged(false);
    fix(46, 7);
    waitSeconds(35);
    assertCenter(45, 6);
    controller.windowFocusChanged(true);
    assertCenter(46, 7);
  }

  @Test
  public void pausingCancelsTheCallbackAndReturningFollowsTheLatestPosition() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    fix(46, 7);
    controller.pause();
    waitSeconds(35);
    assertCenter(45, 6);
    controller.resume().windowFocusChanged(true);
    settle();
    assertCenter(46, 7);
  }

  @Test
  public void screenOffSleepCountsTowardTheIdleDelayWithoutRunningACallback() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    fix(46, 7);
    controller.pause();
    org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(35));
    assertCenter(45, 6);
    controller.resume().windowFocusChanged(true);
    settle();
    assertCenter(46, 7);
  }

  @Test
  public void recreationPreservesTheRemainingInteractionDelay() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    waitSeconds(20);
    controller.recreate().windowFocusChanged(true);
    activity = controller.get();
    settle();
    map = (TrekMapView) field("map");
    map.source(null);
    fix(46, 7);
    assertCenter(45, 6);
    waitSeconds(9);
    assertCenter(45, 6);
    waitSeconds(1);
    assertCenter(46, 7);
  }

  @Test
  public void stoppingRecordingCancelsSnapBackAndOutsideFixesDoNotFollow() throws Exception {
    fix(45, 6);
    touch(MotionEvent.ACTION_DOWN);
    touch(MotionEvent.ACTION_UP);
    TrackingService.running = false;
    invoke("refreshStatus");
    fix(46, 7);
    waitSeconds(35);
    assertCenter(45, 6);
  }
}
