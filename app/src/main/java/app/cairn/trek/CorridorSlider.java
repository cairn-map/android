package app.cairn.trek;

import android.content.Context;
import android.graphics.*;
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

  CorridorSlider(Context context, Scale scale) {
    super(context);
    this.scale = scale;
    setPadding(dp(20), 0, dp(20), dp(28));
    setSplitTrack(false);
    setTickMark(null);
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
