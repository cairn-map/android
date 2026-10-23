package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import btools.router.*;
import java.io.File;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RoutingPreferencesTest {
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    prefs = context.getSharedPreferences("settings", 0);
    prefs.edit().clear().commit();
  }

  private View find(View view, String label) {
    if (label.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof CheckBox && label.contentEquals(((CheckBox) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void controlsApplyImmediatelyAndResetStaysOpenUntilDismissed() {
    try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
      int[] changes = {0}, closed = {0};
      RoutingPreferences.show(controller.get(), prefs, () -> changes[0]++, () -> closed[0]++);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      View root = dialog.getWindow().getDecorView();
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
      assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility());
      assertEquals(0, changes[0]);
      ((Spinner) find(root, "Maximum difficulty")).setSelection(2);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((Spinner) find(root, "Preferred difficulty")).setSelection(2);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((Spinner) find(root, "Marked hiking route preference")).setSelection(3);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((CheckBox) find(root, "Prefer less climbing")).setChecked(true);
      ((CheckBox) find(root, "Allow ferries")).setChecked(false);
      Map<String, String> values = RoutingPreferences.read(prefs);
      assertEquals("2", values.get("SAC_scale_limit"));
      assertEquals("2", values.get("SAC_scale_preferred"));
      assertEquals("2.0", values.get("hiking_routes_preference"));
      assertEquals("1", values.get("consider_elevation"));
      assertEquals("0", values.get("allow_ferries"));
      assertTrue(changes[0] > 0);
      assertTrue(dialog.isShowing());
      assertEquals(0, closed[0]);
      dialog.cancel();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(values, RoutingPreferences.read(prefs));
      assertEquals(1, closed[0]);
      RoutingPreferences.show(controller.get(), prefs, () -> changes[0]++, () -> closed[0]++);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      dialog = ShadowAlertDialog.getLatestAlertDialog();
      root = dialog.getWindow().getDecorView();
      // Raising the preferred level also raises the maximum; lowering the maximum clamps both.
      ((Spinner) find(root, "Preferred difficulty")).setSelection(6);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals("6", RoutingPreferences.read(prefs).get("SAC_scale_limit"));
      ((Spinner) find(root, "Maximum difficulty")).setSelection(0);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals("0", RoutingPreferences.read(prefs).get("SAC_scale_preferred"));
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(dialog.isShowing());
      assertEquals(1, closed[0]);
      assertEquals("3", RoutingPreferences.read(prefs).get("SAC_scale_limit"));
      assertEquals("1", RoutingPreferences.read(prefs).get("SAC_scale_preferred"));
      assertEquals("1", RoutingPreferences.read(prefs).get("allow_ferries"));
      assertEquals(3, ((Spinner) find(root, "Maximum difficulty")).getSelectedItemPosition());
      assertFalse(((CheckBox) find(root, "Prefer less climbing")).isChecked());
      ((CheckBox) find(root, "Prefer less climbing")).setChecked(true);
      assertEquals("1", RoutingPreferences.read(prefs).get("consider_elevation"));
      dialog.dismiss();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(2, closed[0]);
    }
  }

  @Test
  public void savedParametersOverrideTheActualBRouterHikingProfile() throws Exception {
    prefs
        .edit()
        .putInt("brouter.SAC_scale_limit", 2)
        .putInt("brouter.SAC_scale_preferred", 2)
        .putBoolean("brouter.consider_elevation", true)
        .putBoolean("brouter.allow_steps", false)
        .putBoolean("brouter.allow_ferries", false)
        .putFloat("brouter.hiking_routes_preference", 2)
        .commit();
    RoutingData.prepareProfiles(context);
    RoutingContext config = new RoutingContext();
    config.localFunction = new File(RoutingData.profiles(context), "hiking.brf").getAbsolutePath();
    config.keyValues = RoutingPreferences.read(prefs);
    ProfileCache.setSize(1);
    try {
      ProfileCache.parseProfile(config);
      assertEquals(2, config.expctxWay.getVariableValue("SAC_scale_limit", -1), 0);
      assertEquals(2, config.expctxWay.getVariableValue("SAC_scale_preferred", -1), 0);
      assertEquals(1, config.expctxWay.getVariableValue("consider_elevation", -1), 0);
      assertEquals(0, config.expctxWay.getVariableValue("allow_steps", -1), 0);
      assertEquals(0, config.expctxWay.getVariableValue("allow_ferries", -1), 0);
      assertEquals(2, config.expctxWay.getVariableValue("hiking_routes_preference", -1), 0);
      assertTrue(config.expctxWay.getVariableValue("uphillcost", -1) > 0);
    } finally {
      ProfileCache.releaseProfile(config);
      ProfileCache.setSize(0);
    }
  }
}
