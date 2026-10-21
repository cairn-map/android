package app.cairn.trek;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class GraphDownloaderTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private byte[] graph() throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/routing/E5_N45.rd5")) {
      return MapCatalog.bytes(input);
    }
  }

  private interface Response {
    void handle(String request, OutputStream output) throws Exception;
  }

  private static final class Server implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Future<?> job;

    Server(Response... responses) throws IOException {
      socket.setSoTimeout(5000);
      job =
          worker.submit(
              () -> {
                try {
                  for (Response response : responses)
                    try (Socket client = socket.accept()) {
                      client.setSoTimeout(5000);
                      BufferedReader input =
                          new BufferedReader(
                              new InputStreamReader(
                                  client.getInputStream(), StandardCharsets.US_ASCII));
                      StringBuilder request = new StringBuilder();
                      String line;
                      while ((line = input.readLine()) != null && !line.isEmpty())
                        request.append(line).append('\n');
                      response.handle(request.toString(), client.getOutputStream());
                    }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    }

    String url() {
      return "http://127.0.0.1:" + socket.getLocalPort() + "/";
    }

    void finish() throws Exception {
      job.get(6, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
      socket.close();
      worker.shutdownNow();
    }
  }

  private static void response(
      OutputStream output, String status, int length, String extra, byte[] bytes)
      throws IOException {
    output.write(
        ("HTTP/1.1 "
                + status
                + "\r\nContent-Length: "
                + length
                + "\r\nETag: \"revision\"\r\nConnection: close\r\n"
                + extra
                + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    output.write(bytes);
    output.flush();
  }

  private void download(GraphDownloader client, File directory) throws IOException {
    client.download(directory, "E5_N45.rd5", () -> false, text -> {}, bytes -> {});
  }

  @Test
  public void interruptedTransferResumesWithRangeAndOnlyPublishesTheCompleteValidatedGraph()
      throws Exception {
    byte[] bytes = graph();
    File dir = temporary.newFolder();
    try (Server server =
        new Server(
            (request, out) -> response(out, "200 OK", bytes.length, "", Arrays.copyOf(bytes, 3000)),
            (request, out) -> {
              assertTrue(request.contains("Range: bytes=3000-"));
              assertTrue(request.contains("If-Range: \"revision\""));
              response(
                  out,
                  "206 Partial Content",
                  bytes.length - 3000,
                  "Content-Range: bytes 3000-" + (bytes.length - 1) + "/" + bytes.length + "\r\n",
                  Arrays.copyOfRange(bytes, 3000, bytes.length));
            })) {
      GraphDownloader client = new GraphDownloader(server.url());
      assertThrows(IOException.class, () -> download(client, dir));
      assertFalse(new File(dir, "E5_N45.rd5").exists());
      download(client, dir);
      server.finish();
      assertArrayEquals(bytes, Files.readAllBytes(new File(dir, "E5_N45.rd5").toPath()));
      assertFalse(new File(dir, "E5_N45.rd5.resume").exists());
    }
  }

  @Test
  public void aChangedRegionReturning200RestartsInsteadOfAppendingToOldBytes() throws Exception {
    byte[] original = graph(), updated = graph();
    updated[updated.length - 110] ^=
        1; // Creation-time metadata, outside the indexed graph content.
    File dir = temporary.newFolder();
    try (Server server =
        new Server(
            (request, out) ->
                response(out, "200 OK", original.length, "", Arrays.copyOf(original, 1000)),
            (request, out) -> response(out, "200 OK", updated.length, "", updated))) {
      GraphDownloader client = new GraphDownloader(server.url());
      assertThrows(IOException.class, () -> download(client, dir));
      download(client, dir);
      server.finish();
      assertArrayEquals(updated, Files.readAllBytes(new File(dir, "E5_N45.rd5").toPath()));
    }
  }

  @Test
  public void recoversACompletePartialLeftByProcessDeathUsingTheServerValidator() throws Exception {
    byte[] bytes = graph();
    File dir = temporary.newFolder();
    Files.write(new File(dir, "E5_N45.rd5.part").toPath(), bytes);
    Properties meta = new Properties();
    meta.setProperty("validator", "\"revision\"");
    try (OutputStream out = new FileOutputStream(new File(dir, "E5_N45.rd5.resume"))) {
      meta.store(out, "");
    }
    try (Server server =
        new Server(
            (request, out) ->
                response(
                    out,
                    "416 Range Not Satisfiable",
                    0,
                    "Content-Range: bytes */" + bytes.length + "\r\n",
                    new byte[0]))) {
      download(new GraphDownloader(server.url()), dir);
      server.finish();
      assertArrayEquals(bytes, Files.readAllBytes(new File(dir, "E5_N45.rd5").toPath()));
    }
  }

  @Test
  public void cancellationNeverPublishesARegionAsReady() throws Exception {
    byte[] bytes = graph();
    File dir = temporary.newFolder();
    AtomicBoolean stop = new AtomicBoolean();
    try (Server server =
        new Server((request, out) -> response(out, "200 OK", bytes.length, "", bytes))) {
      GraphDownloader client = new GraphDownloader(server.url());
      assertThrows(
          IOException.class,
          () -> client.download(dir, "E5_N45.rd5", stop::get, text -> stop.set(true), space -> {}));
      server.finish();
      assertFalse(new File(dir, "E5_N45.rd5").isFile());
    }
  }
}
