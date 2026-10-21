package app.cairn.trek;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Rejects a download unless it is a newer, complete APK signed by the installed app's signer. */
final class ApkVerifier {
  static long version(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  static long installedVersion(Context context) throws IOException {
    try { return version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)); }
    catch (PackageManager.NameNotFoundException error) { throw new IOException(error); }
  }

  static void verify(Context context, File file, UpdateManifest update, BooleanSupplier stop)
      throws IOException {
    if (file.length() != update.size) throw new IOException("Downloaded APK has the wrong size");
    MessageDigest digest = ReleaseClient.sha256();
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (stop.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Update canceled");
        digest.update(buffer, 0, n);
      }
    }
    if (!ReleaseClient.hex(digest.digest()).equals(update.sha256))
      throw new IOException("Downloaded APK checksum is incorrect");
    PackageManager manager = context.getPackageManager();
    int flags = Build.VERSION.SDK_INT >= 28
        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
    try {
      validate(manager.getPackageInfo(context.getPackageName(), flags), candidate, update);
    } catch (PackageManager.NameNotFoundException error) { throw new IOException(error); }
  }

  static void validate(PackageInfo installed, PackageInfo candidate, UpdateManifest update)
      throws IOException {
    if (candidate == null || candidate.applicationInfo == null)
      throw new IOException("Downloaded file is not a readable APK");
    if (!installed.packageName.equals(candidate.packageName)
        || !update.applicationId.equals(candidate.packageName))
      throw new IOException("Downloaded APK belongs to another app");
    if (version(candidate) != update.versionCode || !update.versionName.equals(candidate.versionName))
      throw new IOException("Downloaded APK does not match the announced version");
    if (version(candidate) <= version(installed))
      throw new IOException("This version is already installed or older");
    if (candidate.applicationInfo.minSdkVersion != update.minSdk || update.minSdk > Build.VERSION.SDK_INT)
      throw new IOException("This update requires a newer Android version");
    Set<String> trusted = signers(installed), offered = signers(candidate);
    if (trusted.isEmpty() || !trusted.equals(offered))
      throw new IOException("Update signing key does not match this installation. The APK was not installed.");
  }

  private static Set<String> signers(PackageInfo info) {
    Signature[] signatures = Build.VERSION.SDK_INT >= 28
        ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
        : info.signatures;
    Set<String> result = new HashSet<>();
    if (signatures != null)
      for (Signature signer : signatures)
        result.add(ReleaseClient.hex(ReleaseClient.sha256().digest(signer.toByteArray())));
    return result;
  }

  private ApkVerifier() {}
}
