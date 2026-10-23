package app.cairn.trek;

import static org.junit.Assert.*;

import android.hardware.*;
import android.view.Surface;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowSensor;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class PhoneHeadingTest {
  private SensorManager manager;
  private PhoneHeading compass;
  private final List<Float> headings = new ArrayList<>();
  private int rotation;

  @Before
  public void setup() {
    manager = RuntimeEnvironment.getApplication().getSystemService(SensorManager.class);
  }

  @After
  public void cleanup() {
    if (compass != null) compass.setActive(false);
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
  }

  private Sensor add(int type) {
    Sensor sensor = ShadowSensor.newInstance(type);
    Shadows.shadowOf(manager).addSensor(sensor);
    return sensor;
  }

  private void start() {
    compass = new PhoneHeading(manager, () -> rotation, headings::add);
    compass.setActive(true);
  }

  private void send(Sensor sensor, long millis, float... values) {
    compass.onSensorChanged(
        SensorEventBuilder.newBuilder()
            .setSensor(sensor)
            .setValues(values)
            .setAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            .setTimestamp(millis * 1_000_000L)
            .build());
  }

  private void azimuth(Sensor sensor, long millis, double degrees) {
    double half = Math.toRadians(-degrees) / 2;
    send(sensor, millis, 0, 0, (float) Math.sin(half), (float) Math.cos(half));
  }

  @Test
  public void prefersGeomagneticVectorAndNeverSubscribesToGyroscopeOrRotationVector() {
    Sensor geomagnetic = add(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
    Sensor accelerometer = add(Sensor.TYPE_ACCELEROMETER);
    Sensor magnetometer = add(Sensor.TYPE_MAGNETIC_FIELD);
    Sensor gyroscope = add(Sensor.TYPE_GYROSCOPE);
    Sensor rotationVector = add(Sensor.TYPE_ROTATION_VECTOR);
    start();
    assertTrue(Shadows.shadowOf(manager).hasListener(compass, geomagnetic));
    for (Sensor unused : List.of(accelerometer, magnetometer, gyroscope, rotationVector))
      assertFalse(Shadows.shadowOf(manager).hasListener(compass, unused));
    compass.setActive(true);
    assertEquals(1, Shadows.shadowOf(manager).getListeners().size());
    compass.setActive(false);
    azimuth(geomagnetic, 1000, 90);
    assertTrue(headings.isEmpty());
  }

  @Test
  public void rateLimitsUpdatesAndIgnoresJitterAcrossNorth() {
    Sensor sensor = add(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
    start();
    azimuth(sensor, 1000, 359);
    assertEquals(359, headings.get(0), 0.01);
    azimuth(sensor, 1100, 90);
    assertEquals(1, headings.size());
    azimuth(sensor, 1300, 1);
    assertEquals(1, headings.size());
    azimuth(sensor, 1600, 5);
    assertEquals(2, headings.size());
    assertEquals(5, headings.get(1), 0.01);
    compass.setActive(false);
    assertTrue(Float.isNaN(headings.get(2)));
    compass.setActive(true);
    azimuth(sensor, 1601, 90);
    assertEquals(90, headings.get(3), 0.01);
  }

  @Test
  public void followsTheTopEdgeInAllFourDisplayRotations() {
    Sensor sensor = add(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
    start();
    int[] rotations = {
      Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
    };
    for (int i = 0; i < rotations.length; i++) {
      rotation = rotations[i];
      azimuth(sensor, 1000 + i * 300, 0);
      assertEquals(i * 90, headings.get(headings.size() - 1), 0.01);
    }
  }

  @Test
  public void fallbackNeedsBothReliableSensorsAndClearsUnreliableHeading() {
    Sensor acceleration = add(Sensor.TYPE_ACCELEROMETER);
    Sensor magnetic = add(Sensor.TYPE_MAGNETIC_FIELD);
    start();
    assertTrue(Shadows.shadowOf(manager).hasListener(compass, acceleration));
    assertTrue(Shadows.shadowOf(manager).hasListener(compass, magnetic));
    send(acceleration, 1000, 0, 0, 9.81f);
    assertTrue(headings.isEmpty());
    send(magnetic, 1000, 0, 40, -10);
    assertEquals(0, headings.get(0), 0.01);
    compass.onAccuracyChanged(magnetic, SensorManager.SENSOR_STATUS_UNRELIABLE);
    assertTrue(Float.isNaN(headings.get(1)));
    send(acceleration, 1300, 0, 0, 9.81f);
    assertEquals(2, headings.size());
    send(magnetic, 1300, -40, 0, -10);
    assertEquals(90, headings.get(2), 0.01);
  }

  @Test
  public void hidesHeadingWhenTopEdgePointsVertically() {
    Sensor sensor = add(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
    start();
    azimuth(sensor, 1000, 0);
    float half = (float) Math.sqrt(0.5);
    send(sensor, 1300, half, 0, 0, half);
    assertTrue(Float.isNaN(headings.get(1)));
  }

  @Test
  public void missingCompassAndRejectedRegistrationLeaveNoListeners() {
    add(Sensor.TYPE_ROTATION_VECTOR);
    add(Sensor.TYPE_GYROSCOPE);
    add(Sensor.TYPE_ACCELEROMETER);
    start();
    assertFalse(compass.available());
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
    add(Sensor.TYPE_MAGNETIC_FIELD);
    Shadows.shadowOf(manager).setForceListenersToFail(true);
    start();
    assertTrue(compass.available());
    assertTrue(Shadows.shadowOf(manager).getListeners().isEmpty());
  }
}
