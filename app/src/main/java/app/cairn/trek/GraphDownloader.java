package app.cairn.trek;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded, resumable HTTPS graph transfer; independently testable without Android services. */
final class GraphDownloader {
  interface SpaceCheck {
    void require(long bytes) throws IOException;
  }

  private final String baseUrl;
  private volatile HttpURLConnection connection;

  GraphDownloader() {
    this("https://brouter.de/brouter/segments4/");
  }

  GraphDownloader(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  private void check(BooleanSupplier stop) throws IOException {
    if (stop.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Paused");
  }

  void cancel() {
    HttpURLConnection http = connection;
    if (http != null) http.disconnect();
  }

  void download(
      File dir,
      String name,
      java.util.function.BooleanSupplier stop,
      java.util.function.Consumer<String> progress,
      SpaceCheck space)
      throws IOException {
    if (!RoutingData.validName(name)) throw new IOException("Invalid routing region filename");
    File part = new File(dir, name + ".part"), meta = new File(dir, name + ".resume");
    Properties properties = new Properties();
    if (meta.isFile())
      try (InputStream in = new FileInputStream(meta)) {
        properties.load(in);
      }
    String validator = properties.getProperty("validator", "");
    long offset = validator.isEmpty() ? 0 : part.length();
    HttpURLConnection http = (HttpURLConnection) new URL(baseUrl + name).openConnection();
    connection = http;
    http.setConnectTimeout(15000);
    http.setReadTimeout(15000);
    http.setInstanceFollowRedirects(false);
    http.setRequestProperty("User-Agent", "Cairn/0.4 Android offline router");
    http.setRequestProperty("Accept-Encoding", "identity");
    if (offset > 0) {
      http.setRequestProperty("Range", "bytes=" + offset + "-");
      http.setRequestProperty("If-Range", validator);
    }
    try {
      check(stop);
      int code = http.getResponseCode();
      if (code == 416 && offset > 0) {
        String range = http.getHeaderField("Content-Range"), etag = http.getHeaderField("ETag");
        if (("bytes */" + offset).equals(range) && validator.equals(etag)) {
          try {
            RoutingData.validate(part);
            check(stop);
            Files.move(
                part.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            meta.delete();
            return;
          } catch (IOException error) {
            if (!stop.getAsBoolean()) {
              part.delete();
              meta.delete();
            }
            throw error;
          }
        }
        part.delete();
        meta.delete();
        throw new IOException("Region changed; resume again to restart this file");
      }
      if (code != 200 && code != 206) throw new IOException("HTTP " + code + " for " + name);
      if (code == 206) {
        String range = http.getHeaderField("Content-Range");
        if (offset <= 0 || range == null || !range.startsWith("bytes " + offset + "-"))
          throw new IOException("Invalid resumed response");
      } else {
        offset = 0;
        // Reset old bytes before committing the new validator, including across process death.
        try (RandomAccessFile reset = new RandomAccessFile(part, "rw")) {
          reset.setLength(0);
        }
      }
      long remaining = http.getContentLengthLong();
      if (remaining < 0 || remaining + offset > 1500L * 1024 * 1024)
        throw new IOException("Invalid or oversized routing region");
      space.require(remaining + 20L * 1024 * 1024);
      String nextValidator = http.getHeaderField("ETag");
      if (nextValidator == null) nextValidator = http.getHeaderField("Last-Modified");
      properties.setProperty("validator", nextValidator == null ? "" : nextValidator);
      File metaPart = new File(dir, name + ".resume.tmp");
      try (OutputStream out = new FileOutputStream(metaPart)) {
        properties.store(out, "Cairn routing resume");
      }
      Files.move(metaPart.toPath(), meta.toPath(), StandardCopyOption.REPLACE_EXISTING);
      long done = offset, expected = offset + remaining, last = 0;
      try (InputStream in = http.getInputStream();
          OutputStream out = new FileOutputStream(part, offset > 0)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          check(stop);
          done += n;
          if (done > expected) throw new IOException("Routing response exceeds expected size");
          out.write(buffer, 0, n);
          long now = (System.nanoTime() / 1000000);
          if (now - last > 2000) {
            last = now;
            progress.accept(name + " · " + done * 100 / Math.max(1, expected) + "%");
          }
        }
      }
      if (done != expected) throw new IOException("Incomplete routing data; resume to finish");
      check(stop);
      try {
        RoutingData.validate(part);
      } catch (IOException error) {
        part.delete();
        meta.delete();
        throw error;
      }
      check(stop);
      Files.move(part.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
      meta.delete();
    } finally {
      http.disconnect();
      if (connection == http) connection = null;
    }
  }
}
