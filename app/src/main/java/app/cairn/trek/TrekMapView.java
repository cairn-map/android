package app.cairn.trek;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.widget.OverScroller;
import java.util.*;
import java.util.concurrent.*;

public final class TrekMapView extends View {
  public interface LongPress {
    void at(Point point);
  }

  public LongPress onLongPress;
  public List<Point> route = Collections.emptyList(),
      track = Collections.emptyList(),
      waypoints = Collections.emptyList();
  public Point position;
  public List<List<Point>> otherRoutes = Collections.emptyList();
  public int bottomOverlay;
  private double lat = 45.9237, lon = 6.8694;
  private int zoom = 13;
  private double zoomScale = 1;
  private List<Point> pendingFit;
  private final float tileSize;
  private TileSource source;
  private volatile int generation = 0;
  private volatile boolean active = true;
  private boolean renderingEnabled = true, windowVisible = true, hierarchyVisible = true;
  private boolean tapCandidate, doubleTap;
  private float downX, downY;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final RectF tileRect = new RectF();
  private final Path linePath = new Path();
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          IgnClient.PARALLELISM,
          IgnClient.PARALLELISM,
          0,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<>());
  private final Map<String, TileRequest> pending = new HashMap<>();
  private final Set<String> missing = new HashSet<>();
  private Set<String> viewport = Collections.emptySet();
  private final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getByteCount();
        }
      };
  private boolean cacheChanged = true;
  private final List<CachedTile> cachedTiles = new ArrayList<>();
  private final List<CachedTile> previousTiles = new ArrayList<>();
  private final GestureDetector gestures;
  private final ScaleGestureDetector scales;
  private final OverScroller panScroller;
  private int panX, panY;
  private long lastPanMove;
  private boolean multiplePointers, releasingPointers;
  private float multiVelocityX, multiVelocityY;
  private long multiEventTime, lastMultiPanMove;
  private float firstFingerX, firstFingerY, secondFingerX, secondFingerY;
  private float pinchX, pinchY;
  private boolean pinched;
  private double pinchVelocity, pinchDirection;
  private long lastPinchMove;
  private float pinchFocusX, pinchFocusY;
  private boolean twoFingerTap;
  private float twoFingerX, twoFingerY;
  // One zoom level per 160 dp, independent of distance from the second tap or event rate.
  private final float quickZoomDistance;
  private VelocityTracker zoomVelocity;
  private boolean quickZooming, quickZoomEligible, zoomFling;
  private float quickFocusX, quickFocusY, quickLastY, quickDirection;
  // Decay in zoom levels, so density and frame rate do not change the coast.
  private static final double ZOOM_DECAY_SECONDS = 0.32;
  private double flingStartLevel, flingVelocity;
  private long flingStarted;
  private long lastQuickMove;
  private boolean tapZoom;
  private long tapZoomStarted;
  private double tapZoomStart, tapZoomTarget;

  public TrekMapView(Context context) {
    super(context);
    tileSize = 256 * getResources().getDisplayMetrics().density;
    quickZoomDistance = dp(160);
    panScroller = new OverScroller(context);
    setContentDescription(
        "Offline trekking map. Drag to move, pinch to zoom. Long press to add a"
            + " planning point.");
    gestures =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onScroll(MotionEvent first, MotionEvent now, float dx, float dy) {
                if (doubleTap || multiplePointers || scales.isInProgress()) return true;
                pinched = false;
                panBy(dx, dy);
                invalidate();
                lastPanMove = now.getEventTime();
                return true;
              }

              @Override
              public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (doubleTap
                    || multiplePointers
                    || scales.isInProgress()
                    || last.getEventTime() - lastPanMove > 100) return true;
                startPanFling(-vx, -vy);
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                // The second press may become a one-finger quick-scale gesture.
                doubleTap = true;
                // Disable before the detector arms its second-press timer. Ignoring the
                // callback alone still lets it enter long-press mode and swallow MOVE events.
                gestures.setIsLongpressEnabled(false);
                return true;
              }

              @Override
              public boolean onDoubleTapEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                  case MotionEvent.ACTION_DOWN:
                    quickZoomEligible = true;
                    quickFocusX = e.getX();
                    quickFocusY = e.getY();
                    quickLastY = e.getY();
                    quickDirection = 0;
                    break;
                  case MotionEvent.ACTION_MOVE:
                    if (!quickZoomEligible || e.getPointerCount() != 1) break;
                    dragQuickZoom(e);
                    break;
                  case MotionEvent.ACTION_UP:
                    if (!quickZoomEligible) break;
                    if (quickZooming) {
                      dragQuickZoom(e);
                      quickZooming = false;
                      if (!flingQuickZoom(e.getEventTime())) invalidate();
                    } else if (tapCandidate) {
                      animateZoom(1, quickFocusX, quickFocusY);
                    }
                    break;
                }
                return true;
              }

              @Override
              public void onLongPress(MotionEvent e) {
                if (doubleTap) return;
                tapCandidate = false;
                if (onLongPress != null)
                  onLongPress.at(
                      new Point(
                          Geo.lat(
                              Geo.y(lat, zoom) + (e.getY() - getHeight() / 2f) / scaledTileSize(),
                              zoom),
                          Geo.wrap(
                              Geo.lon(
                                  Geo.x(lon, zoom)
                                      + (e.getX() - getWidth() / 2f) / scaledTileSize(),
                                  zoom))));
              }
            });
    scales =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScaleBegin(ScaleGestureDetector d) {
                tapCandidate = false;
                twoFingerTap = false;
                pinched = false;
                pinchVelocity = pinchDirection = 0;
                invalidate();
                return true;
              }

              @Override
              public boolean onScale(ScaleGestureDetector d) {
                double before = camera()[2];
                scaleBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                double delta = camera()[2] - before;
                if (Math.abs(delta) > 0.00001 && d.getTimeDelta() > 0) {
                  double direction = Math.signum(delta);
                  double velocity = delta * 1000 / d.getTimeDelta();
                  pinchVelocity =
                      direction == pinchDirection
                          ? pinchVelocity * 0.25 + velocity * 0.75
                          : velocity;
                  pinchVelocity = Math.max(-4, Math.min(4, pinchVelocity));
                  pinchDirection = direction;
                  lastPinchMove = d.getEventTime();
                  pinchFocusX = d.getFocusX();
                  pinchFocusY = d.getFocusY();
                  pinched = true;
                }
                return true;
              }

              @Override
              public void onScaleEnd(ScaleGestureDetector d) {
                invalidate();
              }
            });
    // Handle one-finger zoom in log zoom units; retain the platform detector for pinches.
    scales.setQuickScaleEnabled(false);
  }

  private void dragQuickZoom(MotionEvent event) {
    float y = event.getY();
    if (!Float.isFinite(y)) return;
    if (!quickZooming) {
      float dy = y - quickFocusY;
      int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
      if (Math.abs(dy) <= slop) return;
      quickZooming = true;
      tapCandidate = false;
      // Apply the initial movement threshold once; reversals must have no dead zone.
      quickLastY = quickFocusY + Math.copySign(slop, dy);
      invalidate();
    }
    float delta = y - quickLastY;
    if (delta == 0) return;
    float direction = Math.signum(delta);
    if (quickDirection != 0 && direction != quickDirection && zoomVelocity != null) {
      // Earlier movement must not pull the zoom back after the user reverses and releases.
      zoomVelocity.clear();
      zoomVelocity.addMovement(event);
    }
    quickDirection = direction;
    scaleBy(Math.pow(2, delta / quickZoomDistance), quickFocusX, quickFocusY);
    lastQuickMove = event.getEventTime();
    quickLastY = y;
  }

  private boolean flingQuickZoom(long releasedAt) {
    if (zoomVelocity == null || releasedAt - lastQuickMove > 100) return false;
    zoomVelocity.computeCurrentVelocity(1000, quickZoomDistance * 8);
    float velocity = zoomVelocity.getYVelocity();
    if (!Float.isFinite(velocity)
        || velocity * quickDirection <= 0
        || Math.abs(velocity) < ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity())
      return false;
    return startZoomFling(velocity / quickZoomDistance, quickFocusX, quickFocusY);
  }

  private boolean startZoomFling(double velocity, float focusX, float focusY) {
    double level = camera()[2];
    if (!Double.isFinite(velocity)
        || Math.abs(velocity) < 0.12
        || velocity < 0 && level <= minZoom()
        || velocity > 0 && level >= maxZoom()) return false;
    flingStartLevel = level;
    flingVelocity = velocity;
    quickFocusX = focusX;
    quickFocusY = focusY;
    flingStarted = android.os.SystemClock.uptimeMillis();
    zoomFling = true;
    postInvalidateOnAnimation();
    return true;
  }

  private void startPanFling(float vx, float vy) {
    if (!Float.isFinite(vx) || !Float.isFinite(vy)) return;
    ViewConfiguration config = ViewConfiguration.get(getContext());
    if (Math.hypot(vx, vy) < config.getScaledMinimumFlingVelocity()) return;
    int max = config.getScaledMaximumFlingVelocity();
    panX = panY = 0;
    panScroller.fling(
        0,
        0,
        Math.round(Math.max(-max, Math.min(max, vx))),
        Math.round(Math.max(-max, Math.min(max, vy))),
        Integer.MIN_VALUE,
        Integer.MAX_VALUE,
        Integer.MIN_VALUE,
        Integer.MAX_VALUE);
    invalidate();
    postInvalidateOnAnimation();
  }

  private void checkTwoFingerTap(MotionEvent event) {
    if (!twoFingerTap) return;
    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    // Track each contact: translation, rotation and changing span all cancel a tap.
    if (event.getPointerCount() != 2
        || Math.hypot(event.getX(0) - firstFingerX, event.getY(0) - firstFingerY) > slop
        || Math.hypot(event.getX(1) - secondFingerX, event.getY(1) - secondFingerY) > slop)
      twoFingerTap = false;
  }

  private void animateZoom(int delta, float x, float y) {
    quickFocusX = x;
    quickFocusY = y;
    tapZoom = true;
    tapZoomStarted = android.os.SystemClock.uptimeMillis();
    tapZoomStart = camera()[2];
    tapZoomTarget = Math.max(minZoom(), Math.min(maxZoom(), tapZoomStart + delta));
    invalidate();
    postInvalidateOnAnimation();
  }

  @Override
  public void computeScroll() {
    if (!canRender()) return;
    if (panScroller.computeScrollOffset()) {
      int x = panScroller.getCurrX(), y = panScroller.getCurrY();
      panBy(x - panX, y - panY);
      panX = x;
      panY = y;
      if (panScroller.isFinished()) invalidate();
      else postInvalidateOnAnimation();
    }
    if (tapZoom) {
      double progress =
          Math.min(1, (android.os.SystemClock.uptimeMillis() - tapZoomStarted) / 180.0);
      double eased = 1 - Math.pow(1 - progress, 3);
      double level = tapZoomStart + (tapZoomTarget - tapZoomStart) * eased;
      scaleBy(Math.pow(2, level - camera()[2]), quickFocusX, quickFocusY);
      if (progress < 1) postInvalidateOnAnimation();
      else {
        tapZoom = false;
        invalidate();
      }
      return;
    }
    if (!zoomFling) return;
    double elapsed = (android.os.SystemClock.uptimeMillis() - flingStarted) / 1000.0;
    double remaining = Math.exp(-elapsed / ZOOM_DECAY_SECONDS);
    double travel = flingVelocity * ZOOM_DECAY_SECONDS;
    double target = flingStartLevel + travel * (1 - remaining);
    scaleBy(Math.pow(2, target - camera()[2]), quickFocusX, quickFocusY);
    if (Math.abs(travel * remaining) < 0.002
        || elapsed >= 2.4
        || target <= minZoom()
        || target >= maxZoom()) {
      zoomFling = false;
      invalidate();
    } else postInvalidateOnAnimation();
  }

  void stopMotion() {
    panScroller.forceFinished(true);
    tapZoom = false;
    zoomFling = false;
    quickZooming = false;
    quickZoomEligible = false;
    twoFingerTap = false;
    releasingPointers = false;
    multiVelocityX = multiVelocityY = 0;
    pinched = false;
    pinchVelocity = pinchDirection = 0;
    doubleTap = false;
    if (zoomVelocity != null) zoomVelocity.recycle();
    zoomVelocity = null;
  }

  @Override
  protected void onDetachedFromWindow() {
    windowVisible = false;
    suspendRendering();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    windowVisible = getWindowVisibility() == VISIBLE;
    if (canRender()) invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    windowVisible = visibility == VISIBLE;
    if (executor != null) updateRendering();
  }

  @Override
  public void onVisibilityAggregated(boolean visible) {
    super.onVisibilityAggregated(visible);
    hierarchyVisible = visible;
    if (executor != null) updateRendering();
  }

  /** A paused activity must not load tiles even if its window is still visible. */
  void setRenderingEnabled(boolean enabled) {
    renderingEnabled = enabled;
    updateRendering();
  }

  private boolean canRender() {
    return active && renderingEnabled && windowVisible && hierarchyVisible;
  }

  private void updateRendering() {
    if (canRender()) invalidate();
    else suspendRendering();
  }

  private void suspendRendering() {
    // GestureDetector otherwise retains its delayed tap/long-press callbacks after pause.
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
    gestures.onTouchEvent(cancel);
    scales.onTouchEvent(cancel);
    cancel.recycle();
    stopMotion();
    generation++;
    executor.getQueue().clear();
    pending.clear();
    viewport = Collections.emptySet();
    if (source != null) source.cancelRequests();
  }

  private int minZoom() {
    return source == null ? 2 : source.minZoom();
  }

  private int maxZoom() {
    return source == null ? 19 : source.maxZoom();
  }

  public void source(TileSource value) {
    source(value, true);
  }

  public void source(TileSource value, boolean recenter) {
    stopMotion();
    generation++;
    executor.getQueue().clear();
    if (source != null) source.cancelRequests();
    source = value;
    cache.evictAll();
    cacheChanged = true;
    cachedTiles.clear();
    previousTiles.clear();
    pending.clear();
    missing.clear();
    if (value != null && recenter) {
      lat = value.centerLat();
      lon = value.centerLon();
      zoom = Math.max(value.minZoom(), Math.min(13, value.maxZoom()));
      zoomScale = 1;
    }
    invalidate();
  }

  public TileSource tileSource() {
    return source;
  }

  private float scaledTileSize() {
    return (float) (tileSize * zoomScale);
  }

  /** Translate in screen pixels without resetting the active gesture or its velocity. */
  private void panBy(float dx, float dy) {
    if (!Float.isFinite(dx) || !Float.isFinite(dy)) return;
    lat = Math.max(-85, Math.min(85, Geo.lat(Geo.y(lat, zoom) + dy / scaledTileSize(), zoom)));
    lon = Geo.wrap(Geo.lon(Geo.x(lon, zoom) + dx / scaledTileSize(), zoom));
    invalidate();
  }

  /** Preserve the geographical point under the fingers at every fractional zoom. */
  void scaleBy(double factor, float focusX, float focusY) {
    // Keep malformed gesture input out of the camera calculations.
    if (!Double.isFinite(factor)
        || factor <= 0
        || !Float.isFinite(focusX)
        || !Float.isFinite(focusY)) return;
    double oldWorld = scaledTileSize() * (1 << zoom);
    double anchorX = Geo.x(lon, 0) + (focusX - getWidth() / 2f) / oldWorld;
    double anchorY = Geo.y(lat, 0) + (focusY - getHeight() / 2f) / oldWorld;
    double level = zoom + (Math.log(zoomScale) + Math.log(factor)) / Math.log(2);
    level = Math.max(minZoom(), Math.min(maxZoom(), level));
    zoom = (int) Math.round(level);
    zoomScale = Math.pow(2, level - zoom);
    double newWorld = scaledTileSize() * (1 << zoom);
    lon = Geo.wrap(Geo.lon(anchorX - (focusX - getWidth() / 2f) / newWorld, 0));
    lat = Math.max(-85, Math.min(85, Geo.lat(anchorY - (focusY - getHeight() / 2f) / newWorld, 0)));
    invalidate();
  }

  public void center(Point p) {
    stopMotion();
    lat = Math.max(-85, Math.min(85, p.lat));
    lon = p.lon;
    refreshTiles(false);
  }

  /** Fit every route segment in the space clear of the map controls. */
  public void fitRoute(List<Point> points) {
    if (points.isEmpty()) return;
    stopMotion();
    // Reparenting the map can leave its previous dimensions until the next layout.
    if (getWidth() == 0 || getHeight() == 0 || isLayoutRequested()) {
      pendingFit = new ArrayList<>(points);
      requestLayout();
      return;
    }
    pendingFit = null;
    fitRouteNow(points);
  }

  private void fitRouteNow(List<Point> points) {
    double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    double[] xs = new double[points.size()];
    for (int i = 0; i < points.size(); i++) {
      Point point = points.get(i);
      xs[i] = Geo.x(Geo.wrap(point.lon), 0);
      double y = Geo.y(point.lat, 0);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    // Exclude the largest empty longitude gap so date-line routes stay together.
    Arrays.sort(xs);
    double gap = xs[0] + 1 - xs[xs.length - 1], startX = xs[0];
    for (int i = 1; i < xs.length; i++) {
      double candidate = xs[i] - xs[i - 1];
      if (candidate > gap) {
        gap = candidate;
        startX = xs[i];
      }
    }
    double spanX = Math.max(0, 1 - gap), spanY = maxY - minY;
    float left = dp(32), right = dp(96), top = dp(32);
    float bottom = Math.max(dp(96), bottomOverlay + dp(32));
    double width = Math.max(1, getWidth() - left - right);
    double height = Math.max(1, getHeight() - top - bottom);
    double scale = Math.min(width / (tileSize * spanX), height / (tileSize * spanY));
    double level = Math.max(minZoom(), Math.min(maxZoom(), Math.log(scale) / Math.log(2)));
    zoom = (int) Math.round(level);
    zoomScale = Math.pow(2, level - zoom);
    double world = tileSize * Math.pow(2, level);
    lon = Geo.wrap(Geo.lon(startX + spanX / 2 + (right - left) / (2 * world), 0));
    lat = Math.max(-85, Math.min(85, Geo.lat((minY + maxY) / 2 + (bottom - top) / (2 * world), 0)));
    refreshTiles(false);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (pendingFit != null && getWidth() > 0 && getHeight() > 0) {
      List<Point> points = pendingFit;
      pendingFit = null;
      fitRouteNow(points);
    }
  }

  public Point center() {
    return new Point(lat, lon);
  }

  public int zoomLevel() {
    return zoom;
  }

  double[] camera() {
    return new double[] {lat, lon, zoom + Math.log(zoomScale) / Math.log(2)};
  }

  void restoreCamera(double[] camera) {
    if (camera.length != 3
        || !Double.isFinite(camera[0])
        || !Double.isFinite(camera[1])
        || !Double.isFinite(camera[2])) return;
    stopMotion();
    lat = Math.max(-85, Math.min(85, camera[0]));
    lon = Geo.wrap(camera[1]);
    double level =
        Math.max(
            source == null ? 0 : source.minZoom(),
            Math.min(source == null ? 19 : source.maxZoom(), camera[2]));
    zoom = (int) Math.round(level);
    zoomScale = Math.pow(2, level - zoom);
    refreshTiles(false);
  }

  public double[] bounds() {
    return new double[] {
      Geo.lon(Geo.x(lon, zoom) - getWidth() / 2f / scaledTileSize(), zoom),
      Geo.lat(Geo.y(lat, zoom) + getHeight() / 2f / scaledTileSize(), zoom),
      Geo.lon(Geo.x(lon, zoom) + getWidth() / 2f / scaledTileSize(), zoom),
      Geo.lat(Geo.y(lat, zoom) - getHeight() / 2f / scaledTileSize(), zoom)
    };
  }

  public void zoom(int delta) {
    stopMotion();
    scaleBy(Math.pow(2, delta), getWidth() / 2f, getHeight() / 2f);
    refreshTiles(false);
  }

  public void refreshTiles(boolean discardBitmaps) {
    // Canceling can unblock the worker immediately. Invalidate old work first.
    generation++;
    executor.getQueue().clear();
    if (source != null) source.cancelRequests();
    pending.clear();
    missing.clear();
    if (discardBitmaps) {
      cache.evictAll();
      cacheChanged = true;
      cachedTiles.clear();
      previousTiles.clear();
    }
    invalidate();
  }

  public void reloadOnlineTiles() {
    // Offline parent enlargements must not masquerade as exact tiles after going online.
    previousTiles.clear();
    for (Map.Entry<String, Bitmap> entry : cache.snapshot().entrySet()) {
      String[] key = entry.getKey().split("/");
      previousTiles.add(
          new CachedTile(
              Integer.parseInt(key[0]),
              Integer.parseInt(key[1]),
              Integer.parseInt(key[2]),
              entry.getValue()));
    }
    previousTiles.sort(Comparator.comparingInt(tile -> tile.z));
    cache.evictAll();
    cachedTiles.clear();
    cacheChanged = true;
    refreshTiles(false);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      gestures.setIsLongpressEnabled(true);
      boolean wasMoving = zoomFling || tapZoom || !panScroller.isFinished();
      stopMotion();
      multiplePointers = false;
      if (wasMoving) invalidate();
      zoomVelocity = VelocityTracker.obtain();
      zoomVelocity.clear();
      tapCandidate = true;
      downX = e.getX();
      downY = e.getY();
    }
    if (zoomVelocity != null) zoomVelocity.addMovement(e);
    if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
      pinched = false;
      pinchVelocity = pinchDirection = 0;
      pinchX = focus(e, true, -1);
      pinchY = focus(e, false, -1);
      twoFingerTap = !multiplePointers && tapCandidate && e.getPointerCount() == 2 && !doubleTap;
      twoFingerX = pinchX;
      twoFingerY = pinchY;
      firstFingerX = e.getX(0);
      firstFingerY = e.getY(0);
      secondFingerX = e.getX(1);
      secondFingerY = e.getY(1);
      multiplePointers = true;
      releasingPointers = false;
      multiVelocityX = multiVelocityY = 0;
      multiEventTime = e.getEventTime();
      lastMultiPanMove = 0;
      // Cancel single-finger timers and callbacks for the rest of this contact sequence.
      MotionEvent cancel = MotionEvent.obtain(e);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      gestures.onTouchEvent(cancel);
      cancel.recycle();
      doubleTap = false;
      tapCandidate = false;
      quickZoomEligible = false;
      if (quickZooming) {
        quickZooming = false;
        invalidate();
      }
    }
    // Move the pinch anchor along with the fingers, including two-finger drags
    // whose span never crosses ScaleGestureDetector's scaling threshold.
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE
        && e.getPointerCount() > 1
        && !releasingPointers) {
      float x = focus(e, true, -1), y = focus(e, false, -1);
      checkTwoFingerTap(e);
      float dx = pinchX - x, dy = pinchY - y;
      long dt = e.getEventTime() - multiEventTime;
      if (dt > 0) {
        multiVelocityX = multiVelocityX * 0.25f + dx * 1000 / dt * 0.75f;
        multiVelocityY = multiVelocityY * 0.25f + dy * 1000 / dt * 0.75f;
        if (dx != 0 || dy != 0) lastMultiPanMove = e.getEventTime();
      }
      multiEventTime = e.getEventTime();
      panBy(dx, dy);
      pinchX = x;
      pinchY = y;
    }
    if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && !releasingPointers) {
      checkTwoFingerTap(e);
      if (e.getPointerCount() == 2) {
        // Release momentum while the velocity still describes both fingers. The remaining
        // contact belongs to this gesture, even if it sends MOVE events before lifting.
        releasingPointers = true;
        if (!twoFingerTap) {
          if (pinched && e.getEventTime() - lastPinchMove <= 100)
            startZoomFling(pinchVelocity, pinchFocusX, pinchFocusY);
          if (lastMultiPanMove != 0 && e.getEventTime() - lastMultiPanMove <= 100)
            startPanFling(multiVelocityX, multiVelocityY);
        }
      } else {
        pinchX = focus(e, true, e.getActionIndex());
        pinchY = focus(e, false, e.getActionIndex());
        multiVelocityX = multiVelocityY = 0;
        multiEventTime = e.getEventTime();
      }
    }
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE
        && Math.hypot(e.getX() - downX, e.getY() - downY)
            > ViewConfiguration.get(getContext()).getScaledTouchSlop()) tapCandidate = false;
    scales.onTouchEvent(e);
    if (!multiplePointers) gestures.onTouchEvent(e);
    if (e.getActionMasked() == MotionEvent.ACTION_UP
        && twoFingerTap
        && e.getEventTime() - e.getDownTime() <= ViewConfiguration.getDoubleTapTimeout()) {
      animateZoom(-1, twoFingerX, twoFingerY);
      twoFingerTap = false;
    }
    if (e.getActionMasked() == MotionEvent.ACTION_UP && tapCandidate) performClick();
    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      tapCandidate = false;
      stopMotion();
      invalidate();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_UP
        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      gestures.setIsLongpressEnabled(true);
      doubleTap = false;
      quickZoomEligible = false;
      if (zoomVelocity != null) zoomVelocity.recycle();
      zoomVelocity = null;
    }
    return true;
  }

  private static float focus(MotionEvent event, boolean horizontal, int skip) {
    float sum = 0;
    int count = 0;
    for (int i = 0; i < event.getPointerCount(); i++) {
      if (i == skip) continue;
      sum += horizontal ? event.getX(i) : event.getY(i);
      count++;
    }
    return sum / count;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(
      android.view.accessibility.AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.addAction(
        new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Zoom in"));
    info.addAction(
        new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Zoom out"));
  }

  @Override
  public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
      zoom(1);
      return true;
    }
    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
      zoom(-1);
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }

  private float px(double longitude) {
    return (float)
        (getWidth() / 2f + Geo.wrap(longitude - lon) / 360 * (1 << zoom) * scaledTileSize());
  }

  private float py(double latitude) {
    return (float)
        (getHeight() / 2f + (Geo.y(latitude, zoom) - Geo.y(lat, zoom)) * scaledTileSize());
  }

  @Override
  protected void onDraw(Canvas c) {
    if (!canRender()) return;
    super.onDraw(c);
    android.os.Trace.beginSection("Cairn.map.draw");
    try {
      drawMap(c);
    } finally {
      android.os.Trace.endSection();
    }
  }

  private void drawMap(Canvas c) {
    c.drawColor(AppPalette.BACKGROUND);
    float tileSize = scaledTileSize();
    double cx = Geo.x(lon, zoom), cy = Geo.y(lat, zoom);
    int x0 = (int) Math.floor(cx - getWidth() / 2f / tileSize),
        x1 = (int) Math.ceil(cx + getWidth() / 2f / tileSize) - 1;
    int y0 = (int) Math.floor(cy - getHeight() / 2f / tileSize),
        y1 = (int) Math.ceil(cy + getHeight() / 2f / tileSize) - 1;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1);
    paint.setColor(0x2243474E);
    int drawn = drawCachedBackground(c, cx, cy, tileSize);
    for (int x = x0; x <= x1; x++)
      for (int y = y0; y <= y1; y++) {
        float left = (float) (getWidth() / 2f + (x - cx) * tileSize),
            top = (float) (getHeight() / 2f + (y - cy) * tileSize);
        RectF destination = tileRect;
        destination.set(left, top, left + tileSize, top + tileSize);
        c.drawRect(destination, paint);
      }
    if (source != null && active) {
      List<TilePlanner.Tile> visible =
          TileViewport.ordered(zoom, cx, cy, getWidth(), getHeight(), tileSize);
      Set<String> keys = new HashSet<>();
      for (TilePlanner.Tile tile : visible) keys.add(tile.z + "/" + tile.x + "/" + tile.y);
      if (!keys.equals(viewport)) {
        viewport = keys;
        missing.retainAll(keys);
        // Keep useful in-flight transfers; drop obsolete work and reorder queued tiles
        // around the current center. No gesture or momentum settling delay.
        for (TileRequest task : new ArrayList<>(pending.values())) {
          if (!keys.contains(task.key)) {
            task.cancelled = true;
            executor.remove(task);
            pending.remove(task.key);
          } else if (executor.remove(task)) pending.remove(task.key);
        }
        source.cancelObsoleteRequests();
      }
      boolean complete = true;
      for (TilePlanner.Tile tile : visible) {
        String key = tile.z + "/" + tile.x + "/" + tile.y;
        if (cache.get(key) == null) {
          complete = false;
          request(key, tile.z, tile.x, tile.y);
        }
      }
      if (complete) previousTiles.clear();
    }
    int[] colors = {0xff647BAC, 0xffA079A3, 0xffA27D37};
    for (int i = 0; i < otherRoutes.size(); i++)
      line(c, otherRoutes.get(i), colors[i % colors.length], 3);
    line(c, route, Color.WHITE, 8);
    line(c, route, 0xff235D4D, 5);
    line(c, track, 0xffC36C39, 3);
    paint.setStyle(Paint.Style.FILL);
    for (int i = 0; i < waypoints.size(); i++) {
      Point p = waypoints.get(i);
      paint.setColor(0xff235D4D);
      c.drawCircle(px(p.lon), py(p.lat), dp(12), paint);
      paint.setColor(Color.WHITE);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(dp(12));
      c.drawText("" + (i + 1), px(p.lon), py(p.lat) + dp(4), paint);
    }
    if (position != null) {
      boolean stale = System.currentTimeMillis() - position.time > 120000;
      paint.setColor(0x3381A4CC);
      double ppm = (1 << zoom) * tileSize / (40075016.686 * Math.cos(Math.toRadians(position.lat)));
      c.drawCircle(
          px(position.lon),
          py(position.lat),
          (float) Math.max(dp(19), Math.min(getWidth(), position.accuracy * ppm)),
          paint);
      paint.setColor(Color.WHITE);
      c.drawCircle(px(position.lon), py(position.lat), dp(9), paint);
      paint.setColor(stale ? 0xff777777 : 0xff2767AE);
      c.drawCircle(px(position.lon), py(position.lat), dp(6), paint);
    }
    paint.setTextSize(dp(11));
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setColor(AppPalette.MUTED);
    if (source == null) {
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(dp(16));
      c.drawText("Your offline map goes here", getWidth() / 2f, getHeight() * 0.40f, paint);
      paint.setTextSize(dp(12));
      c.drawText(
          "Routes → Add files to open a map", getWidth() / 2f, getHeight() * 0.40f + dp(24), paint);
    }
    if (source instanceof IgnMap && drawn == 0) {
      IgnMap ign = (IgnMap) source;
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(dp(16));
      c.drawText(
          ign.online() ? "Plan IGN · loading preview" : "Plan IGN · this area is not saved",
          getWidth() / 2f,
          getHeight() * 0.40f,
          paint);
      paint.setTextSize(dp(12));
      c.drawText(
          ign.lastError() != null
              ? "Preview unavailable. Check your connection and try again."
              : ign.online()
                  ? "Tiles are saved as they arrive"
                  : "Tap the cloud to load and keep visible maps.",
          getWidth() / 2f,
          getHeight() * 0.40f + dp(24),
          paint);
    }
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTextSize(dp(10));
    if (source != null && !(source instanceof IgnMap)) {
      String text =
          android.text.Html.fromHtml(source.attribution(), android.text.Html.FROM_HTML_MODE_LEGACY)
              .toString();
      if (text.length() > 100) text = text.substring(0, 100) + "…";
      float width = paint.measureText(text) + dp(16);
      paint.setColor(AppPalette.SURFACE);
      c.drawRect(dp(4), dp(5), Math.min(getWidth(), width), dp(26), paint);
      paint.setColor(AppPalette.MUTED);
      c.drawText(text, dp(8), dp(20), paint);
    }
    // A ground-distance scale remains useful with both saved and enlarged tiles.
    double pixelsPerMeter =
        (1 << zoom) * scaledTileSize() / (40075016.686 * Math.cos(Math.toRadians(lat)));
    double desired = dp(96) / pixelsPerMeter, unit = Math.pow(10, Math.floor(Math.log10(desired)));
    double metres = desired / unit >= 5 ? 5 * unit : desired / unit >= 2 ? 2 * unit : unit;
    float scaleWidth = (float) (metres * pixelsPerMeter),
        right = dp(116),
        bottom = getHeight() - dp(20) - bottomOverlay;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(AppPalette.SURFACE);
    c.drawRect(right - scaleWidth - dp(8), bottom - dp(22), right + dp(6), bottom + dp(7), paint);
    paint.setColor(AppPalette.TEXT);
    paint.setStrokeWidth(dp(2));
    c.drawLine(right - scaleWidth, bottom, right, bottom, paint);
    c.drawLine(right - scaleWidth, bottom - dp(5), right - scaleWidth, bottom + dp(2), paint);
    c.drawLine(right, bottom - dp(5), right, bottom + dp(2), paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    paint.setTextSize(dp(11));
    String scaleLabel =
        metres >= 1000
            ? String.format(Locale.getDefault(), "%.1f km", metres / 1000)
            : String.format(Locale.getDefault(), "%.0f m", metres);
    c.drawText(scaleLabel, right, bottom - dp(8), paint);
  }

  /** Reproject every retained tile as a backdrop, even during a large multi-level pinch. */
  private int drawCachedBackground(Canvas canvas, double cx, double cy, float size) {
    if (cacheChanged) {
      cachedTiles.clear();
      for (Map.Entry<String, Bitmap> entry : cache.snapshot().entrySet()) {
        String[] parts = entry.getKey().split("/");
        cachedTiles.add(
            new CachedTile(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                entry.getValue()));
      }
      cachedTiles.sort(Comparator.comparingInt(tile -> tile.z));
      cacheChanged = false;
    }
    int drawn = 0;
    double world = 1 << zoom;
    List<CachedTile> backdrop = new ArrayList<>(previousTiles);
    backdrop.addAll(cachedTiles);
    backdrop.sort(Comparator.comparingInt(tile -> tile.z));
    for (CachedTile tile : backdrop) {
      double factor = Math.scalb(1, zoom - tile.z);
      double dx = (tile.x + 0.5) * factor - cx;
      dx -= Math.rint(dx / world) * world;
      float left = (float) (getWidth() / 2f + (dx - factor / 2) * size);
      float top = (float) (getHeight() / 2f + (tile.y * factor - cy) * size);
      float width = (float) (factor * size);
      if (left >= getWidth() || top >= getHeight() || left + width <= 0 || top + width <= 0)
        continue;
      tileRect.set(left, top, left + width, top + width);
      canvas.drawBitmap(tile.bitmap, null, tileRect, bitmapPaint);
      drawn++;
    }
    return drawn;
  }

  private static final class CachedTile {
    final int z, x, y;
    final Bitmap bitmap;

    CachedTile(int z, int x, int y, Bitmap bitmap) {
      this.z = z;
      this.x = x;
      this.y = y;
      this.bitmap = bitmap;
    }
  }

  private void request(String key, int z, int x, int y) {
    if (pending.containsKey(key) || missing.contains(key) || !canRender()) return;
    TileRequest task = new TileRequest(key, z, x, y);
    pending.put(key, task);
    executor.execute(task);
  }

  private final class TileRequest implements Runnable {
    final String key;
    final int z, x, y, gen = generation;
    final TileSource current = source;
    volatile boolean cancelled;

    TileRequest(String key, int z, int x, int y) {
      this.key = key;
      this.z = z;
      this.x = x;
      this.y = y;
    }

    boolean obsolete() {
      return cancelled || gen != generation || !active;
    }

    @Override
    public void run() {
      if (obsolete()) return;
      Bitmap result = null;
      try {
        result = current.tile(z, x, y, this::obsolete);
      } catch (RuntimeException ignored) {
      }
      Bitmap value = result;
      if (obsolete()) {
        if (value != null) value.recycle();
        return;
      }
      main.post(
          () -> {
            if (obsolete()) {
              if (value != null) value.recycle();
              return;
            }
            pending.remove(key, this);
            if (value != null) {
              cache.put(key, value);
              cacheChanged = true;
            } else {
              if (missing.size() > 5000) missing.clear();
              missing.add(key);
            }
            invalidate();
          });
    }
  }

  private void line(Canvas c, List<Point> points, int color, float width) {
    if (points.isEmpty()) return;
    Path path = linePath;
    path.reset();
    Point previous = null;
    for (Point p : points) {
      if (previous == null || p.segment != previous.segment) path.moveTo(px(p.lon), py(p.lat));
      else path.lineTo(px(p.lon), py(p.lat));
      previous = p;
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeWidth(dp(width));
    paint.setColor(color);
    c.drawPath(path, paint);
    paint.setStyle(Paint.Style.FILL);
  }

  private float dp(float value) {
    return value * getResources().getDisplayMetrics().density;
  }

  public void dispose() {
    stopMotion();
    active = false;
    generation++;
    executor.shutdownNow();
    main.removeCallbacksAndMessages(null);
    cache.evictAll();
    cacheChanged = true;
    cachedTiles.clear();
    previousTiles.clear();
  }
}
