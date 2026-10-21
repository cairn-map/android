package app.cairn.trek;

import android.content.Context;
import java.io.*;
import java.nio.file.*;

/** Only a fully verified APK is published to the provider's fixed read-only path. */
final class UpdateFiles {
  static File directory(Context context) { return new File(context.getCacheDir(), "app-updates"); }
  static File apk(Context context) { return new File(directory(context), "update.apk"); }
  static File manifest(Context context) { return new File(directory(context), "update.json"); }
  static boolean ready(Context context) { return apk(context).isFile() && manifest(context).isFile(); }

  /** A completed install leaves an obsolete cache across app upgrades. Never expose it again. */
  static boolean discardInstalled(Context context, long installedVersion) {
    if (!ready(context)) return false;
    try {
      if (read(context).versionCode > installedVersion) return false;
    } catch (IOException invalid) {
      // Broken saved metadata cannot be offered safely.
    }
    clear(context);
    return true;
  }

  static UpdateManifest read(Context context) throws IOException {
    File meta = manifest(context);
    if (meta.length() > 16384) throw new IOException("Invalid saved update");
    return new UpdateManifest(Files.readAllBytes(meta.toPath()));
  }

  static void save(Context context, File part, UpdateManifest update) throws IOException {
    Files.move(part.toPath(), apk(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
    File temporary = new File(directory(context), "update.json.tmp");
    try (FileOutputStream out = new FileOutputStream(temporary)) {
      out.write(update.bytes());
      out.getFD().sync();
    }
    Files.move(temporary.toPath(), manifest(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  static void clear(Context context) {
    File[] files = directory(context).listFiles();
    if (files != null) for (File file : files) file.delete();
  }

  private UpdateFiles() {}
}
