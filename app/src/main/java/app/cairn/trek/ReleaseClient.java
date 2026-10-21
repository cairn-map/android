package app.cairn.trek;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.function.*;
import org.json.*;

/** User-triggered HTTPS transfers. No scheduler, retries, service, or automatic polling. */
final class ReleaseClient {
  interface Connections {
    HttpURLConnection open(URL url) throws IOException;
  }

  static final class Release {
    final UpdateManifest manifest;
    final long apkId;
    final String notes;

    Release(UpdateManifest manifest, long apkId, String notes) {
      this.manifest = manifest;
      this.apkId = apkId;
      this.notes = notes;
    }
  }

  private final String api;
  private final Connections connections;
  private volatile HttpURLConnection current;
  private volatile boolean cancelled;

  ReleaseClient() {
    this(BuildConfig.UPDATE_REPOSITORY, url -> (HttpURLConnection) url.openConnection());
  }

  ReleaseClient(String repository, Connections connections) {
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid update repository");
    api = "https://api.github.com/repos/" + repository + "/releases/";
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

  private HttpURLConnection request(String address, String accept, BooleanSupplier stop)
      throws IOException {
    URL url = new URL(address);
    for (int redirects = 0; redirects <= 4; redirects++) {
      check(stop);
      String host = url.getHost();
      boolean apiRequest = "api.github.com".equals(host) && url.toString().startsWith(api);
      if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
          || (url.getPort() != -1 && url.getPort() != 443)
          || !(apiRequest || "release-assets.githubusercontent.com".equals(host)
              || "objects.githubusercontent.com".equals(host)))
        throw new IOException("Unexpected update download destination");
      HttpURLConnection http = connections.open(url);
      current = http;
      http.setConnectTimeout(15000);
      http.setReadTimeout(15000);
      http.setInstanceFollowRedirects(false);
      http.setRequestProperty("User-Agent", "Cairn/" + BuildConfig.VERSION_NAME);
      http.setRequestProperty("Accept", accept);
      http.setRequestProperty("Accept-Encoding", "identity");
      if (apiRequest) {
        http.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
      }
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
        if (code == 401 || code == 403)
          throw new IOException("GitHub access denied or rate limit reached. Try again later.");
        if (code == 404)
          throw new IOException("No published update is available yet. Try again after a release is published.");
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

  private byte[] small(String url, String accept, int limit, BooleanSupplier stop)
      throws IOException {
    HttpURLConnection http = request(url, accept, stop);
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
    try {
      JSONObject release = new JSONObject(new String(
          small(api + "latest", "application/vnd.github+json", 1024 * 1024, stop),
          StandardCharsets.UTF_8));
      if (release.getBoolean("draft") || release.getBoolean("prerelease"))
        throw new IOException("No stable update is published");
      JSONArray assets = release.getJSONArray("assets");
      JSONObject manifestAsset = asset(assets, "update.json");
      UpdateManifest manifest = new UpdateManifest(small(
          assetUrl(manifestAsset.getLong("id")), "application/octet-stream", 16384, stop));
      if (!release.getString("tag_name").equals("v" + manifest.versionName))
        throw new IOException("Update version does not match its release");
      JSONObject apk = asset(assets, manifest.apkName);
      if (apk.getLong("size") != manifest.size)
        throw new IOException("Update APK size does not match its release");
      String digest = apk.optString("digest", "");
      if (!digest.isEmpty() && !digest.equals("sha256:" + manifest.sha256))
        throw new IOException("Update checksum does not match its release");
      String notes = release.optString("body", "");
      return new Release(manifest, apk.getLong("id"), notes.substring(0, Math.min(4000, notes.length())));
    } catch (JSONException error) {
      throw new IOException("Invalid update release response", error);
    }
  }

  private static JSONObject asset(JSONArray assets, String name) throws IOException, JSONException {
    JSONObject found = null;
    for (int i = 0; i < assets.length(); i++) {
      JSONObject value = assets.getJSONObject(i);
      if (name.equals(value.getString("name")) && "uploaded".equals(value.getString("state"))) {
        if (found != null) throw new IOException("Ambiguous update release");
        found = value;
      }
    }
    if (found == null) throw new IOException("This release does not contain a complete Cairn update");
    return found;
  }

  private String assetUrl(long id) throws IOException {
    if (id < 1) throw new IOException("Invalid update asset");
    return api + "assets/" + id;
  }

  void download(Release release, File target, BooleanSupplier stop, IntConsumer progress)
      throws IOException {
    UpdateManifest manifest = release.manifest;
    HttpURLConnection http = request(assetUrl(release.apkId), "application/octet-stream", stop);
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
