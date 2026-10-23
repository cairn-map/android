package app.cairn.trek;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Saved overrides for the bundled BRouter hiking profile. */
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

  static void show(Context context, SharedPreferences prefs, Runnable changed) {
    Map<String, String> values = read(prefs);
    float density = context.getResources().getDisplayMetrics().density;
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (24 * density);
    content.setPadding(padding, 0, padding, padding);
    TextView note = new TextView(context);
    note.setText(
        "BRouter hiking preferences apply to future calculations. Changing them recalculates the"
            + " route being edited. Difficulty uses mapped SAC ratings; unrated paths can still be"
            + " difficult. The maximum strongly penalizes harder paths rather than excluding"
            + " them.");
    content.addView(note);
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
    // Keep the preferred level within the selected maximum in both directions.
    limit.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (preferred.getSelectedItemPosition() > position) preferred.setSelection(position);
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    preferred.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (position > limit.getSelectedItemPosition()) limit.setSelection(position);
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
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
    CairnDialogs.drawer(context)
        .setTitle("Routing preferences")
        .setView(scroll)
        .setNegativeButton("Cancel", null)
        .setNeutralButton(
            "Reset defaults",
            (dialog, which) -> {
              SharedPreferences.Editor edit = prefs.edit();
              for (String key : values.keySet()) edit.remove(PREFIX + key);
              edit.apply();
              if (!values.equals(read(prefs))) changed.run();
            })
        .setPositiveButton(
            "Apply",
            (dialog, which) -> {
              SharedPreferences.Editor edit = prefs.edit();
              edit.putInt(PREFIX + "SAC_scale_limit", limit.getSelectedItemPosition());
              edit.putInt(PREFIX + "SAC_scale_preferred", preferred.getSelectedItemPosition());
              edit.putFloat(
                  PREFIX + "hiking_routes_preference",
                  hikingValues[hiking.getSelectedItemPosition()]);
              for (int i = 0; i < FLAGS.length; i++)
                edit.putBoolean(PREFIX + FLAGS[i], checks.get(i).isChecked());
              edit.apply();
              if (!values.equals(read(prefs))) changed.run();
            })
        .show();
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
