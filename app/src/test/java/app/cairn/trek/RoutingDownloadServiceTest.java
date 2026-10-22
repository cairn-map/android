package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Looper;
import android.os.storage.StorageManager;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = RoutingDownloadServiceTest.Storage.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RoutingDownloadServiceTest {
  @Implements(StorageManager.class)
  public static class Storage extends org.robolectric.shadows.ShadowStorageManager {
    @Implementation
    protected UUID getUuidForPath(File path) {
      return StorageManager.UUID_DEFAULT;
    }

    @Implementation
    protected long getAllocatableBytes(UUID uuid) {
      return 2L * 1024 * 1024 * 1024;
    }
  }

  private android.app.Application context;
  private ServiceController<RoutingDownloadService> controller;
  private RoutingDownloadService service;

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    TrackingService.running = RoutingDownloadService.running = false;
    for (File file : RoutingData.directory(context).listFiles()) assertTrue(file.delete());
    controller = Robolectric.buildService(RoutingDownloadService.class).create();
    service = controller.get();
  }

  @After
  public void stop() {
    controller.destroy();
    TrackingService.running = RoutingDownloadService.running = false;
  }

  private void start() {
    service.onStartCommand(
        new Intent(context, RoutingDownloadService.class)
            .putExtra("request", "planned-route")
            .putStringArrayListExtra(
                "regions", new ArrayList<>(Collections.singletonList("E5_N50.rd5"))),
        0,
        1);
  }

  private void finish() throws Exception {
    Field field = RoutingDownloadService.class.getDeclaredField("worker");
    field.setAccessible(true);
    ((ExecutorService) field.get(service)).submit(() -> {}).get(10, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private List<Intent> events() {
    List<Intent> events = new ArrayList<>();
    for (Intent intent : Shadows.shadowOf(context).getBroadcastIntents())
      if (RoutingDownloadService.EVENT.equals(intent.getAction())) events.add(intent);
    return events;
  }

  @Test
  public void completedTransferPublishesCorrelatedProgressAndSuccessAfterInstallation()
      throws Exception {
    byte[] graph;
    try (InputStream input = getClass().getResourceAsStream("/routing/E5_N50.rd5")) {
      graph = MapCatalog.bytes(input);
    }
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      socket.setSoTimeout(5000);
      ExecutorService server = Executors.newSingleThreadExecutor();
      Future<?> response =
          server.submit(
              () -> {
                try (Socket client = socket.accept()) {
                  client.setSoTimeout(5000);
                  BufferedReader input =
                      new BufferedReader(new InputStreamReader(client.getInputStream()));
                  String line;
                  while ((line = input.readLine()) != null && !line.isEmpty()) {}
                  OutputStream output = client.getOutputStream();
                  output.write(
                      ("HTTP/1.1 200 OK\r\nContent-Length: "
                              + graph.length
                              + "\r\nETag: fixture\r\nConnection: close\r\n\r\n")
                          .getBytes(StandardCharsets.US_ASCII));
                  output.write(graph);
                  output.flush();
                } catch (IOException error) {
                  throw new UncheckedIOException(error);
                }
              });
      try {
        service.client = new GraphDownloader("http://127.0.0.1:" + socket.getLocalPort() + "/");
        start();
        finish();
        response.get(5, TimeUnit.SECONDS);
      } finally {
        server.shutdownNow();
      }
    }
    List<Intent> events = events();
    assertTrue(
        "Expected transfer progress before completion: " + service.lastOutcome.message,
        events.size() >= 2);
    Intent progress = events.get(0), complete = events.get(events.size() - 1);
    assertFalse(progress.getBooleanExtra("finished", true));
    assertTrue(progress.getStringExtra("message").contains("1/1"));
    assertTrue(progress.getStringExtra("message").contains("MB"));
    for (Intent event : events) {
      assertEquals("planned-route", event.getStringExtra("request"));
      assertEquals(context.getPackageName(), event.getPackage());
    }
    assertTrue(complete.getBooleanExtra("finished", false));
    assertTrue(complete.getBooleanExtra("success", false));
    RoutingData.validate(new File(RoutingData.directory(context), "E5_N50.rd5"));
    assertFalse(RoutingDownloadService.running);
  }

  @Test
  public void failedTransferReportsFailureAndNeverInstallsARegion() throws Exception {
    // A local closed port makes failure deterministic without calling the public server.
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    service.client = new GraphDownloader("http://127.0.0.1:" + port + "/");
    start();
    finish();
    Intent event = events().get(0);
    assertTrue(event.getBooleanExtra("finished", false));
    assertFalse(event.getBooleanExtra("success", true));
    assertTrue(event.getStringExtra("message").startsWith("Routing download paused:"));
    assertFalse(new File(RoutingData.directory(context), "E5_N50.rd5").exists());
    assertFalse(RoutingDownloadService.running);
  }

  @Test
  public void recordingRejectsPreparationBeforeItStarts() {
    TrackingService.running = true;
    start();
    assertFalse(RoutingDownloadService.running);
    assertTrue(events().isEmpty());
  }
}
