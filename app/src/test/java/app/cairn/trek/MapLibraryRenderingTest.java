package app.cairn.trek;

import static org.junit.Assert.*;

import android.graphics.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapLibraryRenderingTest {
  private Bitmap draw(TrekMapView map) {
    Bitmap bitmap = Bitmap.createBitmap(786, 1200, Bitmap.Config.ARGB_8888);
    map.draw(new Canvas(bitmap));
    return bitmap;
  }

  @Test
  public void startArrowIsVisibleInViewModeAndSuppressedWhileEditingOrHidden() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 786, 1200);
      map.restoreCamera(new double[] {45, 6, 12});
      List<Point> points = List.of(new Point(45, 6), new Point(45, 6.02));
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("route", "Route", false, true), points));
      map.selectedPathId = "route";
      assertEquals(0xff235D4D, draw(map).getPixel(381, 612));
      map.editing = true;
      assertNotEquals(0xff235D4D, draw(map).getPixel(381, 612));
      map.editing = false;
      map.showRouteStarts = false;
      assertNotEquals(0xff235D4D, draw(map).getPixel(381, 612));
      map.showRouteStarts = true;
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("route", "Route", false, false), points));
      assertNotEquals(0xff235D4D, draw(map).getPixel(381, 612));
    } finally {
      map.dispose();
    }
  }

  @Test
  public void duplicateStartPointsDoNotChangeTheArrowDirectionAndAppendsKeepItStable() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 786, 1200);
      map.restoreCamera(new double[] {45, 6, 12});
      Point start = new Point(45, 6), next = new Point(45.02, 6);
      map.route = List.of(start, next);
      Bitmap expected = draw(map);
      map.route = new ArrayList<>(List.of(start, start, start, next));
      // The south-east tail belongs to a north-facing arrow, away from the route stroke.
      assertEquals(0xff235D4D, expected.getPixel(405, 612));
      assertEquals(0xff235D4D, draw(map).getPixel(405, 612));
      map.route.add(new Point(45.03, 6));
      assertEquals(0xff235D4D, draw(map).getPixel(405, 612));
      map.route.clear();
      assertNotEquals(0xff235D4D, draw(map).getPixel(405, 612));
    } finally {
      map.dispose();
    }
  }

  @Test
  public void selectedRouteAndRecordingShareAnOutlineAboveOverlappingPaths() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 786, 1200);
      map.restoreCamera(new double[] {45, 6, 12});
      List<Point> points = List.of(new Point(45, 5.98), new Point(45, 6.02));
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("route", "Route", false, true), points),
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("trek", "Recording", true, true), points));
      for (String id : List.of("route", "trek")) {
        map.selectedPathId = id;
        Bitmap selected = draw(map);
        assertEquals(0xff235D4D, selected.getPixel(393, 600));
        assertEquals(Color.WHITE, selected.getPixel(393, 607));
      }
      map.selectedPathId = "";
      assertEquals(AppPalette.PAST_TRACK, draw(map).getPixel(393, 600));
    } finally {
      map.dispose();
    }
  }

  @Test
  public void liveRecordingStaysBrightAboveSelectedPathsAndStopsHighlightingWhenFinished() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 786, 1200);
      map.restoreCamera(new double[] {45, 6, 12});
      List<Point> points = List.of(new Point(45, 5.98), new Point(45, 6.02));
      map.track = points;
      map.liveTrackId = "live";
      map.recordingActive = true;
      map.libraryPaths = List.of(
          new TrekMapView.LibraryPath(new Store.LibraryEntry("old", "Past hike", true, true), points),
          new TrekMapView.LibraryPath(new Store.LibraryEntry("live", "Current hike", true, true),
              Collections.emptyList()));
      for (String selected : List.of("old", "live", "")) {
        map.selectedPathId = selected;
        Bitmap image = draw(map);
        assertEquals(AppPalette.LIVE_TRACK, image.getPixel(393, 600));
        assertEquals(AppPalette.LIVE_TRACK, image.getPixel(393, 604));
        assertEquals(0xff3B240B, image.getPixel(393, 607));
      }
      map.recordingActive = false;
      assertEquals(AppPalette.PAST_TRACK, draw(map).getPixel(393, 600));
      assertNotEquals(AppPalette.PAST_TRACK, draw(map).getPixel(393, 604));
      map.recordingActive = true;
      map.libraryPaths = List.of(
          new TrekMapView.LibraryPath(new Store.LibraryEntry("live", "Hidden hike", true, false),
              Collections.emptyList()));
      assertNotEquals(AppPalette.LIVE_TRACK, draw(map).getPixel(393, 600));
      // The live list also draws before the asynchronous library has loaded its row.
      map.libraryPaths = Collections.emptyList();
      assertEquals(AppPalette.LIVE_TRACK, draw(map).getPixel(393, 600));
    } finally {
      map.dispose();
    }
  }

  @Test
  public void singlePointRecordingsAreVisibleAndHiddenCurrentRecordingsStayHidden() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 786, 1200);
      map.restoreCamera(new double[] {45, 6, 12});
      map.track = List.of(new Point(45, 6));
      map.liveTrackId = "trek";
      map.selectedPathId = "trek";
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("trek", "Recording", true, true),
                  Collections.emptyList()));
      assertEquals(0xff235D4D, draw(map).getPixel(393, 600));
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(
                  new Store.LibraryEntry("trek", "Recording", true, false),
                  Collections.emptyList()));
      assertNotEquals(0xff235D4D, draw(map).getPixel(393, 600));
      assertNotEquals(AppPalette.PAST_TRACK, draw(map).getPixel(393, 600));
    } finally {
      map.dispose();
    }
  }
}
