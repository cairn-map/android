package app.cairn.trek;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RoutingDataClearTest {
  @Test
  public void clearRemovesAllGraphsAndTransferFilesButKeepsRoutesMapsAndProfiles()
      throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File directory = RoutingData.directory(context);
    String[] graphs = {
      "E0_N45.rd5",
      "E5_N45.rd5",
      "W5_N45.rd5.part",
      "E5_N50.rd5.resume",
      "E5_N50.rd5.resume.tmp",
      "E10_N50.rd5.import"
    };
    for (String name : graphs) Files.write(new File(directory, name).toPath(), new byte[] {1});
    File unrelated = new File(directory, "notes.txt");
    Files.write(unrelated.toPath(), new byte[] {2});
    RoutingData.prepareProfiles(context);
    try (Store store = new Store(context);
        PlanIgnStore maps = new PlanIgnStore(context)) {
      String id = store.saveRoute(List.of(new Point(45, 6), new Point(45.1, 6.1)), "Keep route");
      String pack =
          maps.create("Keep map", TilePlanner.corridor(List.of(new Point(45, 6)), 100, 8));
      try (ActivityController<MainActivity> controller =
          Robolectric.buildActivity(MainActivity.class).setup()) {
        MainActivity activity = controller.get();
        drain(activity);
        java.lang.reflect.Method menu = MainActivity.class.getDeclaredMethod("showLibrary");
        menu.setAccessible(true);
        menu.invoke(activity);
        AlertDialog savedMaps = ShadowAlertDialog.getLatestAlertDialog();
        View button = find(savedMaps.getWindow().getDecorView(), "Clear all routing data");
        assertNotNull(button);
        assertNull(find(savedMaps.getWindow().getDecorView(), "Routing data"));
        button.performClick();
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        for (String name : graphs) assertTrue(new File(directory, name).isFile());
        button.performClick();
        confirm = ShadowAlertDialog.getLatestAlertDialog();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain(activity);
        for (String name : graphs) assertFalse(name, new File(directory, name).exists());
        assertTrue(unrelated.isFile());
        assertTrue(new File(RoutingData.profiles(context), "hiking.brf").isFile());
        assertFalse(store.read(id).isEmpty());
      }
      assertEquals(id, store.selectedLibraryId());
      assertTrue(maps.packs().stream().anyMatch(saved -> saved.id.equals(pack)));
    }
  }

  @Test
  public void clearRechecksActiveWorkWhenConfirmed() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File graph = new File(RoutingData.directory(context), "E0_N45.rd5");
    Files.write(graph.toPath(), new byte[] {1});
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      drain(activity);
      java.lang.reflect.Method clear = MainActivity.class.getDeclaredMethod("clearRoutingData");
      clear.setAccessible(true);
      for (String flag : List.of("recording", "planning", "calculation", "download")) {
        clear.invoke(activity);
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        Field state = null;
        if (flag.equals("planning") || flag.equals("calculation")) {
          state =
              MainActivity.class.getDeclaredField(
                  flag.equals("planning") ? "planning" : "calculationRunning");
          state.setAccessible(true);
          state.setBoolean(activity, true);
        } else if (flag.equals("recording")) TrackingService.running = true;
        else RoutingDownloadService.running = true;
        try {
          confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          drain(activity);
          assertTrue(flag, graph.isFile());
        } finally {
          if (state != null) state.setBoolean(activity, false);
          TrackingService.running = false;
          RoutingDownloadService.running = false;
        }
      }
    }
  }

  private Object field(MainActivity activity, String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void drain(MainActivity activity) throws Exception {
    for (int i = 0; i < 3; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      ((ExecutorService) field(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle();
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
}
