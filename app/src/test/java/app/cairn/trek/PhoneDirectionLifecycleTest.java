package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.*;
import android.location.LocationManager;
import android.os.Looper;
import android.view.*;
import android.widget.Switch;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSensor;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhoneDirectionLifecycleTest {
  private ActivityController<MainActivity> controller;
  private MainActivity activity;
  private TrekMapView map;
  private SensorManager manager;
  private Bitmap bitmap;

  private Object field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void invoke(String name) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  private void draw() {
    map.layout(0, 0, 800, 1200);
    map.draw(new Canvas(bitmap));
  }

  @Before
  public void setup() throws Exception {
    TrackingService.running = false;
    RuntimeEnvironment.getApplication().deleteDatabase("treks.db");
    RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
    manager = RuntimeEnvironment.getApplication().getSystemService(SensorManager.class);
    Shadows.shadowOf(manager)
        .addSensor(ShadowSensor.newInstance(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR));
    controller = Robolectric.buildActivity(MainActivity.class).setup().windowFocusChanged(true);
    activity = controller.get();
    ((ExecutorService) field("io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    map = (TrekMapView) field("map");
    map.source(null);
    map.restoreCamera(new double[] {48, 2, 14});
    bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
  }

  @After
  public void cleanup() {
    if (controller != null) controller.close();
    if (bitmap != null) bitmap.recycle();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    TrackingService.running = false;
  }

  @Test
  public void optInNeedsVisiblePositionAndStopsOnPauseVisibilityDetachAndLibrary()
      throws Exception {
    map.position = new Point(48, 2);
    draw();
    assertTrue("Off by default", Shadows.shadowOf(manager).getListeners().isEmpty());
    map.setPhoneDirectionEnabled(true);
    map.position = null;
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    map.position = new Point(48, 2);
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    controller.windowFocusChanged(false);
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    controller.windowFocusChanged(true);
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    map.restoreCamera(new double[] {0, 0, 14});
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    map.restoreCamera(new double[] {48, 2, 14});
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    map.setVisibility(View.GONE);
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    map.setVisibility(View.VISIBLE);
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    controller.pause();
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    ((Store) field("store")).prefs.edit().putBoolean("phoneDirection", true).apply();
    controller.resume();
    ((ExecutorService) field("io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    invoke("showLibrary");
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    ((AlertDialog) field("libraryDialog")).dismiss();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    controller.windowFocusChanged(true);
    assertEquals("Map", field("currentPage"));
    draw();
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    ((ViewGroup) map.getParent()).removeView(map);
    draw();
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    LocationManager locations = activity.getSystemService(LocationManager.class);
    assertTrue(
        Shadows.shadowOf(locations).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty());
    assertTrue(
        Shadows.shadowOf(locations)
            .getLocationRequests(LocationManager.NETWORK_PROVIDER)
            .isEmpty());
  }

  @Test
  @Config(sdk = 28)
  public void coneTurnsFromNorthToEastWithoutMovingThePositionDot() {
    map.position = new Point(48, 2, 0, System.currentTimeMillis(), 5, 0);
    draw();
    int north = bitmap.getPixel(400, 570), east = bitmap.getPixel(430, 600);
    map.setPhoneDirectionEnabled(true);
    draw();
    Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, false);
    Shadows.shadowOf(manager)
        .sendSensorEventToListeners(
            SensorEventBuilder.newBuilder()
                .setSensor(sensor)
                .setValues(new float[] {0, 0, 0, 1})
                .setTimestamp(1_000_000_000L)
                .setAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
                .build());
    draw();
    assertNotEquals(north, bitmap.getPixel(400, 570));
    assertEquals(east, bitmap.getPixel(430, 600));
    int dot = bitmap.getPixel(400, 600);
    float half = (float) Math.sqrt(0.5);
    Shadows.shadowOf(manager)
        .sendSensorEventToListeners(
            SensorEventBuilder.newBuilder()
                .setSensor(sensor)
                .setValues(new float[] {0, 0, -half, half})
                .setTimestamp(1_500_000_000L)
                .setAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
                .build());
    draw();
    assertEquals(north, bitmap.getPixel(400, 570));
    assertNotEquals(east, bitmap.getPixel(430, 600));
    assertEquals(dot, bitmap.getPixel(400, 600));
  }

  @Test
  public void settingsPersistTheOptInWithoutChangingGpsInterval() throws Exception {
    invoke("batterySettings");
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    Switch direction = findDirection(dialog.getWindow().getDecorView());
    assertNotNull(direction);
    assertTrue(direction.isEnabled());
    assertFalse(direction.isChecked());
    Store store = (Store) field("store");
    long interval = store.intervalMillis();
    direction.setChecked(true);
    assertTrue(store.prefs.getBoolean("phoneDirection", false));
    assertEquals(interval, store.intervalMillis());
    dialog.dismiss();
    invoke("batterySettings");
    dialog = ShadowAlertDialog.getLatestAlertDialog();
    direction = findDirection(dialog.getWindow().getDecorView());
    assertTrue(direction.isChecked());
    direction.setChecked(false);
    assertFalse(store.prefs.getBoolean("phoneDirection", true));
    assertEquals(interval, store.intervalMillis());
    dialog.dismiss();
  }

  private Switch findDirection(View view) {
    if (view instanceof Switch
        && activity.getString(R.string.phone_direction).contentEquals(((Switch) view).getText()))
      return (Switch) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Switch found = findDirection(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }
}
