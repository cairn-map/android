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
    if (text.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
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

  private <T extends View> T findType(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        T found = findType(group.getChildAt(i), type);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  @Config(sdk = {26, 35})
  public void gpsSliderAndAlertsApplyImmediatelyAndSurviveDismissal() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Store store = (Store) field(activity, "store");
      store.prefs.edit().putInt("intervalSeconds", 137).apply();
      java.lang.reflect.Method settings = MainActivity.class.getDeclaredMethod("batterySettings");
      settings.setAccessible(true);
      settings.invoke(activity);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View content = dialog.getWindow().getDecorView();
      SeekBar slider = findType(content, SeekBar.class);
      assertNotNull(slider);
      assertNull(findType(content, EditText.class));
      assertEquals(GpsInterval.progress(137), slider.getProgress());
      assertEquals(0, slider.getMin());
      assertEquals(GpsInterval.STEPS, slider.getMax());
      assertEquals(-1, dialog.getWindow().getAttributes().width);
      assertEquals(-2, dialog.getWindow().getAttributes().height);
      slider.setProgress(GpsInterval.STEPS);
      assertNotNull(find(content, activity.getString(R.string.gps_interval, 3600)));
      assertEquals(3600000, store.intervalMillis());
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility());
      dialog.cancel();
      assertEquals(3600000, store.intervalMillis());
      for (int seconds : new int[] {1, 60, 300, 3600}) {
        settings.invoke(activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        slider = findType(dialog.getWindow().getDecorView(), SeekBar.class);
        slider.setProgress(GpsInterval.progress(seconds));
        findType(dialog.getWindow().getDecorView(), Switch.class).setChecked(false);
        dialog.cancel();
        assertFalse(dialog.isShowing());
        assertEquals(seconds * 1000L, store.intervalMillis());
        assertFalse(store.prefs.getBoolean("offRoute", true));
      }
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk = 35, qualifiers = "w393dp-h851dp-xhdpi")
  public void updateFeedbackIsTopCenteredAndGpsSettingsWrapTheirContent() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      TextView feedback = (TextView) field(activity, "updateStatus");
      feedback.setText("You’re up to date · Cairn " + BuildConfig.VERSION_NAME);
      feedback.setVisibility(View.VISIBLE);
      View root = activity.getWindow().getDecorView();
      savePreview(root, 786, 1550, "top-centered-update-feedback");
      View parent = (View) feedback.getParent();
      assertEquals(parent.getWidth(), feedback.getLeft() + feedback.getRight(), 1);
      assertTrue(feedback.getTop() < parent.getHeight() / 4);
      assertEquals(Gravity.CENTER, feedback.getGravity());
      java.lang.reflect.Method settings = MainActivity.class.getDeclaredMethod("batterySettings");
      settings.setAccessible(true);
      settings.invoke(activity);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = dialog.getWindow().getDecorView();
      decor.measure(
          View.MeasureSpec.makeMeasureSpec(722, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1550, View.MeasureSpec.AT_MOST));
      int height = decor.getMeasuredHeight();
      assertTrue("GPS settings must fit their content", height < 1550);
      savePreview(decor, 722, height, "gps-slider-drawer");
      assertTrue(findType(decor, SeekBar.class).getHeight() >= 96);
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
      dialog.dismiss();
    }
  }

  @Test
  @Config(sdk = {28, 35})
  public void locationFeedbackReplacesItsProgressAtTheSamePositionAndClearsOnPause()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      TrackingService.running = true;
      java.lang.reflect.Method locate = MainActivity.class.getDeclaredMethod("locate");
      locate.setAccessible(true);
      locate.invoke(activity);
      TextView banner = (TextView) field(activity, "updateStatus");
      assertEquals("Waiting for your next scheduled GPS fix", banner.getText().toString());
      TrekMapView map = (TrekMapView) field(activity, "map");
      map.position = new Point(45, 6, Double.NaN, System.currentTimeMillis(), 5, 0);
      locate.invoke(activity);
      assertTrue(banner.getText().toString().startsWith("Position ±"));
      assertEquals(
          Gravity.TOP | Gravity.CENTER_HORIZONTAL,
          ((FrameLayout.LayoutParams) banner.getLayoutParams()).gravity);
      controller.pause();
      assertEquals(View.GONE, banner.getVisibility());
    } finally {
      TrackingService.running = false;
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk = 35, qualifiers = "w393dp-h851dp-xhdpi")
  public void searchUsesTheAvailableDrawerHeightWithCreditBelowTheResults() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      PlaceSearch original = (PlaceSearch) field(activity, "placeSearch");
      original.close();
      PlaceSearch search =
          new PlaceSearch(
              activity,
              (query, center) ->
                  PhotonClient.parse(
                      """
                      {"features":[
                        {"properties":{"name":"Mont Thabor","osm_value":"peak","country":"France"},
                         "geometry":{"type":"Point","coordinates":[6.563,45.115]}},
                        {"properties":{"name":"Thabor","city":"Rennes","country":"France"},
                         "geometry":{"type":"Point","coordinates":[-1.66,48.11]}}
                      ]}
                      """));
      Field holder = MainActivity.class.getDeclaredField("placeSearch");
      holder.setAccessible(true);
      holder.set(activity, search);
      find(activity.getWindow().getDecorView(), "Search places").performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      View decor = dialog.getWindow().getDecorView();
      EditText query = (EditText) find(decor, "Place name or address");
      query.setText("Thabor");
      query.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      Field worker = PlaceSearch.class.getDeclaredField("worker");
      worker.setAccessible(true);
      ((ExecutorService) worker.get(search)).submit(() -> {}).get(5, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull(find(decor, "Mont Thabor, Mountain, France"));
      savePreview(decor, 786, 1400, "search-results-drawer");
      ScrollView results =
          (ScrollView) find(decor, "Mont Thabor, Mountain, France").getParent().getParent();
      assertTrue("Search results should fill the available height", results.getHeight() > 400);
      savePreview(decor, 786, 900, "search-results-resized-drawer");
      assertTrue("Results should remain usable above the keyboard", results.getHeight() > 200);
      TextView credit = (TextView) find(decor, "© OpenStreetMap");
      assertTrue(credit.getTop() >= results.getBottom());
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
      dialog.dismiss();
    }
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
      assertEquals(AppPalette.PRIMARY, fill(preview));
      assertNull(preview.getImageTintList());
      assertNotSame(offlineBackground, preview.getBackground());
      preview.performClick();
      assertFalse(preview.isSelected());
      assertEquals(AppPalette.SURFACE, fill(preview));
      assertNull(preview.getImageTintList());
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
  @Config(sdk = {26, 28, 35})
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
      assertNull(find(content, "APP UPDATES"));
      assertNull(find(content, "Routing data"));
      assertNotNull(find(content, "Weekend route · selected"));
      String title = ((Store) field(activity, "store")).treks().get(hike);
      assertNotNull(find(content, title + "  ↗"));
      assertSame(parent, map.getParent());
      library.cancel();
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

  @Test
  public void drawerFooterOpensSourceUpdateAndLicenceLinksWithoutAMapControl() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      assertNull(find(activity.getWindow().getDecorView(), "Map attribution and licences"));
      ((View) field(activity, "libraryButton")).performClick();
      AlertDialog library = (AlertDialog) field(activity, "libraryDialog");
      find(library.getWindow().getDecorView(), "Map sources and licences").performClick();
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      TextView body = dialog.findViewById(android.R.id.message);
      assertTrue(body.getText().toString().contains("https://www.data.gouv.fr/datasets/plan-ign"));
      assertTrue(body.getText().toString().contains("OpenStreetMap contributors"));
      assertTrue(body.getText() instanceof android.text.Spanned);
      assertTrue(
          ((android.text.Spanned) body.getText())
                  .getSpans(0, body.length(), android.text.style.URLSpan.class)
                  .length
              >= 3);
    }
  }

  private void savePreview(View view, int width, int height, String name) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888);
    view.draw(new android.graphics.Canvas(bitmap));
    java.io.File file = new java.io.File("build/test-previews/" + name + ".png");
    file.getParentFile().mkdirs();
    try (java.io.OutputStream output = new java.io.FileOutputStream(file)) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(sdk = 28, qualifiers = "w393dp-h851dp-xhdpi")
  public void rightEdgeControlsKeepVisibleIconsAndTheLibraryOpensAsABottomDrawer()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      View root = activity.getWindow().getDecorView();
      savePreview(root, 786, 1550, "map-right-controls");
      for (String name :
          new String[] {"previewButton", "libraryButton", "updateButton", "intervalChip"}) {
        View button = (View) field(activity, name);
        android.graphics.Rect bounds = new android.graphics.Rect();
        button.getGlobalVisibleRect(bounds);
        assertEquals(name + " must align with the right edge", 762, bounds.right);
        assertTrue(name + " must be reachable", bounds.height() >= 96);
      }
      ImageButton toggle = (ImageButton) field(activity, "previewButton");
      for (int state = 0; state < 2; state++) {
        if (state == 1) toggle.performClick();
        android.graphics.Bitmap bitmap =
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        toggle.layout(0, 0, 96, 96);
        toggle.draw(new android.graphics.Canvas(bitmap));
        int bright = 0;
        for (int y = 24; y < 72; y++)
          for (int x = 24; x < 72; x++)
            if (android.graphics.Color.red(bitmap.getPixel(x, y)) > 140
                && android.graphics.Color.blue(bitmap.getPixel(x, y)) > 170) bright++;
        assertTrue("The icon strokes must remain visible in state " + state, bright > 60);
        savePreview(toggle, 96, 96, state == 0 ? "toggle-off" : "toggle-on");
      }
      toggle.performClick();
      ((View) field(activity, "libraryButton")).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog library = (AlertDialog) field(activity, "libraryDialog");
      assertEquals(
          Gravity.BOTTOM,
          library.getWindow().getAttributes().gravity & Gravity.VERTICAL_GRAVITY_MASK);
      savePreview(library.getWindow().getDecorView(), 786, 1400, "routes-drawer");
    }
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land")
  public void compactPlanningKeepsTheRightControlRailScrollable() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      Field planning = MainActivity.class.getDeclaredField("planning");
      planning.setAccessible(true);
      planning.setBoolean(activity, true);
      java.lang.reflect.Method show =
          MainActivity.class.getDeclaredMethod("showPage", String.class);
      show.setAccessible(true);
      show.invoke(activity, "Map");
      View root = activity.getWindow().getDecorView();
      root.measure(
          View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 640, 320);
      View update = (View) field(activity, "updateButton");
      ScrollView rail = (ScrollView) update.getParent().getParent();
      assertTrue("The rail must retain a usable touch viewport", rail.getHeight() >= 48);
      assertTrue(
          "The rail must scroll on a short screen",
          rail.getChildAt(0).getHeight() > rail.getHeight());
      rail.fullScroll(View.FOCUS_DOWN);
      android.graphics.Rect bounds = new android.graphics.Rect();
      assertTrue(((View) field(activity, "intervalChip")).getGlobalVisibleRect(bounds));
      assertEquals(628, bounds.right);
    }
  }
}
