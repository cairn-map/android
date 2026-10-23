package app.cairn.trek;

import android.content.Context;
import btools.util.Crc32;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class RoutingData {
  static File directory(Context context) {
    File file = new File(context.getFilesDir(), "routing/segments4");
    file.mkdirs();
    return file;
  }

  /** Remove only geographic graphs and their partial-transfer files; keep the built-in profile. */
  static void clear(Context context) throws IOException {
    File[] files = directory(context).listFiles();
    if (files == null) throw new IOException("Cannot read routing data directory");
    boolean failed = false;
    for (File file : files) {
      String region = file.getName().split("\\.rd5", 2)[0] + ".rd5";
      if (validName(region)
          && file.getName()
              .matches("[EW][0-9]+_[NS][0-9]+\\.rd5(\\.(part|resume|resume\\.tmp|import))?")) {
        if (!file.delete() && file.exists()) failed = true;
      }
    }
    if (failed) throw new IOException("Some files could not be removed");
  }

  static File profiles(Context context) {
    return new File(context.getFilesDir(), "routing/profiles-1.7.9");
  }

  static synchronized void prepareProfiles(Context context) throws IOException {
    File directory = profiles(context);
    directory.mkdirs();
    for (String name : new String[] {"hiking.brf", "lookups.dat"}) {
      File target = new File(directory, name);
      if (target.isFile() && target.length() > 0) continue;
      File part = new File(directory, name + ".part");
      try (InputStream input = context.getAssets().open("routing/" + name)) {
        Files.copy(input, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String region(double latitude, double longitude) {
    int x = (int) Math.floor(Geo.wrap(longitude) / 5) * 5;
    int y = Math.min(85, (int) Math.floor(latitude / 5) * 5);
    return (x >= 0 ? "E" : "W") + Math.abs(x) + "_" + (y >= 0 ? "N" : "S") + Math.abs(y) + ".rd5";
  }

  static boolean validName(String name) {
    if (name == null || !name.matches("[EW][0-9]{1,3}_[NS][0-9]{1,2}\\.rd5")) return false;
    String[] parts = name.substring(0, name.length() - 4).split("_");
    int x = Integer.parseInt(parts[0].substring(1)) * (parts[0].charAt(0) == 'W' ? -1 : 1);
    int y = Integer.parseInt(parts[1].substring(1)) * (parts[1].charAt(0) == 'S' ? -1 : 1);
    return x >= -180
        && x < 180
        && y >= -90
        && y <= 85
        && x % 5 == 0
        && y % 5 == 0
        && region(y + 0.1, x + 0.1).equals(name);
  }

  static Set<String> required(List<Point> points) {
    Set<String> result = new LinkedHashSet<>();
    Point previous = null;
    for (Point point : points) {
      int steps =
          previous == null || previous.segment != point.segment
              ? 0
              : (int) Math.ceil(Geo.distance(previous, point) / 20000);
      if (steps > 4096)
        throw new IllegalArgumentException("Split this route into shorter sections");
      for (int i = 0; i <= steps; i++) {
        double t = steps == 0 ? 1 : (double) i / steps;
        double lat = previous == null ? point.lat : previous.lat + (point.lat - previous.lat) * t;
        double lon =
            previous == null ? point.lon : previous.lon + Geo.wrap(point.lon - previous.lon) * t;
        // Include a small border margin so graph connections near segment edges work offline.
        for (double dy : new double[] {-0.05, 0.05})
          for (double dx : new double[] {-0.05, 0.05})
            result.add(region(Math.max(-89.9, Math.min(89.9, lat + dy)), lon + dx));
        if (result.size() > 24)
          throw new IllegalArgumentException("Too many routing regions. Split the route.");
      }
      previous = point;
    }
    return result;
  }

  static List<String> missing(Context context, List<Point> points) {
    List<String> missing = new ArrayList<>();
    for (String name : required(points))
      if (!new File(directory(context), name).isFile()) missing.add(name);
    return missing;
  }

  static void validate(File file) throws IOException {
    // Match upstream PhysicalFile's index checks. Block checksums are verified when routed through.
    if (file.length() < 200 || file.length() > 1500L * 1024 * 1024)
      throw new IOException("Invalid routing data size");
    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
      byte[] header = new byte[200];
      input.readFully(header);
      input.seek(0);
      long last = 200;
      for (int i = 0; i < 25; i++) {
        long value = input.readLong();
        if (i == 0 && (value >>> 48) != 11)
          throw new IOException("Routing data version does not match this engine");
        long end = value & 0xffffffffffffL;
        if (end < last || end > file.length()) throw new IOException("Invalid routing index");
        last = end;
      }
      if (file.length() == last) return;
      if (file.length() < last + 112) throw new IOException("Incomplete routing data");
      input.seek(last + 8);
      int crc = input.readInt(), expected = Crc32.crc(header, 0, 200);
      if (crc != expected && (crc ^ 2) != expected)
        throw new IOException("Routing index checksum failed");
    }
  }
}
