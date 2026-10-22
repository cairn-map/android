package app.cairn.trek;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class IgnClientTest {
  static byte[] png() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(header);
    data.writeInt(256);
    data.writeInt(256);
    data.write(new byte[] {8, 2, 0, 0, 0});
    chunk(out, "IHDR", header.toByteArray());
    ByteArrayOutputStream pixels = new ByteArrayOutputStream();
    try (java.util.zip.DeflaterOutputStream compressed =
        new java.util.zip.DeflaterOutputStream(pixels)) {
      compressed.write(new byte[256 * (1 + 256 * 3)]);
    }
    chunk(out, "IDAT", pixels.toByteArray());
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  private static void chunk(OutputStream out, String type, byte[] bytes) throws IOException {
    DataOutputStream data = new DataOutputStream(out);
    byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    data.writeInt(bytes.length);
    data.write(tag);
    data.write(bytes);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(tag);
    crc.update(bytes);
    data.writeInt((int) crc.getValue());
  }

  @Test
  public void completeRasterAccepted() throws Exception {
    IgnClient.validatePng(png());
  }

  @Test
  public void truncatedAndCorruptTilesCannotCountAsDownloaded() throws Exception {
    byte[] bytes = png();
    assertThrows(
        IOException.class,
        () -> IgnClient.validatePng(java.util.Arrays.copyOf(bytes, bytes.length - 6)));
    bytes[40] ^= 1;
    assertThrows(IOException.class, () -> IgnClient.validatePng(bytes));
  }

  @Test
  public void nonMapResponsesRejected() {
    assertThrows(
        IOException.class,
        () -> IgnClient.validatePng("<ExceptionReport>rate limit</ExceptionReport>".getBytes()));
  }

  @Test
  public void sharedClientFillsTheTransferLimitWithoutSerialDelayAndCancelsEveryConnection()
      throws Exception {
    CountDownLatch requestsStarted = new CountDownLatch(IgnClient.PARALLELISM);
    List<HttpURLConnection> connections = Collections.synchronizedList(new ArrayList<>());
    byte[] bytes = png();
    IgnClient client =
        new IgnClient(
            url -> {
              CountDownLatch released = new CountDownLatch(1);
              HttpURLConnection connection =
                  new HttpURLConnection(url) {
                    @Override
                    public int getResponseCode() throws IOException {
                      requestsStarted.countDown();
                      try {
                        if (!released.await(5, TimeUnit.SECONDS))
                          throw new IOException("Transfer did not overlap");
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
                      released.countDown();
                    }

                    @Override
                    public void connect() {}

                    @Override
                    public boolean usingProxy() {
                      return false;
                    }
                  };
              connections.add(connection);
              return connection;
            });
    ExecutorService workers = Executors.newFixedThreadPool(IgnClient.PARALLELISM);
    List<Future<IgnClient.Result>> results = new ArrayList<>();
    try {
      for (int x = 0; x < IgnClient.PARALLELISM; x++) {
        TilePlanner.Tile tile = new TilePlanner.Tile(10, x, 512);
        results.add(workers.submit(() -> client.fetch(tile, null, null, () -> false)));
      }
      assertTrue(
          "The entire pool must start together without the old 350 ms delay",
          requestsStarted.await(2, TimeUnit.SECONDS));
      client.cancel();
      for (Future<IgnClient.Result> result : results) {
        ExecutionException error =
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof InterruptedIOException);
      }
      assertEquals(IgnClient.PARALLELISM, connections.size());
    } finally {
      for (HttpURLConnection connection : connections) connection.disconnect();
      client.cancel();
      workers.shutdownNow();
    }
  }

  @Test
  public void retryAfterHonorsLongDelaysAndHttpDates() {
    assertEquals(120_000, IgnClient.retryDelayMillis("120", 0));
    String future =
        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .plusMinutes(3)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
    assertTrue(IgnClient.retryDelayMillis(future, 0) > 175_000);
    assertEquals(1000, IgnClient.retryDelayMillis("0", 0));
    assertEquals(10_000, IgnClient.retryDelayMillis("invalid", 1));
    assertEquals(5000, IgnClient.retryDelayMillis(null, 0));
  }

  @Test
  public void viewportCancellationDisconnectsObsoleteTransfersAndKeepsUsefulOnes()
      throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
    java.util.concurrent.atomic.AtomicBoolean obsolete =
        new java.util.concurrent.atomic.AtomicBoolean();
    byte[] bytes = png();
    IgnClient client =
        new IgnClient(
            url -> {
              int index = url.getQuery().endsWith("TILECOL=0") ? 0 : 1;
              return new HttpURLConnection(url) {
                public int getResponseCode() throws IOException {
                  started.countDown();
                  try {
                    if (!release[index].await(3, TimeUnit.SECONDS))
                      throw new IOException("Timed out");
                  } catch (InterruptedException error) {
                    throw new InterruptedIOException();
                  }
                  return 200;
                }

                public String getContentType() {
                  return "image/png";
                }

                public InputStream getInputStream() {
                  return new ByteArrayInputStream(bytes);
                }

                public void disconnect() {
                  release[index].countDown();
                }

                public void connect() {}

                public boolean usingProxy() {
                  return false;
                }
              };
            });
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<IgnClient.Result> old =
          workers.submit(
              () -> client.fetch(new TilePlanner.Tile(10, 0, 512), null, null, obsolete::get));
      Future<IgnClient.Result> useful =
          workers.submit(
              () -> client.fetch(new TilePlanner.Tile(10, 1, 512), null, null, () -> false));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      obsolete.set(true);
      client.cancelObsolete();
      assertThrows(ExecutionException.class, () -> old.get(3, TimeUnit.SECONDS));
      assertFalse(useful.isDone());
      release[1].countDown();
      assertArrayEquals(bytes, useful.get(3, TimeUnit.SECONDS).bytes);
    } finally {
      client.cancel();
      workers.shutdownNow();
    }
  }
}
