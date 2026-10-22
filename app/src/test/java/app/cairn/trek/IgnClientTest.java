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
  public void sharedClientTransfersFourTilesConcurrentlyAndCancelsEveryConnection() throws Exception {
    CountDownLatch requestsStarted = new CountDownLatch(4);
    List<HttpURLConnection> connections = Collections.synchronizedList(new ArrayList<>());
    byte[] bytes = png();
    IgnClient client = new IgnClient(url -> {
      CountDownLatch released = new CountDownLatch(1);
      HttpURLConnection connection = new HttpURLConnection(url) {
        @Override public int getResponseCode() throws IOException {
          requestsStarted.countDown();
          try {
            if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("Transfer did not overlap");
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
          }
          return 200;
        }
        @Override public String getContentType() { return "image/png"; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { released.countDown(); }
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
      };
      connections.add(connection);
      return connection;
    });
    ExecutorService workers = Executors.newFixedThreadPool(4);
    List<Future<IgnClient.Result>> results = new ArrayList<>();
    try {
      for (int x = 0; x < 4; x++) {
        TilePlanner.Tile tile = new TilePlanner.Tile(10, x, 512);
        results.add(workers.submit(() -> client.fetch(tile, null, null, () -> false)));
      }
      assertTrue("All four HTTP requests must start before any completes",
          requestsStarted.await(4, TimeUnit.SECONDS));
      client.cancel();
      for (Future<IgnClient.Result> result : results) {
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof InterruptedIOException);
      }
      assertEquals(4, connections.size());
    } finally {
      for (HttpURLConnection connection : connections) connection.disconnect();
      client.cancel();
      workers.shutdownNow();
    }
  }
}
