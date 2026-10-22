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
