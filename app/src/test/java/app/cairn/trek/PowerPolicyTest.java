package app.cairn.trek;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.*;
import android.location.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class PowerPolicyTest {
  @Before
  public void reset() {
    TrackingService.running = false;
  }

  @After
  public void cleanup() {
    TrackingService.running = false;
  }

  private Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private void barrier(TrackingService service) throws Exception {
    HandlerThread thread = (HandlerThread) field(service, "worker");
    CountDownLatch done = new CountDownLatch(1);
    new Handler(thread.getLooper()).post(done::countDown);
    assertTrue(done.await(5, TimeUnit.SECONDS));
  }

  @Test
  public void gpsDefaultsToTwentySecondsAndKeepsACustomInterval() {
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      store.prefs.edit().remove("intervalSeconds").commit();
      assertEquals(20000L, store.intervalMillis());
      store.prefs.edit().putInt("intervalSeconds", 137).commit();
    }
    try (Store reopened = new Store(RuntimeEnvironment.getApplication())) {
      assertEquals(137000L, reopened.intervalMillis());
    }
  }

  @Test
  public void locatingDuringAHikeNeverStartsExtraGpsOrNetworkRequestsEvenWhenTheFixIsOld()
      throws Exception {
    TrackingService.running = true;
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.position = new Point(45, 6, Double.NaN, System.currentTimeMillis() - 3600000, 5, 0);
      Method locate = MainActivity.class.getDeclaredMethod("locate");
      locate.setAccessible(true);
      locate.invoke(activity);
      assertNull(field(activity, "manualLocation"));
      LocationManager manager = activity.getSystemService(LocationManager.class);
      assertTrue(
          Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
      assertTrue(
          Shadows.shadowOf(manager)
              .getLocationRequests(LocationManager.NETWORK_PROVIDER)
              .isEmpty());
      assertEquals(45, map.center().lat, 0);
      map.position = null;
      locate.invoke(activity);
      assertNull(field(activity, "manualLocation"));
    }
  }

  @Test
  public void pausingCancelsPendingLongPressWork() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
    map.onLongPress = p -> callbacks.incrementAndGet();
    try {
      long now = SystemClock.uptimeMillis();
      android.view.MotionEvent down = android.view.MotionEvent.obtain(
          now, now, android.view.MotionEvent.ACTION_DOWN, 30, 30, 0);
      map.onTouchEvent(down);
      down.recycle();
      map.setRenderingEnabled(false);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
      assertEquals(0, callbacks.get());
    } finally {
      map.dispose();
    }
  }

  @Test
  public void pausedActivitySuspendsMapWorkAndResumesOnReturn() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      TrekMapView map = (TrekMapView) field(controller.get(), "map");
      assertEquals(true, field(map, "renderingEnabled"));
      controller.pause();
      assertEquals(false, field(map, "renderingEnabled"));
      controller.resume();
      assertEquals(true, field(map, "renderingEnabled"));
    }
  }

  @Test
  public void frequentFixesAreAllSavedWithoutUpdatingSystemUiEverySecond() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    ServiceController<TrackingService> controller = Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
      android.app.Notification first = null;
      for (int i = 0; i < 3; i++) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(45);
        fix.setLongitude(6);
        fix.setAccuracy(5);
        fix.setTime(1000L + i * 1000L);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        service.onLocationChanged(fix);
        android.app.Notification current = Shadows.shadowOf(notifications).getNotification(1);
        if (i == 0) first = current;
        else assertSame(first, current);
      }
      Store store = (Store) field(service, "store");
      assertEquals(3, store.read((String) field(service, "trekId")).size());
      org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(1));
      Location next = new Location(LocationManager.GPS_PROVIDER);
      next.setAccuracy(5);
      next.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      service.onLocationChanged(next);
      assertNotSame(first, Shadows.shadowOf(notifications).getNotification(1));
      assertEquals(4, store.read((String) field(service, "trekId")).size());
    } finally {
      controller.destroy();
    }
  }

  @Test
  public void recordingUsesOnlyGpsAndRouteUpdatesDoNotRestartTheSubscription() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    ServiceController<TrackingService> controller =
        Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      assertEquals(
          1, Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size());
      Object request =
          Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).get(0);
      assertTrue(
          Shadows.shadowOf(manager)
              .getLocationRequests(LocationManager.NETWORK_PROVIDER)
              .isEmpty());
      service.onStartCommand(
          new Intent(context, TrackingService.class).setAction(TrackingService.UPDATE), 0, 2);
      barrier(service);
      assertSame(
          request,
          Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).get(0));
    } finally {
      controller.destroy();
    }
    assertTrue(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
  }
}
