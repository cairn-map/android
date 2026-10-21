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
public class AppUpdateTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final byte[] apkBytes = "complete signed APK fixture".getBytes(StandardCharsets.UTF_8);

  @Before public void reset() {
    TrackingService.running = false;
    UpdateFiles.clear(RuntimeEnvironment.getApplication());
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
    int code = 200;
    long length = -1;
    boolean disconnected;
    Response(byte[] body) throws Exception { super(new URL("https://api.github.com/")); this.body = body; }
    @Override public int getResponseCode() { return code; }
    @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
    @Override public long getContentLengthLong() { return length; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    @Override public void disconnect() { disconnected = true; }
    @Override public void connect() {}
    @Override public boolean usingProxy() { return false; }
  }

  private ReleaseClient client(List<String> requests, Response... responses) {
    Deque<Response> queue = new ArrayDeque<>(Arrays.asList(responses));
    return new ReleaseClient("dayblox/cairn", url -> {
      requests.add(url.toString());
      if (queue.isEmpty()) throw new IOException("Unexpected request");
      return queue.remove();
    });
  }

  @Test public void checkingReadsOnlyMetadataAndDownloadRequiresASeparateCall() throws Exception {
    List<String> requests = new ArrayList<>();
    Response info = new Response(json(release())), meta = new Response(json(metadata())), apk = new Response(apkBytes);
    ReleaseClient client = client(requests, info, meta, apk);
    assertTrue(requests.isEmpty());
    ReleaseClient.Release result = client.latest(() -> false);
    assertEquals(2, requests.size());
    assertEquals(6, result.manifest.versionCode);
    assertTrue(requests.get(0).endsWith("/releases/latest"));
    assertTrue(requests.get(1).endsWith("/releases/assets/1"));
    File target = new File(temporary.getRoot(), "download.part");
    client.download(result, target, () -> false, n -> {});
    assertArrayEquals(apkBytes, Files.readAllBytes(target.toPath()));
    assertEquals(3, requests.size());
    assertTrue(info.disconnected && meta.disconnected && apk.disconnected);
    assertNull(info.getRequestProperty("Authorization"));
  }

  @Test public void followsGitHubAssetRedirectsWithoutCredentials() throws Exception {
    List<String> requests = new ArrayList<>();
    Response redirect = new Response(new byte[0]);
    redirect.code = 302;
    redirect.location = "https://release-assets.githubusercontent.com/github-production-release-asset/manifest";
    Response meta = new Response(json(metadata()));
    ReleaseClient client = client(requests, new Response(json(release())), redirect, meta);
    client.latest(() -> false);
    assertEquals(3, requests.size());
    assertTrue(redirect.disconnected);
    assertNull(meta.getRequestProperty("Authorization"));
    assertFalse(meta.getInstanceFollowRedirects());
  }

  @Test public void rejectsHttpAndUnrelatedRedirectHostsBeforeOpeningThem() throws Exception {
    for (String address : Arrays.asList("http://release-assets.githubusercontent.com/file",
        "https://evil.example/update", "https://api.github.com/repos/other/app/releases/assets/1",
        "https://release-assets.githubusercontent.com:8443/file")) {
      Response redirect = new Response(new byte[0]);
      redirect.code = 302; redirect.location = address;
      List<String> requests = new ArrayList<>();
      ReleaseClient client = client(requests, new Response(json(release())), redirect);
      assertThrows(IOException.class, () -> client.latest(() -> false));
      assertEquals(2, requests.size());
    }
  }

  @Test public void rejectsTruncationCorruptionAndOversizedTransfersWithoutKeepingPartialApks() throws Exception {
    for (byte[] data : Arrays.asList(Arrays.copyOf(apkBytes, 3), new byte[apkBytes.length],
        Arrays.copyOf(apkBytes, apkBytes.length + 1))) {
      ReleaseClient client = client(new ArrayList<>(), new Response(data));
      File file = new File(temporary.getRoot(), "partial.apk");
      ReleaseClient.Release release = new ReleaseClient.Release(manifest(), 2, "");
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
    ReleaseClient.Release release = new ReleaseClient.Release(manifest(), 2, "");
    assertThrows(InterruptedIOException.class,
        () -> client.download(release, target, stop::get, percent -> { stop.set(true); client.cancel(); }));
    assertFalse(target.exists());
    assertTrue(apk.disconnected);
    assertThrows(InterruptedIOException.class, () -> client.latest(() -> false));
    assertEquals(1, requests.size());
  }

  @Test public void rejectsWrongReleaseMetadataAndMissingAssets() throws Exception {
    for (JSONObject value : Arrays.asList(
        release().put("prerelease", true), release().put("tag_name", "v1.0.0"),
        release().put("assets", new JSONArray()))) {
      ReleaseClient client = client(new ArrayList<>(), new Response(json(value)), new Response(json(metadata())));
      assertThrows(IOException.class, () -> client.latest(() -> false));
    }
    assertThrows(IOException.class,
        () -> new UpdateManifest(json(metadata().put("applicationId", "another.app"))));
    assertThrows(IOException.class,
        () -> new UpdateManifest(json(metadata().put("size", UpdateManifest.MAX_APK_BYTES + 1))));
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
    Intent intent = UpdateActivity.installIntent(context);
    assertEquals(Intent.ACTION_VIEW, intent.getAction());
    assertEquals("content", intent.getData().getScheme());
    assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
    assertNotNull(intent.getClipData());
  }

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk = 28, qualifiers = "w393dp-h851dp-xhdpi")
  public void openingResumingAndLeavingUpdateScreenDoesNotStartAnyUpdateWork() throws Exception {
    try (ActivityController<UpdateActivity> controller = Robolectric.buildActivity(UpdateActivity.class).setup()) {
      UpdateActivity activity = controller.get();
      java.lang.reflect.Field client = UpdateActivity.class.getDeclaredField("client");
      client.setAccessible(true);
      java.lang.reflect.Field transfer = UpdateActivity.class.getDeclaredField("transfer");
      transfer.setAccessible(true);
      assertNull(client.get(activity));
      assertNull(transfer.get(activity));
      android.view.View root = activity.findViewById(android.R.id.content);
      root.measure(android.view.View.MeasureSpec.makeMeasureSpec(786, android.view.View.MeasureSpec.EXACTLY),
          android.view.View.MeasureSpec.makeMeasureSpec(1550, android.view.View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 786, 1550);
      android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(786, 1550, android.graphics.Bitmap.Config.ARGB_8888);
      root.draw(new android.graphics.Canvas(bitmap));
      File preview = new File("build/test-previews/app-updates.png");
      preview.getParentFile().mkdirs();
      try (OutputStream output = new FileOutputStream(preview)) {
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      }
      TrackingService.running = true;
      java.lang.reflect.Field check = UpdateActivity.class.getDeclaredField("checkButton");
      check.setAccessible(true);
      ((android.widget.Button) check.get(activity)).performClick();
      assertNull(client.get(activity));
      assertNull(transfer.get(activity));
      TrackingService.running = false;
      controller.pause().stop().start().resume();
      assertNull(client.get(activity));
      assertNull(transfer.get(activity));
    }
  }
}
