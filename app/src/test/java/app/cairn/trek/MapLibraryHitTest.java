package app.cairn.trek;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapLibraryHitTest {
  @Test
  public void tapsRespectHiddenPathsSegmentBreaksAndNearbySinglePoints() {
    TrekMapView map = new TrekMapView(RuntimeEnvironment.getApplication());
    try {
      map.layout(0, 0, 600, 1000);
      map.restoreCamera(new double[] {45, 6, 12});
      Store.LibraryEntry hidden = new Store.LibraryEntry("hidden", "Hidden", false, false);
      Store.LibraryEntry gaps = new Store.LibraryEntry("gaps", "Gaps", true, true);
      List<Point> segments =
          List.of(new Point(45, 5.9, 0, 0, 0, 0), new Point(45, 6.1, 0, 0, 0, 1));
      map.libraryPaths =
          List.of(
              new TrekMapView.LibraryPath(hidden, List.of(new Point(45, 5.9), new Point(45, 6.1))),
              new TrekMapView.LibraryPath(gaps, segments));
      assertNull("No line across a segment break or hidden route", map.libraryPathAt(300, 500));
      Store.LibraryEntry point = new Store.LibraryEntry("point", "Point", true, true);
      map.libraryPaths = List.of(new TrekMapView.LibraryPath(point, List.of(new Point(45, 6))));
      assertEquals("point", map.libraryPathAt(310, 500));
      assertNull(map.libraryPathAt(400, 500));
    } finally {
      map.dispose();
    }
  }
}
