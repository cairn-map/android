package app.cairn.trek;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class FileOpeningTest {
  private final Uri uri = Uri.parse("content://test.provider/documents/opaque-id");

  private void register(String xml) {
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
        .registerInputStream(uri, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private void drain(MainActivity activity) throws Exception {
    Field field = MainActivity.class.getDeclaredField("io");
    field.setAccessible(true);
    ((ExecutorService) field.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private TrekMapView map(View view) {
    if (view instanceof TrekMapView) return (TrekMapView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TrekMapView found = map(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void viewGpxImportsFromAnOpaqueContentUriAndRetainsTheRecording() throws Exception {
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      // Importing a route must not suppress the recording loaded on activity resume.
      store.prefs.edit().putString("currentTrek", "test-hike").commit();
      store.add("test-hike", new Point(45, 6, Double.NaN, 1000, 5, 0));
    }
    register(
        "<gpx><trk><trkseg><trkpt lat='48.1' lon='2.1'/><trkpt lat='48.2'"
            + " lon='2.2'/></trkseg></trk></gpx>");
    Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml");
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class, intent).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = map(activity.getWindow().getDecorView());
      assertNotNull(map);
      assertEquals(2, map.route.size());
      map.layout(0, 0, 786, 1400);
      double[] bounds = map.bounds();
      for (Point point : map.route) {
        assertTrue(point.lon > bounds[0] && point.lon < bounds[2]);
        assertTrue(point.lat > bounds[1] && point.lat < bounds[3]);
      }
      assertEquals(1, map.track.size());
    }
  }

  @Test
  public void shareXmlWhileAlreadyOpenSelectsPlanIgnWithoutTurningOnNetwork() throws Exception {
    register(
        "<onlinemapsources><onlinemapsource><url><![CDATA[https://data.geopf.fr/wmts?LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2]]></url></onlinemapsource></onlinemapsources>");
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      Intent intent =
          new Intent(Intent.ACTION_SEND)
              .setType("application/xml")
              .putExtra(Intent.EXTRA_STREAM, uri);
      controller.newIntent(intent);
      drain(controller.get());
      TrekMapView map = map(controller.get().getWindow().getDecorView());
      assertTrue(map.tileSource() instanceof IgnMap);
      assertFalse(((IgnMap) map.tileSource()).online());
      java.lang.reflect.Field feedback = MainActivity.class.getDeclaredField("updateStatus");
      feedback.setAccessible(true);
      assertTrue(
          ((android.widget.TextView) feedback.get(controller.get()))
              .getText()
              .toString()
              .contains("Plan IGN is ready"));
    }
  }

  @Test
  public void manifestOffersCairnForTypedGpxAndXmlContent() {
    Context context = RuntimeEnvironment.getApplication();
    for (String type : new String[] {"application/gpx+xml", "application/xml", "text/xml"}) {
      Intent intent =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, type)
              .addCategory(Intent.CATEGORY_DEFAULT);
      assertTrue(
          context.getPackageManager().queryIntentActivities(intent, 0).stream()
              .anyMatch(info -> "app.cairn.trek".equals(info.activityInfo.packageName)));
    }
  }

  @Test
  public void genericBinaryAssociationIsLimitedToMapXmlAndGpxFilenames() {
    Context context = RuntimeEnvironment.getApplication();
    for (String name : new String[] {"route.gpx", "sources.xml", "unrelated.pdf"}) {
      Intent intent =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(
                  Uri.parse("content://test.provider/files/" + name), "application/octet-stream")
              .addCategory(Intent.CATEGORY_DEFAULT);
      boolean offered =
          context.getPackageManager().queryIntentActivities(intent, 0).stream()
              .anyMatch(info -> "app.cairn.trek".equals(info.activityInfo.packageName));
      assertEquals(!name.endsWith("pdf"), offered);
    }
  }

  @Test
  public void sharingMultipleGpxFilesKeepsBothAndDisplaysThemTogether() throws Exception {
    Uri second = Uri.parse("content://test.provider/documents/second-id");
    register("<gpx><rte><rtept lat='45' lon='6'/><rtept lat='45.1' lon='6.1'/></rte></gpx>");
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
        .registerInputStream(
            second,
            new ByteArrayInputStream(
                "<gpx><rte><rtept lat='46' lon='7'/><rtept lat='46.1' lon='7.1'/></rte></gpx>"
                    .getBytes(StandardCharsets.UTF_8)));
    java.util.ArrayList<Uri> files =
        new java.util.ArrayList<>(java.util.Arrays.asList(uri, second));
    Intent intent =
        new Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/gpx+xml")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class, intent).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      drain(activity);
      try (Store store = new Store(activity)) {
        assertEquals(2, store.routes().size());
      }
      TrekMapView map = map(activity.getWindow().getDecorView());
      assertEquals(2, map.route.size());
      assertEquals(1, map.otherRoutes.size());
      assertEquals(46, map.route.get(0).lat, 0);
      assertEquals(45, map.otherRoutes.get(0).get(0).lat, 0);
    }
  }
}
