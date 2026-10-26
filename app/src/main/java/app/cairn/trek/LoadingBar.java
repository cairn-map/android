package app.cairn.trek;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.ProgressBar;

/** Shared rounded transfer indicator. Unknown totals use Android's lifecycle-aware animation. */
final class LoadingBar extends ProgressBar {
  LoadingBar(Context context) {
    super(context, null, android.R.attr.progressBarStyleHorizontal);
    GradientDrawable track = rounded(AppPalette.OUTLINE);
    ClipDrawable fill =
        new ClipDrawable(rounded(AppPalette.ACCENT), Gravity.START, ClipDrawable.HORIZONTAL);
    LayerDrawable layers = new LayerDrawable(new Drawable[] {track, fill});
    layers.setId(0, android.R.id.background);
    layers.setId(1, android.R.id.progress);
    setProgressDrawable(layers);
    setIndeterminateTintList(ColorStateList.valueOf(AppPalette.ACCENT));
    setMax(10000);
    setMinimumHeight(0);
  }

  void showProgress(double fraction) {
    setIndeterminate(fraction < 0);
    if (fraction >= 0) setProgress((int) (Math.max(0, Math.min(1, fraction)) * getMax()));
  }

  private GradientDrawable rounded(int color) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(8 * getResources().getDisplayMetrics().density);
    return drawable;
  }
}
