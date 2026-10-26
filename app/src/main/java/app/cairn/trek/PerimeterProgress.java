package app.cairn.trek;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;

/** Native ProgressBar semantics and animation lifecycle, following the control's outline. */
final class PerimeterProgress extends ProgressBar {
  PerimeterProgress(Context context, float cornerDp) {
    super(context, null, android.R.attr.progressBarStyleHorizontal);
    float density = getResources().getDisplayMetrics().density;
    setProgressDrawable(new Outline(cornerDp * density, density, false));
    setIndeterminateDrawable(new Outline(cornerDp * density, density, true));
    setProgressTintList(null);
    setIndeterminateTintList(null);
    setPadding(0, 0, 0, 0);
    setMinimumHeight(0);
    setMax(10000);
  }

  void showProgress(double fraction) {
    boolean unknown = fraction < 0;
    setIndeterminate(unknown);
    if (!unknown) setProgress((int) (Math.max(0, Math.min(1, fraction)) * getMax()), true);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    return false;
  }

  private static final class Outline extends Drawable implements Animatable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path perimeter = new Path(), segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float radius, inset;
    private final boolean indeterminate;
    private ValueAnimator animator;
    private float phase;
    private int alpha = 255;

    Outline(float radius, float density, boolean indeterminate) {
      this.radius = radius;
      this.indeterminate = indeterminate;
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2.5f * density);
      paint.setStrokeCap(Paint.Cap.ROUND);
      inset = paint.getStrokeWidth() / 2 + density;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
      float left = bounds.left + inset, top = bounds.top + inset;
      float right = bounds.right - inset, bottom = bounds.bottom - inset;
      float r = Math.max(0, Math.min(radius - inset, Math.min(right - left, bottom - top) / 2));
      // Start at twelve o'clock, clockwise, for circles and rounded rectangles alike.
      perimeter.rewind();
      perimeter.moveTo(bounds.exactCenterX(), top);
      perimeter.lineTo(right - r, top);
      perimeter.arcTo(right - 2 * r, top, right, top + 2 * r, -90, 90, false);
      perimeter.lineTo(right, bottom - r);
      perimeter.arcTo(right - 2 * r, bottom - 2 * r, right, bottom, 0, 90, false);
      perimeter.lineTo(left + r, bottom);
      perimeter.arcTo(left, bottom - 2 * r, left + 2 * r, bottom, 90, 90, false);
      perimeter.lineTo(left, top + r);
      perimeter.arcTo(left, top, left + 2 * r, top + 2 * r, 180, 90, false);
      perimeter.close();
      measure.setPath(perimeter, false);
    }

    @Override
    public void draw(Canvas canvas) {
      paint.setColor(AppPalette.OUTLINE);
      paint.setAlpha(alpha);
      canvas.drawPath(perimeter, paint);
      float length = measure.getLength();
      float start = indeterminate ? phase * length : 0;
      float fraction =
          indeterminate
              ? 0.12f + 0.22f * (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI))
              : getLevel() / 10000f;
      float end = start + length * fraction;
      segment.rewind();
      measure.getSegment(start, Math.min(end, length), segment, true);
      if (end > length) measure.getSegment(0, end - length, segment, true);
      paint.setColor(AppPalette.ACCENT);
      paint.setAlpha(alpha);
      canvas.drawPath(segment, paint);
    }

    @Override
    protected boolean onLevelChange(int level) {
      invalidateSelf();
      return true;
    }

    @Override
    public void start() {
      if (!indeterminate || isRunning() || !ValueAnimator.areAnimatorsEnabled()) return;
      if (animator == null) {
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(1800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(
            value -> {
              phase = (float) value.getAnimatedValue();
              invalidateSelf();
            });
      }
      animator.start();
    }

    @Override
    public void stop() {
      if (animator != null) animator.cancel();
    }

    @Override
    public boolean isRunning() {
      return animator != null && animator.isRunning();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
      if (!visible) stop();
      return super.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
      this.alpha = alpha;
      invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
      invalidateSelf();
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
