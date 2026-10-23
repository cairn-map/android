package app.cairn.trek;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

/** Defaults and route-owned parameters for the bundled BRouter hiking profile. */
final class RoutingPreferences {
  private static final String PREFIX = "brouter.";
  static final String[] DIFFICULTIES = {
    "Unrated paths only",
    "T1 · Hiking",
    "T2 · Mountain hiking",
    "T3 · Demanding mountain hiking",
    "T4 · Alpine hiking",
    "T5 · Demanding alpine hiking",
    "T6 · Difficult alpine hiking"
  };
  private static final String[] FLAGS = {
    "consider_elevation", "consider_noise", "consider_river", "consider_forest",
    "consider_town", "iswet", "allow_steps", "allow_ferries"
  };
  private static final String[] LABELS = {
    "Prefer less climbing", "Prefer quieter paths", "Prefer rivers and lakes", "Prefer forest",
    "Avoid towns", "Prefer dry ground", "Allow steps", "Allow ferries"
  };

  static Map<String, String> read(SharedPreferences prefs) {
    Map<String, String> values = new LinkedHashMap<>();
    int limit = Math.max(0, Math.min(6, prefs.getInt(PREFIX + "SAC_scale_limit", 3)));
    int preferred = Math.max(0, Math.min(limit, prefs.getInt(PREFIX + "SAC_scale_preferred", 1)));
    values.put("SAC_scale_limit", Integer.toString(limit));
    values.put("SAC_scale_preferred", Integer.toString(preferred));
    for (String flag : FLAGS)
      values.put(flag, prefs.getBoolean(PREFIX + flag, flag.startsWith("allow_")) ? "1" : "0");
    float hiking = prefs.getFloat(PREFIX + "hiking_routes_preference", 0.2f);
    values.put(
        "hiking_routes_preference",
        Float.toString(Float.isFinite(hiking) ? Math.max(0, Math.min(2, hiking)) : 0.2f));
    return values;
  }

  static String encode(Map<String, String> values) {
    return values == null ? null : new JSONObject(values).toString();
  }

