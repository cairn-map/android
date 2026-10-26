package app.cairn.trek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.widget.SeekBar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A touch/keyboard/accessibility slider drawn as a cached elevation profile. */
final class ElevationGraph extends SeekBar {
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path line = new Path();
  private final Path connectors = new Path();
  private final DashPathEffect connectorDashes;
  private final Path excludedAreas = new Path();
  private final Path gpsMarker = new Path();
  private List<Point> source = Collections.emptyList();
  private ElevationProfile profile = new ElevationProfile();
  private boolean recordingProfile;
  private boolean dirty = true;
  private boolean cursorActive;
  private RouteProximity gpsRoute;
  private long gpsRouteKey;
  private Point gpsPosition;
  private double gpsFraction, gpsDistance = Double.NaN;
  private int pointer = -1;
  private OnSeekBarChangeListener listener;

  ElevationGraph(Context context) {
    super(context);
    connectorDashes = new DashPathEffect(new float[] {dp(4), dp(4)}, 0);
    setMax(10000);
    setKeyProgressIncrement(100);
    setBackground(null);
    setThumb(null);
    setPadding(dp(52), dp(6), dp(14), dp(24));
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    setContentDescription("Elevation along route. Slide to inspect a point.");
  }

  void bind(List<Point> points) {
    bind(points, false);
  }

  void bind(List<Point> points, boolean recording) {
    if (source != points
        || points.size() < profile.points.size()
        || recordingProfile != recording) {
      source = points;
      recordingProfile = recording;
      profile = new ElevationProfile(recording);
      dirty = true;
    }
    for (int i = profile.points.size(); i < points.size(); i++) {
      profile.add(points.get(i));
      dirty = true;
    }
    setEnabled(!points.isEmpty());
    if (dirty) invalidate();
  }

  Point selectedPoint() {
    return cursorActive ? profile.at(getProgress() / (double) getMax()) : null;
  }

  long geometryKey() {
    return profile.geometryKey;
  }

  double gpsDistance() {
    return gpsDistance;
  }

  String pointText(boolean cursor) {
    double fraction = cursor ? getProgress() / (double) getMax() : gpsFraction;
    Point point = cursor ? selectedPoint() : Double.isFinite(gpsDistance) ? profile.at(fraction) : null;
    if (point == null) return "";
    return String.format(
        Locale.getDefault(),
        "%s%.2f km · %s m%s · %.0f%%",
        cursor ? "" : "GPS ",
        profile.distance * fraction / 1000,
        Double.isFinite(point.elevation)
            ? String.format(Locale.getDefault(), "%.0f", point.elevation)
            : "—",
        profile.startTime > 0 && point.time >= profile.startTime
            ? " · " + RouteSummary.durationText(point.time - profile.startTime)
            : "",
        fraction * 100);
  }

  String statusText() {
    if (profile.points.isEmpty()) return "";
    if (profile.hasExcludedElevation) {
      String excluded =
          recordingProfile
              ? "Above sea level · shaded: uncertain elevation excluded"
              : "Above sea level · shaded: elevation unavailable";
      return excluded
          + (profile.connectors.isEmpty()
              ? ""
              : " · dashed: display-only estimate; cursor elevation unavailable");
    }
    return recordingProfile
        ? "Filtered GPS estimate · above sea level"
        : "Elevation above sea level";
  }

  void setGpsPosition(Point position) {
    // Use existing fixes only. Hide stale, inaccurate or distant positions.
    if (position == null
        || position.time <= 0
        || System.currentTimeMillis() - position.time > 120_000
        || !Float.isFinite(position.accuracy)
        || position.accuracy < 0
        || position.accuracy > 50
        || profile.points.isEmpty()) {
      gpsPosition = null;
      if (Double.isFinite(gpsDistance)) {
        gpsDistance = Double.NaN;
        invalidate();
      }
      return;
    }
    if (gpsRoute == null || gpsRouteKey != geometryKey()) {
      gpsRoute = new RouteProximity(profile.points);
      gpsRouteKey = geometryKey();
      gpsPosition = null;
    }
    if (gpsPosition == position) return;
    gpsPosition = position;
    gpsDistance = Double.NaN;
    RouteProximity.Match match = gpsRoute.locate(position);
    if (match != null && match.distance <= 50) {
      gpsFraction = profile.fraction(match.index);
      if (match.fraction > 0)
        gpsFraction +=
            match.fraction * (profile.fraction(match.index + 1) - profile.fraction(match.index));
      gpsDistance = profile.distance * gpsFraction;
    }
    invalidate();
  }

