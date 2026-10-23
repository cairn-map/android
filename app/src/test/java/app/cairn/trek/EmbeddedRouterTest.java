package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class EmbeddedRouterTest {
  private Context context;
  private EmbeddedRouter router;
  private List<Point> result;
  private String error;

  private List<Point> waypoints() {
    return Arrays.asList(new Point(50.002515, 8.720897), new Point(49.997510, 8.723658));
  }

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    TrackingService.running = false;
    router = new EmbeddedRouter(context);
    for (String name : new String[] {"E5_N45.rd5", "E5_N50.rd5"}) {
      try (InputStream input = getClass().getResourceAsStream("/routing/" + name)) {
        Files.copy(
            input,
            new File(RoutingData.directory(context), name).toPath(),
            StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  @After
  public void stop() {
    router.close();
    TrackingService.running = false;
  }

  private void calculate() throws Exception {
    router.calculate(
        waypoints(),
        RoutingPreferences.read(context.getSharedPreferences("settings", 0)),
        (points, message) -> {
          result = points;
          error = message;
        });
    Field field = EmbeddedRouter.class.getDeclaredField("worker");
    field.setAccessible(true);
    ((ExecutorService) field.get(router)).submit(() -> {}).get(15, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void calculatesAnActualWalkingRouteAcrossTwoRegionalGraphsWithoutACompanion()
      throws Exception {
    calculate();
    assertNull(error);
    assertNotNull(result);
    assertTrue(result.size() > 2);
    assertTrue(Geo.distance(result.get(0), waypoints().get(0)) < 100);
    assertTrue(Geo.distance(result.get(result.size() - 1), waypoints().get(1)) < 100);
    assertTrue(Geo.length(result) > Geo.distance(waypoints().get(0), waypoints().get(1)));
  }

  @Test
  public void calculationCopiesTheSuppliedPreferencesBeforeTheWorkerRuns() throws Exception {
    Field workerField = EmbeddedRouter.class.getDeclaredField("worker");
    workerField.setAccessible(true);
    ExecutorService worker = (ExecutorService) workerField.get(router);
    CountDownLatch release = new CountDownLatch(1);
    worker.submit(
        () -> {
          try {
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    Map<String, String> parameters =
        RoutingPreferences.read(context.getSharedPreferences("settings", 0));
    try {
      router.calculate(
          waypoints(),
          parameters,
          (points, message) -> {
            result = points;
            error = message;
          });
      // This would fail profile parsing if the worker retained the caller's mutable map.
      parameters.put("SAC_scale_limit", "invalid-number");
    } finally {
      release.countDown();
    }
    worker.submit(() -> {}).get(15, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull(error);
    assertTrue(result.size() > 2);
  }

  @Test
  public void missingGraphsProduceAnActionableErrorWithoutAFakeRoute() throws Exception {
    assertTrue(new File(RoutingData.directory(context), "E5_N50.rd5").delete());
    calculate();
    assertNull(result);
    assertNotNull(error);
    assertTrue(error.contains("E5_N50.rd5"));
  }

  @Test
  public void explicitOfflineRoutingDuringAHikeDoesNotStartExtraLocationRequests()
      throws Exception {
    TrackingService.running = true;
    calculate();
    assertNull(error);
    assertNotNull(result);
    android.location.LocationManager manager =
        context.getSystemService(android.location.LocationManager.class);
    assertTrue(
        Shadows.shadowOf(manager)
            .getLocationRequests(android.location.LocationManager.GPS_PROVIDER)
            .isEmpty());
    assertTrue(
        Shadows.shadowOf(manager)
            .getLocationRequests(android.location.LocationManager.NETWORK_PROVIDER)
            .isEmpty());
  }

  @Test
  public void graphHeadersAndRegionNamesAreValidated() throws Exception {
    RoutingData.validate(new File(RoutingData.directory(context), "E5_N45.rd5"));
    File invalid = new File(context.getCacheDir(), "bad.rd5");
    try (OutputStream out = new FileOutputStream(invalid)) {
      out.write(new byte[300]);
    }
    assertThrows(IOException.class, () -> RoutingData.validate(invalid));
    assertEquals("W5_N45.rd5", RoutingData.region(48, -1));
    assertFalse(RoutingData.validName("../E5_N45.rd5"));
    assertFalse(RoutingData.validName("E6_N45.rd5"));
  }
}
