package app.cairn.trek;

import static app.cairn.trek.AppPalette.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final int IMPORT_GPX = 10, EXPORT_GPX = 12;
  private Store store;
  private AppUpdates appUpdates;
  private TrekMapView map;
  private OfflineMap offlineMap;
  private IgnMap ignMap;
  private PlanIgnStore mapStore;
  private ImageButton previewButton;
  private TextView downloadStatus;
  private String pendingDownload;
  private EmbeddedRouter router;
  private FrameLayout root;
  private LinearLayout pages, nav;
  private TextView intervalChip, status, recordButton, planStatus;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private List<Point> route = new ArrayList<>(), track = new ArrayList<>();
  private final List<Point> waypoints = new ArrayList<>();
  private List<Point> exportPoints;
  private boolean planning = false,
      destroyed = false,
      receiverRegistered = false,
      calculationRunning = false;
  private String currentPage = "Map";
  private int dataGeneration = 0, routeRevision = 0;
  private ManualLocation manualLocation;
  private PlaceSearch placeSearch;
  private AlertDialog searchDialog;

  private final BroadcastReceiver fixes =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (DownloadService.EVENT.equals(i.getAction())) {
            if (downloadStatus != null) downloadStatus.setText(i.getStringExtra("message"));
            boolean finished = i.getBooleanExtra("finished", false);
            map.refreshTiles(finished);
            if (finished && "Routes".equals(currentPage)) showPage("Routes");
            if (finished && mapDialog != null && mapDialog.isShowing()) {
              mapDialog.dismiss();
              mapMenu();
            }
            return;
          }
          if (i.hasExtra("error")) message(i.getStringExtra("error"));
          if (i.hasExtra("lat")) {
            Point p =
                new Point(
                    i.getDoubleExtra("lat", 0),
                    i.getDoubleExtra("lon", 0),
                    i.getDoubleExtra("elevation", Double.NaN),
                    i.getLongExtra("time", 0),
                    i.getFloatExtra("accuracy", 0),
                    0);
            if (track.isEmpty() || track.get(track.size() - 1).time < p.time) track.add(p);
            map.track = track;
            map.position = p;
            map.invalidate();
          }
          refreshStatus();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    appUpdates = new AppUpdates(this);
    placeSearch = new PlaceSearch(this);
    mapStore = new PlanIgnStore(this);
    ignMap = new IgnMap(this);
    router = new EmbeddedRouter(this);
    root = new FrameLayout(this);
    root.setBackgroundColor(BACKGROUND);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(root);
    pages = column();
    root.addView(pages, new FrameLayout.LayoutParams(-1, -1));
    map = new TrekMapView(this);
    map.source(ignMap);
    if (store.prefs.contains("mapLat"))
      map.restoreCamera(
          new double[] {
            Double.longBitsToDouble(store.prefs.getLong("mapLat", 0)),
            Double.longBitsToDouble(store.prefs.getLong("mapLon", 0)),
            Double.longBitsToDouble(store.prefs.getLong("mapZoom", Double.doubleToLongBits(13)))
          });
    map.onLongPress =
        p -> {
          if (planning && !calculationRunning) {
            waypoints.add(p);
            refreshPlanning();
          }
        };
    nav = row();
    nav.setPadding(dp(4), dp(4), dp(4), dp(4));
    for (String title : new String[] {"Map", "Routes"}) {
      TextView b = button(title, false, () -> showPage(title));
      b.setMinHeight(dp(48));
      b.setTextSize(13);
      b.setPadding(dp(8), dp(10), dp(8), dp(10));
      b.setSingleLine(true);
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    nav.setBackground(surface(SURFACE, OUTLINE, 28));
    FrameLayout.LayoutParams navParams =
        new FrameLayout.LayoutParams(dp(164), dp(56), Gravity.START | Gravity.BOTTOM);
    navParams.setMargins(dp(12), 0, 0, dp(12));
    root.addView(nav, navParams);
    showPage("Map");
    File file = new File(getFilesDir(), "offline.mbtiles");
    if (file.exists())
      io.execute(
          () -> {
            try {
              OfflineMap source = new OfflineMap(file);
              main.post(
                  () -> {
                    if (destroyed) {
                      source.close();
                      return;
                    }
                    offlineMap = source;
                    if (store.prefs.getBoolean("importedMap", false))
                      map.source(source, !store.prefs.contains("mapLat"));
                  });
            } catch (Exception e) {
              main.post(() -> message("Could not open offline map: " + e.getMessage()));
            }
          });
    handleOpenIntent(getIntent());
  }

  @Override
  protected void onResume() {
    super.onResume();
    IntentFilter filter = new IntentFilter(TrackingService.EVENT);
    filter.addAction(DownloadService.EVENT);
    int receiverFlags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0;
    // The API-26 overload uses a signature permission on older systems and
    // adds the explicit non-exported flag on Android 13 and later.
    registerReceiver(fixes, filter, "app.cairn.trek.permission.INTERNAL", null, receiverFlags);
    receiverRegistered = true;
    loadPoints();
    reloadLibrary();
    refreshStatus();
    if ("Routes".equals(currentPage)) appUpdates.resume();
  }

  @Override
  protected void onPause() {
    appUpdates.pause();
    map.stopMotion();
    double[] camera = map.camera();
    store
        .prefs
        .edit()
        .putLong("mapLat", Double.doubleToLongBits(camera[0]))
        .putLong("mapLon", Double.doubleToLongBits(camera[1]))
        .putLong("mapZoom", Double.doubleToLongBits(camera[2]))
        .apply();
    if (receiverRegistered) {
      unregisterReceiver(fixes);
      receiverRegistered = false;
    }
    cancelLocate();
    cancelSearch();
    router.cancel();
    setMapOnline(false);
    super.onPause();
  }

  private void loadPoints() {
    if (destroyed) return;
    int generation = ++dataGeneration;
    int revision = routeRevision;
    io.execute(
        () -> {
          List<Point> loadedRoute = store.read("route"),
              loadedTrack = store.read(store.prefs.getString("currentTrek", null));
          main.post(
              () -> {
                if (destroyed || generation != dataGeneration) return;
                long last =
                    loadedTrack.isEmpty() ? 0 : loadedTrack.get(loadedTrack.size() - 1).time;
                for (Point point : track) if (point.time > last) loadedTrack.add(point);
                if (revision == routeRevision) route = loadedRoute;
                track = loadedTrack;
                map.route = route;
                map.track = track;
                if (!track.isEmpty()
                    && (map.position == null
                        || map.position.time < track.get(track.size() - 1).time))
                  map.position = track.get(track.size() - 1);
                map.invalidate();
                refreshStatus();
              });
        });
  }

  private void showPage(String title) {
    currentPage = title;
    if (!"Map".equals(title)) cancelSearch();
    if (!"Routes".equals(title)) appUpdates.pause();
    if (!"Map".equals(title) && ignMap.online()) setMapOnline(false);
    pages.removeAllViews();
    for (int i = 0; i < nav.getChildCount(); i++) {
      TextView b = (TextView) nav.getChildAt(i);
      boolean selected = b.getText().toString().equals(title);
      b.setTextColor(selected ? ON_PRIMARY : MUTED);
      b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
      b.setSelected(selected);
      b.setBackground(
          buttonBackground(selected ? PRIMARY : SURFACE, Color.TRANSPARENT, 24));
    }
    if (title.equals("Map")) showMap();
    else if (title.equals("Routes")) showRoutes();
    else showRoutes();
    refreshStatus();
  }

  private boolean renderedRecording;

  private void showMap() {
    status = recordButton = planStatus = null;
    renderedRecording = TrackingService.running;
    if (map.getParent() != null) ((ViewGroup) map.getParent()).removeView(map);
    FrameLayout frame = new FrameLayout(this);
    frame.addView(map, new FrameLayout.LayoutParams(-1, -1));
    pages.addView(frame, new LinearLayout.LayoutParams(-1, -1));

    LinearLayout controls = column();
    controls.setGravity(Gravity.END);
    controls.addView(
        iconButton(MapIcon.MAPS, "Saved maps and downloads", this::mapMenu),
        new LinearLayout.LayoutParams(dp(48), dp(48)));
    previewButton =
        iconButton(
            ignMap.online() ? MapIcon.ONLINE : MapIcon.OFFLINE,
            "",
            () -> setMapOnline(!ignMap.online()));
    updatePreviewIcon();
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    iconParams.topMargin = dp(8);
    controls.addView(previewButton, iconParams);
    iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    iconParams.topMargin = dp(8);
    controls.addView(iconButton(MapIcon.LOCATE, "Find my location", this::locate), iconParams);
    iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    iconParams.topMargin = dp(8);
    controls.addView(iconButton(MapIcon.SEARCH, "Search places", this::searchPlaces), iconParams);
    intervalChip = button("GPS · 60s", false, this::batterySettings);
    intervalChip.setTextSize(12);
    intervalChip.setSingleLine(true);
    intervalChip.setMinHeight(dp(48));
    intervalChip.setContentDescription("GPS interval and battery settings");
    LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(-2, dp(48));
    gpsParams.topMargin = dp(8);
    controls.addView(intervalChip, gpsParams);
    FrameLayout.LayoutParams controlsParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
    controlsParams.setMargins(0, 0, dp(12), dp(planning ? 234 : 92));
    frame.addView(controls, controlsParams);

    if (planning) {
      LinearLayout bottom = column();
      bottom.setPadding(dp(12), dp(8), dp(12), dp(8));
      bottom.setBackground(surface(SURFACE, OUTLINE, 24));
      planStatus = label("Long press to add points", 13, MUTED, false);
      bottom.addView(planStatus);
      LinearLayout actions = row();
      actions.addView(
          button(
              "Undo",
              false,
              () -> {
                if (!calculationRunning && !waypoints.isEmpty()) {
                  waypoints.remove(waypoints.size() - 1);
                  refreshPlanning();
                }
              }),
          new LinearLayout.LayoutParams(0, dp(48), 1));
      actions.addView(
          button(
              calculationRunning ? "Cancel" : "Route",
              true,
              () -> {
                if (calculationRunning) router.cancel();
                else calculate();
              }),
          new LinearLayout.LayoutParams(0, dp(48), 1));
      actions.addView(
          button(
              "More",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setItems(
                          new String[] {
                            "Add coordinates", "Download routing data", "Cancel planning"
                          },
                          (d, which) -> {
                            if (which == 0) {
                              addCoordinates();
                              return;
                            }
                            if (which == 1) {
                              routingDataMenu();
                              return;
                            }
                            router.cancel();
                            planning = false;
                            waypoints.clear();
                            map.waypoints = waypoints;
                            showPage("Map");
                          })
                      .show()),
          new LinearLayout.LayoutParams(0, dp(48), 1));
      bottom.addView(actions);
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
      params.setMargins(dp(12), 0, dp(12), dp(80));
      frame.addView(bottom, params);
      refreshPlanning();
    } else {
      LinearLayout hike = row();
      hike.setGravity(Gravity.CENTER_VERTICAL);
      if (renderedRecording) {
        hike.setPadding(dp(4), dp(4), dp(4), dp(4));
        hike.setBackground(surface(SURFACE, OUTLINE, 28));
        status = label("Recording", 12, TEXT, true);
        status.setMaxLines(2);
        status.setPadding(dp(8), 0, dp(4), 0);
        hike.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
      }
      recordButton =
          button(renderedRecording ? "Finish" : "Start hike", true, this::toggleRecording);
      recordButton.setMinHeight(dp(48));
      recordButton.setTextSize(14);
      recordButton.setBackground(buttonBackground(PRIMARY, PRIMARY_BORDER, 24));
      hike.addView(
          recordButton,
          new LinearLayout.LayoutParams(renderedRecording ? dp(80) : -1, dp(48)));
      int width =
          renderedRecording
              ? Math.max(dp(148), getResources().getDisplayMetrics().widthPixels - dp(200))
              : dp(120);
      FrameLayout.LayoutParams params =
          new FrameLayout.LayoutParams(
              width, dp(renderedRecording ? 56 : 48), Gravity.END | Gravity.BOTTOM);
      params.setMargins(0, 0, dp(12), dp(renderedRecording ? 12 : 16));
      frame.addView(hike, params);
    }
    map.bottomOverlay = dp(planning ? 160 : 0);
  }

  private LinearLayout page(String title, String subtitle) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = column();
    content.setPadding(dp(20), dp(20), dp(20), dp(100));
    content.addView(label(title, 29, TEXT, true));
    TextView sub = label(subtitle, 14, MUTED, false);
    sub.setPadding(0, dp(8), 0, dp(24));
    content.addView(sub);
    scroll.addView(content);
    pages.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    return content;
  }

  private List<Store.RouteEntry> routeEntries = new ArrayList<>();
  private AlertDialog mapDialog;

  private void reloadLibrary() {
    if (destroyed) return;
    io.execute(
        () -> {
          List<Store.RouteEntry> entries = store.routes();
          List<List<Point>> overlays = new ArrayList<>();
          String active = store.prefs.getString("activeRouteId", "");
          boolean activeVisible = true;
          for (Store.RouteEntry entry : entries) {
            if (entry.id.equals(active)) activeVisible = entry.visible;
            else if (entry.visible) overlays.add(store.read(entry.id));
          }
          boolean showActive = activeVisible;
          main.post(
              () -> {
                if (destroyed) return;
                routeEntries = entries;
                map.otherRoutes = overlays;
                map.route = showActive ? route : Collections.emptyList();
                map.invalidate();
                if ("Routes".equals(currentPage)) showPage("Routes");
              });
        });
  }

  private void showRoutes() {
    LinearLayout content = page("Routes", "Your GPX library · offline routing built in");
    LinearLayout actions = row();
    actions.addView(
        button("Add files", false, () -> openDocument(IMPORT_GPX)),
        new LinearLayout.LayoutParams(0, dp(52), 1));
    actions.addView(
        button(
            "Create route",
            true,
            () -> {
              planning = true;
              waypoints.clear();
              map.waypoints = waypoints;
              showPage("Map");
            }),
        new LinearLayout.LayoutParams(0, dp(52), 1));
    content.addView(actions);
    content.addView(button("Routing data", false, this::routingDataMenu));
    content.addView(spacer(20));
    if (routeEntries.isEmpty())
      content.addView(
          label(
              "Open GPX files with Cairn from Android, or use Add files. Each file stays in your"
                  + " library.",
              14,
              MUTED,
              false));
    String active = store.prefs.getString("activeRouteId", "");
    for (Store.RouteEntry entry : routeEntries) {
      String detail =
          entry.id.equals(active)
              ? (store.prefs.getBoolean("follow", false) ? " · following" : " · selected")
              : "";
      content.addView(
          button(
              entry.title + detail + (entry.visible ? "" : " · hidden"),
              false,
              () -> routeActions(entry)));
    }
    content.addView(spacer(24));
    content.addView(label("RECORDED HIKES", 11, MUTED, true));
    LinkedHashMap<String, String> treks = store.treks();
    if (treks.isEmpty())
      content.addView(label("Your recordings will appear here.", 14, MUTED, false));
    if (!treks.isEmpty())
      content.addView(label("Swipe left on a hike to remove it · tap to export", 12, MUTED, false));
    for (Map.Entry<String, String> trek : treks.entrySet()) {
      TextView recording =
          button(
              trek.getValue() + "  ↗",
              false,
              () ->
                  io.execute(
                      () -> {
                        List<Point> points = store.read(trek.getKey());
                        main.post(
                            () -> {
                              if (!destroyed) export(points, trek.getKey() + ".gpx");
                            });
                      }));
      if (TrackingService.running
          && trek.getKey().equals(store.prefs.getString("currentTrek", ""))) {
        content.addView(recording);
      } else {
        Runnable remove = () -> removeTrek(trek.getKey());
        TextView removeButton = button("Remove", false, remove);
        removeButton.setContentDescription("Remove " + trek.getValue());
        SwipeToRemoveView swipe = new SwipeToRemoveView(this, recording, removeButton, remove);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        content.addView(swipe, params);
      }
    }
    content.addView(spacer(20));
    content.addView(
        label(
            "BRouter 1.7.9 · routing © OpenStreetMap contributors (ODbL). Routing data and Plan IGN"
                + " tiles are separate downloads.",
            12,
            MUTED,
            false));
    TextView licences = label("Licences & attribution", 12, ACCENT, true);
    licences.setPadding(0, dp(16), 0, dp(16));
    licences.setOnClickListener(
        v -> {
          try (InputStream input = getAssets().open("routing/LICENSE-BROUTER.txt")) {
            message(
                new String(MapCatalog.bytes(input), java.nio.charset.StandardCharsets.UTF_8)
                    + "\n"
                    + "Routing data: © OpenStreetMap contributors, Open Database Licence (ODbL)."
                    + " https://www.openstreetmap.org/copyright\n"
                    + "Plan IGN: © IGN, Licence Ouverte 2.0.\n"
                    + "Source and update information: https://www.data.gouv.fr/datasets/plan-ign\n"
                    + "Licence: https://github.com/etalab/licence-ouverte/blob/master/LO.md");
          } catch (IOException error) {
            message(error.getMessage());
          }
        });
    content.addView(licences);
    TextView ignCredit = label("", 12, MUTED, false);
    ignCredit.setText(
        android.text.Html.fromHtml(
            "Plan IGN · © IGN · <a"
                + " href=\"https://github.com/etalab/licence-ouverte/blob/master/LO.md\">Licence"
                + " Ouverte 2.0</a><br><a"
                + " href=\"https://www.data.gouv.fr/datasets/plan-ign\">Source and update"
                + " information</a>",
            android.text.Html.FROM_HTML_MODE_LEGACY));
    ignCredit.setLinkTextColor(ACCENT);
    ignCredit.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    content.addView(ignCredit);
    content.addView(spacer(32));
    content.addView(appUpdates.view());
  }

  private void removeTrek(String id) {
    io.execute(
        () -> {
          try {
            boolean current = id.equals(store.prefs.getString("currentTrek", ""));
            store.deleteTrek(id);
            main.post(
                () -> {
                  if (destroyed) return;
                  if (current && !store.prefs.contains("currentTrek")) {
                    track = new ArrayList<>();
                    map.track = track;
                    map.invalidate();
                  }
                  loadPoints();
                  if ("Routes".equals(currentPage)) showPage("Routes");
                });
          } catch (RuntimeException error) {
            main.post(
                () -> {
                  if (destroyed) return;
                  if ("Routes".equals(currentPage)) showPage("Routes");
                  message(error.getMessage());
                });
          }
        });
  }

  private void routeActions(Store.RouteEntry entry) {
    new AlertDialog.Builder(this)
        .setTitle(entry.title)
        .setItems(
            new String[] {
              "Show and select",
              "Follow route",
              "Save offline corridor",
              "Export GPX",
              entry.visible ? "Hide on map" : "Show on map",
              "Rename",
              "Remove from library"
            },
            (d, which) -> {
              if (which <= 2) {
                io.execute(
                    () -> {
                      List<Point> points = store.activateRoute(entry.id);
                      store.routeVisible(entry.id, true);
                      if (which == 1) store.prefs.edit().putBoolean("follow", true).apply();
                      main.post(
                          () -> {
                            if (destroyed) return;
                            routeRevision++;
                            route = points;
                            map.route = points;
                            map.center(points.get(0));
                            updateService();
                            showPage("Map");
                            reloadLibrary();
                            if (which == 2) prepareDownload(true);
                          });
                    });
              } else if (which == 3)
                io.execute(
                    () -> {
                      List<Point> points = store.read(entry.id);
                      main.post(
                          () -> {
                            if (!destroyed) export(points, entry.title + ".gpx");
                          });
                    });
              else if (which == 4)
                io.execute(
                    () -> {
                      store.routeVisible(entry.id, !entry.visible);
                      main.post(this::reloadLibrary);
                    });
              else if (which == 5) {
                EditText title = new EditText(this);
                title.setSingleLine(true);
                title.setText(entry.title);
                new AlertDialog.Builder(this)
                    .setTitle("Route name")
                    .setView(title)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(
                        "Save",
                        (a, b) -> {
                          String name = title.getText().toString().trim();
                          if (!name.isEmpty())
                            io.execute(
                                () -> {
                                  store.renameRoute(entry.id, name);
                                  main.post(this::reloadLibrary);
                                });
                        })
                    .show();
              } else
                new AlertDialog.Builder(this)
                    .setTitle("Remove " + entry.title + "?")
                    .setMessage(
                        "Remove this saved route from Cairn? Your original file stays in Android.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton(
                        "Remove",
                        (a, b) ->
                            io.execute(
                                () -> {
                                  store.deleteRoute(entry.id);
                                  main.post(
                                      () -> {
                                        loadPoints();
                                        reloadLibrary();
                                        updateService();
                                      });
                                }))
                    .show();
            })
        .show();
  }

  private void mapMenu() {
    LinearLayout content = column();
    content.setPadding(dp(20), dp(8), dp(20), dp(16));
    content.addView(
        label(
            "Plan IGN tiles viewed online stay on this phone automatically. Only visited zoom"
                + " levels are kept; download a route corridor for complete coverage at your chosen"
                + " detail.",
            14,
            MUTED,
            false));
    downloadStatus =
        label(DownloadService.running ? "Map download in progress…" : "", 13, ACCENT, false);
    content.addView(downloadStatus);
    if (DownloadService.running)
      content.addView(
          button(
              "Pause map download",
              false,
              () ->
                  startService(
                      new Intent(this, DownloadService.class).setAction(DownloadService.PAUSE))));
    for (PlanIgnStore.Pack pack : mapStore.packs())
      content.addView(
          button(
              pack.name + "\n" + pack.description(),
              false,
              () -> {
                if (mapDialog != null) mapDialog.dismiss();
                new AlertDialog.Builder(this)
                    .setTitle(pack.name)
                    .setItems(
                        new String[] {
                          "Show area", "Resume / check updates", "Remove downloaded area"
                        },
                        (d, choice) -> {
                          if (choice == 0) {
                            selectPlanIgn();
                            map.center(pack.center);
                            showPage("Map");
                          } else if (choice == 1) {
                            if (DownloadService.running) {
                              message("Pause the current download first.");
                              return;
                            }
                            io.execute(
                                () -> {
                                  if ("complete".equals(pack.state)) mapStore.refresh(pack.id);
                                  main.post(() -> beginDownload(pack.id));
                                });
                          } else
                            new AlertDialog.Builder(this)
                                .setMessage("Remove " + pack.name + " from this phone?")
                                .setNegativeButton("Keep", null)
                                .setPositiveButton(
                                    "Remove",
                                    (a, b) ->
                                        io.execute(
                                            () -> {
                                              mapStore.remove(pack.id);
                                              main.post(
                                                  () -> {
                                                    if (!destroyed) {
                                                      map.refreshTiles(true);
                                                      mapMenu();
                                                    }
                                                  });
                                            }))
                                .show();
                        })
                    .show();
              }));
    if (offlineMap != null && map.tileSource() != offlineMap)
      content.addView(
          button(
              "Show imported MBTiles",
              false,
              () -> {
                setMapOnline(false);
                store.prefs.edit().putBoolean("importedMap", true).apply();
                map.source(offlineMap);
                if (mapDialog != null) mapDialog.dismiss();
              }));
    if (map.tileSource() != ignMap)
      content.addView(
          button(
              "Switch to Plan IGN",
              false,
              () -> {
                selectPlanIgn();
                if (mapDialog != null) mapDialog.dismiss();
              }));
    content.addView(
        button(
            "Clear viewed-map cache",
            false,
            () -> {
              if (TrackingService.running || DownloadService.running) {
                message("Finish recording or pause the map download first.");
                return;
              }
              new AlertDialog.Builder(this)
                  .setMessage(
                      "Remove automatically kept map tiles? Downloaded route corridors and other"
                          + " saved packs will stay available.")
                  .setNegativeButton("Keep", null)
                  .setPositiveButton(
                      "Clear",
                      (d, w) -> {
                        setMapOnline(false);
                        if (mapDialog != null) mapDialog.dismiss();
                        io.execute(
                            () -> {
                              mapStore.clearViewed();
                              main.post(
                                  () -> {
                                    if (!destroyed) {
                                      map.refreshTiles(true);
                                      mapMenu();
                                    }
                                  });
                            });
                      })
                  .show();
            }));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    mapDialog =
        new AlertDialog.Builder(this)
            .setTitle("Saved maps")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create();
    mapDialog.show();
  }

  private void routingDataMenu() {
    routingDataMenu(false);
  }

  private void routingDataMenu(boolean aroundMap) {
    boolean hasPlanningPoints = planning && !waypoints.isEmpty();
    boolean usingMapCenter =
        RoutingRegionMenu.usesMapCenter(aroundMap, hasPlanningPoints, !route.isEmpty());
    List<Point> points =
        usingMapCenter
            ? Collections.singletonList(map.center())
            : hasPlanningPoints
                ? new ArrayList<>(waypoints)
                : new ArrayList<>(route);
    List<String> needed;
    try {
      needed = RoutingData.missing(this, points);
    } catch (IllegalArgumentException error) {
      message(error.getMessage());
      return;
    }
    String[] downloaded =
        RoutingData.directory(this).list((dir, name) -> RoutingData.validName(name));
    String text =
        "Walking engine is included. Graph data covers 5° regions and can be hundreds of MB."
            + " Prepare over Wi-Fi before hiking.\n\n";
    text +=
        "For "
            + (usingMapCenter
                ? "the map center"
                : hasPlanningPoints ? "your planning points" : "the selected GPX")
            + ":\n"
            + (needed.isEmpty() ? "Ready offline" : String.join("\n", needed));
    text +=
        "\n\nInstalled: "
            + (downloaded == null || downloaded.length == 0
                ? "none"
                : String.join(", ", downloaded));
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle("Offline routing data")
            .setMessage(text)
            .setNegativeButton("Close", null);
    if (RoutingDownloadService.running)
      builder.setPositiveButton(
          "Pause download",
          (d, w) ->
              startService(new Intent(this, RoutingDownloadService.class).setAction("pause")));
    else if (!needed.isEmpty())
      builder.setPositiveButton(
          "Download / resume",
          (d, w) -> {
            if (TrackingService.running) {
              message("Finish the hike before downloading routing data.");
              return;
            }
            setMapOnline(false);
            if (DownloadService.running)
              startService(
                  new Intent(this, DownloadService.class).setAction(DownloadService.PAUSE));
            startForegroundService(
                new Intent(this, RoutingDownloadService.class)
                    .putStringArrayListExtra("regions", new ArrayList<>(needed)));
          });
    builder.setNeutralButton(
        "Regions",
        (d, w) -> {
          List<RoutingRegionMenu.Item> items =
              RoutingRegionMenu.items(usingMapCenter, downloaded);
          String[] labels = new String[items.size()];
          for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;
          new AlertDialog.Builder(this)
              .setTitle("Routing regions")
              .setItems(
                  labels,
                  (a, which) -> {
                    RoutingRegionMenu.Item item = items.get(which);
                    if (item.action == RoutingRegionMenu.Action.USE_MAP_CENTER) {
                      routingDataMenu(true);
                      return;
                    }
                    if (TrackingService.running
                        || calculationRunning
                        || RoutingDownloadService.running) {
                      message(
                          "Finish the current recording, calculation, or routing download first.");
                      return;
                    }
                    boolean discard = item.action == RoutingRegionMenu.Action.DISCARD_PAUSED;
                    String prompt =
                        discard
                            ? "Discard paused routing downloads? Finished regions stay available."
                            : "Remove " + item.region + "? Download it again later to update it.";
                    new AlertDialog.Builder(this)
                        .setMessage(prompt)
                        .setNegativeButton("Keep", null)
                        .setPositiveButton(
                            "Remove",
                            (b, c) -> {
                              if (discard) {
                                File[] files = RoutingData.directory(this).listFiles();
                                if (files != null)
                                  for (File file : files)
                                    if (file.getName()
                                        .matches(
                                            "[EW][0-9]+_[NS][0-9]+\\.rd5\\.(part|resume|resume\\.tmp|import)"))
                                      file.delete();
                              } else
                                new File(RoutingData.directory(this), item.region).delete();
                              routingDataMenu(aroundMap);
                            })
                        .show();
                  })
              .show();
        });
    builder.show();
  }

  private void selectPlanIgn() {
    store.prefs.edit().putBoolean("importedMap", false).apply();
    if (map.tileSource() != ignMap) map.source(ignMap, false);
  }

  private void setMapOnline(boolean value) {
    if (value && TrackingService.running) {
      message(
          "Online preview stays off during recording. Finish the hike before preparing new maps.");
      return;
    }
    ignMap.online(value);
    if (value) {
      if (RoutingDownloadService.running)
        startService(new Intent(this, RoutingDownloadService.class).setAction("pause"));
      if (DownloadService.running)
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.PAUSE));
      selectPlanIgn();
      map.reloadOnlineTiles();
    } else map.refreshTiles(false);
    updatePreviewIcon();
  }

  private void updatePreviewIcon() {
    if (previewButton == null) return;
    previewButton.setImageDrawable(new MapIcon(ignMap.online() ? MapIcon.ONLINE : MapIcon.OFFLINE));
    String hint =
        ignMap.online()
            ? "Online. Visible tiles are kept. Tap for offline mode"
            : "Offline maps. Tap to load and keep visible Plan IGN tiles";
    previewButton.setContentDescription(hint);
    previewButton.setTooltipText(hint);
    previewButton.setSelected(ignMap.online());
  }

  private void prepareDownload(boolean corridor) {
    if (TrackingService.running) {
      message("Finish recording before downloading maps.");
      return;
    }
    if (corridor && route.isEmpty()) {
      message("Import or create a GPX route first.");
      return;
    }
    if (!corridor && (map.getWidth() == 0 || map.getHeight() == 0)) {
      message("Open the map and choose your area first.");
      return;
    }
    LinearLayout content = column();
    content.setPadding(dp(24), dp(8), dp(24), dp(12));
    EditText name = new EditText(this);
    name.setSingleLine(true);
    name.setText(corridor ? "Route corridor" : "Plan IGN area");
    content.addView(name);
    content.addView(label("Map detail", 14, TEXT, true));
    Spinner detail = new Spinner(this);
    detail.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"Standard", "Detailed", "Maximum detail"}));
    detail.setSelection(1);
    content.addView(detail);
    EditText width = new EditText(this);
    width.setInputType(
        android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
    width.setText("1.0");
    if (corridor) {
      content.addView(label("Kilometres on each side of the route", 14, TEXT, true));
      content.addView(width);
    }
    content.addView(
        label(
            corridor
                ? "Save terrain around the entire GPX, including the spaces between its points."
                : "Save the area currently visible on your map. Use Preview Plan IGN to change it"
                    + " first.",
            13,
            MUTED,
            false));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(corridor ? "Save route corridor" : "Save map area")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Estimate download", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      double radius;
                      try {
                        radius =
                            Double.parseDouble(width.getText().toString().replace(',', '.')) * 1000;
                        if (!Double.isFinite(radius) || radius < 100 || radius > 10000)
                          throw new NumberFormatException();
                      } catch (NumberFormatException e) {
                        width.setError("Choose 0.1–10 km");
                        return;
                      }
                      String title = name.getText().toString().trim();
                      if (title.isEmpty()) {
                        name.setError("Give this area a name");
                        return;
                      }
                      if (title.length() > 80) title = title.substring(0, 80);
                      String savedTitle = title;
                      double[] bounds = map.bounds();
                      List<Point> points = new ArrayList<>(route);
                      int zoom = new int[] {16, 17, 19}[detail.getSelectedItemPosition()];
                      dialog.dismiss();
                      io.execute(
                          () -> {
                            try {
                              TilePlanner.Plan plan =
                                  corridor
                                      ? TilePlanner.corridor(points, radius, zoom)
                                      : TilePlanner.area(
                                          bounds[0], bounds[1], bounds[2], bounds[3], zoom);
                              main.post(
                                  () -> {
                                    if (destroyed) return;
                                    long available =
                                        new android.os.StatFs(getFilesDir().getPath())
                                            .getAvailableBytes();
                                    String estimate =
                                        String.format(
                                            Locale.getDefault(),
                                            (corridor
                                                ? "Corridor: %1$.1f km on each side · detail zoom %2$d\n\n"
                                                : "Map area · detail zoom %2$d\n\n")
                                                + "%3$,d tiles · roughly %4$.0f MB\n"
                                                + "%5$.0f MB available on this phone.\n\n"
                                                + "Detailed tiles download first; overview tiles"
                                                + " cover larger areas at lower zoom levels.\n\n"
                                                + "Downloads can be paused and resumed. Actual size"
                                                + " varies; maximum detail needs much more"
                                                + " storage.",
                                            radius / 1000,
                                            zoom,
                                            plan.tiles.size(),
                                            plan.estimatedBytes() / 1e6,
                                            available / 1e6);
                                    new AlertDialog.Builder(this)
                                        .setTitle(savedTitle)
                                        .setMessage(estimate)
                                        .setNegativeButton("Cancel", null)
                                        .setPositiveButton(
                                            "Download",
                                            (choice, which) ->
                                                io.execute(
                                                    () -> {
                                                      try {
                                                        String id =
                                                            mapStore.create(savedTitle, plan);
                                                        main.post(() -> beginDownload(id));
                                                      } catch (RuntimeException e) {
                                                        main.post(
                                                            () ->
                                                                message(
                                                                    "Could not prepare download: "
                                                                        + e.getMessage()));
                                                      }
                                                    }))
                                        .show();
                                  });
                            } catch (IllegalArgumentException e) {
                              main.post(() -> message(e.getMessage()));
                            }
                          });
                    }));
    dialog.show();
  }

  private void beginDownload(String id) {
    if (destroyed) return;
    if (TrackingService.running) {
      message("The map selection is saved. Resume the download after recording.");
      return;
    }
    if (DownloadService.running) {
      message("The selection is saved. Pause the current download before starting another.");
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      pendingDownload = id;
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 102);
      return;
    }
    launchDownload(id);
  }

  private void launchDownload(String id) {
    if (destroyed || id == null) return;
    setMapOnline(false);
    if (RoutingDownloadService.running)
      startService(new Intent(this, RoutingDownloadService.class).setAction("pause"));
    if (TrackingService.running || DownloadService.running) {
      message(
          "Resume this saved download from the map's layers button when the current task has"
              + " finished.");
      return;
    }
    try {
      startForegroundService(new Intent(this, DownloadService.class).putExtra("pack", id));
      main.postDelayed(
          () -> {
            if (!destroyed && "Routes".equals(currentPage)) showPage("Routes");
          },
          400);
    } catch (RuntimeException e) {
      message("Download could not start: " + e.getMessage());
    }
  }

  private void refreshStatus() {
    if ("Map".equals(currentPage) && !planning && renderedRecording != TrackingService.running) {
      showPage("Map");
      return;
    }
    if (intervalChip != null)
      intervalChip.setText(getString(R.string.gps_interval, store.intervalMillis() / 1000));
    if (status != null) {
      String text = String.format(Locale.getDefault(), "%.1f km", Geo.length(track) / 1000);
      if (map.position == null) text += "\nAwaiting GPS";
      else text += "\n±" + Math.round(map.position.accuracy) + " m";
      status.setText(text);
    }
    if (recordButton != null)
      recordButton.setText(TrackingService.running ? "Finish" : "Start hike");
  }

  private void batterySettings() {
    LinearLayout content = column();
    content.setPadding(dp(24), dp(8), dp(24), dp(12));
    content.addView(label("Seconds between requested GPS updates", 15, TEXT, true));
    EditText seconds = new EditText(this);
    seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    seconds.setText(String.format(Locale.getDefault(), "%d", store.intervalMillis() / 1000));
    seconds.setSelectAllOnFocus(true);
    content.addView(seconds);
    LinearLayout presets = row();
    for (int n : new int[] {15, 60, 300})
      presets.addView(
          button(
              n + "s", false, () -> seconds.setText(String.format(Locale.getDefault(), "%d", n))),
          new LinearLayout.LayoutParams(0, dp(48), 1));
    content.addView(presets);
    content.addView(
        label(
            "Longer intervals reduce requested fixes and make track detail and alerts less"
                + " frequent. Android controls actual delivery. This build does not promise that"
                + " the GPS chip sleeps between fixes.",
            13,
            MUTED,
            false));
    Switch alerts = new Switch(this);
    alerts.setText(R.string.off_route_alerts);
    alerts.setMinHeight(dp(56));
    alerts.setChecked(store.prefs.getBoolean("offRoute", true));
    content.addView(alerts);
    content.addView(
        label(
            "Alerts need two clear off-route fixes. With a 5-minute interval they can take around"
                + " 10 minutes or longer. Turn alerts are not implemented in this first build.",
            13,
            MUTED,
            false));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Battery & GPS")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        int n = Integer.parseInt(seconds.getText().toString());
                        if (n < 1 || n > 3600) throw new NumberFormatException();
                        store
                            .prefs
                            .edit()
                            .putInt("intervalSeconds", n)
                            .putBoolean("offRoute", alerts.isChecked())
                            .apply();
                        updateService();
                        refreshStatus();
                        dialog.dismiss();
                      } catch (NumberFormatException e) {
                        seconds.setError("Choose 1–3600 seconds");
                      }
                    }));
    dialog.show();
  }

  private void toggleRecording() {
    if (TrackingService.running) {
      new AlertDialog.Builder(this)
          .setTitle("Finish this hike?")
          .setMessage("Your track stays saved on this phone.")
          .setNegativeButton("Keep recording", null)
          .setPositiveButton(
              "Finish hike",
              (d, w) -> {
                startService(
                    new Intent(this, TrackingService.class).setAction(TrackingService.STOP));
                main.postDelayed(this::refreshStatus, 300);
              })
          .show();
      return;
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      String[] permissions =
          Build.VERSION.SDK_INT >= 33
              ? new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
              }
              : new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
              };
      requestPermissions(permissions, 100);
      return;
    }
    if (!getSystemService(LocationManager.class).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      new AlertDialog.Builder(this)
          .setTitle("Enable GPS to record")
          .setMessage("Offline maps still work with location switched off.")
          .setNegativeButton("Cancel", null)
          .setPositiveButton(
              "Location settings",
              (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
          .show();
      return;
    }
    cancelLocate();
    cancelSearch();
    router.cancel();
    dataGeneration++;
    track = new ArrayList<>();
    map.track = track;
    map.position = null;
    setMapOnline(false);
    if (DownloadService.running) stopService(new Intent(this, DownloadService.class));
    stopService(new Intent(this, RoutingDownloadService.class));
    try {
      startForegroundService(new Intent(this, TrackingService.class).setAction("start"));
      main.postDelayed(this::refreshStatus, 500);
    } catch (RuntimeException e) {
      message(e.getMessage());
    }
  }

  private void updateService() {
    if (TrackingService.running)
      startService(new Intent(this, TrackingService.class).setAction(TrackingService.UPDATE));
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 100) {
      if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
          == PackageManager.PERMISSION_GRANTED) toggleRecording();
      else message("Precise location permission is needed for GPS track recording.");
    }
    if (code == 101
        && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED)) locate();
    if (code == 102) {
      String id = pendingDownload;
      pendingDownload = null;
      launchDownload(id);
    }
  }

  private void locate() {
    if (TrackingService.running) {
      cancelLocate();
      if (map.position != null) {
        map.center(map.position);
        showLocationAccuracy(map.position, true);
      } else
        Toast.makeText(this, "Waiting for your next scheduled GPS fix", Toast.LENGTH_LONG).show();
      return;
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          101);
      return;
    }
    if (manualLocation == null) manualLocation = new ManualLocation(this);
    Toast.makeText(this, "Finding your position…", Toast.LENGTH_SHORT).show();
    manualLocation.start(
        new ManualLocation.Callback() {
          @Override
          public void found(Point point, boolean cached) {
            map.position = point;
            map.center(point);
            showLocationAccuracy(point, cached);
          }

          @Override
          public void unavailable(String reason) {
            message(reason);
          }
        });
  }

  private void showLocationAccuracy(Point point, boolean cached) {
    long seconds = Math.max(0, (System.currentTimeMillis() - point.time) / 1000);
    String text = String.format(Locale.getDefault(), "Position ±%.0f m", point.accuracy);
    if (cached) text += " · " + seconds + "s ago";
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  private void cancelLocate() {
    if (manualLocation != null) manualLocation.cancel();
  }

  private void openDocument(int code) {
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(Intent.CATEGORY_OPENABLE),
        IMPORT_GPX);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleOpenIntent(intent);
  }

  @SuppressWarnings("deprecation")
  private void handleOpenIntent(Intent intent) {
    if (intent == null
        || !(Intent.ACTION_VIEW.equals(intent.getAction())
            || Intent.ACTION_SEND.equals(intent.getAction())
            || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
    importOfferedFiles(intent);
    setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
  }

  @SuppressWarnings("deprecation")
  private void importOfferedFiles(Intent intent) {
    LinkedHashSet<Uri> uris = new LinkedHashSet<>();
    if (intent.getData() != null) uris.add(intent.getData());
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (stream instanceof Uri) uris.add((Uri) stream);
    }
    if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      ArrayList<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (streams != null)
        for (Object stream : streams) if (stream instanceof Uri) uris.add((Uri) stream);
    }
    ClipData clip = intent.getClipData();
    if (clip != null)
      for (int i = 0; i < clip.getItemCount(); i++)
        if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
    if (uris.isEmpty()) {
      message("Choose a local GPX, map-source XML, MBTiles, or routing RD5 file.");
      return;
    }
    if (uris.size() > 50) {
      message("Add up to 50 files at once.");
      return;
    }
    for (Uri uri : uris) {
      if (!("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) {
        message("Only local file and content URIs are supported.");
        continue;
      }
      importFile(uri);
    }
  }

  private String fileName(Uri uri) {
    String name = null;
    if ("content".equals(uri.getScheme())) {
      try (android.database.Cursor cursor =
          getContentResolver()
              .query(
                  uri,
                  new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                  null,
                  null,
                  null)) {
        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
      } catch (RuntimeException ignored) {
      }
    }
    if (name == null) name = uri.getLastPathSegment();
    return name == null ? "Imported route" : name;
  }

  private void importFile(Uri uri) {
    io.execute(
        () -> {
          try {
            String filename = fileName(uri), lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mbtiles")) {
              if (TrackingService.running)
                throw new IOException("Finish recording before importing large map files");
              importMap(uri);
              return;
            }
            if (lower.endsWith(".rd5")) {
              if (TrackingService.running || calculationRunning || RoutingDownloadService.running)
                throw new IOException(
                    "Finish the current recording, calculation, or routing download first");
              if (!RoutingData.validName(filename))
                throw new IOException("Keep the original regional filename, such as E5_N45.rd5");
              File temporary = new File(RoutingData.directory(this), filename + ".import");
              try {
                try (InputStream input = getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(temporary)) {
                  if (input == null) throw new IOException("Cannot read routing file");
                  byte[] buffer = new byte[65536];
                  long total = 0;
                  int n;
                  while ((n = input.read(buffer)) != -1) {
                    if (TrackingService.running || destroyed || (total += n) > 1500L * 1024 * 1024)
                      throw new IOException("Routing import canceled or too large");
                    output.write(buffer, 0, n);
                  }
                }
                RoutingData.validate(temporary);
                java.nio.file.Files.move(
                    temporary.toPath(),
                    new File(RoutingData.directory(this), filename).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              } finally {
                temporary.delete();
              }
              main.post(
                  () -> {
                    if (!destroyed)
                      Toast.makeText(this, "Routing region ready offline", Toast.LENGTH_LONG)
                          .show();
                  });
              return;
            }
            byte[] bytes;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
              if (input == null) throw new IOException("Cannot read this file");
              bytes = MapCatalog.bytes(input);
            }
            if ("gpx".equals(MapCatalog.root(bytes)))
              importRoute(
                  Gpx.read(new ByteArrayInputStream(bytes)),
                  filename.replaceFirst("(?i)\\.(gpx|xml)$", ""));
            else {
              MapCatalog catalog = MapCatalog.read(bytes);
              main.post(
                  () -> {
                    if (destroyed) return;
                    if (catalog.supported > 0) {
                      setMapOnline(false);
                      selectPlanIgn();
                      showPage("Map");
                    }
                    message(
                        catalog.supported > 0
                            ? "Plan IGN is ready. "
                                + (catalog.sources - catalog.supported)
                                + " other providers in this XML are unsupported. Tap the cloud to"
                                + " load maps."
                            : "Read "
                                + catalog.sources
                                + " sources; none is the supported public Plan IGN layer.");
                  });
            }
          } catch (Exception e) {
            main.post(() -> message("Could not add file: " + e.getMessage()));
          }
        });
  }

  private void importRoute(List<Point> loaded, String title) {
    store.saveRoute(loaded, title.isEmpty() ? "Imported route" : title);
    main.post(
        () -> {
          if (destroyed) return;
          routeRevision++;
          route = loaded;
          map.route = route;
          map.center(route.get(0));
          updateService();
          showPage("Map");
          reloadLibrary();
        });
  }

  private void export(List<Point> points, String filename) {
    if (points.isEmpty()) {
      message("There are no track points to export yet.");
      return;
    }
    exportPoints = new ArrayList<>(points);
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_TITLE, filename),
        EXPORT_GPX);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request != EXPORT_GPX) {
      importOfferedFiles(data);
      return;
    }
    Uri uri = data.getData();
    List<Point> snapshot = exportPoints;
    if (uri == null || snapshot == null) {
      message("Export was interrupted. Please export again.");
      return;
    }
    io.execute(
        () -> {
          try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Cannot write GPX");
            Gpx.write(output, snapshot, "Cairn hike");
            main.post(
                () -> {
                  if (!destroyed) Toast.makeText(this, "GPX exported", Toast.LENGTH_SHORT).show();
                });
          } catch (Exception error) {
            main.post(() -> message("Could not export: " + error.getMessage()));
          }
        });
  }

  private void importMap(Uri uri) throws IOException {
    File temporary = new File(getFilesDir(), "import.mbtiles");
    try {
      try (InputStream in = getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(temporary)) {
        if (in == null) throw new IOException("Cannot read map file");
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      }
      try (OfflineMap validation = new OfflineMap(temporary)) {}
      main.post(
          () -> {
            if (destroyed) {
              temporary.delete();
              return;
            }
            map.source(null);
            if (offlineMap != null) {
              offlineMap.close();
              offlineMap = null;
            }
            File target = new File(getFilesDir(), "offline.mbtiles");
            try {
              java.nio.file.Files.move(
                  temporary.toPath(),
                  target.toPath(),
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              offlineMap = new OfflineMap(target);
              store.prefs.edit().putBoolean("importedMap", true).apply();
              map.source(offlineMap);
              showPage("Map");
            } catch (Exception e) {
              message("Could not store map: " + e.getMessage());
            }
          });
    } catch (RuntimeException | IOException e) {
      temporary.delete();
      throw new IOException(e.getMessage(), e);
    }
  }

  private void refreshPlanning() {
    map.waypoints = waypoints;
    map.invalidate();
    if (planStatus != null)
      planStatus.setText(
          calculationRunning
              ? "Calculating on this device…"
              : waypoints.size() + " points · long press to add another");
  }

  private void addCoordinates() {
    LinearLayout content = column();
    content.setPadding(dp(24), 0, dp(24), 0);
    EditText input = new EditText(this);
    input.setHint("45.9237, 6.8694");
    input.setSingleLine(true);
    content.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Add latitude, longitude")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        if (calculationRunning) return;
                        String[] value = input.getText().toString().split(",");
                        if (value.length != 2) throw new IllegalArgumentException();
                        waypoints.add(
                            new Point(
                                Double.parseDouble(value[0].trim()),
                                Double.parseDouble(value[1].trim())));
                        refreshPlanning();
                        dialog.dismiss();
                      } catch (IllegalArgumentException e) {
                        input.setError("Enter valid latitude, longitude in decimal degrees");
                      }
                    }));
    dialog.show();
  }

  private void calculate() {
    if (calculationRunning) return;
    if (waypoints.size() < 2) {
      message("Add at least two points to calculate a route.");
      return;
    }
    calculationRunning = true;
    refreshPlanning();
    router.calculate(
        new ArrayList<>(waypoints),
        (points, error) -> {
          if (destroyed) return;
          calculationRunning = false;
          if (error != null) {
            refreshPlanning();
            message(error);
            return;
          }
          io.execute(
              () -> {
                try {
                  store.saveRoute(points, "Planned route " + java.time.LocalDate.now());
                  main.post(
                      () -> {
                        if (destroyed) return;
                        routeRevision++;
                        route = points;
                        map.route = route;
                        waypoints.clear();
                        planning = false;
                        map.waypoints = waypoints;
                        updateService();
                        showPage("Map");
                        reloadLibrary();
                      });
                } catch (RuntimeException e) {
                  main.post(
                      () -> {
                        refreshPlanning();
                        message("Could not save route: " + e.getMessage());
                      });
                }
              });
        });
  }

  private void searchPlaces() {
    if (TrackingService.running) {
      message(
          "Finish recording before searching online. Offline maps and saved routes remain"
              + " available.");
      return;
    }
    cancelSearch();
    LinearLayout content = column();
    content.setPadding(dp(24), dp(8), dp(24), dp(8));
    EditText query = new EditText(this);
    query.setHint("Town, mountain, or address");
    query.setContentDescription("Place name or address");
    query.setSingleLine(true);
    query.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
    query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    content.addView(query);
    TextView feedback =
        label("Online search · uses your phone’s geocoding service", 12, MUTED, false);
    content.addView(feedback);
    LinearLayout results = column();
    ScrollView scroll = new ScrollView(this);
    scroll.addView(results);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(220)));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Search places")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", null)
            .create();
    searchDialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          placeSearch.cancel();
          if (searchDialog == dialog) searchDialog = null;
        });
    PlaceSearch.Callback callback =
        new PlaceSearch.Callback() {
          @Override
          public void results(List<Address> places) {
            if (!dialog.isShowing() || destroyed || TrackingService.running) return;
            results.removeAllViews();
            feedback.setText(
                places.isEmpty()
                    ? "No places found. Try a nearby town or a more specific name."
                    : "Choose a place to show on the map");
            for (Address place : places) {
              results.addView(
                  button(
                      PlaceSearch.label(place),
                      false,
                      () -> {
                        cancelLocate();
                        double[] camera = map.camera();
                        map.restoreCamera(
                            new double[] {
                              place.getLatitude(), place.getLongitude(), Math.max(13, camera[2])
                            });
                        dialog.dismiss();
                      }));
            }
          }

          @Override
          public void unavailable(String reason) {
            if (!dialog.isShowing() || destroyed || TrackingService.running) return;
            feedback.setText(reason);
          }
        };
    query.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          public void onTextChanged(CharSequence text, int start, int before, int count) {
            results.removeAllViews();
            query.setError(null);
            String value = text.toString().trim();
            feedback.setText(
                value.length() < 2 ? "Type at least 2 characters to find places" : "Searching…");
            placeSearch.suggest(value, callback);
          }

          public void afterTextChanged(android.text.Editable text) {}
        });
    dialog.setOnShowListener(
        d -> {
          query.requestFocus();
          dialog
              .getWindow()
              .setSoftInputMode(
                  WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                      | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          Runnable submit =
              () -> {
                String text = query.getText().toString().trim();
                if (text.isEmpty()) {
                  query.setError("Enter a place name");
                  return;
                }
                results.removeAllViews();
                feedback.setText("Searching…");
                placeSearch.search(text, callback);
              };
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
          query.setOnEditorActionListener(
              (v, action, event) -> {
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
                submit.run();
                return true;
              });
        });
    dialog.show();
  }

  private void cancelSearch() {
    if (placeSearch != null) placeSearch.cancel();
    if (searchDialog != null) searchDialog.dismiss();
  }

  private void message(String text) {
    if (!destroyed && !isFinishing())
      new AlertDialog.Builder(this)
          .setMessage(text == null ? "Operation could not be completed" : text)
          .setPositiveButton("OK", null)
          .show();
  }

  private LinearLayout column() {
    LinearLayout value = new LinearLayout(this);
    value.setOrientation(LinearLayout.VERTICAL);
    return value;
  }

  private LinearLayout row() {
    LinearLayout value = new LinearLayout(this);
    value.setOrientation(LinearLayout.HORIZONTAL);
    GradientDrawable gap = new GradientDrawable();
    gap.setColor(Color.TRANSPARENT);
    gap.setSize(dp(8), 1);
    value.setDividerDrawable(gap);
    value.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    return value;
  }

  private TextView label(String text, int size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    view.setLineSpacing(dp(3), 1);
    return view;
  }

  private TextView button(String text, boolean primary, Runnable action) {
    TextView view = label(text, 15, primary ? ON_PRIMARY : ACCENT, true);
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(52));
    view.setPadding(dp(12), dp(10), dp(12), dp(10));
    view.setBackground(
        buttonBackground(primary ? PRIMARY : SURFACE, primary ? PRIMARY_BORDER : OUTLINE, 16));
    view.setOnClickListener(v -> action.run());
    view.setFocusable(true);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(8);
    view.setLayoutParams(params);
    return view;
  }

  private ImageButton iconButton(int icon, String description, Runnable action) {
    ImageButton view = new ImageButton(this);
    view.setImageDrawable(new MapIcon(icon));
    view.setPadding(dp(12), dp(12), dp(12), dp(12));
    view.setBackground(buttonBackground(SURFACE, OUTLINE, 16));
    view.setContentDescription(description);
    view.setTooltipText(description);
    view.setOnClickListener(v -> action.run());
    return view;
  }

  private GradientDrawable round(int color, int radius) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(radius));
    return background;
  }

  private GradientDrawable surface(int color, int border, int radius) {
    GradientDrawable background = round(color, radius);
    background.setStroke(dp(1), border);
    return background;
  }

  private RippleDrawable buttonBackground(int color, int border, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(RIPPLE), surface(color, border, radius), round(Color.WHITE, radius));
  }

  private View spacer(int height) {
    View view = new View(this);
    view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
    return view;
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    appUpdates.close();
    cancelSearch();
    placeSearch.close();
    main.removeCallbacksAndMessages(null);
    cancelLocate();
    router.close();
    map.dispose();
    ignMap.close();
    if (offlineMap != null) offlineMap.close();
    io.execute(
        () -> {
          store.close();
          mapStore.close();
        });
    io.shutdown();
    super.onDestroy();
  }
}
