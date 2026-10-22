package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final byte[] apkBytes = "complete signed APK fixture".getBytes(StandardCharsets.UTF_8);

  @Before public void reset() {
    TrackingService.running = false;
    UpdateFiles.clear(RuntimeEnvironment.getApplication());
    org.robolectric.shadows.ShadowStatFs.registerStats(
        UpdateFiles.directory(RuntimeEnvironment.getApplication()), 1000000, 1000000, 1000000);
  }

  private JSONObject metadata() throws Exception {
    return new JSONObject().put("schema", 1).put("applicationId", "app.cairn.trek")
        .put("versionCode", 6).put("versionName", "0.6.0").put("minSdk", 26)
        .put("apkName", "Cairn-0.6.0.apk").put("size", apkBytes.length)
        .put("sha256", ReleaseClient.hex(ReleaseClient.sha256().digest(apkBytes)));
  }

  private UpdateManifest manifest() throws Exception { return new UpdateManifest(json(metadata())); }
  private byte[] json(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }

  private JSONObject release() throws Exception {
    return new JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v0.6.0")
        .put("body", "New version")
        .put("assets", new JSONArray()
            .put(new JSONObject().put("name", "update.json").put("state", "uploaded").put("id", 1))
            .put(new JSONObject().put("name", "Cairn-0.6.0.apk").put("state", "uploaded")
                .put("id", 2).put("size", apkBytes.length)));
  }

  private static final class Response extends HttpURLConnection {
    final byte[] body;
    String location;
    final Map<String, String> headers = new HashMap<>();
    int code = 200;
    long length = -1;
    boolean disconnected;
    Response(byte[] body) throws Exception { super(new URL("https://api.github.com/")); this.body = body; }
    @Override public int getResponseCode() { return code; }
    @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : headers.get(name); }
    @Override public long getContentLengthLong() { return length; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    @Override public void disconnect() { disconnected = true; }
    @Override public void connect() {}
    @Override public boolean usingProxy() { return false; }
  }

  private ReleaseClient client(List<String> requests, Response... responses) {
    Deque<Response> queue = new ArrayDeque<>(Arrays.asList(responses));
    return new ReleaseClient("cairn-map/android", url -> {
      requests.add(url.toString());
      if (queue.isEmpty()) throw new IOException("Unexpected request");
      return queue.remove();
    });
  }

  @Test public void checkUsesPublicAssetDownloadsWithoutRestApi() throws Exception {
    assertEquals("cairn-map/android", BuildConfig.UPDATE_REPOSITORY);
    List<String> requests = new ArrayList<>();
    Response manifestRedirect = new Response(new byte[0]);
    manifestRedirect.code = 302;
    manifestRedirect.location =
        "https://github.com/cairn-map/android/releases/download/v0.6.0/update.json";
    Response apkRedirect = new Response(new byte[0]);
    apkRedirect.code = 302;
    apkRedirect.location = "https://release-assets.githubusercontent.com/signed-apk";
    Response metadataResponse = new Response(json(metadata()));
    Response apkResponse = new Response(apkBytes);
    ReleaseClient client = client(requests, manifestRedirect, metadataResponse,
        apkRedirect, apkResponse);
    ReleaseClient.Release result = client.latest(() -> false);
    assertEquals(6, result.manifest.versionCode);
    assertEquals("https://github.com/cairn-map/android/releases/latest/download/update.json",
        requests.get(0));
    assertEquals(2, requests.size());
    File target = new File(temporary.getRoot(), "download.part");
    client.download(result, target, () -> false, n -> {});
    assertArrayEquals(apkBytes, Files.readAllBytes(target.toPath()));
    assertEquals(4, requests.size());
    assertEquals("https://github.com/cairn-map/android/releases/download/v0.6.0/Cairn-0.6.0.apk",
        requests.get(2));
    for (String url : requests) assertFalse(url.contains("api.github.com"));
    assertNull(metadataResponse.getRequestProperty("Authorization"));
    assertTrue(manifestRedirect.disconnected && apkRedirect.disconnected);
  }

  @Test public void rejectsUntrustedDownloadRedirects() throws Exception {
    for (String address : Arrays.asList("http://release-assets.githubusercontent.com/file",
        "https://evil.example/update",
        "https://api.github.com/repos/cairn-map/android/releases/assets/1",
        "https://github.com/other/app/releases/download/v0.6.0/update.json",
        "https://release-assets.githubusercontent.com:8443/file")) {
      Response redirect = new Response(new byte[0]);
      redirect.code = 302;
      redirect.location = address;
      List<String> requests = new ArrayList<>();
      ReleaseClient client = client(requests, redirect);
      assertThrows(IOException.class, () -> client.latest(() -> false));
      assertEquals(1, requests.size());
    }
  }

  @Test public void reportsRateLimitAndAuthenticationSeparately() throws Exception {
    Response rate = new Response(new byte[0]);
    rate.code = 403;
    rate.headers.put("X-RateLimit-Remaining", "0");
    IOException first = assertThrows(IOException.class,
        () -> client(new ArrayList<>(), rate).latest(() -> false));
    assertTrue(first.getMessage().contains("rate limit"));
    Response authentication = new Response(new byte[0]);
    authentication.code = 401;
    IOException second = assertThrows(IOException.class,
        () -> client(new ArrayList<>(), authentication).latest(() -> false));
    assertTrue(second.getMessage().contains("authentication"));
  }

  @Test public void rejectsTruncationCorruptionAndOversizedTransfersWithoutKeepingPartialApks() throws Exception {
    for (byte[] data : Arrays.asList(Arrays.copyOf(apkBytes, 3), new byte[apkBytes.length],
        Arrays.copyOf(apkBytes, apkBytes.length + 1))) {
      ReleaseClient client = client(new ArrayList<>(), new Response(data));
      File file = new File(temporary.getRoot(), "partial.apk");
      ReleaseClient.Release release = new ReleaseClient.Release(manifest(), "");
      assertThrows(IOException.class, () -> client.download(release, file, () -> false, p -> {}));
      assertFalse(file.exists());
    }
  }

  @Test public void cancelDisconnectsAndPreventsFurtherRequestsOrPublishingAPartialDownload() throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    Response apk = new Response(apkBytes);
    List<String> requests = new ArrayList<>();
    ReleaseClient client = client(requests, apk);
    File target = new File(temporary.getRoot(), "partial.apk");
    ReleaseClient.Release release = new ReleaseClient.Release(manifest(), "");
    assertThrows(InterruptedIOException.class,
        () -> client.download(release, target, stop::get, percent -> { stop.set(true); client.cancel(); }));
    assertFalse(target.exists());
    assertTrue(apk.disconnected);
    assertThrows(InterruptedIOException.class, () -> client.latest(() -> false));
    assertEquals(1, requests.size());
  }

  @Test public void rejectsMalformedManifest() throws Exception {
    ReleaseClient client = client(new ArrayList<>(),
        new Response(json(metadata().put("applicationId", "another.app"))));
    assertThrows(IOException.class, () -> client.latest(() -> false));
    assertThrows(IOException.class,
        () -> new UpdateManifest(json(metadata().put("size", UpdateManifest.MAX_APK_BYTES + 1))));
  }

  @Test public void installedOrOlderSavedUpdateIsClearedButNewerStays() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File dir = UpdateFiles.directory(context);
    assertTrue(dir.isDirectory() || dir.mkdirs());
    File staged = new File(dir, "staged.apk");
    Files.write(staged.toPath(), apkBytes);
    UpdateFiles.save(context, staged, manifest());
    assertFalse(UpdateFiles.discardInstalled(context, 5));
    assertTrue(UpdateFiles.ready(context));
    assertTrue(UpdateFiles.discardInstalled(context, 6));
    assertFalse(UpdateFiles.ready(context));

    Files.write(staged.toPath(), apkBytes);
    UpdateFiles.save(context, staged, manifest());
    assertTrue(UpdateFiles.discardInstalled(context, 7));
    assertFalse(UpdateFiles.ready(context));
  }

  private PackageInfo info(int code, String signer) {
    PackageInfo value = new PackageInfo();
    value.packageName = "app.cairn.trek";
    if (Build.VERSION.SDK_INT >= 28) value.setLongVersionCode(code);
    else value.versionCode = code;
    value.versionName = code == 6 ? "0.6.0" : "0.5.0";
    value.applicationInfo = new ApplicationInfo();
    value.applicationInfo.minSdkVersion = 26;
    Signature[] signatures = {new Signature(signer.getBytes(StandardCharsets.UTF_8))};
    if (Build.VERSION.SDK_INT >= 28) {
      value.signingInfo = Shadow.newInstanceOf(SigningInfo.class);
      Shadows.shadowOf(value.signingInfo).setSignatures(signatures);
    } else value.signatures = signatures;
    return value;
  }

  @Test public void onlyAcceptsNewerApksWithMatchingIdentityVersionAndActualSigner() throws Exception {
    UpdateManifest manifest = manifest();
    ApkVerifier.validate(info(5, "trusted"), info(6, "trusted"), manifest);
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(5, "trusted"), info(6, "wrong"), manifest));
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(6, "trusted"), info(6, "trusted"), manifest));
    PackageInfo other = info(6, "trusted");
    other.packageName = "other.app";
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(5, "trusted"), other, manifest));
    PackageInfo wrongVersion = info(7, "trusted");
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(5, "trusted"), wrongVersion, manifest));
    PackageInfo unsupported = info(6, "trusted");
    unsupported.applicationInfo.minSdkVersion = Build.VERSION.SDK_INT + 1;
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(5, "trusted"), unsupported, manifest));
  }

  @Test @Config(sdk = 26)
  public void verifiesTheLegacySigningApiOnAndroidEight() throws Exception {
    ApkVerifier.validate(info(5, "trusted"), info(6, "trusted"), manifest());
    assertThrows(IOException.class, () -> ApkVerifier.validate(info(5, "trusted"), info(6, "other"), manifest()));
  }

  @Test public void providerOnlySharesTheExactStagedApkReadOnly() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File dir = UpdateFiles.directory(context);
    assertTrue(dir.isDirectory() || dir.mkdirs());
    File part = new File(dir, "part");
    Files.write(part.toPath(), apkBytes);
    UpdateFiles.save(context, part, manifest());
    UpdateFileProvider provider = Robolectric.buildContentProvider(UpdateFileProvider.class).create().get();
    try (android.os.ParcelFileDescriptor descriptor = provider.openFile(UpdateFileProvider.uri(context), "r")) {
      assertNotNull(descriptor);
    }
    assertThrows(FileNotFoundException.class, () -> provider.openFile(UpdateFileProvider.uri(context), "rw"));
    assertThrows(FileNotFoundException.class, () -> provider.openFile(
        android.net.Uri.parse("content://app.cairn.trek.updates/../database.db"), "r"));
    Intent intent = AppUpdates.installIntent(context);
    assertEquals(Intent.ACTION_VIEW, intent.getAction());
    assertEquals("content", intent.getData().getScheme());
    assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
    assertNotNull(intent.getClipData());
  }

  private Object field(Object object, String name) throws Exception {
    java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private void drain(AppUpdates updates) throws Exception {
    java.util.concurrent.ExecutorService worker =
        (java.util.concurrent.ExecutorService) field(updates, "worker");
    worker.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  }

  private AppUpdates updater(
      MainActivity activity, ReleaseClient client, AppUpdates.Verifier verifier) throws Exception {
    ((AppUpdates) field(activity, "appUpdates")).close();
    AppUpdates updates = new AppUpdates(activity, () -> client, verifier);
    java.lang.reflect.Field holder = MainActivity.class.getDeclaredField("appUpdates");
    holder.setAccessible(true);
    holder.set(activity, updates);
    java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showPage", String.class);
    show.setAccessible(true);
    show.invoke(activity, "Map");
    updates.resume();
    return updates;
  }

  private void tapUpdate(MainActivity activity) throws Exception {
    ((android.view.View) field(activity, "updateButton")).performClick();
  }

  @Test
  public void oneTapChecksDownloadsVerifiesAndLaunchesInstallerWithoutAnAppPopup()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<String> verified = new ArrayList<>();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
      AppUpdates updates =
          updater(
              activity,
              client(requests, new Response(json(metadata())), new Response(apkBytes)),
              (file, manifest, stop) -> {
                assertArrayEquals(apkBytes, Files.readAllBytes(file.toPath()));
                verified.add(manifest.versionName);
              });
      android.app.AlertDialog previous =
          org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      tapUpdate(activity);
      tapUpdate(activity); // A second tap cannot duplicate the transfer.
      drain(updates);
      assertEquals(2, requests.size());
      assertEquals(Collections.singletonList("0.6.0"), verified);
      assertTrue(UpdateFiles.ready(activity));
      Intent install = Shadows.shadowOf(activity).getNextStartedActivity();
      assertNotNull(install);
      assertEquals(Intent.ACTION_VIEW, install.getAction());
      assertEquals("application/vnd.android.package-archive", install.getType());
      assertSame(previous, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
      assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
  }

  @Test
  public void permissionGoesStraightToAndroidSettingsAndInstallationContinuesOnReturn()
      throws Exception {
    List<String> requests = new ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger verified =
        new java.util.concurrent.atomic.AtomicInteger();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
      AppUpdates updates =
          updater(
              activity,
              client(requests, new Response(json(metadata())), new Response(apkBytes)),
              (file, manifest, stop) -> verified.incrementAndGet());
      tapUpdate(activity);
      drain(updates);
      assertEquals(
          android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Shadows.shadowOf(activity).getNextStartedActivity().getAction());
      updates.pause();
      Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
      updates.resume();
      drain(updates);
      assertEquals(
          Intent.ACTION_VIEW, Shadows.shadowOf(activity).getNextStartedActivity().getAction());
      assertEquals(2, requests.size());
      assertEquals(2, verified.get()); // Retained APK is reverified after leaving the app.
    }
  }

  @Test
  public void anUpToDateOrUnsupportedReleaseNeverDownloadsOrInstalls() throws Exception {
    for (JSONObject meta :
        Arrays.asList(
            metadata()
                .put("versionCode", 5)
                .put("versionName", "0.5.0")
                .put("apkName", "Cairn-0.5.0.apk"),
            metadata().put("minSdk", 35))) {
      List<String> requests = new ArrayList<>();
      try (ActivityController<MainActivity> controller =
          Robolectric.buildActivity(MainActivity.class).setup()) {
        MainActivity activity = controller.get();
        AppUpdates updates =
            updater(
                activity,
                client(requests, new Response(json(meta))),
                (file, manifest, stop) -> fail("No APK should reach verification"));
        tapUpdate(activity);
        drain(updates);
        assertEquals(1, requests.size());
        assertFalse(UpdateFiles.ready(activity));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
      }
    }
  }

  @Test
  public void failedVerificationCannotPublishOrInstallAnApk() throws Exception {
    List<String> requests = new ArrayList<>();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      AppUpdates updates =
          updater(
              activity,
              client(requests, new Response(json(metadata())), new Response(apkBytes)),
              (file, manifest, stop) -> {
                throw new IOException("Signing key mismatch");
              });
      tapUpdate(activity);
      drain(updates);
      assertFalse(UpdateFiles.ready(activity));
      assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
      assertTrue(
          ((android.widget.TextView) field(activity, "updateStatus"))
              .getText()
              .toString()
              .contains("Signing key mismatch"));
    }
  }

  @Test
  public void leavingTheAppCancelsTheUpdateAndDoesNotRestartOnResume() throws Exception {
    java.util.concurrent.CountDownLatch verifying = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    List<String> requests = new ArrayList<>();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      AppUpdates updates =
          updater(
              activity,
              client(requests, new Response(json(metadata())), new Response(apkBytes)),
              (file, manifest, stop) -> {
                verifying.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              });
      tapUpdate(activity);
      assertTrue(verifying.await(5, java.util.concurrent.TimeUnit.SECONDS));
      updates.pause();
      release.countDown();
      drain(updates);
      updates.resume();
      drain(updates);
      assertFalse(UpdateFiles.ready(activity));
      assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
      assertEquals(2, requests.size());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void recordingBlocksTheOneTapUpdateBeforeAnyNetworkWork() throws Exception {
    List<String> requests = new ArrayList<>();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      AppUpdates updates =
          updater(
              activity,
              client(requests),
              (file, manifest, stop) -> fail("Recording must not start update verification"));
      TrackingService.running = true;
      tapUpdate(activity);
      drain(updates);
      assertTrue(requests.isEmpty());
      assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
      assertTrue(
          ((android.widget.TextView) field(activity, "updateStatus"))
              .getText()
              .toString()
              .contains("Finish your hike"));
    } finally {
      TrackingService.running = false;
    }
  }
}
