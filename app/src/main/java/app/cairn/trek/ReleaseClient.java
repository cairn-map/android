package app.cairn.trek;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.function.*;
import org.json.*;

/** User-triggered public release downloads; no REST API quota, credentials or polling. */
final class ReleaseClient {
  interface Connections {
    HttpURLConnection open(URL url) throws IOException;
  }

  static final class Release {
    final UpdateManifest manifest;
    final String notes;

    Release(UpdateManifest manifest, String notes) {
      this.manifest = manifest;
      this.notes = notes;
    }
  }

  private final String releases;
  private final String releasePath;
  private final Connections connections;
  private volatile HttpURLConnection current;
  private volatile boolean cancelled;

  ReleaseClient() {
    this(BuildConfig.UPDATE_REPOSITORY, url -> (HttpURLConnection) url.openConnection());
  }

  ReleaseClient(String repository, Connections connections) {
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid update repository");
    releasePath = "/" + repository + "/releases/";
    releases = "https://github.com" + releasePath;
    this.connections = connections;
  }

  void cancel() {
    cancelled = true;
    HttpURLConnection http = current;
    if (http != null) http.disconnect();
  }

  private void check(BooleanSupplier stop) throws InterruptedIOException {
    if (cancelled || stop.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Update canceled");
  }

  private boolean allowed(URL url, String assetName) {
    String host = url.getHost(), path = url.getPath();
    if ("release-assets.githubusercontent.com".equals(host)
        || "objects.githubusercontent.com".equals(host)) return true;
    if (!"github.com".equals(host)) return false;
    if (path.equals(releasePath + "latest/download/" + assetName)) return true;
    String prefix = releasePath + "download/v", suffix = "/" + assetName;
    if (!path.startsWith(prefix) || !path.endsWith(suffix)) return false;
    String version = path.substring(prefix.length(), path.length() - suffix.length());
    return version.matches("[0-9]+\\.[0-9]+\\.[0-9]+");
  }

  private static IOException denied(HttpURLConnection http, int code) {
    String remaining = http.getHeaderField("X-RateLimit-Remaining");
    String retryAfter = http.getHeaderField("Retry-After");
    if (code == 429 || "0".equals(remaining) || (code == 403 && retryAfter != null)) {
      String hint = retryAfter != null && retryAfter.matches("[0-9]{1,8}")
          ? " Retry after " + retryAfter + " seconds."
          : " Try again later.";
      return new IOException("GitHub rate limit reached (HTTP " + code + ")." + hint);
    }
    return new IOException(
        code == 401 ? "GitHub authentication required (HTTP 401)."
            : "GitHub access denied (HTTP 403). Try another network or later.");
  }

  private HttpURLConnection request(String address, String assetName, BooleanSupplier stop)
      throws IOException {
    URL url = new URL(address);
    for (int redirects = 0; redirects <= 4; redirects++) {
      check(stop);
      if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
          || (url.getPort() != -1 && url.getPort() != 443)
          || !allowed(url, assetName))
        throw new IOException("Unexpected update download destination");
      HttpURLConnection http = connections.open(url);
      current = http;
      http.setConnectTimeout(15000);
      http.setReadTimeout(15000);
      http.setInstanceFollowRedirects(false);
      http.setRequestProperty("User-Agent", "Cairn/" + BuildConfig.VERSION_NAME);
      http.setRequestProperty("Accept", "application/octet-stream");
      http.setRequestProperty("Accept-Encoding", "identity");
      boolean handedOff = false;
      try {
        check(stop);
        int code = http.getResponseCode();
        check(stop);
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
          String location = http.getHeaderField("Location");
          if (location == null) throw new IOException("Missing update download destination");
          url = new URL(url, location);
          continue;
        }
        if (code == 401 || code == 403 || code == 429) throw denied(http, code);
        if (code == 404)
          throw new IOException("No published update asset is available yet (HTTP 404).");
        if (code != 200) throw new IOException("Update server returned HTTP " + code);
        handedOff = true;
        return http;
      } finally {
        if (!handedOff) {
          http.disconnect();
          if (current == http) current = null;
        }
      }
    }
    throw new IOException("Too many update redirects");
  }

  private byte[] small(String url, String name, int limit, BooleanSupplier stop)
      throws IOException {
    HttpURLConnection http = request(url, name, stop);
    try (InputStream in = http.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (http.getContentLengthLong() > limit) throw new IOException("Update response is too large");
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        check(stop);
        if (out.size() + n > limit) throw new IOException("Update response is too large");
        out.write(buffer, 0, n);
      }
      check(stop);
      return out.toByteArray();
    } finally {
      http.disconnect();
      if (current == http) current = null;
    }
  }

  Release latest(BooleanSupplier stop) throws IOException {
    // GitHub's stable-release download URL avoids unauthenticated REST API limits.
    // APK bytes are still verified against this manifest and Android's installed signer.
    UpdateManifest manifest = new UpdateManifest(
        small(releases + "latest/download/update.json", "update.json", 16384, stop));
    return new Release(manifest, "");
  }

  void download(Release release, File target, BooleanSupplier stop, IntConsumer progress)
      throws IOException {
    UpdateManifest manifest = release.manifest;
    HttpURLConnection http = request(
        releases + "download/v" + manifest.versionName + "/" + manifest.apkName,
        manifest.apkName, stop);
    boolean complete = false;
    try {
      long length = http.getContentLengthLong();
      if (length != -1 && length != manifest.size) throw new IOException("Incorrect update size");
      MessageDigest digest = sha256();
      long count = 0;
      int lastPercent = -1;
      try (InputStream in = http.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          check(stop);
          count += n;
          if (count > manifest.size) throw new IOException("Update exceeds expected size");
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
          int percent = (int) (count * 100 / manifest.size);
          if (percent != lastPercent) { lastPercent = percent; progress.accept(percent); }
        }
        out.getFD().sync();
      }
      check(stop);
      if (count != manifest.size || !hex(digest.digest()).equals(manifest.sha256))
        throw new IOException("Update download is incomplete or its checksum is incorrect. Try again.");
      complete = true;
    } finally {
      http.disconnect();
      if (current == http) current = null;
      if (!complete) target.delete();
    }
  }

  static MessageDigest sha256() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }

  static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }
}
