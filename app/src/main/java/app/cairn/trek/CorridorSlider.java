package app.cairn.trek;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.SeekBar;
import java.util.Locale;

/** Corridor scales with the same labelled tick marks as the GPS interval slider. */
final class CorridorSlider extends SeekBar {
  enum Scale {
    ZOOM,
    KILOMETRES
  }

  private final Scale scale;
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int slop;
  private float downX, downY;
  private boolean moved;
  private OnSeekBarChangeListener listener;

  CorridorSlider(Context context, Scale scale) {
    super(context);
    this.scale = scale;
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    setPadding(dp(20), 0, dp(20), dp(28));
    setSplitTrack(false);
    setTickMark(null);
  }

  @Override
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
    this.listener = listener;
    super.setOnSeekBarChangeListener(listener);
  }

  private int position(float x) {
    float width = getWidth() - getPaddingLeft() - getPaddingRight();
    if (width <= 0) return getMin();
    float fraction = (x - getPaddingLeft()) / width;
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
    return Math.max(
        getMin(), Math.min(getMax(), getMin() + Math.round(fraction * (getMax() - getMin()))));
  }

  private int nearestTick(int progress) {
    if (scale == Scale.ZOOM) return progress;
    int nearest = getMin();
    for (int km = 1; km <= 10; km++) {
      int tick = km * 10 - 1;
      if (tick >= getMin()
          && tick <= getMax()
          && Math.abs(tick - progress) < Math.abs(nearest - progress)) nearest = tick;
    }
    return nearest;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        moved = false;
        downX = event.getX();
        downY = event.getY();
        setPressed(true);
        if (listener != null) listener.onStartTrackingTouch(this);
        return true;
      case MotionEvent.ACTION_MOVE:
        moved |= Math.hypot(event.getX() - downX, event.getY() - downY) > slop;
        if (moved) setProgress(position(event.getX()));
        return true;
      case MotionEvent.ACTION_UP:
        moved |= Math.hypot(event.getX() - downX, event.getY() - downY) > slop;
        int progress = position(event.getX());
        setProgress(moved ? progress : nearestTick(progress));
        if (!moved) performClick();
      // Fall through: preserve the last value when Android cancels a drag.
      case MotionEvent.ACTION_CANCEL:
        setPressed(false);
        if (listener != null) listener.onStopTrackingTouch(this);
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

  @Override
  protected synchronized void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    ink.setColor(AppPalette.MUTED);
    ink.setTextAlign(Paint.Align.CENTER);
    ink.setTextSize(dp(10));
    ink.setStrokeWidth(dp(1));
    if (scale == Scale.ZOOM) {
      for (int zoom = getMin(); zoom <= getMax(); zoom++)
        tick(canvas, zoom, Integer.toString(zoom));
    } else {
      tick(canvas, 0, String.format(Locale.getDefault(), "%.1f", 0.1));
      for (int km = 1; km <= 10; km++) tick(canvas, km * 10 - 1, Integer.toString(km));
    }
  }

  private void tick(Canvas canvas, int progress, String label) {
    if (progress < getMin() || progress > getMax()) return;
    float fraction =
        getMax() == getMin() ? 0 : (progress - getMin()) / (float) (getMax() - getMin());
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
    float width = getWidth() - getPaddingLeft() - getPaddingRight();
    float x = getPaddingLeft() + fraction * width;
    float track = (getHeight() - getPaddingBottom()) / 2f;
    canvas.drawLine(x, track + dp(9), x, track + dp(14), ink);
    canvas.drawText(label, x, track + dp(17) - ink.ascent(), ink);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
