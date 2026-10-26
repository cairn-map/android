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
    java.util.concurrent.atomic.AtomicInteger callbacks =
        new java.util.concurrent.atomic.AtomicInteger();
    map.onLongPress = p -> callbacks.incrementAndGet();
    try {
      long now = SystemClock.uptimeMillis();
      android.view.MotionEvent down =
          android.view.MotionEvent.obtain(
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
    ServiceController<TrackingService> controller =
        Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      android.app.NotificationManager notifications =
          context.getSystemService(android.app.NotificationManager.class);
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
      assertSame(first, Shadows.shadowOf(notifications).getNotification(1));
      org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(4));
      next.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      service.onLocationChanged(next);
      assertSame(first, Shadows.shadowOf(notifications).getNotification(1));
      assertEquals(5, store.read((String) field(service, "trekId")).size());
    } finally {
      controller.destroy();
    }
  }

  @Test
  public void screenOffFixesArePersistedWithoutSendingUiBroadcasts() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    ServiceController<TrackingService> controller =
        Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      assertEquals(0, TrackingService.fixObservers.get());
      int before =
          Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size();
      Location fix = new Location(LocationManager.GPS_PROVIDER);
      fix.setLatitude(45);
      fix.setLongitude(6);
      fix.setAccuracy(5);
      fix.setTime(System.currentTimeMillis());
      fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      service.onLocationChanged(fix);
      assertEquals(
          before,
          Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size());
      Store store = (Store) field(service, "store");
      assertEquals(1, store.read((String) field(service, "trekId")).size());
      try (ActivityController<MainActivity> activity =
          Robolectric.buildActivity(MainActivity.class).setup()) {
        assertEquals(1, TrackingService.fixObservers.get());
        service.onLocationChanged(fix);
        assertTrue(
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                .anyMatch(
                    intent ->
                        TrackingService.EVENT.equals(intent.getAction())
                            && intent.hasExtra("lat")));
        activity.pause();
        assertEquals(0, TrackingService.fixObservers.get());
        activity.resume();
        assertEquals(1, TrackingService.fixObservers.get());
      }
      assertEquals(0, TrackingService.fixObservers.get());
    } finally {
      controller.destroy();
    }
  }

  @Test
  public void distanceStatusDoesNotRescanAMultiDayTrackOnEveryFix() throws Exception {
    class CountedTrack extends java.util.ArrayList<Point> {
      int reads;

      @Override
      public Point get(int index) {
        reads++;
        return super.get(index);
      }
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ((java.util.concurrent.ExecutorService) field(activity, "io"))
          .submit(() -> {})
          .get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      CountedTrack track = new CountedTrack();
      for (int i = 0; i < 10000; i++) track.add(new Point(45 + i * .000001, 6, 0, i, 5, 0));
      Field points = MainActivity.class.getDeclaredField("track");
      points.setAccessible(true);
      points.set(activity, track);
      double expected = Geo.length(track);
      Field length = MainActivity.class.getDeclaredField("trackLength");
      length.setAccessible(true);
      length.setDouble(activity, expected);
      // Seed both derived caches when injecting a historical track without loadPoints().
      Field summary = MainActivity.class.getDeclaredField("trackSummary");
      summary.setAccessible(true);
      summary.set(activity, RouteSummary.from(track));
      track.reads = 0;
      Point next = new Point(45.01, 6, 0, 10000, 5, 0);
      android.content.BroadcastReceiver receiver =
          (android.content.BroadcastReceiver) field(activity, "fixes");
      Intent fix =
          new Intent(TrackingService.EVENT)
              .putExtra("lat", next.lat)
              .putExtra("lon", next.lon)
              .putExtra("time", next.time)
              .putExtra("accuracy", next.accuracy);
      receiver.onReceive(activity, fix);
      assertTrue("No historical rescan: " + track.reads, track.reads < 20);
      assertEquals(
          expected + Geo.distance(track.get(9999), next), length.getDouble(activity), 1e-6);
      receiver.onReceive(activity, fix); // A duplicate UI event must not add distance again.
      assertEquals(10001, track.size());
      assertEquals(Geo.length(track), length.getDouble(activity), 1e-6);
      assertEquals(
          length.getDouble(activity), ((RouteSummary) summary.get(activity)).distance, 1e-6);
    }
  }

  @Test
  public void threeVirtualDaysKeepEveryFixWithNoExtraSubscriptionsOrMinuteUiRefreshes()
      throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    try (Store settings = new Store(context)) {
      settings.prefs.edit().putInt("intervalSeconds", 20).commit();
    }
    ServiceController<TrackingService> controller =
        Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      assertEquals(0, TrackingService.fixObservers.get());
      android.app.NotificationManager notifications =
          context.getSystemService(android.app.NotificationManager.class);
      android.app.Notification previous = Shadows.shadowOf(notifications).getNotification(1);
      int changes = 0;
      int fixes = 3 * 24 * 3600 / 20;
      for (int i = 0; i < fixes; i++) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(45 + i * .000001);
        fix.setLongitude(6);
        fix.setAccuracy(5);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        service.onLocationChanged(fix);
        android.app.Notification current = Shadows.shadowOf(notifications).getNotification(1);
        if (previous != current) changes++;
        previous = current;
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20));
      }
      assertEquals(0, changes);
      Store store = (Store) field(service, "store");
      assertEquals(fixes, store.read((String) field(service, "trekId")).size());
      assertEquals(
          1, Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size());
      assertTrue(
          Shadows.shadowOf(manager)
              .getLocationRequests(LocationManager.NETWORK_PROVIDER)
              .isEmpty());
    } finally {
      controller.destroy();
    }
    assertTrue(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
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

  @Test
  public void onlyIntervalChangesRefreshTheRecordingNotification() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    ServiceController<TrackingService> controller =
        Robolectric.buildService(TrackingService.class).create();
    TrackingService service = controller.get();
    try {
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      android.app.NotificationManager notifications =
          context.getSystemService(android.app.NotificationManager.class);
      android.app.Notification initial = Shadows.shadowOf(notifications).getNotification(1);
      assertEquals(
          "Cairn · Recording your hike",
          initial.extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
      assertEquals("Stop recording", initial.actions[0].title);
      Location fix = new Location(LocationManager.GPS_PROVIDER);
      fix.setAccuracy(5);
      fix.setAltitude(123);
      fix.setVerticalAccuracyMeters(7);
      fix.setTime(1000000);
      fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      service.onLocationChanged(fix);
      // Fixes are persisted without posting recording notifications.
      fix.setTime(1001000);
      service.onLocationChanged(fix);
      service.onStartCommand(
          new Intent(context, TrackingService.class).setAction(TrackingService.UPDATE), 0, 2);
      barrier(service);
      android.app.Notification notification = Shadows.shadowOf(notifications).getNotification(1);
      assertSame(initial, notification);
      Store store = (Store) field(service, "store");
      store.prefs.edit().putInt("intervalSeconds", 5).commit();
      service.onStartCommand(
          new Intent(context, TrackingService.class).setAction(TrackingService.UPDATE), 0, 3);
      barrier(service);
      notification = Shadows.shadowOf(notifications).getNotification(1);
      assertNotSame(initial, notification);
      String text =
          notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString();
      assertEquals("GPS interval: 5 seconds", text);
      assertFalse(text.contains("Waiting"));
      assertFalse(text.contains("Last fix"));
      assertFalse(notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_WHEN));
      java.util.List<Point> points = store.read((String) field(service, "trekId"));
      assertEquals(2, points.size());
      assertEquals(123, points.get(1).elevation, 0);
      assertEquals(7, points.get(1).verticalAccuracy, 0);
      assertEquals(
          1, Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size());
    } finally {
      controller.destroy();
    }
  }
}
