package app.cairn.trek;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class IgnClientTest {
  static byte[] png() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(header);
    data.writeInt(256);
    data.writeInt(256);
    data.write(new byte[] {8, 2, 0, 0, 0});
    chunk(out, "IHDR", header.toByteArray());
    ByteArrayOutputStream pixels = new ByteArrayOutputStream();
    try (java.util.zip.DeflaterOutputStream compressed =
        new java.util.zip.DeflaterOutputStream(pixels)) {
      compressed.write(new byte[256 * (1 + 256 * 3)]);
    }
    chunk(out, "IDAT", pixels.toByteArray());
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  private static void chunk(OutputStream out, String type, byte[] bytes) throws IOException {
    DataOutputStream data = new DataOutputStream(out);
    byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    data.writeInt(bytes.length);
    data.write(tag);
    data.write(bytes);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(tag);
    crc.update(bytes);
    data.writeInt((int) crc.getValue());
  }

  @Test
  public void completeRasterAccepted() throws Exception {
    IgnClient.validatePng(png());
  }

  @Test
  public void truncatedAndCorruptTilesCannotCountAsDownloaded() throws Exception {
    byte[] bytes = png();
    assertThrows(
        IOException.class,
        () -> IgnClient.validatePng(java.util.Arrays.copyOf(bytes, bytes.length - 6)));
    bytes[40] ^= 1;
    assertThrows(IOException.class, () -> IgnClient.validatePng(bytes));
  }

  @Test
  public void nonMapResponsesRejected() {
    assertThrows(
        IOException.class,
        () -> IgnClient.validatePng("<ExceptionReport>rate limit</ExceptionReport>".getBytes()));
  }
}
