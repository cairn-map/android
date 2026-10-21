package app.cairn.trek;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import btools.router.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Direct in-process BRouter core. No companion, binding, server, or background idle work. */
public final class EmbeddedRouter implements AutoCloseable {
  interface Callback {
    void done(List<Point> route, String error);
  }

  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile RoutingEngine engine;
  private volatile boolean closed, busy, canceled;

  EmbeddedRouter(Context context) {
    this.context = context.getApplicationContext();
  }

  void calculate(List<Point> points, Callback callback) {
    if (closed || busy) {
      callback.done(null, "A route calculation is already running");
      return;
    }
    busy = true;
    canceled = false;
    worker.execute(
        () -> {
          List<Point> result = null;
          String error = null;
          try {
            if (canceled || closed) throw new IOException("Calculation canceled");
            RoutingData.prepareProfiles(context);
            List<String> missing = RoutingData.missing(context, points);
            if (!missing.isEmpty())
              throw new IOException(
                  "Download routing data in Routes → Routing data first: "
                      + String.join(", ", missing));
            int memory = context.getSystemService(ActivityManager.class).getMemoryClass();
            result =
                run(
                    RoutingData.directory(context),
                    new File(RoutingData.profiles(context), "hiking.brf"),
                    points,
                    Math.max(16, Math.min(96, memory / 3)));
          } catch (Exception | OutOfMemoryError e) {
            error =
                e instanceof OutOfMemoryError
                    ? "Not enough memory for this route. Try a shorter section."
                    : e.getMessage();
          } finally {
            engine = null;
            ProfileCache.setSize(0); // Release parsed profiles after explicit calculations.
            busy = false;
          }
          List<Point> value = result;
          String message = error;
          main.post(
              () -> {
                if (!closed)
                  callback.done(
                      canceled ? null : value, canceled ? "Calculation canceled" : message);
              });
        });
  }

  List<Point> run(File segments, File profile, List<Point> points, int memory) throws IOException {
    if (points.size() < 2) throw new IOException("Choose at least two route points");
    List<OsmNodeNamed> nodes = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) {
      Point point = points.get(i);
      OsmNodeNamed node = new OsmNodeNamed();
      node.ilon = (int) Math.round((point.lon + 180) * 1000000);
      node.ilat = (int) Math.round((point.lat + 90) * 1000000);
      node.name = i == 0 ? "from" : i == points.size() - 1 ? "to" : "via" + i;
      nodes.add(node);
    }
    RoutingContext config = new RoutingContext();
    config.localFunction = profile.getAbsolutePath();
    config.memoryclass = memory;
    config.turnInstructionMode = 0;
    ProfileCache.setSize(1);
    RoutingEngine current = new RoutingEngine(null, null, segments, nodes, config);
    engine = current;
    if (canceled || closed) {
      current.terminate();
      ProfileCache.releaseProfile(config);
      throw new IOException("Calculation canceled");
    }
    current.quite = true;
    current.doRun(60000);
    if (canceled || closed) throw new IOException("Calculation canceled");
    if (current.getErrorMessage() != null) throw new IOException(current.getErrorMessage());
    OsmTrack track = current.getFoundTrack();
    if (track == null || track.nodes.size() < 2) throw new IOException("No walkable route found");
    if (track.nodes.size() > 100000)
      throw new IOException("Route is too large. Split it into sections.");
    List<Point> result = new ArrayList<>(track.nodes.size());
    for (OsmPathElement node : track.nodes)
      result.add(
          new Point(
              node.getILat() / 1000000.0 - 90,
              node.getILon() / 1000000.0 - 180,
              node.getSElev() == Short.MIN_VALUE ? Double.NaN : node.getElev(),
              0,
              0,
              0));
    return result;
  }

  void cancel() {
    canceled = true;
    RoutingEngine current = engine;
    if (current != null) current.terminate();
  }

  @Override
  public void close() {
    closed = true;
    cancel();
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
  }
}
