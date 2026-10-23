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
  private ImageButton previewButton, libraryButton, updateButton;
  private TextView updateStatus;
  private AlertDialog libraryDialog;
  private FrameLayout libraryBody;
  private TextView downloadStatus;
  private String pendingDownload;
  private EmbeddedRouter router;
  private FrameLayout root;
  private LinearLayout pages;
  private TextView intervalChip, status, recordButton, planStatus;
  private ImageButton editButton;
  private Store.LibraryEntry editingEntry;
  private String newRouteId = "route-" + UUID.randomUUID();
  private List<Point> plannedRoute = Collections.emptyList();
  private int editRevision, calculatedRevision = -1;
  private boolean editDirty, finishEditing, savingEdit;
  private final Deque<NodeUndo> nodeUndo = new ArrayDeque<>();

  private static final class NodeUndo {
    final int index, kind;
    final Point point;
    boolean dirty, calculated;
    List<Point> geometry;

    NodeUndo(
        int index, int kind, Point point, boolean dirty, boolean calculated, List<Point> geometry) {
      this.index = index;
      this.kind = kind;
      this.point = point;
      this.dirty = dirty;
      this.calculated = calculated;
      this.geometry = geometry;
    }
  }

  private final Runnable autoRoute = this::autoCalculate;
  private AlertDialog routingPrompt, routingPreferencesDialog;
  private List<Point> pendingRoute;
  private String routingRequest, routingProgress;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private List<Point> route = new ArrayList<>(), track = new ArrayList<>();
  private double trackLength;
  private final List<Point> waypoints = new ArrayList<>();
  private List<Point> exportPoints;
  private boolean planning = false,
      destroyed = false,
      resumed = false,
      receiverRegistered = false,
      calculationRunning = false;
  private String currentPage = "Map";
  private int dataGeneration = 0, routeRevision = 0;
  private ManualLocation manualLocation;
  private PlaceSearch placeSearch;
  private AlertDialog searchDialog;
  private AppFeedback feedback;
  private static final long FOLLOW_RESUME_DELAY = 30_000;
  private long followResumeAt;
  private boolean followTouching, followingRecording;
  private final Runnable resumeFollow = this::followPosition;

  private final BroadcastReceiver fixes =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (RoutingDownloadService.EVENT.equals(i.getAction())) {
            if (pendingRoute == null
                || !Objects.equals(routingRequest, i.getStringExtra("request"))) return;
            routingProgress = i.getStringExtra("message");
            if (i.getBooleanExtra("finished", false)) {
              if (i.getBooleanExtra("success", false)) finishRoutingDownload();
              else {
                clearPendingRoute();
                message(i.getStringExtra("message") + ". Finish editing to resume.");
              }
            } else refreshPlanning();
            return;
          }
          if (DownloadService.EVENT.equals(i.getAction())) {
            if (downloadStatus != null) downloadStatus.setText(i.getStringExtra("message"));
            boolean finished = i.getBooleanExtra("finished", false);
            if (finished) map.refreshTiles(true);
            else map.tilesAdded();
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
            if (track.isEmpty() || track.get(track.size() - 1).time < p.time) {
              if (!track.isEmpty()) {
                Point previous = track.get(track.size() - 1);
                if (previous.segment == p.segment) trackLength += Geo.distance(previous, p);
              }
              track.add(p);
            }
            map.track = track;
            map.liveTrackId = store.prefs.getString("currentTrek", "");
            map.position = p;
            map.invalidate();
            followPosition();
          }
          refreshStatus();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    followingRecording = TrackingService.running;
    if (state != null && followingRecording) followResumeAt = state.getLong("followResumeAt");
    store = new Store(this);
    appUpdates = new AppUpdates(this);
    placeSearch = new PlaceSearch(this);
    mapStore = new PlanIgnStore(this);
    ignMap = new IgnMap(this);
    router = new EmbeddedRouter(this);
    if (state != null && state.getBoolean("planning")) {
      planning = true;
      editDirty = state.getBoolean("editDirty");
      newRouteId = state.getString("newRouteId", newRouteId);
      String editingId = state.getString("editingId");
      if (editingId != null)
        editingEntry =
            new Store.LibraryEntry(
                editingId,
                state.getString("editingTitle"),
                state.getBoolean("editingRecording"),
                true);
      double[] coordinates = state.getDoubleArray("planningPoints");
      if (coordinates != null)
        for (int i = 0; i + 1 < coordinates.length; i += 2)
          waypoints.add(new Point(coordinates[i], coordinates[i + 1]));
      if (editingRecording()) {
        try {
          if (!state.getBoolean("recordingDraft")) throw new IOException("Draft unavailable");
          waypoints.addAll(RecordingDraft.read(this, newRouteId));
        } catch (IOException | RuntimeException error) {
          waypoints.clear();
          waypoints.addAll(store.read(editingEntry.id));
          editDirty = false;
          main.post(() -> message("Could not restore edits. The saved recording is unchanged."));
        }
        plannedRoute = new ArrayList<>(waypoints);
        calculatedRevision = editRevision;
      }
      routingRequest = state.getString("routingRequest");
      if (routingRequest != null) pendingRoute = new ArrayList<>(waypoints);
      if ((!editingRecording() || editDirty)
          && getLastNonConfigurationInstance() instanceof List<?>) {
        for (Object undo : (List<?>) getLastNonConfigurationInstance())
          if (undo instanceof NodeUndo) nodeUndo.addLast((NodeUndo) undo);
      }
    }
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
    feedback = new AppFeedback(this);
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
    map.onLongPress = p -> addNode(waypoints.size(), p);
    map.onNodeAdded = this::addNode;
    map.onNodeMoved =
        (index, original) -> {
          rememberNode(index, 2, original);
          nodesChanged();
        };
    map.onNodeDeleted = this::deleteNode;
    map.onPathSelected =
        id -> {
          if (planning) return;
          for (Store.LibraryEntry entry : libraryEntries)
            if (entry.id.equals(id)) {
              selectLibraryEntry(entry);
              break;
            }
        };
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
    resumed = true;
    map.setRenderingEnabled(true);
    IntentFilter filter = new IntentFilter(TrackingService.EVENT);
    filter.addAction(DownloadService.EVENT);
    filter.addAction(RoutingDownloadService.EVENT);
    int receiverFlags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0;
    // The API-26 overload uses a signature permission on older systems and
    // adds the explicit non-exported flag on Android 13 and later.
    registerReceiver(fixes, filter, "app.cairn.trek.permission.INTERNAL", null, receiverFlags);
    receiverRegistered = true;
    TrackingService.fixObservers.incrementAndGet();
    loadPoints();
    reloadLibrary();
    refreshStatus();
    appUpdates.resume();
    if (planning) {
      // Any result still in flight was canceled when the activity paused.
      if (calculationRunning) editRevision++;
      refreshPlanning();
      if (pendingRoute == null) main.postDelayed(autoRoute, 250);
    }
    if (pendingRoute != null && !RoutingDownloadService.running) {
      RoutingDownloadService.Outcome outcome = RoutingDownloadService.lastOutcome;
      if (outcome != null && Objects.equals(routingRequest, outcome.request) && !outcome.success) {
        clearPendingRoute();
        message(outcome.message + ". Finish editing to resume.");
      } else finishRoutingDownload();
    }
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return new ArrayList<>(nodeUndo);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putLong("followResumeAt", followResumeAt);
    state.putBoolean("planning", planning);
    state.putBoolean("editDirty", editDirty);
    state.putString("newRouteId", newRouteId);
    if (editingEntry != null) {
      state.putString("editingId", editingEntry.id);
      state.putString("editingTitle", editingEntry.title);
      state.putBoolean("editingRecording", editingEntry.recording);
    }
    if (editingRecording()) {
      try {
        RecordingDraft.write(this, newRouteId, waypoints);
        state.putBoolean("recordingDraft", true);
      } catch (IOException | RuntimeException error) {
        message("Could not preserve unsaved edits. Finish editing before leaving this screen.");
      }
    } else {
      double[] coordinates = new double[waypoints.size() * 2];
      for (int i = 0; i < waypoints.size(); i++) {
        coordinates[i * 2] = waypoints.get(i).lat;
        coordinates[i * 2 + 1] = waypoints.get(i).lon;
      }
      state.putDoubleArray("planningPoints", coordinates);
    }
    state.putString("routingRequest", routingRequest);
  }

  @Override
  protected void onPause() {
    resumed = false;
    followTouching = false;
    dataGeneration++;
    main.removeCallbacksAndMessages(null);
    map.setRenderingEnabled(false);
    appUpdates.pause();
    feedback.clear();
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
      TrackingService.fixObservers.decrementAndGet();
      unregisterReceiver(fixes);
      receiverRegistered = false;
    }
    cancelLocate();
    cancelSearch();
    if (routingPrompt != null) routingPrompt.dismiss();
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
                if (destroyed || !resumed || generation != dataGeneration) return;
                long last =
                    loadedTrack.isEmpty() ? 0 : loadedTrack.get(loadedTrack.size() - 1).time;
                if (TrackingService.running)
                  for (Point point : track) if (point.time > last) loadedTrack.add(point);
                if (revision == routeRevision) route = loadedRoute;
                track = loadedTrack;
                trackLength = Geo.length(track);
                map.route = route;
                map.track = track;
                if (!track.isEmpty()
                    && (map.position == null
                        || map.position.time < track.get(track.size() - 1).time))
                  map.position = track.get(track.size() - 1);
                map.invalidate();
                followPosition();
                refreshStatus();
              });
        });
  }

  private void showPage(String title) {
    if ("Routes".equals(title)) {
      showLibrary();
      return;
    }
    if (libraryDialog != null) libraryDialog.dismiss();
    currentPage = "Map";
    pages.removeAllViews();
    showMap();
    refreshStatus();
  }

  private void showLibrary() {
    if (libraryDialog != null && libraryDialog.isShowing()) {
      libraryBody.removeAllViews();
      showRoutes();
      return;
    }
    cancelSearch();
    map.stopMotion();
    if (ignMap.online()) setMapOnline(false);
    currentPage = "Routes";
    libraryBody = new FrameLayout(this);
    showRoutes();
    AlertDialog popout =
        CairnDialogs.drawer(this)
            .fullHeight()
            .setTitle("Routes & Recordings")
            .setView(libraryBody)
            .create();
    libraryDialog = popout;
    popout.setOnDismissListener(
        dialog -> {
          if (libraryDialog != popout) return;
          libraryDialog = null;
          libraryBody = null;
          currentPage = "Map";
          if (libraryButton != null) setIconActive(libraryButton, false);
          if (!destroyed) refreshStatus();
        });
    libraryDialog.show();
    setIconActive(libraryButton, true);
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
    libraryButton = iconButton(MapIcon.PATH, "Routes and recordings", () -> showPage("Routes"));
    iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    iconParams.topMargin = dp(8);
    controls.addView(libraryButton, iconParams);
    updateButton = iconButton(MapIcon.UPDATE, "Update Cairn", () -> appUpdates.update());
    iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
    iconParams.topMargin = dp(8);
    controls.addView(updateButton, iconParams);
    intervalChip =
        button(
            getString(R.string.gps_interval, store.intervalMillis() / 1000),
            false,
            this::batterySettings);
    intervalChip.setTextSize(12);
    intervalChip.setSingleLine(true);
    intervalChip.setMinHeight(dp(48));
    intervalChip.setContentDescription("GPS settings");
    LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(-2, dp(48));
    LinearLayout bottomActions = row();
    bottomActions.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
    bottomActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    selectionPanel = row();
    selectionPanel.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
    selectionPanel.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int actionsLeft = left + dp(12); // Include the action strip's map margin.
          int offset = planning ? dp(actionsLeft >= dp(136) ? 68 : 136) : 0;
          if (map.scaleBottomOverlay != offset) {
            map.scaleBottomOverlay = offset;
            map.invalidate();
          }
        });
    bottomActions.addView(selectionPanel, new LinearLayout.LayoutParams(-2, dp(48)));
    gpsParams.leftMargin = dp(8);
    bottomActions.addView(intervalChip, gpsParams);
    HorizontalScrollView actionStrip = new HorizontalScrollView(this);
    actionStrip.setHorizontalScrollBarEnabled(false);
    actionStrip.setFillViewport(true);
    actionStrip.addView(bottomActions);
    bottomActions.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            actionStrip.scrollTo(bottomActions.getWidth(), 0));
    FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
    stripParams.setMargins(dp(12), 0, dp(12), dp(80));
    frame.addView(actionStrip, stripParams);
    controls.setGravity(Gravity.END | Gravity.BOTTOM);
    ScrollView controlRail = new ScrollView(this);
    controlRail.setFillViewport(true);
    controlRail.setVerticalScrollBarEnabled(false);
    controlRail.addView(controls);
    FrameLayout.LayoutParams controlsParams =
        new FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.TOP);
    controlsParams.setMargins(0, dp(12), dp(12), dp(144));
    frame.addView(controlRail, controlsParams);

    updateStatus = feedback.view();
    appUpdates.listen(
        (message, busy) -> {
          if (destroyed) return;
          feedback.update(message, busy);
          updateButton.setEnabled(!busy);
          setIconActive(updateButton, busy);
          updateButton.setContentDescription(busy ? message : "Update Cairn");
          updateButton.setTooltipText(busy ? message : "Check, download and install Cairn updates");
        });

    if (planning) {
      planStatus = label("Tap to add nodes · drag nodes to move", 13, TEXT, false);
      planStatus.setMaxLines(2);
      planStatus.setGravity(Gravity.CENTER);
      planStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
      planStatus.setBackground(surface(SURFACE, OUTLINE, 16));
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
      params.setMargins(dp(12), 0, dp(12), dp(16));
      frame.addView(planStatus, params);
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
          recordButton, new LinearLayout.LayoutParams(renderedRecording ? dp(80) : -1, dp(48)));
      int width =
          renderedRecording
              ? Math.min(dp(240), getResources().getDisplayMetrics().widthPixels - dp(24))
              : dp(120);
      FrameLayout.LayoutParams params =
          new FrameLayout.LayoutParams(
              width, dp(renderedRecording ? 56 : 48), Gravity.END | Gravity.BOTTOM);
      params.setMargins(0, 0, dp(12), dp(renderedRecording ? 12 : 16));
      frame.addView(hike, params);
    }
    map.bottomOverlay = dp(136);
    map.scaleBottomOverlay = planning ? dp(68) : 0;
    updateSelectionActions();
  }

  private LinearLayout libraryContent() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = column();
    content.setPadding(dp(20), dp(8), dp(20), dp(24));
    TextView sub = label("Your saved routes and recorded hikes", 14, MUTED, false);
    sub.setPadding(0, dp(8), 0, dp(24));
    content.addView(sub);
    scroll.addView(content);
    libraryBody.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    return content;
  }

  private List<Store.LibraryEntry> libraryEntries = new ArrayList<>();
  private Store.LibraryEntry selectedEntry;
  private List<Point> selectedPoints = Collections.emptyList();
  private LinearLayout selectionPanel;
  private int libraryGeneration;
  private AlertDialog mapDialog;

  private void reloadLibrary() {
    if (destroyed) return;
    int generation = ++libraryGeneration;
    io.execute(
        () -> {
          List<Store.LibraryEntry> entries = store.library();
          List<TrekMapView.LibraryPath> paths = new ArrayList<>();
          List<List<Point>> overlays = new ArrayList<>();
          String selected = store.selectedLibraryId();
          String active = store.prefs.getString("activeRouteId", "");
          String current = store.prefs.getString("currentTrek", "");
          Store.LibraryEntry selection = null;
          List<Point> selectionPoints = Collections.emptyList();
          boolean activeVisible = true;
          for (Store.LibraryEntry entry : entries) {
            // The current recording already has a live list, loaded by loadPoints().
            // Rendering and selection use that list; do not read a second full copy here.
            List<Point> points =
                entry.visible && !entry.id.equals(current)
                    ? store.read(entry.id)
                    : Collections.emptyList();
            paths.add(new TrekMapView.LibraryPath(entry, points));
            if (entry.id.equals(selected) && entry.visible) {
              selection = entry;
              selectionPoints = points;
            }
            if (entry.id.equals(active)) activeVisible = entry.visible;
            else if (!entry.recording && entry.visible) overlays.add(points);
          }
          Store.LibraryEntry loadedSelection = selection;
          List<Point> loadedPoints = selectionPoints;
          boolean showActive = activeVisible;
          main.post(
              () -> {
                if (destroyed || !resumed || generation != libraryGeneration) return;
                libraryEntries = entries;
                selectedEntry = loadedSelection;
                selectedPoints = loadedPoints;
                if (planning
                    && plannedRoute.isEmpty()
                    && editingEntry != null
                    && loadedSelection != null
                    && editingEntry.id.equals(loadedSelection.id)) {
                  plannedRoute = loadedPoints;
                  map.editRoute = plannedRoute;
                }
                map.libraryPaths = paths;
                map.selectedPathId = loadedSelection == null ? "" : loadedSelection.id;
                map.liveTrackId = current;
                map.otherRoutes = overlays;
                map.route = showActive ? route : Collections.emptyList();
                map.invalidate();
                updateSelectionActions();
                if ("Routes".equals(currentPage)) showPage("Routes");
              });
        });
  }

  private void showRoutes() {
    LinearLayout content = libraryContent();
    LinearLayout actions = row();
    actions.addView(
        button("Add files", false, () -> openDocument(IMPORT_GPX)),
        new LinearLayout.LayoutParams(0, dp(52), 1));
    actions.addView(
        button(
            "Create route",
            true,
            () -> {
              if (!planning) startEditing(null, Collections.emptyList(), Collections.emptyList());
              else showPage("Map");
            }),
        new LinearLayout.LayoutParams(0, dp(52), 1));
    content.addView(actions);
    content.addView(spacer(20));
    content.addView(label("Tap to select · swipe left or right to delete", 12, MUTED, false));
    content.addView(label("SAVED ROUTES", 11, MUTED, true));
    if (libraryEntries.stream().noneMatch(entry -> !entry.recording))
      content.addView(
          label(
              "Open GPX files with Cairn from Android, or use Add files. Each file stays in your"
                  + " library.",
              14,
              MUTED,
              false));
    for (Store.LibraryEntry entry : libraryEntries)
      if (!entry.recording) addLibraryEntry(content, entry);
    content.addView(spacer(24));
    content.addView(label("RECORDED HIKES", 11, MUTED, true));
    boolean hasRecordings = false;
    for (Store.LibraryEntry entry : libraryEntries) {
      if (!entry.recording) continue;
      hasRecordings = true;
      addLibraryEntry(content, entry);
    }
    if (!hasRecordings)
      content.addView(label("Your recordings will appear here.", 14, MUTED, false));
    content.addView(attributionFooter());
  }

  private void addLibraryEntry(LinearLayout content, Store.LibraryEntry entry) {
    boolean selected = entry.id.equals(store.selectedLibraryId()) && entry.visible;
    TextView item =
        button(
            entry.title + (selected ? " · selected" : "") + (entry.visible ? "" : " · hidden"),
            selected,
            () -> selectLibraryEntry(entry));
    item.setSelected(selected);
    if (!entry.recording)
      item.setOnLongClickListener(
          v -> {
            renameRoute(entry);
            return true;
          });
    boolean recordingNow =
        entry.recording
            && TrackingService.running
            && entry.id.equals(store.prefs.getString("currentTrek", ""));
    if (recordingNow) content.addView(item);
    else {
      Runnable remove = () -> removeLibraryEntry(entry);
      TextView removeButton = button("Delete", false, remove);
      removeButton.setContentDescription("Delete " + entry.title);
      SwipeToRemoveView swipe = new SwipeToRemoveView(this, item, removeButton, remove);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.topMargin = dp(8);
      content.addView(swipe, params);
    }
  }

  private List<Point> selectionSnapshot() {
    return new ArrayList<>(
        selectedEntry != null
                && selectedEntry.recording
                && selectedEntry.id.equals(store.prefs.getString("currentTrek", ""))
            ? track
            : selectedPoints);
  }

  private void addSelectionAction(int icon, String description, Runnable action) {
    ImageButton view = iconButton(icon, description, action);
    ((MapIcon) view.getDrawable()).colors(ON_SELECTION, SELECTION);
    view.setBackground(buttonBackground(SELECTION, SELECTION_BORDER, 16));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
    if (selectionPanel.getChildCount() > 0) params.leftMargin = dp(6);
    selectionPanel.addView(view, params);
  }

  private void updateSelectionActions() {
    if (selectionPanel == null) return;
    selectionPanel.removeAllViews();
    selectionPanel.setVisibility(View.VISIBLE);
    if (planning) {
      addSelectionAction(MapIcon.UNDO, "Undo last edit", this::undoNode);
      View undo = selectionPanel.getChildAt(selectionPanel.getChildCount() - 1);
      undo.setEnabled(map.editing && !nodeUndo.isEmpty());
      undo.setAlpha(undo.isEnabled() ? 1f : 0.4f);
      if (!editingRecording())
        addSelectionAction(MapIcon.TUNE, "Routing preferences", this::routingPreferences);
      addSelectionAction(
          MapIcon.CENTER,
          "Center selected",
          () ->
              map.fitRoute(
                  calculatedRevision == editRevision && !plannedRoute.isEmpty()
                      ? plannedRoute
                      : waypoints));
      addSelectionAction(
          MapIcon.EDIT,
          pendingRoute != null ? "Pause routing download" : "Finish editing",
          this::toggleEditing);
      editButton = (ImageButton) selectionPanel.getChildAt(selectionPanel.getChildCount() - 1);
      editButton.setSelected(true);
      ((MapIcon) editButton.getDrawable()).colors(SELECTION, ON_SELECTION);
      editButton.setBackground(buttonBackground(ON_SELECTION, SELECTION_BORDER, 16));
      editButton.setEnabled(!savingEdit);
    } else if (selectedEntry != null) {
      Store.LibraryEntry entry = selectedEntry;
      addSelectionAction(
          MapIcon.SAVE_MAP,
          "Save offline corridor",
          () -> prepareDownload(true, selectionSnapshot()));
      addSelectionAction(
          MapIcon.EXPORT, "Export GPX", () -> export(selectionSnapshot(), entry.title + ".gpx"));
      addSelectionAction(MapIcon.HIDE, "Hide", () -> hideLibraryEntry(entry));
      // Keep Center and Edit beside GPS in both modes, with secondary actions to the left.
      addSelectionAction(
          MapIcon.CENTER, "Center selected", () -> map.fitRoute(selectionSnapshot()));
      addSelectionAction(
          MapIcon.EDIT, entry.recording ? "Edit recording" : "Edit route", this::toggleEditing);
    } else {
      addSelectionAction(MapIcon.EDIT, "Create route", this::toggleEditing);
    }
  }

  private void toggleEditing() {
    if (planning) {
      if (savingEdit) return;
      if (pendingRoute != null) {
        cancelRoutingDownload();
        return;
      }
      if (finishEditing) {
        finishEditing = false;
        refreshPlanning();
        return;
      }
      if (!editDirty) {
        leaveEditing();
        return;
      }
      if (editingRecording()) {
        if (waypoints.isEmpty()) {
          message("Keep at least one point in the recording.");
          return;
        }
        saveEditedPath();
        return;
      }
      if (waypoints.size() < 2) {
        leaveEditing();
        return;
      }
      finishEditing = true;
      if (calculatedRevision == editRevision && !plannedRoute.isEmpty()) saveEditedPath();
      else {
        refreshPlanning();
        calculate();
      }
      return;
    }
    if (selectedEntry == null) {
      startEditing(null, Collections.emptyList(), Collections.emptyList());
      return;
    }
    Store.LibraryEntry entry = selectedEntry;
    if (recordingActive(entry)) {
      message("Finish recording before editing this hike.");
      return;
    }
    List<Point> geometry = selectionSnapshot();
    io.execute(
        () -> {
          List<Point> loaded = entry.recording ? store.read(entry.id) : geometry;
          List<Point> nodes = entry.recording ? loaded : store.routeNodes(entry.id);
          if (nodes.isEmpty() && !entry.recording) nodes = RouteNodes.fromGeometry(loaded);
          List<Point> anchors = nodes;
          main.post(
              () -> {
                if (!destroyed
                    && resumed
                    && !planning
                    && selectedEntry != null
                    && entry.id.equals(selectedEntry.id)
                    && !recordingActive(entry)) startEditing(entry, loaded, anchors);
              });
        });
  }

  private void startEditing(Store.LibraryEntry entry, List<Point> geometry, List<Point> nodes) {
    editingEntry = entry;
    newRouteId = "route-" + UUID.randomUUID();
    planning = true;
    editDirty = finishEditing = savingEdit = false;
    editRevision++;
    calculatedRevision = editRevision;
    plannedRoute = new ArrayList<>(geometry);
    waypoints.clear();
    waypoints.addAll(nodes);
    nodeUndo.clear();
    showPage("Map");
  }

  private boolean editingRecording() {
    return editingEntry != null && editingEntry.recording;
  }

  private boolean recordingActive(Store.LibraryEntry entry) {
    return entry.recording
        && TrackingService.running
        && entry.id.equals(store.prefs.getString("currentTrek", ""));
  }

  private void addNode(int index, Point point) {
    if (!planning || pendingRoute != null || finishEditing || savingEdit) return;
    if (editingRecording() && !waypoints.isEmpty()) {
      int segment = waypoints.get(index > 0 ? index - 1 : 0).segment;
      point = new Point(point.lat, point.lon, point.elevation, point.time, point.accuracy, segment);
    }
    rememberNode(index, 0, null);
    waypoints.add(index, point);
    nodesChanged();
  }

  private void rememberNode(int index, int kind, Point point) {
    nodeUndo.addFirst(
        new NodeUndo(
            index,
            kind,
            point,
            editDirty,
            calculatedRevision == editRevision,
            editingRecording() ? Collections.emptyList() : plannedRoute));
    // Keep history bounded without copying full-resolution recordings for each edit.
    if (nodeUndo.size() > 100) nodeUndo.removeLast();
  }

  private void undoNode() {
    if (!map.editing || nodeUndo.isEmpty()) return;
    NodeUndo undo = nodeUndo.removeFirst();
    if (undo.kind == 0) waypoints.remove(undo.index);
    else if (undo.kind == 1) waypoints.add(undo.index, undo.point);
    else waypoints.set(undo.index, undo.point);
    nodesChanged();
    editDirty = undo.dirty;
    if (!editingRecording()) {
      plannedRoute = undo.geometry;
      if (undo.calculated) calculatedRevision = editRevision;
    }
    refreshPlanning();
  }

  private void nodesChanged() {
    editDirty = true;
    editRevision++;
    main.removeCallbacks(autoRoute);
    if (editingRecording()) {
      plannedRoute = new ArrayList<>(waypoints);
      calculatedRevision = editRevision;
      refreshPlanning();
      return;
    }
    if (waypoints.size() < 2) plannedRoute = Collections.emptyList();
    refreshPlanning();
    if (routingPreferencesDialog == null) main.postDelayed(autoRoute, 250);
  }

  private void deleteNode(int index) {
    if (!map.editing || index < 0 || index >= waypoints.size()) return;
    rememberNode(index, 1, waypoints.get(index));
    waypoints.remove(index);
    nodesChanged();
  }

  private void routingPreferences() {
    if (routingPreferencesDialog != null) return;
    if (pendingRoute != null || finishEditing || savingEdit) {
      message("Finish or pause the current operation before changing routing preferences.");
      return;
    }
    main.removeCallbacks(autoRoute);
    if (calculationRunning) {
      editRevision++;
      router.cancel();
    }
    routingPreferencesDialog =
        RoutingPreferences.show(
            this,
            store.prefs,
            () -> {
              if (planning) {
                // Node undo must retain newer routing preferences and recalculate with them.
                for (NodeUndo undo : nodeUndo) {
                  undo.dirty = true;
                  undo.calculated = false;
                  undo.geometry = Collections.emptyList();
                }
                nodesChanged();
              }
            },
            () -> {
              routingPreferencesDialog = null;
              if (!destroyed) autoCalculate();
            });
  }

  private void autoCalculate() {
    if (!editingRecording()
        && resumed
        && routingPreferencesDialog == null
        && planning
        && editDirty
        && calculatedRevision != editRevision
        && waypoints.size() >= 2
        && !routingBusy()) calculate();
  }

  private void leaveEditing() {
    main.removeCallbacks(autoRoute);
    cancelRoutingDownload();
    if (editingRecording()) RecordingDraft.delete(this, newRouteId);
    planning = finishEditing = savingEdit = false;
    editingEntry = null;
    nodeUndo.clear();
    waypoints.clear();
    plannedRoute = Collections.emptyList();
    refreshPlanning();
    showPage("Map");
  }

  private void saveEditedPath() {
    if (savingEdit) return;
    savingEdit = true;
    refreshPlanning();
    Store.LibraryEntry entry = editingEntry;
    String draftId = newRouteId;
    List<Point> geometry = new ArrayList<>(plannedRoute), nodes = new ArrayList<>(waypoints);
    io.execute(
        () -> {
          try {
            String name =
                entry == null ? "Planned route " + java.time.LocalDate.now() : entry.title;
            String id;
            if (entry == null) id = store.saveRoute(draftId, geometry, name, nodes);
            else {
              id = entry.id;
              if (entry.recording) store.updateTrek(id, geometry);
              else store.updateRoute(id, geometry, nodes);
            }
            root.post(
                () -> {
                  if (destroyed) return;
                  if (entry != null
                      && entry.recording
                      && id.equals(store.prefs.getString("currentTrek", ""))) {
                    dataGeneration++;
                    track = new ArrayList<>(geometry);
                    trackLength = Geo.length(track);
                    map.track = track;
                    map.position = track.get(track.size() - 1);
                  }
                  leaveEditing();
                  showLibrarySelection(
                      new Store.LibraryEntry(id, name, entry != null && entry.recording, true),
                      geometry);
                });
          } catch (RuntimeException error) {
            root.post(
                () -> {
                  if (destroyed) return;
                  savingEdit = finishEditing = false;
                  refreshPlanning();
                  message(
                      "Could not save "
                          + (entry != null && entry.recording ? "recording: " : "route: ")
                          + error.getMessage());
                });
          }
        });
  }

  private void selectLibraryEntry(Store.LibraryEntry entry) {
    if (planning) {
      message("Finish editing before selecting another route or recording.");
      return;
    }
    libraryGeneration++;
    io.execute(
        () -> {
          try {
            List<Point> points = store.select(entry);
            main.post(
                () -> {
                  if (destroyed || !resumed) return;
                  showLibrarySelection(entry, points);
                });
          } catch (RuntimeException error) {
            main.post(() -> message("Could not select: " + error.getMessage()));
          }
        });
  }

  private void showLibrarySelection(Store.LibraryEntry entry, List<Point> points) {
    selectedEntry = new Store.LibraryEntry(entry.id, entry.title, entry.recording, true);
    selectedPoints = points;
    if (!entry.recording) {
      routeRevision++;
      route = points;
      map.route = points;
      updateService();
    }
    map.selectedPathId = entry.id;
    showPage("Map");
    map.fitRoute(points);
    reloadLibrary();
  }

  private void hideLibraryEntry(Store.LibraryEntry entry) {
    libraryGeneration++;
    io.execute(
        () -> {
          store.libraryVisible(entry, false);
          main.post(this::reloadLibrary);
        });
  }

  private void removeLibraryEntry(Store.LibraryEntry entry) {
    libraryGeneration++;
    io.execute(
        () -> {
          try {
            if (entry.recording) store.deleteTrek(entry.id);
            else store.deleteRoute(entry.id);
            main.post(
                () -> {
                  if (destroyed) return;
                  if (entry.recording && entry.id.equals(map.liveTrackId)) {
                    track = new ArrayList<>();
                    trackLength = 0;
                    map.track = track;
                  }
                  loadPoints();
                  reloadLibrary();
                  updateService();
                });
          } catch (RuntimeException error) {
            main.post(
                () -> {
                  reloadLibrary();
                  message(error.getMessage());
                });
          }
        });
  }

  private void renameRoute(Store.LibraryEntry entry) {
    EditText title = new EditText(this);
    title.setSingleLine(true);
    title.setText(entry.title);
    CairnDialogs.dialog(this)
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
                CairnDialogs.drawer(this)
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
                            CairnDialogs.dialog(this)
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
              CairnDialogs.dialog(this)
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
    content.addView(button("Routing data", false, this::routingDataMenu));
    content.addView(button("Routing preferences", false, this::routingPreferences));
    content.addView(attributionFooter());
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    mapDialog =
        CairnDialogs.drawer(this).fullHeight().setTitle("Saved maps").setView(scroll).create();
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
            : hasPlanningPoints ? new ArrayList<>(waypoints) : new ArrayList<>(route);
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
        CairnDialogs.drawer(this).fullHeight().setTitle("Offline routing data").setMessage(text);
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
          List<RoutingRegionMenu.Item> items = RoutingRegionMenu.items(usingMapCenter, downloaded);
          String[] labels = new String[items.size()];
          for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;
          CairnDialogs.drawer(this)
              .fullHeight()
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
                    CairnDialogs.dialog(this)
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
                              } else new File(RoutingData.directory(this), item.region).delete();
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
    setIconActive(previewButton, ignMap.online());
  }

  private void setIconActive(ImageButton view, boolean active) {
    if (view == null) return;
    view.setSelected(active);
    // Give custom drawables their own colors: ImageView tint can mask their strokes.
    view.setImageTintList(null);
    int background = active ? PRIMARY : SURFACE;
    if (view.getDrawable() instanceof MapIcon)
      ((MapIcon) view.getDrawable()).colors(active ? ON_PRIMARY : ACCENT, background);
    GradientDrawable fill = surface(background, active ? ACCENT : OUTLINE, 18);
    fill.setStroke(dp(active ? 2 : 1), active ? ACCENT : OUTLINE);
    view.setBackground(
        new RippleDrawable(ColorStateList.valueOf(RIPPLE), fill, round(Color.WHITE, 18)));
    if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(active ? "On" : "Off");
  }

  private void prepareDownload(boolean corridor, List<Point> corridorPoints) {
    if (TrackingService.running) {
      message("Finish recording before downloading maps.");
      return;
    }
    if (corridor && corridorPoints.isEmpty()) {
      message("Select a route or recording with points first.");
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
      content.addView(label("Kilometres on each side of the path", 14, TEXT, true));
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
        CairnDialogs.drawer(this)
            .setTitle(corridor ? "Save offline corridor" : "Save map area")
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
                      List<Point> points = new ArrayList<>(corridorPoints);
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
                                                    ? "Corridor: %1$.1f km on each side · detail"
                                                        + " zoom %2$d\n\n"
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
                                    CairnDialogs.dialog(this)
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
    if (destroyed || !resumed) return;
    if (followingRecording != TrackingService.running) {
      followPosition();
    }
    if ("Map".equals(currentPage) && !planning && renderedRecording != TrackingService.running) {
      showPage("Map");
      reloadLibrary();
      return;
    }
    if (intervalChip != null)
      setStatusText(intervalChip, getString(R.string.gps_interval, store.intervalMillis() / 1000));
    if (status != null) {
      String text = String.format(Locale.getDefault(), "%.1f km", trackLength / 1000);
      if (map.position == null) text += "\nAwaiting GPS";
      else text += "\n±" + Math.round(map.position.accuracy) + " m";
      setStatusText(status, text);
    }
    if (recordButton != null)
      setStatusText(recordButton, TrackingService.running ? "Finish" : "Start hike");
  }

  private void setStatusText(TextView view, String text) {
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  private void batterySettings() {
    LinearLayout content = column();
    content.setPadding(dp(24), dp(8), dp(24), dp(12));
    LinearLayout intervalRow = row();
    intervalRow.setGravity(Gravity.CENTER_VERTICAL);
    intervalRow.addView(
        label("Update interval", 15, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
    TextView interval = label("", 20, ACCENT, true);
    intervalRow.addView(interval);
    content.addView(intervalRow);
    GpsIntervalSlider seconds = new GpsIntervalSlider(this);
    seconds.setMax(GpsInterval.STEPS);
    seconds.setKeyProgressIncrement(GpsInterval.STEPS / 100);
    seconds.setProgress(GpsInterval.progress((int) (store.intervalMillis() / 1000)));
    seconds.setContentDescription("Seconds between requested GPS updates");
    interval.setText(
        getString(R.string.gps_interval_value, GpsInterval.seconds(seconds.getProgress())));
    seconds.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            int value = GpsInterval.seconds(progress);
            interval.setText(getString(R.string.gps_interval_value, value));
            bar.setContentDescription(value + " seconds between requested GPS updates");
            store.prefs.edit().putInt("intervalSeconds", value).apply();
            refreshStatus();
            if (!seconds.isTouchTracking()) updateService();
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            updateService();
          }
        });
    content.addView(seconds, new LinearLayout.LayoutParams(-1, dp(64)));
    content.addView(label(getString(R.string.gps_interval_guidance), 13, MUTED, false));
    Switch alerts = new Switch(this);
    alerts.setText(R.string.off_route_alerts);
    alerts.setMinHeight(dp(56));
    alerts.setChecked(store.prefs.getBoolean("offRoute", true));
    alerts.setOnCheckedChangeListener(
        (button, checked) -> {
          store.prefs.edit().putBoolean("offRoute", checked).apply();
          updateService();
        });
    content.addView(alerts);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    CairnDialogs.drawer(this).setTitle("GPS").setView(scroll).show();
  }

  private void toggleRecording() {
    if (TrackingService.running) {
      CairnDialogs.dialog(this)
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
      CairnDialogs.dialog(this)
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
    cancelRoutingDownload();
    router.cancel();
    dataGeneration++;
    track = new ArrayList<>();
    trackLength = 0;
    map.track = track;
    map.position = null;
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
      followResumeAt = 0;
      main.removeCallbacks(resumeFollow);
      if (map.position != null) {
        map.center(map.position);
        showLocationAccuracy(map.position, true);
      } else feedback.show("Waiting for your next scheduled GPS fix", 6000, "location");
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
    feedback.show("Finding your position…", 3500, "location");
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
            feedback.show(reason, 6000, "location");
          }
        });
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    syncFollowRecording();
    if (TrackingService.running) {
      followTouching =
          event.getActionMasked() != MotionEvent.ACTION_UP
              && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
      followResumeAt = SystemClock.elapsedRealtime() + FOLLOW_RESUME_DELAY;
      main.removeCallbacks(resumeFollow);
      if (!followTouching) main.postDelayed(resumeFollow, FOLLOW_RESUME_DELAY);
    }
    return super.dispatchTouchEvent(event);
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) followPosition();
    else {
      followTouching = false;
      main.removeCallbacks(resumeFollow);
    }
  }

  /** Follow existing recording fixes only; the single idle callback never requests location. */
  private void followPosition() {
    main.removeCallbacks(resumeFollow);
    syncFollowRecording();
    if (destroyed
        || !resumed
        || !TrackingService.running
        || planning
        || followTouching
        || !hasWindowFocus()) return;
    long remaining = followResumeAt - SystemClock.elapsedRealtime();
    if (remaining > 0) main.postDelayed(resumeFollow, remaining);
    else if (map.position != null) {
      Point center = map.center();
      if (center.lat != map.position.lat || center.lon != map.position.lon) map.center(map.position);
    }
  }

  private void syncFollowRecording() {
    if (followingRecording == TrackingService.running) return;
    followingRecording = TrackingService.running;
    followResumeAt = 0;
    followTouching = false;
    main.removeCallbacks(resumeFollow);
  }

  private void showLocationAccuracy(Point point, boolean cached) {
    long seconds = Math.max(0, (System.currentTimeMillis() - point.time) / 1000);
    String text = String.format(Locale.getDefault(), "Position ±%.0f m", point.accuracy);
    if (cached) text += " · " + seconds + "s ago";
    feedback.show(text, 6000, "location");
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
                    if (!destroyed) showToast("Routing region ready offline", Toast.LENGTH_LONG);
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
    String name = title.isEmpty() ? "Imported route" : title;
    String id = store.saveRoute(loaded, name);
    main.post(
        () -> {
          if (destroyed) return;
          showLibrarySelection(new Store.LibraryEntry(id, name, false, true), loaded);
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
                  if (!destroyed) showToast("GPX exported", Toast.LENGTH_SHORT);
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
    map.editing = planning && pendingRoute == null && !finishEditing && !savingEdit;
    map.showRouteStarts = !planning;
    map.editRoute = planning ? plannedRoute : Collections.emptyList();
    map.editingPathId = planning && editingEntry != null ? editingEntry.id : "";
    map.invalidate();
    if (planStatus != null)
      planStatus.setText(
          savingEdit
              ? editingRecording() ? "Saving recording…" : "Saving route…"
              : pendingRoute != null
                  ? routingProgress == null ? "Downloading routing data…" : routingProgress
                  : calculationRunning
                      ? "Calculating on this device…"
                      : waypoints.size()
                          + " nodes · tap map to add · tap node to delete · drag to move");
    updateSelectionActions();
  }

  private void calculate() {
    if (!planning
        || editingRecording()
        || !resumed
        || routingPreferencesDialog != null
        || routingBusy()
        || savingEdit
        || (routingPrompt != null && routingPrompt.isShowing())) return;
    if (waypoints.size() < 2) {
      message("Add at least two points to calculate a route.");
      return;
    }
    List<Point> requested = new ArrayList<>(waypoints);
    List<String> missing;
    try {
      missing = RoutingData.missing(this, requested);
    } catch (IllegalArgumentException error) {
      finishEditing = false;
      refreshPlanning();
      message(error.getMessage());
      return;
    }
    if (missing.isEmpty()) {
      calculate(requested);
      return;
    }
    if (TrackingService.running) {
      finishEditing = false;
      refreshPlanning();
      message("Finish the hike before downloading routing data, then edit the route again.");
      return;
    }
    if (RoutingDownloadService.running) {
      finishEditing = false;
      refreshPlanning();
      message("Routing data is downloading. Finish editing when it completes.");
      return;
    }
    routingPrompt =
        CairnDialogs.dialog(this)
            .setTitle("Download routing data?")
            .setMessage(
                "The offline trail network for this route isn't installed yet.\n\n"
                    + missing.size()
                    + (missing.size() == 1 ? " missing region." : " missing regions.")
                    + " Regional downloads can be hundreds of MB; use Wi-Fi."
                    + " Download size is shown during transfer.\n\n"
                    + "Cairn will calculate your route when the data is ready.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Download & route", (d, w) -> downloadAndRoute(requested, missing))
            .create();
    routingPrompt.setOnDismissListener(
        d -> {
          routingPrompt = null;
          if (pendingRoute == null) finishEditing = false;
          refreshPlanning();
        });
    routingPrompt.show();
  }

  private boolean routingBusy() {
    return calculationRunning || pendingRoute != null;
  }

  private void downloadAndRoute(List<Point> requested, List<String> missing) {
    if (!resumed || !planning || TrackingService.running || RoutingDownloadService.running) {
      message("Routing download unavailable. Finish editing to try again.");
      return;
    }
    pendingRoute = requested;
    routingRequest = UUID.randomUUID().toString();
    routingProgress = null;
    setMapOnline(false);
    if (DownloadService.running)
      startService(new Intent(this, DownloadService.class).setAction(DownloadService.PAUSE));
    refreshPlanning();
    try {
      startForegroundService(
          new Intent(this, RoutingDownloadService.class)
              .putStringArrayListExtra("regions", new ArrayList<>(missing))
              .putExtra("request", routingRequest));
    } catch (RuntimeException error) {
      clearPendingRoute();
      message("Could not start routing download: " + error.getMessage());
    }
  }

  private void clearPendingRoute() {
    finishEditing = false;
    pendingRoute = null;
    routingRequest = null;
    routingProgress = null;
    refreshPlanning();
  }

  private void cancelRoutingDownload() {
    if (pendingRoute == null) return;
    finishEditing = false;
    clearPendingRoute();
    startService(new Intent(this, RoutingDownloadService.class).setAction("pause"));
  }

  private void finishRoutingDownload() {
    if (pendingRoute == null || !resumed || destroyed) return;
    List<Point> requested = pendingRoute;
    boolean finish = finishEditing;
    clearPendingRoute();
    if (!planning) return;
    if (!RoutingData.missing(this, requested).isEmpty()) {
      message("Routing download paused. Finish editing to resume.");
      return;
    }
    finishEditing = finish;
    calculate(requested);
  }

  private void calculate(List<Point> requested) {
    if (routingPreferencesDialog != null) return;
    int revision = editRevision;
    calculationRunning = true;
    refreshPlanning();
    router.calculate(
        requested,
        (points, error) -> {
          if (destroyed) return;
          calculationRunning = false;
          if (!resumed || !planning) return;
          if (revision != editRevision) {
            refreshPlanning();
            main.removeCallbacks(autoRoute);
            main.post(autoRoute);
            return;
          }
          if (error != null) {
            finishEditing = false;
            refreshPlanning();
            message(error);
            return;
          }
          plannedRoute = points;
          calculatedRevision = revision;
          refreshPlanning();
          if (finishEditing) saveEditedPath();
        });
  }

  private void searchPlaces() {
    cancelSearch();
    placeSearch.near(map.center());
    LinearLayout content = column();
    content.setPadding(dp(24), dp(8), dp(24), dp(8));
    EditText query = new EditText(this);
    query.setContentDescription("Place name or address");
    query.setSingleLine(true);
    query.setTextSize(16);
    query.setTextColor(TEXT);
    query.setMinHeight(dp(52));
    query.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
    query.setBackgroundColor(Color.TRANSPARENT);
    MapIcon searchIcon = new MapIcon(MapIcon.SEARCH);
    searchIcon.setBounds(0, 0, dp(22), dp(22));
    query.setCompoundDrawablesRelative(searchIcon, null, null, null);
    query.setCompoundDrawablePadding(dp(12));
    query.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
    query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    FrameLayout searchBar = new FrameLayout(this);
    searchBar.setBackground(surface(BACKGROUND, OUTLINE, 26));
    searchBar.addView(query, new FrameLayout.LayoutParams(-1, -2));
    ImageButton clearSearch =
        iconButton(
            MapIcon.CLOSE,
            "Clear search",
            () -> {
              query.getText().clear();
              query.requestFocus();
            });
    clearSearch.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(RIPPLE), round(Color.TRANSPARENT, 24), round(Color.WHITE, 24)));
    clearSearch.setVisibility(View.GONE);
    FrameLayout.LayoutParams clearParams =
        new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
    clearParams.setMarginEnd(dp(2));
    searchBar.addView(clearSearch, clearParams);
    TextView searchStatus = label("", 13, MUTED, false);
    searchStatus.setVisibility(View.GONE);
    content.addView(searchStatus);
    java.util.function.Consumer<String> showSearchStatus =
        text -> {
          searchStatus.setText(text);
          searchStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
    TextView searchCredit = label("", 12, MUTED, false);
    searchCredit.setText(
        android.text.Html.fromHtml(
            "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap</a>",
            android.text.Html.FROM_HTML_MODE_LEGACY));
    searchCredit.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    searchCredit.setLinkTextColor(ACCENT);
    LinearLayout results = column();
    ScrollView scroll = new ScrollView(this);
    scroll.addView(results);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(searchCredit);
    AlertDialog dialog =
        CairnDialogs.drawer(this).fullHeight().setCustomTitle(searchBar).setView(content).create();
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
            if (!dialog.isShowing() || destroyed) return;
            results.removeAllViews();
            showSearchStatus.accept(places.isEmpty() ? "No results" : "");
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
            if (!dialog.isShowing() || destroyed) return;
            showSearchStatus.accept(reason);
          }
        };
    query.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          public void onTextChanged(CharSequence text, int start, int before, int count) {
            clearSearch.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            query.setPaddingRelative(dp(16), dp(12), dp(text.length() == 0 ? 16 : 56), dp(12));
            results.removeAllViews();
            String value = text.toString().trim();
            showSearchStatus.accept(value.length() < 2 ? "" : "Searching…");
            placeSearch.suggest(value, callback);
          }

          public void afterTextChanged(android.text.Editable text) {}
        });
    dialog.setOnShowListener(
        d -> {
          // AlertDialog only detects editors in its content, not in a custom title.
          dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
          query.requestFocus();
          dialog
              .getWindow()
              .setSoftInputMode(
                  WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                      | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          Runnable submit =
              () -> {
                String text = query.getText().toString().trim();
                if (text.isEmpty()) return;
                results.removeAllViews();
                showSearchStatus.accept("Searching…");
                placeSearch.search(text, callback);
              };
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

  private void showToast(String text, int duration) {
    if (!destroyed && !isFinishing())
      feedback.show(text, duration == Toast.LENGTH_SHORT ? 3500 : 6000);
  }

  private TextView attributionFooter() {
    TextView footer = label("© IGN · © OpenStreetMap · Licences", 11, MUTED, false);
    footer.setGravity(Gravity.CENTER);
    footer.setPadding(0, dp(16), 0, dp(16));
    footer.setOnClickListener(v -> showAttribution());
    footer.setContentDescription("Map sources and licences");
    return footer;
  }

  private void showAttribution() {
    try (InputStream input = getAssets().open("routing/LICENSE-BROUTER.txt")) {
      String text =
          "Plan IGN: IGN, Licence Ouverte 2.0.\n"
              + "Source and update information: https://www.data.gouv.fr/datasets/plan-ign\n"
              + "Licence: https://github.com/etalab/licence-ouverte/blob/master/LO.md\n\n"
              + "Routing and place search: © OpenStreetMap contributors (ODbL).\n"
              + "https://www.openstreetmap.org/copyright\n"
              + "Place search: Photon, provided by komoot; device geocoder fallback.\n\n";
      if (map.tileSource() instanceof OfflineMap)
        text += "Imported map: " + map.tileSource().attribution() + "\n\n";
      android.text.SpannableString links =
          new android.text.SpannableString(
              text + new String(MapCatalog.bytes(input), java.nio.charset.StandardCharsets.UTF_8));
      android.text.util.Linkify.addLinks(links, android.text.util.Linkify.WEB_URLS);
      AlertDialog dialog =
          CairnDialogs.dialog(this)
              .setTitle("Licences & attribution")
              .setMessage(links)
              .setPositiveButton("OK", null)
              .show();
      TextView body = dialog.findViewById(android.R.id.message);
      if (body != null)
        body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    } catch (IOException error) {
      message(error.getMessage());
    }
  }

  private void message(String text) {
    showToast(text == null ? "Operation could not be completed" : text, Toast.LENGTH_LONG);
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
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
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
    if (isFinishing() && editingRecording()) RecordingDraft.delete(this, newRouteId);
    if (routingPreferencesDialog != null) routingPreferencesDialog.dismiss();
    if (libraryDialog != null) libraryDialog.dismiss();
    appUpdates.close();
    feedback.close();
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
