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
  private final float tileSize;
  private TileSource source;
  private volatile int generation = 0;
  private volatile boolean active = true;
  private boolean tapCandidate, doubleTap;
  private float downX, downY;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final RectF tileRect = new RectF();
  private final Path linePath = new Path();
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
  private final Set<String> pending = new HashSet<>(), missing = new HashSet<>();
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
  // One zoom level per 160 dp, independent of distance from the second tap or event rate.
  private final float quickZoomDistance;
  private final OverScroller zoomScroller;
  private VelocityTracker zoomVelocity;
  private boolean quickZooming, quickZoomEligible, zoomFling;
  private float quickFocusX, quickFocusY, quickLastY, quickDirection;
  private int flingPosition;
  private long lastQuickMove;

  public TrekMapView(Context context) {
    super(context);
    tileSize = 256 * getResources().getDisplayMetrics().density;
    quickZoomDistance = dp(160);
    zoomScroller = new OverScroller(context);
    zoomScroller.setFriction(0.02f);
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
                if (doubleTap || scales.isInProgress()) return true;
                center(
                    new Point(
                        Geo.lat(Geo.y(lat, zoom) + dy / scaledTileSize(), zoom),
                        Geo.wrap(Geo.lon(Geo.x(lon, zoom) + dx / scaledTileSize(), zoom))));
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                // The second press may become a one-finger quick-scale gesture.
                doubleTap = true;
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
                      if (!flingQuickZoom(e.getEventTime())) refreshTiles(false);
                    } else if (tapCandidate) {
                      scaleBy(2, e.getX(), e.getY());
                      refreshTiles(false);
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
                refreshTiles(false);
                return true;
              }

              @Override
              public boolean onScale(ScaleGestureDetector d) {
                scaleBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
              }

              @Override
              public void onScaleEnd(ScaleGestureDetector d) {
                refreshTiles(false);
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
      refreshTiles(false);
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
    zoomVelocity.computeCurrentVelocity(1000, quickZoomDistance * 4);
    float velocity = zoomVelocity.getYVelocity();
    if (!Float.isFinite(velocity)
        || velocity * quickDirection <= 0
        || Math.abs(velocity) < ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity())
      return false;
    double level = camera()[2];
    int minimum = (int) ((minZoom() - level) * quickZoomDistance);
    int maximum = (int) ((maxZoom() - level) * quickZoomDistance);
    if (velocity < 0 && minimum == 0 || velocity > 0 && maximum == 0) return false;
    flingPosition = 0;
    zoomFling = true;
    zoomScroller.fling(0, 0, 0, (int) velocity, 0, 0, minimum, maximum);
    postInvalidateOnAnimation();
    return true;
  }

  @Override
  public void computeScroll() {
    if (!zoomFling) return;
    if (zoomScroller.computeScrollOffset()) {
      int position = zoomScroller.getCurrY();
      scaleBy(
          Math.pow(2, (position - flingPosition) / quickZoomDistance), quickFocusX, quickFocusY);
      flingPosition = position;
      postInvalidateOnAnimation();
    } else {
      zoomFling = false;
      refreshTiles(false);
    }
  }

  void stopMotion() {
    zoomScroller.forceFinished(true);
    zoomFling = false;
    quickZooming = false;
    quickZoomEligible = false;
    doubleTap = false;
    if (zoomVelocity != null) zoomVelocity.recycle();
    zoomVelocity = null;
  }

  @Override
  protected void onDetachedFromWindow() {
    stopMotion();
    super.onDetachedFromWindow();
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
      boolean wasMoving = zoomFling;
      stopMotion();
      if (wasMoving) refreshTiles(false);
      zoomVelocity = VelocityTracker.obtain();
      tapCandidate = true;
      downX = e.getX();
      downY = e.getY();
    }
    if (zoomVelocity != null) zoomVelocity.addMovement(e);
    if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
      tapCandidate = false;
      quickZoomEligible = false;
      if (quickZooming) {
        quickZooming = false;
        refreshTiles(false);
      }
    }
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE
        && Math.hypot(e.getX() - downX, e.getY() - downY)
            > ViewConfiguration.get(getContext()).getScaledTouchSlop()) tapCandidate = false;
    scales.onTouchEvent(e);
    gestures.onTouchEvent(e);
    if (e.getActionMasked() == MotionEvent.ACTION_UP && tapCandidate) performClick();
    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      tapCandidate = false;
      if (quickZooming) refreshTiles(false);
      stopMotion();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_UP
        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      doubleTap = false;
      quickZoomEligible = false;
      if (zoomVelocity != null) zoomVelocity.recycle();
      zoomVelocity = null;
    }
    return true;
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
    super.onDraw(c);
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
    int n = 1 << zoom, drawn = drawCachedBackground(c, cx, cy, tileSize);
    for (int x = x0; x <= x1; x++)
      for (int y = y0; y <= y1; y++) {
        float left = (float) (getWidth() / 2f + (x - cx) * tileSize),
            top = (float) (getHeight() / 2f + (y - cy) * tileSize);
        RectF destination = tileRect;
        destination.set(left, top, left + tileSize, top + tileSize);
        c.drawRect(destination, paint);
        if (source != null && y >= 0 && y < n) {
          int wrappedX = Math.floorMod(x, n);
          String key = zoom + "/" + wrappedX + "/" + y;
          Bitmap bitmap = cache.get(key);
          if (bitmap != null) {
            c.drawBitmap(bitmap, null, destination, bitmapPaint);
            drawn++;
          } else {
          }
        }
      }
    if (source != null && !scales.isInProgress() && !quickZooming && !zoomFling) {
      boolean complete = true;
      for (TilePlanner.Tile tile :
          TileViewport.ordered(zoom, cx, cy, getWidth(), getHeight(), tileSize)) {
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
    if (source != null) {
      String text =
          android.text.Html.fromHtml(source.attribution(), android.text.Html.FROM_HTML_MODE_LEGACY)
              .toString();
      if (!route.isEmpty() || !otherRoutes.isEmpty()) text += " · routing © OSM";
      if (text.length() > 100) text = text.substring(0, 100) + "…";
      float width = paint.measureText(text) + dp(16);
      paint.setColor(AppPalette.SURFACE);
      c.drawRect(dp(4), dp(5), Math.min(getWidth(), width), dp(26), paint);
      paint.setColor(AppPalette.MUTED);
      c.drawText(text, dp(8), dp(20), paint);
    }
    // A ground-distance scale remains useful with both saved and enlarged tiles.
    double pixelsPerMeter = (1 << zoom) * tileSize / (40075016.686 * Math.cos(Math.toRadians(lat)));
    double desired = dp(96) / pixelsPerMeter, unit = Math.pow(10, Math.floor(Math.log10(desired)));
    double metres = desired / unit >= 5 ? 5 * unit : desired / unit >= 2 ? 2 * unit : unit;
    float scaleWidth = (float) (metres * pixelsPerMeter),
        right = dp(116),
        bottom = getHeight() - dp(92) - bottomOverlay;
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
    if (pending.contains(key) || missing.contains(key) || !active) return;
    pending.add(key);
    TileSource current = source;
    int gen = generation;
    executor.execute(
        () -> {
          if (gen != generation || !active) return;
          Bitmap result = null;
          try {
            result = current.tile(z, x, y, () -> gen != generation || !active);
          } catch (RuntimeException ignored) {
          }
          Bitmap value = result;
          main.post(
              () -> {
                if (gen != generation || !active) return;
                pending.remove(key);
                if (value != null) {
                  cache.put(key, value);
                  cacheChanged = true;
                } else {
                  if (missing.size() > 5000) missing.clear();
                  missing.add(key);
                }
                invalidate();
              });
        });
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
