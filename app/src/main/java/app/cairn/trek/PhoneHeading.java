package app.cairn.trek;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Foreground compass: no gyroscope, wake-up sensors, timers or location requests. */
final class PhoneHeading implements SensorEventListener {
  static final int PERIOD_US = 200_000;
  static final long MIN_UPDATE_NS = 250_000_000L;
  private final SensorManager manager;
  private final IntSupplier rotation;
  private final Consumer<Float> changed;
  private final Sensor vector, acceleration, magnetic;
  private final float[] matrix = new float[9], screen = new float[9], angles = new float[3];
  private final float[] gravity = new float[3], field = new float[3];
  private boolean running, attempted, haveGravity, haveField;
  private long lastUpdate;
  private float heading = Float.NaN;

  PhoneHeading(SensorManager manager, IntSupplier rotation, Consumer<Float> changed) {
    this.manager = manager;
    this.rotation = rotation;
    this.changed = changed;
    vector = sensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
    acceleration = sensor(Sensor.TYPE_ACCELEROMETER);
    magnetic = sensor(Sensor.TYPE_MAGNETIC_FIELD);
  }

  private Sensor sensor(int type) {
    return manager == null ? null : manager.getDefaultSensor(type, false);
  }

  boolean available() {
    return vector != null || (acceleration != null && magnetic != null);
  }

  void setActive(boolean active) {
    if (!active) {
      if (running) manager.unregisterListener(this);
      running = attempted = haveGravity = haveField = false;
      lastUpdate = 0;
      publish(Float.NaN);
      return;
    }
    if (attempted || !available()) return;
    attempted = true;
    // Register only non-wake-up, magnetometer/accelerometer based sensors.
    if (vector != null) running = manager.registerListener(this, vector, PERIOD_US);
    else {
      boolean a = manager.registerListener(this, acceleration, PERIOD_US);
      boolean m = manager.registerListener(this, magnetic, PERIOD_US);
      running = a && m;
      if (!running) manager.unregisterListener(this);
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!running) return;
    if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        || event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) {
      unreliable(event.sensor);
      return;
    }
    if (event.sensor == acceleration) {
      System.arraycopy(event.values, 0, gravity, 0, 3);
      haveGravity = true;
    } else if (event.sensor == magnetic) {
      System.arraycopy(event.values, 0, field, 0, 3);
      haveField = true;
    }
    if (lastUpdate != 0 && event.timestamp - lastUpdate < MIN_UPDATE_NS) return;
    if (event.sensor == vector) SensorManager.getRotationMatrixFromVector(matrix, event.values);
    else if (!haveGravity || !haveField) return;
    else if (!SensorManager.getRotationMatrix(matrix, null, gravity, field)) {
      publish(Float.NaN);
      return;
    }
    lastUpdate = event.timestamp;
    int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
    switch (rotation.getAsInt()) {
      case Surface.ROTATION_90:
        x = SensorManager.AXIS_Y;
        y = SensorManager.AXIS_MINUS_X;
        break;
      case Surface.ROTATION_180:
        x = SensorManager.AXIS_MINUS_X;
        y = SensorManager.AXIS_MINUS_Y;
        break;
      case Surface.ROTATION_270:
        x = SensorManager.AXIS_MINUS_Y;
        y = SensorManager.AXIS_X;
        break;
    }
    SensorManager.remapCoordinateSystem(matrix, x, y, screen);
    // The top edge has no useful horizontal direction when pointing vertically.
    if (Math.hypot(screen[1], screen[4]) < 0.1) {
      publish(Float.NaN);
      return;
    }
    SensorManager.getOrientation(screen, angles);
    float next = (float) ((Math.toDegrees(angles[0]) + 360) % 360);
    if (Float.isFinite(next)
        && (!Float.isFinite(heading) || Math.abs(((next - heading + 540) % 360) - 180) >= 3))
      publish(next);
  }

  private void unreliable(Sensor sensor) {
    lastUpdate = 0;
    if (sensor == acceleration) haveGravity = false;
    if (sensor == magnetic) haveField = false;
    publish(Float.NaN);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    if (running && accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) unreliable(sensor);
  }

  private void publish(float next) {
    if (Float.compare(heading, next) == 0) return;
    heading = next;
    changed.accept(next);
  }
}