  void setCursorActive(boolean active) {
    cursorActive = active;
    invalidate();
  }

  void clearCursor() {
    cursorActive = false;
    // Reset keyboard/accessibility stepping without reporting a new inspected point.
    super.setOnSeekBarChangeListener(null);
    setProgress(0);
    super.setOnSeekBarChangeListener(listener);
    invalidate();
  }

  double selectedDistance() {
    return profile.distance * getProgress() / getMax();
  }

  private float plotWidth() {
    return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
  }

  private float bottom() {
    return getHeight() - getPaddingBottom();
  }

  double axisMinimum() {
    if (!profile.hasElevation()) return 0;
    return profile.maximum - profile.minimum < 20
        ? profile.minimum + (profile.maximum - profile.minimum) / 2 - 10
        : profile.minimum;
  }

  double axisMaximum() {
    return profile.maximum - profile.minimum < 20 ? axisMinimum() + 20 : profile.maximum;
  }

  private float y(double elevation) {
    return (float)
        (bottom()
            - (elevation - axisMinimum())
                / (axisMaximum() - axisMinimum())
                * (bottom() - getPaddingTop()));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    dirty = true;
  }

  @Override
  protected synchronized void onDraw(Canvas canvas) {
    if (dirty) {
      line.rewind();
      connectors.rewind();
      excludedAreas.rewind();
      Point previous = null;
      int excludedStart = -1;
      for (int i = 0; i < profile.points.size(); i++) {
        Point point = profile.points.get(i);
        if (!Double.isFinite(point.elevation)) {
          if (excludedStart < 0) excludedStart = i;
          previous = null;
          continue;
        }
        if (excludedStart >= 0) {
          addExcludedArea(excludedStart, i - 1);
          excludedStart = -1;
        }
        float x = getPaddingLeft() + (float) profile.fraction(i) * plotWidth();
        if (previous == null || previous.segment != point.segment) {
          line.moveTo(x, y(point.elevation));
          // A tiny line with round caps also renders isolated elevation samples.
          line.lineTo(x + .01f, y(point.elevation));
        } else line.lineTo(x, y(point.elevation));
        previous = point;
      }
      if (excludedStart >= 0) addExcludedArea(excludedStart, profile.points.size() - 1);
      // x is cumulative distance, so a straight connector interpolates by distance,
      // regardless of excluded sample spacing. Keep it separate from accepted strokes.
      for (ElevationProfile.Connector connector : profile.connectors) {
        connectors.moveTo(
            getPaddingLeft() + (float) profile.fraction(connector.first) * plotWidth(),
            y(profile.points.get(connector.first).elevation));
        connectors.lineTo(
            getPaddingLeft() + (float) profile.fraction(connector.last) * plotWidth(),
            y(profile.points.get(connector.last).elevation));
      }
      dirty = false;
    }
    ink.setStyle(Paint.Style.FILL);
    ink.setColor(AppPalette.ON_SELECTION);
    ink.setAlpha(38);
    canvas.drawPath(excludedAreas, ink);
    ink.setAlpha(255);
    ink.setStyle(Paint.Style.STROKE);
    ink.setStrokeWidth(dp(1));
    ink.setColor(AppPalette.OUTLINE);
    canvas.drawLine(getPaddingLeft(), bottom(), getWidth() - getPaddingRight(), bottom(), ink);
    ink.setStyle(Paint.Style.FILL);
    ink.setTextSize(dp(10));
    ink.setColor(AppPalette.MUTED);
    ink.setTextAlign(Paint.Align.LEFT);
    float distanceBaseline = bottom() + dp(4) - ink.ascent();
    canvas.drawText("0 km", getPaddingLeft(), distanceBaseline, ink);
    ink.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText(
        String.format(Locale.getDefault(), "%.1f km", profile.distance / 1000),
        getWidth() - getPaddingRight(),
        distanceBaseline,
        ink);
    if (profile.hasElevation()) {
      canvas.drawText(
          String.format(Locale.getDefault(), "%.0f m", axisMaximum()),
          getPaddingLeft() - dp(6),
          getPaddingTop() + dp(4),
          ink);
      canvas.drawText(
          String.format(Locale.getDefault(), "%.0f m", axisMinimum()),
          getPaddingLeft() - dp(6),
          bottom(),
          ink);
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(dp(2));
      ink.setStrokeCap(Paint.Cap.ROUND);
      ink.setColor(AppPalette.ON_SELECTION);
      canvas.drawPath(line, ink);
      ink.setAlpha(160);
      ink.setPathEffect(connectorDashes);
      canvas.drawPath(connectors, ink);
      ink.setPathEffect(null);
      ink.setAlpha(255);
    } else {
      ink.setTextAlign(Paint.Align.CENTER);
      canvas.drawText(
          "—",
          getPaddingLeft() + plotWidth() / 2,
          (getPaddingTop() + bottom()) / 2,
          ink);
    }
    if (Double.isFinite(gpsDistance)) {
      float x = getPaddingLeft() + (float) gpsFraction * plotWidth();
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(dp(2));
      ink.setColor(AppPalette.ACCENT);
      canvas.drawLine(x, getPaddingTop(), x, bottom(), ink);
      gpsMarker.rewind();
      gpsMarker.moveTo(x, bottom() - dp(6));
      gpsMarker.lineTo(x - dp(4), bottom() + dp(2));
      gpsMarker.lineTo(x + dp(4), bottom() + dp(2));
      gpsMarker.close();
      ink.setStyle(Paint.Style.FILL);
      canvas.drawPath(gpsMarker, ink);
    }
    Point point = selectedPoint();
    if (point != null) {
      float x = getPaddingLeft() + plotWidth() * getProgress() / getMax();
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(dp(1));
      ink.setColor(AppPalette.ON_SELECTION);
      canvas.drawLine(x, getPaddingTop(), x, bottom(), ink);
    }
  }

