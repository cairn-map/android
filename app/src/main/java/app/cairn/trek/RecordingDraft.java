package app.cairn.trek;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** Keep full-resolution recording edits out of Android's size-limited saved-state Bundle. */
final class RecordingDraft {
  private static AtomicFile file(Context context, String id) {
    return new AtomicFile(new File(context.getFilesDir(), "recording-edit-" + id));
  }

  static void write(Context context, String id, List<Point> points) throws IOException {
    AtomicFile file = file(context, id);
    FileOutputStream stream = file.startWrite();
    try {
      DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream));
      output.writeInt(points.size());
      for (Point point : points) {
        output.writeDouble(point.lat);
        output.writeDouble(point.lon);
        output.writeDouble(point.elevation);
        output.writeLong(point.time);
        output.writeFloat(point.accuracy);
        output.writeInt(point.segment);
      }
      output.flush();
      file.finishWrite(stream);
    } catch (IOException | RuntimeException error) {
      file.failWrite(stream);
      throw error;
    }
  }

  static List<Point> read(Context context, String id) throws IOException {
    try (DataInputStream input =
        new DataInputStream(new BufferedInputStream(file(context, id).openRead()))) {
      int count = input.readInt();
      if (count < 0) throw new IOException("Invalid recording draft");
      List<Point> points = new ArrayList<>();
      for (int i = 0; i < count; i++)
        points.add(
            new Point(
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readLong(),
                input.readFloat(),
                input.readInt()));
      return points;
    }
  }

  static void delete(Context context, String id) {
    file(context, id).delete();
  }
}