  static Map<String, String> decode(String encoded) {
    if (encoded == null) return null;
    try {
      JSONObject json = new JSONObject(encoded);
      Map<String, String> values = new LinkedHashMap<>();
      Iterator<String> keys = json.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        values.put(key, json.getString(key));
      }
      return values;
    } catch (JSONException error) {
      throw new IllegalArgumentException("Invalid routing preferences", error);
    }
  }

  static AlertDialog show(
      Context context, SharedPreferences prefs, Runnable changed, Runnable closed) {
    return show(
        context,
        "Default routing preferences",
        read(prefs),
        values -> {
          SharedPreferences.Editor edit = prefs.edit();
          edit.putInt(PREFIX + "SAC_scale_limit", Integer.parseInt(values.get("SAC_scale_limit")));
          edit.putInt(
              PREFIX + "SAC_scale_preferred", Integer.parseInt(values.get("SAC_scale_preferred")));
          edit.putFloat(
              PREFIX + "hiking_routes_preference",
              Float.parseFloat(values.get("hiking_routes_preference")));
          for (String flag : FLAGS) edit.putBoolean(PREFIX + flag, "1".equals(values.get(flag)));
          edit.apply();
          changed.run();
        },
        "Reset defaults",
        () -> {
          Map<String, String> defaults = new LinkedHashMap<>();
          defaults.put("SAC_scale_limit", "3");
          defaults.put("SAC_scale_preferred", "1");
          defaults.put("hiking_routes_preference", "0.2");
          for (String flag : FLAGS) defaults.put(flag, flag.startsWith("allow_") ? "1" : "0");
          return defaults;
        },
        closed);
  }

  static AlertDialog show(
      Context context,
      String title,
      Map<String, String> initial,
      Consumer<Map<String, String>> changed,
      String resetLabel,
      Supplier<Map<String, String>> resetValues,
      Runnable closed) {
    Map<String, String> values = new LinkedHashMap<>(initial);
    float density = context.getResources().getDisplayMetrics().density;
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (24 * density);
    content.setPadding(padding, 0, padding, padding);
    Spinner limit =
        choice(
            context,
            content,
            "Maximum difficulty",
            DIFFICULTIES,
            Integer.parseInt(values.get("SAC_scale_limit")));
    Spinner preferred =
        choice(
            context,
            content,
            "Preferred difficulty",
            DIFFICULTIES,
            Integer.parseInt(values.get("SAC_scale_preferred")));
    String[] hikingLabels = {"None", "Normal", "Strong", "Very strong"};
    float[] hikingValues = {0, 0.2f, 1, 2};
    float savedHiking = Float.parseFloat(values.get("hiking_routes_preference"));
    int hikingIndex = 0;
    for (int i = 1; i < hikingValues.length; i++)
      if (Math.abs(hikingValues[i] - savedHiking)
          < Math.abs(hikingValues[hikingIndex] - savedHiking)) hikingIndex = i;
    Spinner hiking =
        choice(context, content, "Marked hiking route preference", hikingLabels, hikingIndex);
    List<CheckBox> checks = new ArrayList<>();
    for (int i = 0; i < FLAGS.length; i++) {
      CheckBox check = new CheckBox(context);
      check.setText(LABELS[i]);
      check.setMinHeight((int) (48 * density));
      check.setChecked("1".equals(values.get(FLAGS[i])));
      checks.add(check);
      content.addView(check);
    }
    ScrollView scroll = new ScrollView(context);
    scroll.addView(content);
    boolean[] resetting = {false};
    Runnable save =
        () -> {
          if (resetting[0]) return;
          Map<String, String> next = new LinkedHashMap<>();
          int maximum = limit.getSelectedItemPosition();
          next.put("SAC_scale_limit", Integer.toString(maximum));
          next.put(
              "SAC_scale_preferred",
              Integer.toString(Math.min(maximum, preferred.getSelectedItemPosition())));
          next.put(
              "hiking_routes_preference",
              Float.toString(hikingValues[hiking.getSelectedItemPosition()]));
          for (int i = 0; i < FLAGS.length; i++)
            next.put(FLAGS[i], checks.get(i).isChecked() ? "1" : "0");
          if (!values.equals(next)) {
            values.clear();
            values.putAll(next);
            changed.accept(new LinkedHashMap<>(values));
          }
        };
    // Keep the preferred level within the selected maximum in both directions,
    // including in saved preferences before the other spinner's callback runs.
    limit.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (preferred.getSelectedItemPosition() > position) preferred.setSelection(position);
            save.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    preferred.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (position > limit.getSelectedItemPosition()) limit.setSelection(position);
            save.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    hiking.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            save.run();
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    for (CheckBox check : checks) check.setOnCheckedChangeListener((button, checked) -> save.run());
    AlertDialog dialog =
        CairnDialogs.drawer(context)
            .setTitle(title)
            .setView(scroll)
            .setNeutralButton(resetLabel, null)
            .create();
    dialog.setOnDismissListener(d -> closed.run());
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(
                    button -> {
                      Map<String, String> defaults = resetValues.get();
                      resetting[0] = true;
                      limit.setSelection(Integer.parseInt(defaults.get("SAC_scale_limit")));
                      preferred.setSelection(Integer.parseInt(defaults.get("SAC_scale_preferred")));
                      float target = Float.parseFloat(defaults.get("hiking_routes_preference"));
                      int nearest = 0;
                      for (int i = 1; i < hikingValues.length; i++)
                        if (Math.abs(hikingValues[i] - target)
                            < Math.abs(hikingValues[nearest] - target)) nearest = i;
                      hiking.setSelection(nearest);
                      for (int i = 0; i < FLAGS.length; i++)
                        checks.get(i).setChecked("1".equals(defaults.get(FLAGS[i])));
                      resetting[0] = false;
                      save.run();
                    }));
    dialog.show();
    return dialog;
  }

  private static Spinner choice(
      Context context, LinearLayout content, String label, String[] choices, int selected) {
    TextView title = new TextView(context);
    title.setText(label);
    title.setPadding(0, (int) (16 * context.getResources().getDisplayMetrics().density), 0, 0);
    content.addView(title);
    Spinner spinner = new Spinner(context);
    spinner.setContentDescription(label);
    spinner.setMinimumHeight((int) (48 * context.getResources().getDisplayMetrics().density));
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, choices);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    spinner.setSelection(selected);
    content.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
    title.setLabelFor(View.generateViewId());
    spinner.setId(title.getLabelFor());
    return spinner;
  }
}