  private void addExcludedArea(int first, int last) {
    double left = first == 0 ? 0 : profile.fraction(first - 1);
    double right = last == profile.points.size() - 1 ? 1 : profile.fraction(last + 1);
    float x = getPaddingLeft() + (float) left * plotWidth();
    float end = getPaddingLeft() + (float) right * plotWidth();
    excludedAreas.addRect(
        x, getPaddingTop(), Math.max(x + dp(1), end), bottom(), Path.Direction.CW);
  }

  private void select(float x) {
    int progress =
        Math.max(
            0, Math.min(getMax(), Math.round((x - getPaddingLeft()) / plotWidth() * getMax())));
    // An unchanged endpoint can still need revealing after the user pans the map.
    if (progress == getProgress() && listener != null)
      listener.onProgressChanged(this, progress, true);
    else setProgress(progress);
  }

  @Override
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
    this.listener = listener;
    super.setOnSeekBarChangeListener(listener);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        pointer = event.getPointerId(0);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        setPressed(true);
        select(event.getX());
        return true;
      case MotionEvent.ACTION_MOVE:
        int index = event.findPointerIndex(pointer);
        if (index >= 0) select(event.getX(index));
        return true;
      case MotionEvent.ACTION_UP:
        select(event.getX());
        performClick();
      // Fall through to release the gesture.
      case MotionEvent.ACTION_CANCEL:
        pointer = -1;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
