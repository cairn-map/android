package app.cairn.trek;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.location.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

/** Same harness is injected into previous and current checkouts by the Actions runner. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = VirtualPowerBenchmarkTest.Notifications.class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VirtualPowerBenchmarkTest {
  @Implements(NotificationManager.class)
  public static class Notifications extends ShadowNotificationManager {
    static final AtomicInteger posts = new AtomicInteger();

    @Implementation
    protected void notify(int id, Notification notification) {
      posts.incrementAndGet();
      super.notify(null, id, notification);
    }

    @Implementation
    protected void notify(String tag, int id, Notification notification) {
      posts.incrementAndGet();
      super.notify(tag, id, notification);
    }
  }

  private static Object field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(object);
  }

  private static void barrier(TrackingService service) throws Exception {
    HandlerThread thread = (HandlerThread) field(service, "worker");
    CountDownLatch done = new CountDownLatch(1);
    assertTrue(new Handler(thread.getLooper()).post(done::countDown));
    assertTrue("Recorder worker must drain", done.await(5, TimeUnit.SECONDS));
  }

  private static void settle(MainActivity activity) throws Exception {
    ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private static boolean probe(TrekMapView map, Bitmap target) {
    target.eraseColor(Color.TRANSPARENT);
    map.draw(new Canvas(target));
    return target.getPixel(target.getWidth() / 2, target.getHeight() / 2) != Color.TRANSPARENT;
  }

  private static int hiddenTileProbe(TrekMapView map) throws Exception {
    AtomicInteger reads = new AtomicInteger();
    map.source(new TileSource() {
      public Bitmap tile(int z, int x, int y) { reads.incrementAndGet(); return null; }
      public int minZoom() { return 2; }
      public int maxZoom() { return 19; }
      public double centerLat() { return 45; }
      public double centerLon() { return 6; }
      public String attribution() { return "Offline benchmark fixture"; }
    });
    Bitmap image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    probe(map, image);
    ThreadPoolExecutor executor = (ThreadPoolExecutor) field(map, "executor");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) {
      assertTrue("Tile workers must drain", System.nanoTime() < deadline);
      Thread.sleep(1);
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    map.source(null);
    image.recycle();
    return reads.get();
  }

  @Test
  public void writeVirtualMatrix() throws Exception {
    JSONArray scenarios = new JSONArray();
    for (String screen : new String[] {"off", "on", "check"})
      for (int interval : new int[] {1, 5, 20, 30, 60, 300})
        scenarios.put(scenario(screen, interval));
    JSONObject result = new JSONObject().put("schema", 1).put("protocol", "virtual-power-v1")
        .put("sdk", 28).put("duration_seconds", 3600).put("scenarios", scenarios)
        .put("render_cpu", renderCpu());
    File output = new File("build/reports/virtual-power/scenarios.json");
    assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
    try (Writer writer = new FileWriter(output)) { writer.write(result.toString(2)); }
  }

  private JSONArray renderCpu() throws Exception {
    // Android's compile bootclasspath omits java.management; use the host JVM interface.
    Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
    Class<?> bean = Class.forName("java.lang.management.ThreadMXBean");
    Object clock = factory.getMethod("getThreadMXBean").invoke(null);
    assertEquals(true, bean.getMethod("isCurrentThreadCpuTimeSupported").invoke(clock));
    if (!(Boolean) bean.getMethod("isThreadCpuTimeEnabled").invoke(clock))
      bean.getMethod("setThreadCpuTimeEnabled", boolean.class).invoke(clock, true);
    java.lang.reflect.Method cpuTime = bean.getMethod("getCurrentThreadCpuTime");
    JSONArray results = new JSONArray();
    for (int points : new int[] {1000, 10000}) {
      TrekMapView view = new TrekMapView(RuntimeEnvironment.getApplication());
      Bitmap image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
      try {
        List<Point> route = new ArrayList<>();
        for (int i = 0; i < points; i++) route.add(new Point(45 + i * .000001, 6 + Math.sin(i * .01) * .001));
        view.route = route;
        view.layout(0, 0, 128, 128);
        for (int i = 0; i < 20; i++) probe(view, image);
        double[] samples = new double[7];
        for (int sample = 0; sample < samples.length; sample++) {
          long start = (Long) cpuTime.invoke(clock);
          for (int frame = 0; frame < 5; frame++) probe(view, image);
          samples[sample] = ((Long) cpuTime.invoke(clock) - start) / 5_000_000.0;
        }
        Arrays.sort(samples);
        results.put(new JSONObject().put("points", points).put("samples", samples.length)
            .put("median_ms", samples[3]).put("p95_ms", samples[6])
            .put("batch_ms", new JSONArray(samples)));
      } finally {
        view.dispose();
        image.recycle();
      }
    }
    return results;
  }

  private JSONObject scenario(String screen, int interval) throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    TrackingService.running = false;
    try (Store settings = new Store(context)) {
      settings.prefs.edit().clear().putInt("intervalSeconds", interval).commit();
    }
    Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    LocationManager manager = context.getSystemService(LocationManager.class);
    Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
    ServiceController<TrackingService> recorder = Robolectric.buildService(TrackingService.class).create();
    TrackingService service = recorder.get();
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity = controller.get();
    TrekMapView map = (TrekMapView) field(activity, "map");
    map.source(null);
    map.layout(0, 0, 128, 128);
    Bitmap target = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    boolean visible = true;
    int hiddenDraws = 0, hiddenTiles = 0, fixes = 0, visibleDraws = 0, queuedRecorderTasks = 0;
    try {
      settle(activity);
      service.onStartCommand(new Intent(context, TrackingService.class).setAction("start"), 0, 1);
      barrier(service);
      Object request = Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).get(0);
      long requestedMillis = ((Number) request.getClass().getMethod("getInterval").invoke(request)).longValue();
      Notifications.posts.set(0);
      for (int second = 0; second < 3600; second++) {
        boolean show = screen.equals("on") || screen.equals("check") && second % 300 < 15;
        if (show != visible) {
          if (show) controller.resume();
          else controller.pause();
          visible = show;
          settle(activity);
          // Adversarial late draw: measure whether hidden drawing is actually suppressed.
          if (!visible) {
            if (probe(map, target)) hiddenDraws++;
            hiddenTiles += hiddenTileProbe(map);
          }
        }
        if (second % interval == 0) {
          Location fix = new Location(LocationManager.GPS_PROVIDER);
          fix.setLatitude(45 + second * .000001);
          fix.setLongitude(6);
          fix.setAccuracy(5);
          fix.setTime(System.currentTimeMillis());
          fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
          service.onLocationChanged(fix);
          fixes++;
          Shadows.shadowOf(Looper.getMainLooper()).idle();
          if (visible && probe(map, target)) visibleDraws++;
        }
        // Virtual time, no one-hour wait. Runs real due callbacks, exposing timer loops.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      }
      barrier(service);
      HandlerThread worker = (HandlerThread) field(service, "worker");
      if (!Shadows.shadowOf(worker.getLooper()).getNextScheduledTaskTime().isZero()) queuedRecorderTasks++;
      Store store = (Store) field(service, "store");
      int saved = store.read((String) field(service, "trekId")).size();
      int gps = Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size();
      int network = Shadows.shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).size();
      int notifications = Notifications.posts.get();
      recorder.destroy();
      worker.join(5000);
      assertFalse("Recorder worker must stop", worker.isAlive());
      int afterStop = Shadows.shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size();
      return new JSONObject().put("screen", screen).put("interval_seconds", interval)
          .put("injected_fixes", fixes).put("saved_fixes", saved)
          .put("requested_interval_ms", requestedMillis).put("gps_subscriptions", gps)
          .put("network_subscriptions", network).put("subscriptions_after_stop", afterStop)
          .put("notification_posts", notifications).put("hidden_draws", hiddenDraws)
          .put("hidden_tile_reads", hiddenTiles)
          .put("visible_draws", visibleDraws).put("queued_recorder_tasks", queuedRecorderTasks);
    } finally {
      if (TrackingService.running) recorder.destroy();
      if (!visible) controller.resume();
      controller.pause().stop().destroy();
      assertTrue(((ExecutorService) field(activity, "io")).awaitTermination(5, TimeUnit.SECONDS));
      target.recycle();
      TrackingService.running = false;
    }
  }
}
