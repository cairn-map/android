package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class DownloadServiceTest {
  @Test
  public void stalledTileDoesNotHoldUpThePipelineAndPauseRemainsResumable() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("plan-ign.mbtiles");
    DownloadService.running = false;
    TrackingService.running = false;
    CountDownLatch initialPool = new CountDownLatch(IgnClient.PARALLELISM);
    CountDownLatch replacementStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    AtomicBoolean blockFirst = new AtomicBoolean(true);
    AtomicInteger started = new AtomicInteger();
    byte[] bytes = IgnClientTest.png();
    try (PlanIgnStore store = new PlanIgnStore(context)) {
      Set<TilePlanner.Tile> tiles = new HashSet<>();
      for (int x = 0; x < IgnClient.PARALLELISM * 3; x++)
        tiles.add(new TilePlanner.Tile(17, x, 50000));
      tiles.add(new TilePlanner.Tile(8, 0, 100));
      String id = store.create("Pipeline", new TilePlanner.Plan(tiles, new Point(45, 6)));
      ServiceController<DownloadService> controller =
          Robolectric.buildService(DownloadService.class).create();
      DownloadService service = controller.get();
      service.client =
          new IgnClient(
              url ->
                  new HttpURLConnection(url) {
                    @Override
                    public int getResponseCode() throws IOException {
                      int count = started.incrementAndGet();
                      if (count <= IgnClient.PARALLELISM)
                        assertTrue(
                            "Detailed tiles must start before overview tiles",
                            url.toString().contains("TILEMATRIX=17&"));
                      initialPool.countDown();
                      if (count > IgnClient.PARALLELISM) replacementStarted.countDown();
                      try {
                        if (!initialPool.await(3, TimeUnit.SECONDS))
                          throw new IOException("Pool did not fill");
                        if (url.toString().endsWith("TILECOL=0")
                            && url.toString().contains("TILEMATRIX=17&")
                            && blockFirst.getAndSet(false)) {
                          if (!releaseSlow.await(5, TimeUnit.SECONDS))
                            throw new IOException("Slow tile did not cancel");
                        }
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                      }
                      return 200;
                    }

                    @Override
                    public String getContentType() {
                      return "image/png";
                    }

                    @Override
                    public InputStream getInputStream() {
                      return new ByteArrayInputStream(bytes);
                    }

                    @Override
                    public void disconnect() {
                      if (url.toString().endsWith("TILECOL=0")) releaseSlow.countDown();
                    }

                    @Override
                    public void connect() {}

                    @Override
                    public boolean usingProxy() {
                      return false;
                    }
                  });
      try {
        service.onStartCommand(
            new Intent(context, DownloadService.class).putExtra("pack", id), 0, 1);
        assertTrue(
            "A free slot must refill without waiting for the stalled first tile",
            replacementStarted.await(4, TimeUnit.SECONDS));
        assertEquals("The first tile must still be in flight", 1, releaseSlow.getCount());
        service.onStartCommand(
            new Intent(context, DownloadService.class).setAction(DownloadService.PAUSE), 0, 2);
        awaitFinished();
        assertNull(
            "Canceled data must not commit", store.cached(new TilePlanner.Tile(17, 0, 50000)));
        assertEquals("paused", store.packs().get(0).state);
        assertTrue(store.packs().get(0).done > 0);
        assertFalse(store.finish(id));
        service.onStartCommand(
            new Intent(context, DownloadService.class).putExtra("pack", id), 0, 3);
        awaitFinished();
        assertTrue(store.finish(id));
        assertEquals(tiles.size(), store.packs().get(0).done);
        assertEquals("complete", store.packs().get(0).state);
      } finally {
        releaseSlow.countDown();
        controller.destroy();
        awaitFinished();
      }
    }
  }

  private void awaitFinished() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (DownloadService.running && System.nanoTime() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      Thread.sleep(5);
    }
    assertFalse("Download coordinator did not finish", DownloadService.running);
  }
}
