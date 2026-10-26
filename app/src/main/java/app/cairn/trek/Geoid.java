package app.cairn.trek;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Offline EGM96 separation in metres: sea-level height = ellipsoid height - separation. */
final class Geoid {
  private static final int ROWS = 721, COLUMNS = 1440;

  private Geoid() {}

  // Lazy loading is never requested by the recorder. Missing data must not masquerade as zero.
  private static final class Model {
    static final short[] GRID = read();

    private static short[] read() {
      InputStream resource = Geoid.class.getResourceAsStream("egm96-15cm.bin.gz");
      if (resource == null) return null;
      try (DataInputStream input =
          new DataInputStream(new BufferedInputStream(new GZIPInputStream(resource)))) {
        if (input.readLong() != 0x45474d3936434d31L
            || input.readUnsignedShort() != ROWS
            || input.readUnsignedShort() != COLUMNS) return null;
        short[] grid = new short[ROWS * COLUMNS];
        for (int i = 0; i < grid.length; i++) grid[i] = input.readShort();
        if (input.read() != -1) return null;
        return grid;
      } catch (IOException error) {
        return null;
      }
    }
  }

  static double separation(double latitude, double longitude) {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || latitude < -90
        || latitude > 90
        || longitude < -180
        || longitude > 180) return Double.NaN;
    short[] grid = Model.GRID;
    if (grid == null) return Double.NaN;
    double row = (latitude + 90) * 4;
    double column = ((longitude + 180) % 360) * 4;
    int south = Math.min(ROWS - 2, (int) row), west = (int) column;
    int east = (west + 1) % COLUMNS;
    double northWeight = row - south, eastWeight = column - west;
    double lower =
        grid[south * COLUMNS + west] * (1 - eastWeight) + grid[south * COLUMNS + east] * eastWeight;
    double upper =
        grid[(south + 1) * COLUMNS + west] * (1 - eastWeight)
            + grid[(south + 1) * COLUMNS + east] * eastWeight;
    return (lower * (1 - northWeight) + upper * northWeight) / 100;
  }
}
