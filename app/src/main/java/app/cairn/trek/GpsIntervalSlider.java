package app.cairn.trek;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.SeekBar;

/** A logarithmic interval slider: taps choose ticks; drags keep whole-second precision. */
final class GpsIntervalSlider extends SeekBar {
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int slop;
  private float downX, downY;
  private boolean tracking, moved;
  private OnSeekBarChangeListener listener;

  GpsIntervalSlider(Context context) {
    super(context);
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

  boolean isTouchTracking() {
    return tracking;
  }

  private int position(float x) {
    float fraction = (x - getPaddingLeft()) / (getWidth() - getPaddingLeft() - getPaddingRight());
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
    return Math.max(0, Math.min(getMax(), Math.round(fraction * getMax())));
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        // Own the whole padded touch area, even when the finger sits below the track.
        getParent().requestDisallowInterceptTouchEvent(true);
        tracking = true;
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
        setProgress(moved ? progress : GpsInterval.nearestTick(progress));
        if (!moved) performClick();
      // Fall through: the last value also applies if Android cancels a drag.
      case MotionEvent.ACTION_CANCEL:
        tracking = false;
        setPressed(false);
        if (listener != null) listener.onStopTrackingTouch(this);
        getParent().requestDisallowInterceptTouchEvent(false);
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
    float width = getWidth() - getPaddingLeft() - getPaddingRight();
    float track = (getHeight() - getPaddingBottom()) / 2f;
    for (int seconds : GpsInterval.TICKS) {
      float fraction = GpsInterval.progress(seconds) / (float) getMax();
      if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
      float x = getPaddingLeft() + fraction * width;
      canvas.drawLine(x, track + dp(9), x, track + dp(14), ink);
      canvas.drawText(Integer.toString(seconds), x, getHeight() - dp(6), ink);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
