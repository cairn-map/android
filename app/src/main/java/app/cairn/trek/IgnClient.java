package app.cairn.trek;

import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;

/** Public Plan IGN only; no credentials or paid map layers. Usable in JVM integration checks. */
public final class IgnClient {
  public static final String LAYER = "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2";
  public static final int PARALLELISM = 32;
  private static final java.util.concurrent.Semaphore slots =
      new java.util.concurrent.Semaphore(PARALLELISM, true);
  private static volatile long retryAfterNanos;
  private final java.util.Set<HttpURLConnection> active =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.AtomicInteger generation =
      new java.util.concurrent.atomic.AtomicInteger();

  interface Connections {
    HttpURLConnection open(URL url) throws IOException;
  }

  private final Connections connections;

  public IgnClient() {
    this(url -> (HttpURLConnection) url.openConnection());
  }

  IgnClient(Connections connections) {
    this.connections = connections;
  }

  public static final class Result {
    public final byte[] bytes;
    public final String etag, modified;

    Result(byte[] bytes, String etag, String modified) {
      this.bytes = bytes;
      this.etag = etag;
      this.modified = modified;
    }
  }

  public static String url(TilePlanner.Tile tile) {
    return "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER="
        + LAYER
        + "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM_0_19&TILEMATRIX="
        + tile.z
        + "&TILEROW="
        + tile.y
        + "&TILECOL="
        + tile.x;
  }

  private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Download paused");
  }

  private static void waitFor(long millis, BooleanSupplier cancelled) throws IOException {
    long until = System.nanoTime() + millis * 1_000_000L;
    while (System.nanoTime() < until) {
      check(cancelled);
      try {
        Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Download paused");
      }
    }
  }

  private static void awaitServer(BooleanSupplier cancelled) throws IOException {
    while (true) {
      check(cancelled);
      long delay = (retryAfterNanos - System.nanoTime() + 999999) / 1_000_000;
      if (delay <= 0) return;
      waitFor(delay, cancelled);
    }
  }

  public Result fetch(
      TilePlanner.Tile tile, String etag, String modified, BooleanSupplier cancelled)
      throws IOException {
    int epoch = generation.get();
    BooleanSupplier stop = () -> cancelled.getAsBoolean() || epoch != generation.get();
    boolean acquired = false;
    try {
      while (!acquired) {
        check(stop);
        awaitServer(stop);
        try {
          acquired = slots.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Download paused");
        }
      }
      return fetchWithSlot(tile, etag, modified, stop);
    } finally {
      if (acquired) slots.release();
    }
  }

  private Result fetchWithSlot(
      TilePlanner.Tile tile, String etag, String modified, BooleanSupplier stop)
      throws IOException {
    for (int attempt = 0; attempt < 3; attempt++) {
      check(stop);
      awaitServer(stop);
      HttpURLConnection connection = connections.open(new URL(url(tile)));
      active.add(connection);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "Cairn/0.4 (private Android trekking app)");
      connection.setRequestProperty("Accept", "image/png");
      if (etag != null && !etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
      else if (modified != null && !modified.isEmpty())
        connection.setRequestProperty("If-Modified-Since", modified);
      try {
        check(stop);
        int code = connection.getResponseCode();
        if (code == 429 || code == 503) {
          long retry = retryDelayMillis(connection.getHeaderField("Retry-After"), attempt);
          synchronized (IgnClient.class) {
            retryAfterNanos = Math.max(retryAfterNanos, System.nanoTime() + retry * 1_000_000L);
          }
          if (attempt == 2)
            throw new IOException("IGN is busy. Your download is saved; resume it later.");
          connection.disconnect();
          continue;
        }
        if (code == 304) return new Result(null, etag, modified);
        if (code != 200)
          throw new IOException(
              "IGN returned HTTP " + code + ". The package is incomplete; resume it later.");
        String mime = connection.getContentType();
        if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/png"))
          throw new IOException("IGN did not return a PNG map tile");
        byte[] bytes;
        try (InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = in.read(buffer)) != -1) {
            check(stop);
            if (out.size() + count > 2 * 1024 * 1024)
              throw new IOException("Map tile exceeds the size limit");
            out.write(buffer, 0, count);
          }
          bytes = out.toByteArray();
        }
        validatePng(bytes);
        check(stop);
        return new Result(
            bytes, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
      } finally {
        connection.disconnect();
        active.remove(connection);
      }
    }
    throw new IOException("Could not download map tile");
  }

  static long retryDelayMillis(String header, int attempt) {
    if (header != null) {
      try {
        return Math.max(
            1000, Math.min(86_400_000, Math.multiplyExact(Long.parseLong(header.trim()), 1000)));
      } catch (NumberFormatException | ArithmeticException ignored) {
        try {
          return Math.max(
              1000,
              Math.min(
                  86_400_000,
                  java.time.ZonedDateTime.parse(
                              header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                          .toInstant()
                          .toEpochMilli()
                      - System.currentTimeMillis()));
        } catch (java.time.DateTimeException ignoredDate) {
          // Missing or malformed Retry-After uses a bounded exponential fallback.
        }
      }
    }
    return 5000L << attempt;
  }

  public static void validatePng(byte[] bytes) throws IOException {
    byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    if (bytes.length < 45) throw new IOException("Incomplete PNG map tile");
    for (int i = 0; i < 8; i++)
      if (bytes[i] != signature[i]) throw new IOException("Invalid PNG map tile");
    if (integer(bytes, 16) != 256 || integer(bytes, 20) != 256)
      throw new IOException("Unexpected map tile dimensions");
    boolean end = false;
    int position = 8;
    while (position + 12 <= bytes.length) {
      int length = integer(bytes, position);
      if (length < 0 || (long) position + 12 + length > bytes.length)
        throw new IOException("Truncated PNG map tile");
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      crc.update(bytes, position + 4, length + 4);
      if (crc.getValue() != Integer.toUnsignedLong(integer(bytes, position + 8 + length)))
        throw new IOException("Corrupt PNG map tile");
      if (bytes[position + 4] == 'I'
          && bytes[position + 5] == 'E'
          && bytes[position + 6] == 'N'
          && bytes[position + 7] == 'D') {
        end = length == 0;
        break;
      }
      position += 12 + length;
    }
    if (!end) throw new IOException("PNG map tile has no end marker");
  }

  private static int integer(byte[] bytes, int at) {
    return (bytes[at] & 255) << 24
        | (bytes[at + 1] & 255) << 16
        | (bytes[at + 2] & 255) << 8
        | bytes[at + 3] & 255;
  }

  public void cancel() {
    generation.incrementAndGet();
    for (HttpURLConnection connection : active) connection.disconnect();
  }
}
