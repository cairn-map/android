package app.cairn.trek;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.location.*;
import android.os.*;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ManualLocationTest {
  private Context context;
  private LocationManager manager;
  private ManualLocation locate;
  private int found, failed;
  private Point point;
  private boolean cachedResult;
  private final ManualLocation.Callback callback =
      new ManualLocation.Callback() {
        @Override
        public void found(Point result, boolean cached) {
          found++;
          point = result;
          cachedResult = cached;
        }

        @Override
        public void unavailable(String reason) {
          failed++;
        }
      };

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    locate = new ManualLocation(context);
  }

  @After
  public void stop() {
    locate.cancel();
  }

  private Location location(String provider, float accuracy, long ageSeconds) {
    Location location = new Location(provider);
    location.setLatitude(48.8);
    location.setLongitude(2.3);
    location.setAccuracy(accuracy);
    location.setTime(System.currentTimeMillis() - ageSeconds * 1000);
    return location;
  }

  @Test
  public void networkPositionWorksWithoutSatelliteFixAndStopsBothSubscriptions() {
    locate.start(callback);
    Shadows.shadowOf(manager).simulateLocation(location(LocationManager.NETWORK_PROVIDER, 60, 0));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, found);
    assertEquals(60, point.accuracy, 0);
    Shadows.shadowOf(manager).simulateLocation(location(LocationManager.GPS_PROVIDER, 5, 0));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    assertEquals(1, found);
    assertEquals(0, failed);
  }

  @Test
  public void recentCachedPositionIsShownWhileAFreshPositionIsRequested() {
    Shadows.shadowOf(manager)
        .setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER, location(LocationManager.NETWORK_PROVIDER, 30, 10));
    locate.start(callback);
    assertEquals(1, found);
    assertTrue(cachedResult);
    assertFalse(Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
    assertFalse(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty());
    Location fresh = location(LocationManager.GPS_PROVIDER, 5, 0);
    fresh.setLatitude(48.9);
    Shadows.shadowOf(manager).simulateLocation(fresh);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(2, found);
    assertFalse(cachedResult);
    assertEquals(48.9, point.lat, 0);
    assertTrue(Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
    assertTrue(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty());
    assertFalse(ManualLocation.usable(location(LocationManager.GPS_PROVIDER, 3, 300)));
  }

  @Test
  public void cachedPositionDoesNotKeepRequestsAlivePastTheTimeout() {
    Shadows.shadowOf(manager)
        .setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER, location(LocationManager.NETWORK_PROVIDER, 30, 16));
    locate.start(callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    assertEquals(1, found);
    assertTrue(cachedResult);
    assertEquals(0, failed);
    assertTrue(Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
    assertTrue(
        Shadows.shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty());
  }

  @Test
  public void approximatePermissionCanStillLocateUsingTheNetwork() {
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    locate.start(callback);
    Shadows.shadowOf(manager).simulateLocation(location(LocationManager.NETWORK_PROVIDER, 3000, 0));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    assertEquals(1, found);
    assertEquals(3000, point.accuracy, 0);
    assertEquals(0, failed);
  }

  @Test
  public void cancelAndTimeoutRemoveLocationRequests() {
    locate.start(callback);
    locate.cancel();
    Shadows.shadowOf(manager).simulateLocation(location(LocationManager.GPS_PROVIDER, 5, 0));
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    assertEquals(0, found);
    assertEquals(0, failed);
    Shadows.shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, null);
    locate.start(callback);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    assertEquals(1, failed);
    Shadows.shadowOf(manager).simulateLocation(location(LocationManager.NETWORK_PROVIDER, 50, 0));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, found);
  }
}
