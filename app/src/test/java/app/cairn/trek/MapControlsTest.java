package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class MapControlsTest {
  @Before
  public void reset() {
    TrackingService.running = false;
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
  }

  private Object field(MainActivity activity, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void drain(MainActivity activity) throws Exception {
    for (int i = 0; i < 3; i++) {
      ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private View find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void onlineToggleChangesItsFillAndIconContrastAndRejectedToggleStaysOff()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ImageButton preview = (ImageButton) field(activity, "previewButton");
      Drawable offlineBackground = preview.getBackground();
      assertEquals(AppPalette.SURFACE, fill(preview));
      assertFalse(preview.isSelected());
      preview.performClick();
      assertTrue(preview.isSelected());
      assertEquals(AppPalette.ACCENT, fill(preview));
      assertEquals(AppPalette.BACKGROUND, preview.getImageTintList().getDefaultColor());
      assertNotSame(offlineBackground, preview.getBackground());
      preview.performClick();
      assertFalse(preview.isSelected());
      assertEquals(AppPalette.SURFACE, fill(preview));
      assertEquals(AppPalette.ACCENT, preview.getImageTintList().getDefaultColor());
      TrackingService.running = true;
      preview.performClick();
      assertFalse(preview.isSelected());
    } finally {
      TrackingService.running = false;
    }
  }

  private int fill(View button) {
    return ((GradientDrawable) ((RippleDrawable) button.getBackground()).getDrawable(0))
        .getColor()
        .getDefaultColor();
  }

  @Test
  public void pathButtonOpensBothLibrariesAndClosingPreservesTheMapCamera() throws Exception {
    String hike;
    try (Store store = new Store(RuntimeEnvironment.getApplication())) {
      store.saveRoute(Arrays.asList(new Point(45, 6), new Point(45.1, 6.1)), "Weekend route");
      hike = store.newTrek();
      store.add(hike, new Point(46, 7));
    }
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.restoreCamera(new double[] {48, 2, 12.4});
      double[] camera = map.camera();
      Object parent = map.getParent();
      ImageButton path = (ImageButton) field(activity, "libraryButton");
      assertNull(find(activity.getWindow().getDecorView(), "Routes"));
      path.performClick();
      AlertDialog library = (AlertDialog) field(activity, "libraryDialog");
      View content = library.getWindow().getDecorView();
      assertTrue(path.isSelected());
      assertNotNull(find(content, "SAVED ROUTES"));
      assertNotNull(find(content, "RECORDED HIKES"));
      assertNotNull(find(content, "Weekend route · selected"));
      String title = ((Store) field(activity, "store")).treks().get(hike);
      assertNotNull(find(content, title + "  ↗"));
      assertSame(parent, map.getParent());
      library.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertFalse(path.isSelected());
      assertArrayEquals(camera, map.camera(), 1e-10);
      path.performClick();
      library = (AlertDialog) field(activity, "libraryDialog");
      library.cancel();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals("Map", field(activity, "currentPage"));
      assertFalse(path.isSelected());
      assertArrayEquals(camera, map.camera(), 1e-10);
    }
  }
}
