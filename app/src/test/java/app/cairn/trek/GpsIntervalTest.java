package app.cairn.trek;

import static org.junit.Assert.*;

import org.junit.Test;

public class GpsIntervalTest {
  @Test
  public void everySavedSecondRoundTripsExactly() {
    for (int seconds = 1; seconds <= 3600; seconds++)
      assertEquals(seconds, GpsInterval.seconds(GpsInterval.progress(seconds)));
  }

  @Test
  public void equalSliderDistancesMultiplyTheIntervalAndStayWithinBounds() {
    assertEquals(1, GpsInterval.seconds(0));
    assertEquals(60, GpsInterval.seconds(GpsInterval.STEPS / 2));
    assertEquals(3600, GpsInterval.seconds(GpsInterval.STEPS));
    int previous = 0;
    for (int position = 0; position <= GpsInterval.STEPS; position++) {
      int seconds = GpsInterval.seconds(position);
      assertTrue(seconds >= previous && seconds >= 1 && seconds <= 3600);
      previous = seconds;
    }
    assertTrue(GpsInterval.progress(15) > GpsInterval.STEPS / 4);
  }
}
